package com.mall.ai.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个 HTTP 请求生成/透传 traceId，写进 MDC 和响应头。
 *
 * <p>三个细节：
 * 1) {@code finally} 里必须 {@code MDC.remove}：Tomcat 线程是复用的，
 *    不清理会让下一个请求的日志带上一个请求的 traceId——比"没有"更糟，会误导排查。
 * 2) 优先用网关/调用方传进来的 {@code X-Trace-Id}，实现跨服务串联；
 *    没有才自己生成。
 * 3) 长度取 16 位十六进制，不打 36 位 UUID：日志里 grep 更省眼。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 注意：SSE 场景下 doFilter 返回时流还没结束，
            // 但后续推送在别的线程里，那里必须自己 set 一次 MDC（见 ChatStreamService）
            MDC.remove(MDC_KEY);
        }
    }
}