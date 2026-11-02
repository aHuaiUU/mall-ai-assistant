package com.mall.ai.dto;

/**
 * 会话记忆消息。
 * 一条历史消息。刻意不用 Spring AI 的 Message 接口：
 *  Message 是多态的（user / assistant / tool，还带 toolCalls、metadata），
 *  存 Redis 要自己按类型分派序列化，反序列化容易丢字段。
 *  我们只需要「两段文本」，用 record 最省事。
 * @param role
 * @param content
 * @param ts
 */
public record MemoryMessageVO(String role,String content,long ts) {

    public static MemoryMessageVO user(String content) {
        return new MemoryMessageVO("user",content,System.currentTimeMillis());
    }

    public static MemoryMessageVO assistant(String content){
        return new MemoryMessageVO("assistant",content,System.currentTimeMillis());
    }
}
