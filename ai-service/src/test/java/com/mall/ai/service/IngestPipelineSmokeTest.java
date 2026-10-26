package com.mall.ai.service;

import com.mall.ai.reader.CustomTextSplitter;
import com.mall.ai.reader.DocumentReaderFactory;
import com.mall.ai.reader.SplitContext;
import com.mall.ai.reader.TextNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库管线的冒烟测试：解析 → 归一化 → 切分。
 *
 * 不覆盖 {@code IngestService} 的编排部分：那一段依赖 MySQL、Redis 锁、向量写入和
 * 外部模型调用，属于集成测试，需要单独准备环境。
 */
class IngestPipelineSmokeTest {

    private final DocumentReaderFactory readerFactory = new DocumentReaderFactory();
    private final TextNormalizer normalizer = new TextNormalizer();
    private final CustomTextSplitter splitter = new CustomTextSplitter();

    @Test
    @DisplayName("Markdown 样本：解析出文字、归一化无 CR、切分不超限")
    void markdownPipeline() {
        List<Document> documents = readerFactory.read(
                new ClassPathResource("sample-catalog.md"), "sample-catalog.md");
        assertFalse(documents.isEmpty(), "解析结果不能为空");

        String raw = documents.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(raw.contains("商品 ID"), "解析结果应包含商品标题，实际："
                + (raw.length() > 80 ? raw.substring(0, 80) : raw));

        String normalized = normalizer.normalize(raw);
        assertFalse(normalized.isBlank(), "归一化后不能为空");
        assertFalse(normalized.contains("\r"), "归一化后不应残留 \\r");

        List<String> chunks = splitter.split(normalized,
                new SplitContext("CATEGORY", "休闲鞋", 500, 0, true));
        assertFalse(chunks.isEmpty(), "切分结果不能为空");
        chunks.forEach(c -> assertTrue(c.length() <= 500, "存在超限块：" + c.length()));
    }
}