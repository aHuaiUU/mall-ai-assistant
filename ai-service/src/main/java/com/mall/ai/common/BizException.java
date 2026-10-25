package com.mall.ai.common;

import lombok.Getter;

/**
 * 业务异常。code 约定见 docs/architecture.md 4.2：
 * 0 成功；4xx 客户端错误；50001 文档解析失败；50002 向量化失败；50003 模型不可用。
 *
 * <p>用 code 而不是 HTTP 状态码：调用方（前端/测试脚本）需要区分「文件坏了」和「模型挂了」，
 * 这两种情况下用户的处理动作完全不同。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
