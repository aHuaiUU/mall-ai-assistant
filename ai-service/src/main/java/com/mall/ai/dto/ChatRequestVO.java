package com.mall.ai.dto;

import lombok.Data;

/**
 * 问答请求。
 *
 * sessionId 第 5 步还不用（会话记忆在第 6 步），但协议里必须有：
 * 前端第一次不带，服务端生成并通过 done 帧回传，第 6 步接记忆时前端就能直接复用，
 * 不用再改一次协议。
 */
@Data
public class ChatRequestVO {

    /** 会话标识；为空则服务端生成，随 done 帧返回 */
    private String sessionId;

    /** 用户提问 */
    private String message;

    /**
     * 调试用：true 时不检索、直接问模型。
     * 保留它是因为流式出问题时需要一个「排除 RAG」的对照实验入口，
     * 否则分不清是模型慢还是检索慢。
     */
    private boolean skipRetrieval;
}