package com.mall.ai.config;

import com.mall.ai.tool.ProductTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 构建全局唯一的 {@link ChatClient}。
 *
 * <p>系统提示词是静态的（角色、资料使用规则、输出格式），在启动时一次性加载并通过
 * {@code defaultSystem} 绑定。每轮变化的只有资料片段和用户问题，它们拼在 user message 里，
 * 这样不需要每次请求重新渲染模板。
 *
 * 提示词文件刻意不含占位符：检索片段里可能出现花括号（商品 spec 是 JSON，
 * 形如 {"颜色": "银色"}），一旦把片段当模板变量渲染就会破坏模板解析。
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT_PATH = "prompts/system.st";

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ProductTool productTool) {
        String systemPrompt = loadSystemPrompt();
        log.info("系统提示词加载完成，长度：{}", systemPrompt.length());
        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(productTool)
                .build();
    }

    private String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource(SYSTEM_PROMPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 提示词缺失属于打包错误，启动就应该失败，不要静默降级成空提示词
            throw new IllegalStateException("系统提示词加载失败：" + SYSTEM_PROMPT_PATH, e);
        }
    }
}
