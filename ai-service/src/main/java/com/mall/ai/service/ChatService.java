package com.mall.ai.service;

import com.mall.ai.common.BizException;
import com.mall.ai.dto.AskResultVO;
import com.mall.ai.dto.KbHitVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单轮问答编排：检索 → 拼上下文 → 调模型 → 标注来源。
 *
 * <p>不用 Spring AI 的 QuestionAnswerAdvisor：它把「检索 → 拼装 → 调用」整条链路藏起来，
 * 而本项目的两个硬约束恰好要在这两层控制——检索为空时不能把空上下文送进模型，
 * 回答必须能标注来源。自己拼装才能显式处理这两种情况。
 *
 * <p>上下文用字符串拼装而不是 PromptTemplate：商品 spec 是 JSON（形如 {"颜色":"银色"}），
 * 花括号会破坏模板语法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    /** 检索为空时给模型的占位符；system 提示词要求看到它就直接回答「没有相关信息」 */
    static final String EMPTY_CONTEXT = "（无相关资料）";

    private final ChatClient chatClient;
    private final RagService ragService;

    @Value("${ai.rag.max-context-chunks:5}")
    private int maxContextChunks;

    /**
     * @param skipRetrieval true 时不检索，直接问模型（只验证模型连通性）
     */
    public AskResultVO ask(String question, boolean skipRetrieval) {
        if (question == null || question.isBlank()) {
            throw new BizException(400, "问题不能为空");
        }
        long start = System.currentTimeMillis();

        List<KbHitVO> hits = skipRetrieval ? List.of() : ragService.retrieve(question);
        String userMessage = buildUserMessage(question, hits);

        String answer;
        try {
            answer = chatClient.prompt().user(userMessage).call().content();
        } catch (Exception e) {
            // 模型侧失败单独一个 code，前端据此提示「稍后重试」而不是「服务器错误」
            log.error("模型调用失败：{}", e.getMessage(), e);
            throw new BizException(50003, "模型调用失败：" + e.getMessage(), e);
        }

        AskResultVO result = new AskResultVO();
        result.setQuestion(question);
        result.setAnswer(answer);
        result.setUsedContext(!hits.isEmpty());
        result.setHitCount(hits.size());
        result.setContextChars(userMessage.length());
        result.setCostMs(System.currentTimeMillis() - start);
        result.setHits(hits);
        log.info("问答完成：hit={} usedContext={} contextChars={} cost={}ms",
                hits.size(), result.isUsedContext(), userMessage.length(), result.getCostMs());
        return result;
    }

    /**
     * 拼出要发给模型的 user message。
     *
     * <p>检索为空时只放占位符，不拼空的 doc 标签——空上下文会让模型自由发挥，
     * 这是原型项目踩过的坑。
     */
    /** 拼出要发给模型的 user message。调试端点 /dev/probe/context 也复用它，避免两处逻辑漂移。 */
    public String buildUserMessage(String question, List<KbHitVO> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("资料片段：\n");
        if (hits.isEmpty()) {
            sb.append(EMPTY_CONTEXT).append('\n');
        } else {
            int limit = Math.min(hits.size(), maxContextChunks);
            for (int i = 0; i < limit; i++) {
                KbHitVO hit = hits.get(i);
                sb.append("<doc id=\"").append(i + 1).append("\" source=\"")
                        .append(hit.getTitle() == null ? "未命名文档" : hit.getTitle())
                        .append("\">\n")
                        .append(hit.getContent() == null ? "" : hit.getContent().strip())
                        .append("\n</doc>\n");
            }
        }
        sb.append("\n用户问题：").append(question).append('\n');
        return sb.toString();
    }
}
