package com.mall.ai.service;

import com.mall.ai.entity.ToolCallLog;
import com.mall.ai.filters.TraceIdFilter;
import com.mall.ai.mapper.ToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 记录一次工具调用。
 *
 * 设计取舍：日志写库放在 工具方法内部 同步调用，而不是用 AOP 拦截 {@code @Tool}。
 * 理由是 AOP 拿不到「模型原始入参 JSON」和「工具实际返回的文本」这两样东西，
 * 而它们恰好是排查问题最需要的信息。工具方法数量很少，手动记的成本可以接受。
 *
 * 落库是异步的 CompletableFuture：写日志失败不应该影响回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolCallLogService {

    private static final int MAX_ARGUMENTS = 4000;
    private static final int MAX_DIGEST = 480;

    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 日志专用线程池。
     *
     * 原来用 {@code CompletableFuture.runAsync}，走的是公共 ForkJoinPool：
     * 既拿不到 MDC（traceId 变 untracked），也无法给线程命名，
     * 排日志时看不出是哪个任务写的；池子被别的并行任务占满时日志会延迟落库。
     */
    private static final java.util.concurrent.ExecutorService LOG_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "tool-log");
                t.setDaemon(true);
                return t;
            });

    public void record(String toolName, String arguments, String result, String errorMsg,
                       long costMs, String sessionId) {
        record(toolName, arguments, result, errorMsg, costMs, sessionId, null);
    }

    /**
     * @param arguments  模型给的入参 JSON，原样传
     * @param result     工具返回的文本；失败时传 null
     * @param errorMsg   失败原因；成功时传 null
     * @param costMs     耗时毫秒
     * @param sessionId  会话 ID，暂可为 null
     * @param traceId    跟踪 ID，暂可为 null
     */
    public void record(String toolName, String arguments, String result,
                       String errorMsg, long costMs, String sessionId, String traceId) {
        boolean success = errorMsg == null || errorMsg.isBlank();

        ToolCallLog entry = new ToolCallLog();
        entry.setTraceId(resolveTraceId(traceId));
        entry.setSessionId(sessionId);
        entry.setToolName(toolName);
        entry.setArguments(truncate(arguments, MAX_ARGUMENTS));
        entry.setResultDigest(success ? truncate(result, MAX_DIGEST) : null);
        entry.setSuccess(success ? 1 : 0);
        entry.setErrorMsg(success ? null : truncate(errorMsg, MAX_DIGEST));
        entry.setCostMs((int) Math.min(costMs, Integer.MAX_VALUE));

        LOG_EXECUTOR.execute(() -> {
            try {
                toolCallLogMapper.insert(entry);
            } catch (Exception e) {
                log.warn("工具调用日志落库失败 tool={} traceId={} 原因={}",
                        toolName, entry.getTraceId(), e.getMessage());
            }
        });
    }

    // 从 explicit 中获取 trace ID，若为空则用 untracked
    private String resolveTraceId(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String fromMdc = MDC.get(TraceIdFilter.MDC_KEY);
        return fromMdc == null || fromMdc.isBlank() ? "untracked" : fromMdc;
    }


    /** 便捷方法：不需要 sessionId 时用这个 */
    public void record(String toolName, String arguments, String result, String errorMsg, long costMs) {
        record(toolName, arguments, result, errorMsg, costMs, null);
    }
    // 截断字符串，防止数据库字段溢出
    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
