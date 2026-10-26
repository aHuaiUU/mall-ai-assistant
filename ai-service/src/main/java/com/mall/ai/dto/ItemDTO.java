package com.mall.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 商品（镜像 hmall 的 {@code com.hmall.api.dto.ItemDTO}）。
 *
 * 为什么不直接依赖 hm-api 的 jar：hmall 是 Boot 2.7 + {@code javax.*}，
 * ai-service 是 Boot 3.5 + {@code jakarta.*}，把 hm-api 引进来会和
 * spring-boot-starter-web 的注解体系冲突。这里只复制字段，代价是 hmall 改字段时要手动同步。
 *
 * price 单位是「分」，28900 表示 289.00 元。工具层返回给模型前必须换算成元，
 * 否则模型会把「28900」当成人均消费报给用户。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemDTO {

    private Long id;
    /** SKU 名称 */
    private String name;
    /** 价格，单位：分 */
    private Integer price;
    /** 库存数量 —— 就在这个对象里，不需要单独的「查库存」接口 */
    private Integer stock;
    private String image;
    /** 类目名称 */
    private String category;
    /** 品牌名称 */
    private String brand;
    /** 规格 */
    private String spec;
    /** 销量 */
    private Integer sold;
    private Integer commentCount;
    private Boolean isAD;
    /** 商品状态 1-正常，2-下架，3-删除 */
    private Integer status;

    /** 分 → 元，仅用于展示，不改变原始字段。 */
    public String priceYuan() {
        return price == null ? "-" : String.format("%.2f", price / 100.0);
    }
}