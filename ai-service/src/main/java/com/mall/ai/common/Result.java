package com.mall.ai.common;

import lombok.Data;

/**
 * ai-service 自己的统一响应体（非 SSE 接口用）。
 *
 * code 约定：0 成功；4xx 客户端错误；50001 文档解析失败；50002 向量化失败；50003 模型不可用。
 *
 * 不要用它来反序列化 hmall 的响应，两者不一致（已核对 hmall 源码）：
 *
 *   - hmall 的类是 {@code com.hmall.common.domain.R}，成功码是 200，不是 0；
 *   - 更关键：item-service 和 trade-service 的 Controller 根本不返回 R 包装，
 *       直接返回 {@code ItemDTO} / {@code PageDTO<ItemDTO>} / {@code OrderVO}。
 *
 * 所以 Feign 客户端的返回类型直接用 {@code com.mall.ai.dto} 下的镜像类，
 * 套一层 {@code Result} 会解析出 null 且不报错——这是最难查的一类 bug。
 */
@Data
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.msg = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> fail(int code, String msg) {
        Result<T> r = new Result<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public boolean isSuccess() {
        return code == 0;
    }
}