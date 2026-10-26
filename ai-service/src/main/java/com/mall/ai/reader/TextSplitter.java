package com.mall.ai.reader;

import java.util.List;

/**
 * 切分器契约：把归一化后的全文切成若干块，每块需能独立检索、独立读懂。
 *
 * 输入已由 {@link TextNormalizer} 处理（换行统一、连续空行合并、中文断行已拼接）。
 *
 * 两种形态：有 Markdown 标题时块内不得跨条目；无标题时按长度切，尽量落在段落边界。
 *
 * 实现必须守住三条不变量：每块长度 ≤ {@code ctx.chunkSize()}；块内不跨条目；
 * 拼接后内容不丢（overlap 造成的重复除外）。
 */
public interface TextSplitter {

    /**
     * @param normalizedText 已归一化的全文
     * @param ctx            切分参数与文本特征
     * @return 切分结果，按原顺序；不允许返回 null，无内容时返回空列表
     */
    List<String> split(String normalizedText, SplitContext ctx);
}