package com.mall.ai.service;

import com.mall.ai.dto.ChatRequestVO;
import com.mall.ai.dto.KbHitVO;
import com.mall.ai.dto.MemoryMessageVO;
import com.mall.ai.filters.TraceIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 流式问答编排。事件协议：delta / tool / done / error。
 *
 * <h3>为什么用 SseEmitter 而不是返回 Flux</h3>
 * 项目是 Spring MVC（Tomcat）。直接返回 {@code Flux<ServerSentEvent>} 需要 WebFlux，
 * 而引入 WebFlux 会让整站变成响应式栈，与阻塞的 MyBatis/JDBC 混在一起，
 * 事务和连接池都会出问题。{@link SseEmitter} 是 MVC 下的标准做法：
 * 请求线程立刻返回，容器把连接挂起，后续由我们自己往 emitter 里写。
 *
 * <h3>为什么必须 subscribe 而不能 collect</h3>
 * {@code stream()} 返回的是<b>冷流</b>：没有订阅者就一个字节都不会流动。
 * 而 {@code toStream().collect()} / {@code blockLast()} 会把流式变成「等全文生成完再一次性返回」，
 * 首字延迟从数百毫秒劣化到全文生成时间——这就是原型项目那个坑。
 * <p>
 * 为什么所有 send 都走同一把锁
 * {@link SseEmitter#send} 不是线程安全的。心跳线程和模型回调线程并发写会交错出坏帧
 * （SSE 帧边界被破坏，前端 JSON 解析失败，且是偶发、极难复现）。
 * 常见做法是把心跳并进 Flux 用 {@code Flux.merge} 保证单线程发送，但
 * {@code Flux.interval} 永不完成，merge 后的流也就永不完成，{@code doFinally} 收不到，
 * done 帧发不出来。加一把锁比跟完成语义较劲简单可靠得多。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    private final ChatClient chatClient;
    private final RagService ragService;
    private final ChatService chatService;
    private final MeterRegistry meterRegistry;
    // 会话归档服务
    private final ConversationArchiveService archiveService;
    // 会话记忆服务 redis
    private final ConversationMemoryService memoryService;
    // 问答缓存服务
    private final QaCacheService qaCacheService;

    @Value("${ai.stream.timeout-ms:180000}")
    private long timeoutMs;

    @Value("${ai.stream.heartbeat-seconds:15}")
    private long heartbeatSeconds;

    /**
     * 心跳用独立的单线程调度器。
     * 用 daemon 线程：应用停止时不阻塞 JVM 退出。
     */
    private final ScheduledExecutorService heartbeatExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /**
     * 打开一条 SSE 连接并开始流式回答。
     * <p>
     * 关键：立刻返回，方法体内不做任何检索/模型调用。
     * 检索要调 embedding 接口 + 查 MySQL，几百毫秒到几秒；放在返回之前，
     * 首字节时间就会被检索拖长，流式的意义就丢了一半。
     */
    public SseEmitter stream(ChatRequestVO request) {
        // 从 Servlet 线程拿到MDC中的 traceId，用于日志和监控
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);

        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? newSessionId()        // 新会话，生成新会话 ID
                : request.getSessionId(); // 旧会话，直接使用会话 ID
        String question = request.getMessage();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        StreamSession session = new StreamSession(emitter, sessionId, question, traceId);

        // 三个回调都必须接：容器侧的完成、超时、错误，都要释放上游，
        // 否则用户已经关页面了，模型还在往下生成、token 还在计费
        emitter.onCompletion(session::dispose);
        emitter.onTimeout(() -> {
            session.finishWithError("CLIENT_TIMEOUT", "连接超时，请重试");
            session.dispose();
        });
        emitter.onError(e -> session.dispose());

        // 阻塞的准备工作丢到弹性线程池，让 controller 立刻拿到 emitter 返回
        Schedulers.boundedElastic().schedule(() -> prepareAndStream(session, request));
        return emitter;
    }

    // 准备流式回答，包括检索、拼装上下文、订阅模型流
    private void prepareAndStream(StreamSession session, ChatRequestVO request) {
        // boundedElastic 线程没有 MDC：手动补上，这个线程后续的日志才有 traceId
        MDC.put(TraceIdFilter.MDC_KEY, session.traceId);
        String question = request.getMessage();

        // 加载会话记忆，从 Redis 读取 JSON 字符串
        List<MemoryMessageVO> history = memoryService.load(session.sessionId);

        // 追问：从会话记忆中提取用户最近一次的问题
        String lastQuestion = memoryService.lastUserQuestion(history);
        // 检索查询：追问时，将用户最近一次的问题与当前问题拼接否则，直接使用当前问题
        String retrievalQuery = (lastQuestion == null || lastQuestion.isBlank())
                ? question
                : lastQuestion + " " + question;
        session.retrievalQuery = retrievalQuery;
        // 读/写边界分开：
        //   读：只要「无历史 + 未跳过检索」就查缓存（key 只依赖 question + kbVersion）
        //   写：还要确认命中都是 POLICY/FAQ（商品类带价格库存，缓存就是答错价）
        boolean cacheAllowed = !request.isSkipRetrieval() && history.isEmpty();
        if (cacheAllowed) {
            QaCacheService.CachedAnswer cached = qaCacheService.get(question);
            if (cached != null) {
                log.info("问答缓存命中 session={} 字数={}", session.sessionId, cached.answer().length());
                // 回放缓存内容：直接发送缓存内容，不进行检索
                session.replayCached(cached);
                return;
            }
        }

        List<KbHitVO> hits; // 检索到的知识库文档
        String userMessage; // 提示词上下文
        try {
            hits = request.isSkipRetrieval()
                    ? List.of()
                    : ragService.retrieve(retrievalQuery);
            userMessage = chatService.buildUserMessage(question, hits);
        } catch (Exception e) {
            log.error("流式问答前置失败 session：{} 原因：{}}", session.sessionId, e.getMessage(), e);
            session.finishWithError("RETRIEVE_FAILED", "检索失败，请稍后重试");
            session.dispose();
            return;
        }
        session.hits = hits;
        session.contextChars = userMessage.length();

        // 只有「命中政策/FAQ」才允许缓存：商品类目命中里可能带价格库存，缓存 1 小时就是答错价
        boolean policyOnly = !hits.isEmpty() && hits.stream()
                .allMatch(h -> isStaticDoc(h.getSourceType()));
        session.cacheable = cacheAllowed && policyOnly;

        try {
            List<Message> historyMessages = history.stream()
                    .map(m -> "user".equals(m.role())
                            ? (Message) new UserMessage(m.content())
                            : (Message) new AssistantMessage(m.content()))
                    .toList();

            Flux<ChatResponse> flux = chatClient.prompt()
                    .messages(historyMessages)
                    .user(userMessage)
                    .toolContext(Map.of(
                            "sessionId", session.sessionId,
                            "traceId", session.traceId))
                    .stream()
                    .chatResponse();
            session.startStreaming(flux);
        } catch (Exception e) {
            log.error("流式订阅失败 session：{} 原因：{}", session.sessionId, e.getMessage(), e);
            session.finishWithError("MODEL_UNAVAILABLE", "模型调用失败，请稍后重试");
            session.dispose();
        }
    }

    private Boolean isStaticDoc(String sourceType) {
        return "POLICY".equals(sourceType) || "FAQ".equals(sourceType);
    }


    private String newSessionId() {
        // 去掉短横线，前端放到 URL / 日志里都干净
        return "s" + UUID.randomUUID().toString().replace("-", "");
    }

    @PreDestroy
    void shutdown() {
        heartbeatExecutor.shutdownNow();
    }

    // ==========================================================================
    // 单条连接的会话状态：把「这条连接的所有可变状态」收在一个对象里，
    // 避免多请求并发时状态串号（静态 Map 存 emitter 那个坑的反面）
    // ==========================================================================
    private final class StreamSession {

        private final SseEmitter emitter;
        private final String sessionId;
        private final String answerId = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        private final String originalQuestion;
        private final String traceId;
        private boolean isCached; // 是否缓存命中

        /**
         * 终止守卫：done 与 error 互斥，且各只能发一次
         */
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        /**
         * send 的互斥锁：保证心跳与 delta 不会交错写坏帧
         */
        private final Object sendLock = new Object();

        private final AtomicLong seq = new AtomicLong();// 序列号，用于 delta 帧
        private final AtomicInteger deltaCount = new AtomicInteger();
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

        private final long startNanos = System.nanoTime();
        /**
         * 首字延迟（毫秒）；-1 表示还没收到第一个非空增量
         */
        private volatile long firstTokenMs = -1;

        private volatile List<KbHitVO> hits = List.of(); //检索到的知识库文档
        private volatile int contextChars;  // 上下文字符数，用于写记忆和写缓存。
        private volatile boolean cacheable;
        private volatile String retrievalQuery; // 检索查询
        private volatile int answerChars;  // 累积最终回答，用于写记忆和写缓存。
        private volatile Integer promptTokens; // 提示词 token数
        private volatile Integer completionTokens; // 回答 token数

        /**
         * 累积最终回答，用于写记忆和写缓存。不用它做展示——展示走 delta 帧
         */
        private final StringBuilder answer = new StringBuilder();

        private StreamSession(SseEmitter emitter, String sessionId, String originalQuestion, String traceId) {
            this.emitter = emitter;
            this.sessionId = sessionId;
            this.originalQuestion = originalQuestion;
            this.traceId = traceId;
        }

        void startStreaming(Flux<ChatResponse> flux) {
            scheduleHeartbeat();
            // subscribe 是非阻塞的：订阅后立刻返回，后续在模型的数据到达时回调
            Disposable d = flux.subscribe(this::onChunk, this::onStreamError, this::onStreamComplete);
            disposable.set(d);
            // 竞态处理：如果流在这个赋值之前就已经结束（本地缓存命中、极短的回复），
            // 终止回调已经把 terminated 置位，此时要立刻补一次 dispose
            if (terminated.get()) {
                d.dispose();
            }
        }

        private void onChunk(ChatResponse response) {
            if (terminated.get()) {
                return;
            }
            captureUsage(response);

            String text = extractText(response);
            if (text == null || text.isEmpty()) {
                // 有些实现会先发一个空 delta（只带 role），不能把它算成「首字」
                return;
            }
            if (firstTokenMs < 0) {
                firstTokenMs = elapsedMs();
                log.info("流式首字 session={} firstTokenMs={} deltaCount={}",
                        sessionId, firstTokenMs, deltaCount.get());
            }

            answerChars += text.length(); // 累加回答字符数
            answer.append(text); // 记录回答文本
            deltaCount.incrementAndGet(); // 增加 delta 帧 个数
            send("delta", Map.of("content", text));
        }

        /**
         * 流式下 usage 通常只在最后一个 chunk 出现；也可能完全不出现（取决于服务端）
         */
        private void captureUsage(ChatResponse response) {
            if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
                return;
            }
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                promptTokens = usage.getPromptTokens();
            }
            if (usage.getCompletionTokens() != null) {
                completionTokens = usage.getCompletionTokens();
            }
        }

        /**
         * 取增量文本。
         * 防御性判空：某些 chunk 只有 usage 或只有 finishReason，getResult() 为 null。
         */
        private String extractText(ChatResponse response) {
            if (response.getResult() == null || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        }

        private void onStreamComplete() {
            finishWithDone();
        }

        private void onStreamError(Throwable error) {
            log.error("流式模型调用失败 session={} 原因={}", sessionId, error.getMessage(), error);
            finishWithError("MODEL_UNAVAILABLE", "模型调用失败，请稍后重试");
        }

        /**
         * 正常收尾：先落记忆/缓存，再发 done，最后关连接。
         * <p>
         * 顺序不能反：done 一发出，前端就断开、onCompletion 会 dispose，
         * 之后再做落库就处于「连接已结束但仍要写 Redis」的状态，异常时记忆会悄悄丢。
         */
        private void finishWithDone() {
            String source = isCached ? "cache" : "model";
            // 缓存未命中
            isCached = false;
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancelHeartbeat();

            String answerText = answer.toString();
            persistTurn(answerText, true);

            Map<String, Object> payload = buildPayload(false);
//            log.info("流式完成 session={} tokens={} 估={} firstTokenMs={} costMs={} hit={} delta={} 字数={} cached=false",
//                    sessionId, payload.get("tokens"), payload.get("tokenEstimated"),
//                    firstTokenMs, elapsedMs(),
//                    hits.size(), deltaCount.get(), answerChars
            log.info("qa_done trace={} session={} source={} firstTokenMs={} costMs={} tokens={} estimated={} hits={} usedContext={} answerChars={}",
                    traceId, sessionId, source, firstTokenMs, elapsedMs(),
                    payload.get("tokens"), payload.get("tokenEstimated"), hits.size(), !hits.isEmpty(), answerChars);

            // 记录指标
            recordMetrics(source, isCached);
            writeEvent("done", payload);
            complete();

            // 归档放最后且异步；completionTokens 从 payload 取，拿不到就 null 让归档层估
            archiveService.archiveTurn(sessionId, null, originalQuestion, answerText,
                    (Integer) payload.get("completionTokens"));
        }

        /**
         * 缓存命中时的收尾。多带 cached / hitCount / titles 三个字段，前端可以显示「来自缓存」。
         */
        private void finishWithCachedDone(QaCacheService.CachedAnswer cached) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            String source = isCached ? "cache" : "model";
            // 缓存命中
            isCached = true;

            cancelHeartbeat();
            Map<String, Object> payload = buildPayload(true);
            payload.put("hitCount", cached.hitCount());
            payload.put("titles", cached.titles());
            log.info("流式完成（缓存回放） session={} firstTokenMs={} costMs={} 字数={}",
                    sessionId, firstTokenMs, elapsedMs(), answerChars);

            // 记录指标
            recordMetrics(source, isCached);

            writeEvent("done", payload);
            complete();

            // 缓存命中的那一轮也必须归档：否则 Redis 记忆里有、MySQL 里没有，两边不一致
            archiveService.archiveTurn(sessionId, null, originalQuestion, cached.answer(),
                    (Integer) payload.get("completionTokens"));
        }

        // 埋点
        private void recordMetrics(String source, boolean isCached) {
            // 关键：缓存命中和真实模型必须分开打 tag。
            // 缓存回放 firstTokenMs 只有几十毫秒，混在一起会把模型 TTFT 平均值拉低一个数量级，
            if (firstTokenMs >= 0) {
                Timer.builder("ai.qa.ttft")
                        .description("首个 delta 到达耗时")
                        .tag("source", source)
                        .register(meterRegistry)
                        .record(Duration.ofMillis(firstTokenMs));
            }
            Timer.builder("ai.qa.cost")
                    .tag("source", source)
                    .register(meterRegistry)
                    .record(Duration.ofMillis(elapsedMs()));
            Counter.builder("ai.qa.requests")
                    .tag("source", source)
                    .tag("usedContext", String.valueOf(!hits.isEmpty()))
                    .register(meterRegistry)
                    .increment();
        }

        /**
         * 缓存命中时的回放：分块发 delta，保持前端打字机效果。
         *
         * <p>逐块走和 onChunk 一样的记账（firstTokenMs / answerChars / deltaCount），
         * 这样 done 帧里的统计口径和真实流式完全一致，不会出现「缓存命中时 tokens=0」。
         */
        void replayCached(QaCacheService.CachedAnswer cached) {
            String text = cached.answer() == null ? "" : cached.answer();
            int chunkSize = 12; // 约 6 ~ 12 个字符，和模型 delta 粒度相当

            for (int from = 0; from < text.length(); from += chunkSize) {
                if (terminated.get()) {
                    return; // 客户端断开，不再往下发
                }
                // 取当前块
                String piece = text.substring(from, Math.min(from + chunkSize, text.length()));
                if (firstTokenMs < 0) {
                    // 第一个 delta，记录当前时间作为 firstTokenMs
                    firstTokenMs = elapsedMs();
                }

                answer.append(piece);
                answerChars += piece.length();
                deltaCount.incrementAndGet();
                send("delta", Map.of("content", piece));
                try {
                    Thread.sleep(20); // 20ms ≈ 每秒 600 字,接近真实流式手感
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            persistTurn(text, false); // 缓存命中也要写记忆，否则「那第二款呢」接不上
            finishWithCachedDone(cached);
        }

        /**
         * 落记忆 + 可选落缓存。失败只记日志：模型已经答完，不能因为写缓存失败让用户看不到答案。
         *
         * @param writeCache 缓存回放时传 false——刚从缓存读出来又写回去，只会平白延长 TTL
         */
        private void persistTurn(String answerText, boolean writeCache) {
            if (answerText == null || answerText.isBlank()) {
                return;
            }
            try {

                memoryService.appendTurn(sessionId, originalQuestion, answerText);

                if (writeCache && cacheable) {
                    qaCacheService.put(originalQuestion, new QaCacheService.CachedAnswer(
                            answerText, hits.size(), titlesOf(hits), !hits.isEmpty()));
                    log.info("已写入问答缓存 session：{} 字数：{}", sessionId, answerChars);
                }
            } catch (Exception e) {
                // 写 redis 失败，不影响这次回答交付
                log.warn("记忆或缓存写入失败 session：{} 原因：{}", sessionId, e.getMessage(), e);
            }
        }

        /**
         * done 帧的公共 payload；cached 决定前端展示口径
         */
        private Map<String, Object> buildPayload(boolean cached) {
            boolean estimated = completionTokens == null || completionTokens <= 0;
            int tokens = estimated ? answerChars + (promptTokens == null ? 0 : promptTokens)
                    : (promptTokens == null ? 0 : promptTokens) + completionTokens;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("answerId", answerId);
            payload.put("tokens", tokens);
            payload.put("tokenEstimated", estimated);
            payload.put("promptTokens", promptTokens);
            payload.put("completionTokens", completionTokens);
            payload.put("firstTokenMs", firstTokenMs);
            payload.put("costMs", elapsedMs());
            payload.put("usedContext", !hits.isEmpty());
            payload.put("hitCount", hits.size());
            payload.put("contextChars", contextChars);
            payload.put("answerChars", answerChars);
            payload.put("deltaCount", deltaCount.get());
            payload.put("cached", cached);
            return payload;
        }

        // 从检索命中的文档列表里，提取出所有不重复、非空的标题
        private List<String> titlesOf(List<KbHitVO> hits) {
            return hits.stream()
                    .map(KbHitVO::getTitle)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }

        /**
         * 异常收尾：发 error 事件后关连接。与 done 互斥。
         */
        void finishWithError(String code, String message) {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            cancelHeartbeat();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", message);
            payload.put("sessionId", sessionId);
            writeEvent("error", payload);
            complete();
            log.warn("流式终止（异常）session={} code={} firstTokenMs={} costMs={}",
                    sessionId, code, firstTokenMs, elapsedMs());
        }

        /**
         * 唯一的事件写出入口。三件事：
         * 1) 拿到锁，避免心跳与 delta 并发写
         * 2) 写前再查一次 terminated（可能在等锁期间被终止）
         * 3) IOException / IllegalStateException = 客户端已断开，立刻释放上游
         */
        private void send(String eventName, Object payload) {
            if (terminated.get()) {
                return;
            }
            synchronized (sendLock) {
                if (terminated.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(seq.incrementAndGet()))
                            .name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    // 客户端关闭页面、网络断开、容器已回收 —— 判定连接已死。
                    // 这里不会再发 error（发出去也没人收），直接释放。
                    log.info("SSE 写失败判定客户端断开 session={} event={} 原因={}",
                            sessionId, eventName, e.getMessage());
                    terminated.set(true);
                    dispose();
                }
            }
        }

        /**
         * 用于终止帧：此时 terminated 已经是 true，不能再走 send() 的守卫
         */
        private void writeEvent(String eventName, Object payload) {
            synchronized (sendLock) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(seq.incrementAndGet()))
                            .name(eventName)
                            .data(payload, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    log.info("SSE 终止帧写失败（客户端可能已断开）session={} event={}", sessionId, eventName);
                }
            }
        }

        private void complete() {
            try {
                // 一定要显式 complete：只发 done 不关连接，前端会一直挂到超时
                emitter.complete();
            } catch (Exception e) {
                log.debug("complete 异常（可忽略）session={} 原因={}", sessionId, e.getMessage());
            }
        }

        private void scheduleHeartbeat() {
            if (heartbeatSeconds <= 0) {
                return;
            }
            ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(
                    this::sendHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
            heartbeat.set(future);
        }

        /**
         * 心跳：SSE 注释行 {@code : keep-alive}。
         *
         * <p>它不产生事件、不触发前端回调，只是让中间的网关/Nginx/负载均衡认为连接还活着。
         * 没有心跳时，Nginx 默认 60s 空闲就断，用户会看到"回答到一半没了"。
         */
        private void sendHeartbeat() {
            if (terminated.get()) {
                return;
            }
            synchronized (sendLock) {
                if (terminated.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (Exception e) {
                    log.info("心跳写失败判定客户端断开 session={}", sessionId);
                    terminated.set(true);
                    dispose();
                }
            }
        }

        private void cancelHeartbeat() {
            ScheduledFuture<?> future = heartbeat.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }

        /**
         * 释放上游：必须在 onCompletion / onTimeout / onError 里调用
         */
        void dispose() {
            cancelHeartbeat();
            Disposable d = disposable.getAndSet(null);
            if (d != null && !d.isDisposed()) {
                d.dispose();
            }
        }

        // 计算 响应耗时
        private long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        String answerText() {
            return answer.toString();
        }
    }
}