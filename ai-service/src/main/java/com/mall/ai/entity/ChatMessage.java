package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息（表 chat_message）。存全量原文，用于审计/回放/演示。
 * 与 Redis 记忆的区别：Redis 是「给模型看的窗口」（截断 240 字、24h），这里是「归档」。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    /** user / assistant / tool / system */
    private String role;
    private String content;
    private Integer tokenCount;
    /** role=tool 时记录工具名 */
    private String toolName;
    private LocalDateTime createTime;
}