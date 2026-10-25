package com.mall.ai.reader;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 【临时兜底实现 —— 你自己的 {@code CustomTextSplitter} 写完后请删掉这个类】
 *
 * <p>故意写得很笨：按空行分段落，按长度累加，overlap 取上一块尾部若干字符。
 * <b>完全不看 Markdown 结构</b>，所以商品目录会被切在商品中间。
 *
 * <p>留它只为了一件事：让「解析 → 归一化 → 切分 → 向量化 → 落库」这条链路现在就能跑通，
 * 你能先看到端到端效果，再回头改切分质量。等你写出按 {@code ## } 边界切的实现，
 * 删掉这个类，Spring 会注入你的实现（{@code TextSplitter} 是接口，只有一个实现时不必加注解）。
 */
@Component
public class LengthOnlySplitter implements TextSplitter {

    @Override
    public List<String> split(String normalizedText, SplitContext ctx) {
        List<String> chunks = new ArrayList<>();
        if (normalizedText == null || normalizedText.isBlank()) {
            return chunks;
        }

        int chunkSize = Math.max(100, ctx.chunkSize());
        int overlap = Math.max(0, Math.min(ctx.overlap(), chunkSize / 2));

        StringBuilder current = new StringBuilder();
        for (String paragraph : normalizedText.split("\n")) {
            // 单行超过上限：先切段再塞，避免整块超限
            if (paragraph.length() > chunkSize) {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    current = tail(current.toString(), overlap);
                }
                int from = 0;
                while (from < paragraph.length()) {
                    int to = Math.min(from + chunkSize, paragraph.length());
                    chunks.add(paragraph.substring(from, to));
                    from = to;
                }
                current = new StringBuilder();
                continue;
            }

            if (current.length() + paragraph.length() + 1 > chunkSize && !current.isEmpty()) {
                chunks.add(current.toString());
                current = tail(current.toString(), overlap);
            }
            current.append(paragraph).append('\n');
        }

        if (!current.isEmpty() && !current.toString().isBlank()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /** 取上一块尾部 overlap 个字符作为下一块的起始上下文 */
    private StringBuilder tail(String previous, int overlap) {
        if (overlap <= 0) {
            return new StringBuilder();
        }
        int from = Math.max(0, previous.length() - overlap);
        return new StringBuilder(previous.substring(from));
    }
}
