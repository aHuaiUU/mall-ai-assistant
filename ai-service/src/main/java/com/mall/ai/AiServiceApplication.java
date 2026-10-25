package com.mall.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ai-service 启动类。
 *
 * <p>与 hmall 的关系：独立进程，只通过 HTTP（OpenFeign）调用 hmall 的 GET 接口，
 * 不共享数据库、不共享 Spring 容器，因此 Boot 2.7 与 Boot 3.5 可以共存。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mall.ai.client")
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}
