package com.mall.ai.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 切分器测试。全部走 classpath 样本，不依赖本机绝对路径，换机器也能跑。
 *
 * 断言的是三条不变量（不是「跑通不报错」）：
 *
 *   - 每块长度 ≤ chunkSize
 *   - 有结构的文档里，块不跨商品边界
 *   - 内容不丢
 *
 * 外加一条：超长段落不能把切分器拖进死循环（曾真实发生过 OOM）。
 */
class CustomTextSplitterTest {

    private static final int CHUNK_SIZE = 500;

    private final CustomTextSplitter splitter = new CustomTextSplitter();
    private final TextNormalizer normalizer = new TextNormalizer();

    // ---------- 有结构：商品目录 ----------

    @Test
    @DisplayName("商品目录：每块不超限、块不跨商品、10 款商品不丢")
    void splitCatalogRespectsProductBoundaries() throws IOException {
        String normalized = normalizeSampleCatalog();
        List<String> chunks = splitter.split(normalized,
                new SplitContext("CATEGORY", "休闲鞋", CHUNK_SIZE, 0, true));

        assertFalse(chunks.isEmpty(), "切分结果不能为空");

        // 不变量 1：每块不超限
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= CHUNK_SIZE,
                    "存在超限块：length=" + chunk.length());
            assertFalse(chunk.isBlank(), "存在空白块");
        }

        // 不变量 2：除了第 1 块（含前言），每块都必须以商品标题开头
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startsWith("## "),
                    "第 " + i + " 块不是从商品边界开始：" + head(chunks.get(i)));
        }

        // 不变量 3：10 款商品一个都不能丢
        String joined = String.join("\n", chunks);
        int productCount = countOccurrences(joined, "## 商品 ID");
        assertEquals(10, productCount, "商品标题数量应等于样本中的 10 款");

        // 样本约 1500 字符，chunkSize=500，至少 3 块
        assertTrue(chunks.size() >= 3, "块数异常偏少：" + chunks.size());
    }

    @Test
    @DisplayName("商品目录：归一化后不应残留 CR，否则块首匹配会失败")
    void normalizedCatalogHasNoCarriageReturn() throws IOException {
        assertFalse(normalizeSampleCatalog().contains("\r"),
                "归一化后仍残留 \\r，startsWith(\"## \") 会失效");
    }

    // ---------- 无结构：政策类长文本 ----------

    @Test
    @DisplayName("长文本：超长段落被硬切、总字数不丢、不死循环")
    void splitPlainTextHardCutsLongParagraph() {
        String shortA = "第一段。";
        String shortB = "第二段。";
        String longParagraph = "长".repeat(1200);
        String shortC = "最后一段。";
        String text = shortA + "\n" + shortB + "\n" + longParagraph + "\n" + shortC;

        List<String> chunks = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> splitter.split(text, new SplitContext("POLICY", "政策", CHUNK_SIZE, 0, false)),
                "切分器超时：疑似死循环");

        assertFalse(chunks.isEmpty());

        // 每块不超限
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= CHUNK_SIZE, "存在超限块：" + chunk.length());
        }

        // 1200 字的长段要切成 500 + 500 + 200 三块
        long exactlyFull = chunks.stream().filter(c -> c.length() == CHUNK_SIZE).count();
        assertEquals(2, exactlyFull, "1200 字应切出两块满 500 的片段");

        // 总字数不丢（切分只是分割，不删内容）
        int total = chunks.stream().mapToInt(String::length).sum();
        assertEquals(text.replace("\n", "").length(), total, "字符总数应保持不变");
    }

    // ---------- helpers ----------

    private String normalizeSampleCatalog() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/sample-catalog.md")) {
            assertTrue(in != null, "测试资源 /sample-catalog.md 缺失");
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return normalizer.normalize(raw);
        }
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    private String head(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}