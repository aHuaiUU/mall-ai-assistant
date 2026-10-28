package com.mall.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用日志（表 {@code tool_call_log}）。
 *
 * <p>这张表是可观测性的落点：工具调用成功率、平均耗时、失败原因全从这里算。
 *
 * <p>{@code arguments} 存模型给的<b>原始 JSON</b>，不要存对象的 toString()——
 * 出问题时要能原样复现模型的入参。
 *
 * <p>{@code resultDigest} 只存截断摘要（列宽 500），不存全量：工具结果可能有几千字，
 * 存全量表会膨胀，而排查时只需要知道「返回了大概什么」。
 */
@Data
@TableName("tool_call_log")
public class ToolCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路追踪 ID，当前可能为空（第 7 步补 MDC） */
    private String traceId;

    private String sessionId;

    private String toolName;

    /** 入参 JSON */
    private String arguments;

    /** 结果摘要，截断到 500 字以内 */
    private String resultDigest;

    /** 1 成功 / 0 失败 */
    private Integer success;

    private String errorMsg;

    private Integer costMs;

    private LocalDateTime createTime;
}
