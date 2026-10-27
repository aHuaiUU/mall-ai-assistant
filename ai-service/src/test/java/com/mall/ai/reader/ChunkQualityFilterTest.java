package com.mall.ai.reader;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 噪声片段判定测试。样本取自政策 PDF 的真实切片，阈值也是在这批数据上量出来的。
 */
class ChunkQualityFilterTest {

    private final ChunkQualityFilter filter = new ChunkQualityFilter(50, 3);

    /** 退货条件决策矩阵被 Tika 抽成流水句，实测「是/否」token 有 10 个 */
    private static final String TABLE_DUMP = """
            未开箱 否 是开箱未动 否 是开箱安装 是 是开箱后未使用退机缺件扣除缺件费用 是
            自签收次日起7日内，发生产品质量问题退货 用户不同意维修 否 否超过签收次日起7日
            因产品质量问题退机的证明未安装使用 否 是能修坚持退机 是 是在保修期内，符合换货条件的
            """;

    /** 保修年限表：同样是表格，但「是/否」token 为 0，属于有用信息，不能过滤 */
    private static final String WARRANTY_TABLE = """
            品类 类型 保修内容 保修期限冰箱整机 机器本身的零件（不包含耗材） 3年
            冰箱核心部件 压缩机、蒸发器、冷凝器 5年净水器整机 机器本身的零件含龙头（不包含滤芯） 3年
            """;

    /** 正常段落 */
    private static final String PARAGRAPH = """
            七天无理由退货：凡通过云米商城以及云米线上授权店等电商平台购买的产品，自签收次日起七日内
            可享受无理由退货服务；等级品、样机不适用该政策。退货时，用户须出示有效的购买凭证，并退回
            已开具的发票。若发票丢失，需承担相应的税点。
            """;

    @Test
    @DisplayName("决策矩阵流水句被判定为噪声")
    void tableDumpIsFragment() {
        assertTrue(filter.isFragment(TABLE_DUMP));
    }

    @Test
    @DisplayName("保修年限表不能被误杀")
    void warrantyTableIsNotFragment() {
        assertFalse(filter.isFragment(WARRANTY_TABLE));
    }

    @Test
    @DisplayName("正常段落不是噪声")
    void paragraphIsNotFragment() {
        assertFalse(filter.isFragment(PARAGRAPH));
    }

    @Test
    @DisplayName("过短的切片是噪声，空白也是")
    void tooShortOrBlankIsFragment() {
        assertTrue(filter.isFragment("其他特殊情况 视情况定"));
        assertTrue(filter.isFragment(""));
        assertTrue(filter.isFragment(null));
    }
}
