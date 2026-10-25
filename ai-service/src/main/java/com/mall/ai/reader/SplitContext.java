package com.mall.ai.reader;

/**
 * 切分上下文：告诉切分器「这段文本是什么、按多大切」。
 *
 * <p>用 record 而不是普通类：它是纯数据、不可变，没必要给 setter。
 *
 * @param sourceType   CATEGORY / POLICY / FAQ / MANUAL，决定要不要按结构切
 * @param title        文档标题，用于块内加上下文（可选）
 * @param chunkSize    单块目标字符数上限
 * @param overlap      相邻块重叠字符数；结构化文档建议 0，连续论述建议 80
 * @param hasHeadings  文本里是否有 Markdown 标题结构（实测商品目录有 400 个 {@code ##}）
 */
public record SplitContext(
        String sourceType,
        String title,
        int chunkSize,
        int overlap,
        boolean hasHeadings) {
}
