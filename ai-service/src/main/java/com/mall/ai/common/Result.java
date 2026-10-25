package com.mall.ai.common;

import lombok.Data;

/**
 * 统一响应体（非 SSE 接口用）。
 *
 * <p>code 约定：0 成功；4xx 客户端错误；50001 文档解析失败；50002 向量化失败；50003 模型不可用。
 *
 * <p><b>注意</b>：这个类同时用来反序列化 hmall 的响应，所以字段名必须和 hmall 的
 * {@code Result} 保持一致（hmall 是 code/msg/data）。等拿到 hmall 源码要核对一遍：
 * 如果 hmall 的 code 是 long 或字段名不同，这里直接改成对应的 DTO，
 * 不要用「能编译就行」的心态糊过去，否则 Feign 调通但解析成 null。
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
