package com.mall.ai.reader;

import java.util.List;

/**
 * 切分器契约。<b>这个实现由你自己写</b>（交接说明里「核心链路自己写」的部分）。
 *
 * <p>输入已经过 {@link TextNormalizer} 处理：换行统一成 {@code \n}、连续空行合并、
 * 中文断行已拼接、行尾无空白。你只需要关心「怎么切」。
 *
 * <p>实现要覆盖两种形态（实测数据见 docs/pitfalls-and-interview.md）：
 * <ol>
 *   <li><b>有结构</b>（{@code hasHeadings=true}）：商品目录，400 个
 *       {@code ## 商品 ID xxx}，每款约 128 字符。要求<b>块内不跨商品</b>——
 *       把 A 款鞋的上半句和 B 款鞋的下半句放进同一个 chunk，检索出来模型读不懂。</li>
 *   <li><b>无结构</b>（{@code hasHeadings=false}）：售后政策，条款式连续文本。
 *       按长度切 + overlap 兜底，但要尽量落在段落/条款边界上，别切在句子中间。</li>
 * </ol>
 *
 * <p>必须守住的不变量（写完建议单测直接断言这几条）：
 * <ul>
 *   <li>每块非空、长度 ≤ {@code ctx.chunkSize()}（超出说明兜底硬切没做）</li>
 *   <li>块按原顺序排列，拼接后内容不丢（除了 overlap 的重复部分）</li>
 *   <li>有结构时，不以 {@code ## } 开头的块不允许出现（否则说明切进了商品中间）</li>
 * </ul>
 */
public interface TextSplitter {

    /**
     * @param normalizedText 已归一化的全文
     * @param ctx            切分参数与文本特征
     * @return 切分结果，按原顺序；不允许返回 null，无内容时返回空列表
     */
    List<String> split(String normalizedText, SplitContext ctx);
}
