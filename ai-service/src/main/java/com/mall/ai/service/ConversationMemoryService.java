package com.mall.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.ai.dto.MemoryMessageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆：Redis List，一个会话一个 key，按「轮」裁剪。
 *
 * <p>为什么用 List 而不是 Hash：对话天然有序、只追加、只裁掉最旧的，
 * List 的 RPUSH / LTRIM / LRANGE 正好对应，而且 LTRIM 是 O(1) 级别的裁剪。
 *
 * <p>为什么按「轮」裁剪而不是按「条」：一轮 = user + assistant 两条。
 * 按条裁会把一轮从中间切断，出现「有回答没提问」的历史，模型会困惑。
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationMemoryService {

    // 会话ID 前缀
    private static final String KEY_PREFIX = "ai:memory:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /** 窗口轮数（1 轮 = 1 问 + 1 答） 超出后丢弃最早的轮次 */
    @Value("${ai.memory.window-turns:5}")
    private int windowTurns;

    @Value("${ai.memory.max-chars-per-message:240}")
    private int maxCharsPerMessage; // 每条消息截断保留最大字符数

    /**
     * 加载会话记忆， 从 Redis 读取 JSON 字符串，解析为 MemoryMessageVO 列表。
     * @param sessionId 会话ID
     * @return 会话记忆列表
     */
    public List<MemoryMessageVO> load(String sessionId){
        if(sessionId == null || sessionId.isBlank()){
            return List.of();
        }
        List<String> raw =
                redis.opsForList().range(key(sessionId), 0, -1);

        if(raw == null || raw.isEmpty()){
            return List.of();
        }
        List<MemoryMessageVO> messages = new ArrayList<>(raw.size());
        for(String json : raw){
            try{
                // 解析 JSON 字符串为 MemoryMessageVO
                messages.add(objectMapper.readValue(json, MemoryMessageVO.class));
            }catch(JsonProcessingException e){
                log.warn("历史消息解析失败，已跳过 session：{} 原因：{}",
                        sessionId,e.getMessage());
            }
        }
        return messages;
    }

    /**
     * 追加会话记忆。
     * 追加一轮对话（user + assistant），并裁剪窗口、刷新 TTL
     * 用一次 {@code rightPushAll} 写两条：Redis 单条命令天生原子
     * 避免并发下出现 A 的提问 + B 的回答 这种错配
     * @param sessionId 会话ID
     * @param userText 用户提问
     * @param assistantText 助手回答
     */
    public void appendTurn(String sessionId,String userText, String assistantText){
        if(sessionId  == null || sessionId.isBlank()){
            return;
        }
        if(assistantText == null || assistantText.isBlank()){
            // 没有回答就不写记忆，否则下一轮会读到一条空的 assistant 消息
            return;
        }

        String key = key(sessionId);
        List<String> payload = List.of(
                toJson(MemoryMessageVO.user(truncate(userText))),
                toJson(MemoryMessageVO.assistant(truncate(assistantText)))
        );
        // 固定大小的滑动消息窗口，常用于 LLM 对话里限制上下文轮数，避免 prompt 无限膨胀
        redis.opsForList().rightPushAll(key, payload);
        // 只留最后 windowTurns 轮：负索引表示从尾部数
        redis.opsForList().trim(key, -(long) windowTurns * 2, -1); // 保留最后 windowTurns 轮
        redis.expire(key, Duration.ofHours(1));
    }

    public void clear(String sessionId){
        if(sessionId != null && !sessionId.isBlank()){
            redis.delete(key(sessionId));
        }
    }
    // 取最近一条提问，用于追问时的检索拼装
    public String lastUserQuestion(List<MemoryMessageVO> history){
        for(int i = history.size() - 1; i >= 0; i--){
            if("user".equals(history.get(i).role())){
                return history.get(i).content();
            }
        }
        return null;
    }

    // 会话记忆生成 key：ai:memory:sessionId
    private String key(String sessionId){
        return KEY_PREFIX + sessionId;
    }
    // 截断文本，防止超出字符上限
    private String truncate(String text){
        if(text == null){
            return "";
        }
        // 这里用 strip() 是因为用户输入可能包含空格，而模型不关心空格
        String stripped = text.strip();
        return stripped.length() <= maxCharsPerMessage
                ? stripped
                : stripped.substring(0, maxCharsPerMessage) + "...";
    }
    // 序列化为 JSON 字符串
    private String toJson(MemoryMessageVO message){
        try{
            return objectMapper.writeValueAsString(message);
        }catch(JsonProcessingException e){
            throw new IllegalStateException("历史消息序列化失败",e);
        }
    }
}
