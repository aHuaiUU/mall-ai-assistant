package com.mall.ai.reader;

/**
 * 切分上下文：文本是什么、按多大切。
 *
 * @param sourceType  CATEGORY / POLICY / FAQ / MANUAL
 * @param title       文档标题
 * @param chunkSize   单块字符数上限
 * @param overlap     相邻块重叠字符数；结构化文档用 0，连续论述用 80
 * @param hasHeadings 文本是否有 Markdown 标题结构
 */
public record SplitContext(
        String sourceType,
        String title,
        int chunkSize,
        int overlap,
        boolean hasHeadings) {
}