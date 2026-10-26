package com.mall.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 分页结果（镜像 hmall 的 {@code com.hmall.common.domain.PageDTO}）。
 *
 * 注意 hmall 的商品接口没有用统一响应体 {@code R} 包装，
 * 直接返回这个对象。所以 Feign 方法的返回类型就是 {@code PageDTO<ItemDTO>}，
 * 不要再套一层 {@code R<PageDTO<...>>} —— 套了会解析出 null 且不报错。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageDTO<T> {

    private Long total;
    private Long pages;
    private List<T> list;
}