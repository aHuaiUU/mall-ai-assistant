package com.mall.ai.controller;

import com.mall.ai.client.ItemClient;
import com.mall.ai.dto.ItemDTO;
import com.mall.ai.dto.PageDTO;
import com.mall.ai.mapper.KbChunkMapper;
import com.mall.ai.mapper.KbDocumentMapper;
import com.mall.ai.reader.DocumentReaderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【临时验证入口，第 4 步工具化之后删掉】
 *
 * <p>只为第 1 步的完成标准服务：「能通过 ItemClient 查到一条真实商品」。
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

    /**
     * 检查点 1：解析。上传一个文件，看抽出来的文本长什么样。
     *
     * <p>为什么先做这个而不是先做切分：切分策略取决于真实文本形态。
     * PDF 抽出来有没有页眉页脚、md 的 {@code ##} 标题还在不在、中文有没有乱码——
     * 这些不看一眼就设计切分器，是空想。
     *
     * <p>返回里刻意带上「结构统计」：行数、空行数、以 # 开头的标题数。
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
     * <p>这张表建错（字段名、逻辑删除、唯一键）在第 2 步会表现为「入库成功但查不到」，
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
}
