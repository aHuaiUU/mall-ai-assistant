package com.mall.ai.controller;

import com.mall.ai.client.ItemClient;
import com.mall.ai.dto.AskResultVO;
import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.KbHitVO;
import com.mall.ai.dto.PageDTO;
import com.mall.ai.mapper.KbChunkMapper;
import com.mall.ai.mapper.KbDocumentMapper;
import com.mall.ai.reader.DocumentReaderFactory;
import com.mall.ai.service.ChatService;
import com.mall.ai.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 【临时验证入口，第 4 步工具化之后删掉】
 *
 * 只为第 1 步的完成标准服务：「能通过 ItemClient 查到一条真实商品」。
 * 打开 http://localhost:9090/dev/probe/search?key=RIMOWA 能出商品，就说明
 * Feign 地址、超时、JSON 反序列化、price 单位换算是通的。
 */
@Slf4j
@RestController
@RequestMapping("/dev/probe")
@RequiredArgsConstructor
public class DevProbeController {

    private final ItemClient itemClient;
    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final DocumentReaderFactory documentReaderFactory;
    private final RagService ragService;
    private final ChatService chatService;
    private final ChatClient chatClient;

    /**
     * 检查点 1：检索。不调模型、不花钱，几秒钟能跑一次。
     *
     * 先用它确认「问题能检到正确的段落」，再去接模型——否则模型答得不好时，
     * 分不清是检索喂错了还是提示词的问题。
     */
    @GetMapping("/kb")
    public Map<String, Object> kb(@RequestParam("q") String question,
                                  @RequestParam(value = "k", defaultValue = "5") int k,
                                  @RequestParam(value = "threshold", defaultValue = "0.4") double threshold) {
        long start = System.currentTimeMillis();
        List<KbHitVO> hits = ragService.retrieve(question, k, threshold);

        List<Map<String, Object>> items = hits.stream().map(h -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", h.getDocId());
            item.put("title", h.getTitle());
            item.put("chunkIndex", h.getChunkIndex());
            item.put("score", h.getScore());
            item.put("chars", h.getContent() == null ? 0 : h.getContent().length());
            item.put("snippet", h.getSnippet());
            return item;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("k", k);
        result.put("threshold", threshold);
        result.put("hitCount", hits.size());
        result.put("costMs", System.currentTimeMillis() - start);
        result.put("hits", items);
        return result;
    }

    /**
     * 检查点 1b：阈值扫描。看同一问题在不同阈值下命中的条数。
     *
     * 阈值不能拍脑袋：这条曲线是第 8 步评测集的雏形——找「召回开始掉、但仍能命中目标段落」的那一档。
     */
    @GetMapping("/kb/sweep")
    public Map<String, Object> kbSweep(@RequestParam("q") String question,
                                       @RequestParam(value = "k", defaultValue = "10") int k) {
        double[] thresholds = {0.0, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8};
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("k", k);
        result.put("hitCountByThreshold", ragService.sweep(question, thresholds, k));
        return result;
    }

    /**
     * 检查点 2：上下文拼装。返回「即将发给模型的用户消息」，不调模型。
     *
     * 打印出来人工看一眼：片段有没有截断、花括号有没有异常、空检索时是不是只剩占位符。
     */
    @GetMapping("/context")
    public Map<String, Object> context(@RequestParam("q") String question) {
        List<KbHitVO> hits = ragService.retrieve(question);
        String message = chatService.buildUserMessage(question, hits);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hitCount", hits.size());
        result.put("chars", message.length());
        result.put("userMessage", message);
        return result;
    }

    /**
     * 检查点 3/4/5：单轮问答。
     *
     * skipRetrieval=true 只验证模型连通；false 走完整 RAG。
     */
    @GetMapping("/ask")
    public AskResultVO ask(@RequestParam("q") String question,
                           @RequestParam(value = "skipRetrieval", defaultValue = "false") boolean skipRetrieval) {
        return chatService.ask(question, skipRetrieval);
    }

    /**
     * 检查点 1：解析。上传一个文件，看抽出来的文本长什么样。
     *
     * 为什么先做这个而不是先做切分：切分策略取决于真实文本形态。
     * PDF 抽出来有没有页眉页脚、md 的 {@code ##} 标题还在不在、中文有没有乱码——
     * 这些不看一眼就设计切分器，是空想。
     *
     * 返回里刻意带上「结构统计」：行数、空行数、以 # 开头的标题数。
     * 商品目录如果标题数和商品数对得上，说明 md 结构完整、可以按标题切。
     */
    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestParam("file") MultipartFile file) {
        long start = System.currentTimeMillis();
        List<Document> documents = documentReaderFactory.read(file.getResource(), file.getOriginalFilename());

        String text = documents.stream().map(Document::getText).collect(Collectors.joining("\n"));
        String[] lines = text.split("\n", -1);
        long blankLines = Arrays.stream(lines).filter(String::isBlank).count();
        long headingLines = Arrays.stream(lines).filter(l -> l.startsWith("#")).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", file.getOriginalFilename());
        result.put("sizeBytes", file.getSize());
        result.put("documentCount", documents.size());
        result.put("chars", text.length());
        // 按字符估 token：中文 1 字≈1 token 偏保守；这个字段只用于控制上下文长度，不需要精确
        result.put("approxTokens", text.length());
        result.put("lines", lines.length);
        result.put("blankLines", blankLines);
        result.put("headingLines", headingLines);
        result.put("costMs", System.currentTimeMillis() - start);
        result.put("preview", text.length() > 600 ? text.substring(0, 600) : text);
        log.info("parse probe: file={} docs={} chars={} lines={} headings={}",
                file.getOriginalFilename(), documents.size(), text.length(), lines.length, headingLines);
        return result;
    }

