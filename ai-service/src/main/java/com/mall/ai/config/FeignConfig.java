package com.mall.ai.config;

import feign.RequestInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 全局配置：只放「所有 client 都要」的东西。
 *
 * 超时不在这里配，放在 application.yaml 的
 * {@code spring.cloud.openfeign.client.config.default} 里：改超时不用重新编译。
 *
 * 重试故意不配：Feign 默认 NEVER_RETRY。AI 层调的都是只读接口，
 * 但自动重试会让一次用户提问变成多次下游调用，把「查不到」变成「查三次」，
 * 耗时和下游压力都会翻倍。要重试就在工具层显式做，可观测、可计数。
 */
@Configuration
public class FeignConfig {

    /** 透传 traceId，让 hmall 那边的日志能和 AI 侧的日志串起来。 */
    @Bean
    public RequestInterceptor traceIdInterceptor() {
        return template -> {
            String traceId = MDC.get("traceId");
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}