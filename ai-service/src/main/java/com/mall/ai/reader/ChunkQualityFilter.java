package com.mall.ai.reader;

import java.util.Set;

/**
 * 判断一个切片是否「噪声片段」，用于检索结果过滤。
 *
 * <p>目标是识别 PDF 表格被抽成流水句的那类内容：形如
 * {@code 未开箱 否 是开箱未动 否 是开箱安装 是 是…}。这类文本对自然语言提问没有语义，
 * 却经常因为包含大量实词而排到检索第一位，白占一个上下文名额。
 *
 * <p>两条规则，都是在真实数据上量出来的（政策 PDF 共 21 块）：
 * <ol>
 *   <li>是否 token 数 ≥ 阈值：决策矩阵被抽成「是 否 是 否」交替的流水句，
 *       实测该块有 10 个「是/否」token，而保修年限表只有 0 个，
 *       所以这条能区分「无用决策矩阵」和「有用数据表」。</li>
 *   <li>长度低于下限：承载不了完整语义。</li>
 * </ol>
 *
 * <p>过滤放在检索之后而不是入库之前：入库时删掉等于永久丢失信息，
 * 而这些表格里其实含「7 日内」「不予办理」等事实，换个问题可能就有用。
 */
public class ChunkQualityFilter {

    private static final Set<String> YES_NO_TOKENS = Set.of("是", "否");

    /** 低于这个长度的切片视为噪声 */
    private final int minChunkChars;

    /** 「是/否」token 达到这个数量视为决策矩阵流水句 */
    private final int yesNoTokenThreshold;

    public ChunkQualityFilter(int minChunkChars, int yesNoTokenThreshold) {
        this.minChunkChars = minChunkChars;
        this.yesNoTokenThreshold = yesNoTokenThreshold;
    }

    public boolean isFragment(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String stripped = text.strip();
        if (stripped.length() < minChunkChars) {
            return true;
        }
        return countYesNoTokens(stripped) >= yesNoTokenThreshold;
    }

    private int countYesNoTokens(String text) {
        int count = 0;
        for (String token : text.split("\\s+")) {
            if (YES_NO_TOKENS.contains(token.strip())) {
                count++;
            }
        }
        return count;
    }
}