    /**
     * 验证 mall_ai 库连通与两张知识库表可用。
     *
     * 这张表建错（字段名、逻辑删除、唯一键）在第 2 步会表现为「入库成功但查不到」，
     * 先用一次 count 把问题暴露出来，比后面查 bug 便宜。
     */
    @GetMapping("/db")
    public Map<String, Object> db() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kbDocumentCount", kbDocumentMapper.selectCount(null));
        result.put("kbChunkCount", kbChunkMapper.selectCount(null));
        return result;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam(value = "key", required = false) String key,
                                      @RequestParam(value = "maxPrice", required = false) Integer maxPrice) {
        long start = System.currentTimeMillis();
        PageDTO<ItemDTO> page = itemClient.search(key, null, null, null, maxPrice, 1, 5);
        long cost = System.currentTimeMillis() - start;

        List<String> brief = page.getList().stream()
                .map(i -> i.getName() + "｜" + i.priceYuan() + " 元｜库存 " + i.getStock())
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("costMs", cost);
        result.put("total", page.getTotal());
        result.put("pages", page.getPages());
        result.put("returned", page.getList().size());
        result.put("items", brief);
        log.info("probe ok: total={} returned={} cost={}ms", page.getTotal(), page.getList().size(), cost);
        return result;
    }

    @GetMapping("/item/{id}")
    public Map<String, Object> item(@PathVariable("id") Long id) {
        ItemDTO item = itemClient.detail(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", item != null);
        if (item != null) {
            result.put("id", item.getId());
            result.put("name", item.getName());
            result.put("priceYuan", item.priceYuan());
            result.put("stock", item.getStock());
            result.put("status", item.getStatus());
        }
        return result;
    }

    /**
     * 检查点 1：最小流式探针。
     *
     * <p>刻意什么都不加：不检索、不心跳、不发 done。
     * 这样如果它也不通，问题一定在「Spring MVC 能不能把 SseEmitter 的帧推出去」，
     * 而不是检索慢、心跳写坏了帧、协议拼错了。
     */
    @GetMapping(value = "/stream-raw", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRaw(@RequestParam("q") String q) {
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicLong seq = new AtomicLong();

        Disposable disposable = chatClient.prompt().user(q).stream().content().subscribe(
                chunk -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(seq.incrementAndGet()))
                                .name("delta")
                                .data(Map.of("content", chunk), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        // 抛出去会让 Reactor 走 onError → 下面的 err 分支
                        throw new RuntimeException(e);
                    }
                },
                err -> {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data(Map.of("message", String.valueOf(err.getMessage())),
                                        MediaType.APPLICATION_JSON));
                    } catch (IOException ignored) {
                        // 客户端已断开
                    }
                    emitter.complete();
                },
                emitter::complete);

        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(disposable::dispose);
        emitter.onError(e -> disposable.dispose());
        return emitter;
    }
}