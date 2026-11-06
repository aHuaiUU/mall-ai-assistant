package com.mall.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.mall.ai.entity.ChatMessage;
import com.mall.ai.entity.ChatSession;
import com.mall.ai.entity.ToolCallLog;
import com.mall.ai.mapper.ChatMessageMapper;
import com.mall.ai.mapper.ChatSessionMapper;
import com.mall.ai.mapper.ToolCallLogMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 会话归档：把一轮问答写进 chat_session / chat_message。
 *
 * <p>与 Redis 记忆的分工：Redis 存「给模型看的窗口」（5 轮、单条截断 240 字、24h TTL）；
 * 这里存「全量归档」（原文、可 SQL 查询、可复盘）。两者都要有：
 * 删掉前者回答变差，删掉后者运营无法查历史。
 *
 * <p>为什么异步：done 帧不能等数据库。归档失败只记日志——它是可观测性资产，
 * 不是业务正确性的一部分。
 *
 * <p>为什么用编程式事务（TransactionTemplate）而不是 {@code @Transactional}：
 * 归档跑在独立线程池里，{@code @Transactional} 依赖的代理和线程绑定在这里都不成立，
 * 自调用注解更是直接失效。TransactionTemplate 是显式开启事务，在哪个线程都有效。
 */
@Slf4j
@Service
public class ConversationArchiveService {

    private static final int TITLE_MAX = 30;
    private static final int TOOL_DIGEST_MAX = 500;

    /**
     * 有界队列 + CallerRunsPolicy。
     * 不用 newFixedThreadPool：它的队列是无界的，归档积压时会把内存吃满；
     * 队列满了让调用线程自己写，形成反压，宁可慢一点也不吞内存。
     */
    private static final ThreadPoolExecutor ARCHIVE_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            r -> {
                Thread t = new Thread(r, "conv-archive");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ToolCallLogMapper toolCallLogMapper;
    private final TransactionTemplate transactionTemplate;

    public ConversationArchiveService(ChatSessionMapper sessionMapper,
                                      ChatMessageMapper messageMapper,
                                      ToolCallLogMapper toolCallLogMapper,
                                      PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.toolCallLogMapper = toolCallLogMapper;
        // 自己 new，不依赖自动配置：TransactionTemplate 的 Bean 是否被注册取决于
        // 事务管理器数量，显式构造少一个"环境变了就失效"的隐患
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 归档一轮问答。
     *
     * @param completionTokens 模型返回的 completion tokens；拿不到传 null，这里按字符估算
     */
    public void archiveTurn(String sessionId, Long userId, String question,
                            String answer, Integer completionTokens) {
        if (sessionId == null || sessionId.isBlank() || answer == null || answer.isBlank()) {
            return;
        }
        String q = question == null ? "" : question.strip();
        String a = answer.strip();
        int answerTokens = (completionTokens != null && completionTokens > 0)
                ? completionTokens
                : estimateTokens(a);

        ARCHIVE_EXECUTOR.execute(() -> {
            try {
                // 一个事务里写完：user → tool* → assistant。
                // 不加事务时，中途失败会留下「有提问没回答」的半条会话，比完全没有更难解释。
                transactionTemplate.executeWithoutResult(status -> {
                    ensureSession(sessionId, userId, q);
                    insertMessage(sessionId, "user", q, estimateTokens(q));
                    insertToolMessages(sessionId);        // 插在 user 与 assistant 之间，顺序即时间序
                    insertMessage(sessionId, "assistant", a, answerTokens);
                    touchSession(sessionId);              // 刷新 update_time
                });
            } catch (Exception e) {
                log.warn("会话归档失败 session={} 原因={}", sessionId, e.getMessage());
            }
        });
    }

    /** 首次见到这个 sessionId 就建一行；并发插入撞唯一键时忽略（另一个人已经建好了）。 */
    private void ensureSession(String sessionId, Long userId, String question) {
        Long exists = sessionMapper.selectCount(
                new LambdaQueryWrapper<ChatSession>().eq(ChatSession::getSessionId, sessionId));
        if (exists != null && exists > 0) {
            return;
        }
        ChatSession session = new ChatSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        session.setTitle(question.length() <= TITLE_MAX ? question : question.substring(0, TITLE_MAX));
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException ignored) {
            // 并发下已被别的线程插入，正常
        }
    }

    /**
     * 刷新会话的 update_time。
     *
     * <p>必须显式做：`ON UPDATE CURRENT_TIMESTAMP` 只在**真的发生 UPDATE** 时才触发，
     * 而 ensureSession 只有首轮 insert。不刷新的话「最近会话」列表永远按首轮时间排。
     */
    private void touchSession(String sessionId) {
        sessionMapper.update(null, new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .set(ChatSession::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 把本轮的工调用也落成 chat_message（role=tool）。
     *
     * <p>数据来源是 tool_call_log（按 sessionId 查），而不是从模型响应里抠——
     * 框架内部执行工具时，工具结果不会出现在 SSE 帧里。
     */
    private void insertToolMessages(String sessionId) {
        List<ToolCallLog> logs = toolCallLogMapper.selectList(
                new LambdaQueryWrapper<ToolCallLog>()
                        .eq(ToolCallLog::getSessionId, sessionId)
                        .orderByAsc(ToolCallLog::getId));
        for (ToolCallLog item : logs) {
            boolean ok = item.getSuccess() != null && item.getSuccess() == 1;
            String digest = ok ? "ok: " + nvl(item.getResultDigest())
                    : "fail: " + nvl(item.getErrorMsg());
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(sessionId);
            msg.setRole("tool");
            msg.setToolName(item.getToolName());          // ← 这就是细化点 4
            msg.setContent(truncate(nvl(item.getArguments()) + " => " + digest, TOOL_DIGEST_MAX));
            msg.setTokenCount(0);
            messageMapper.insert(msg);
        }
    }

    private void insertMessage(String sessionId, String role, String content, int tokens) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setTokenCount(tokens);
        messageMapper.insert(msg);
    }

    /** 字符估 token（中文 1 字≈1 token，偏保守）。只用于展示，不需要精确。 */
    private int estimateTokens(String text) {
        return text == null ? 0 : text.length();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        ARCHIVE_EXECUTOR.shutdownNow();
    }
}