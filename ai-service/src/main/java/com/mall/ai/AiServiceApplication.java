package com.mall.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * ai-service 启动类。
 *
 * 与 hmall 的关系：独立进程，只通过 HTTP（OpenFeign）调用 hmall 的 GET 接口，
 * 不共享数据库、不共享 Spring 容器，因此 Boot 2.7 与 Boot 3.5 可以共存。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.mall.ai.client")
// 显式声明扫描路径：不依赖「@Mapper 注解 + 自动扫描」的隐式行为，
// 后者在自定义了 basePackages 或加了多模块时容易静默失效（启动不报错，注入时才炸）。
@MapperScan("com.mall.ai.mapper")
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServiceApplication.class, args);
    }
}