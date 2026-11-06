package com.mall.ai.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;


/**
 * 问答缓存
 *
 * 失效策略的关键设计：把知识库版本号编进 key，而不是事后 SCAN 删除
 * 入库/删除文档时只做一次 {@code INCR}，所有旧 key 自然不再被命中
 * 过期后由 TTL 自动回收。SCAN + DEL 在数据量大时会阻塞 Redis，而且多实例下容易漏删
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaCacheService {

    private static final String KEY_PREFIX = "ai:cache:qa:";
    private static final String VERSION_KEY = "ai:cache:kb-version";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Value("${ai.cache.ttl-minutes:60}")
    private long ttlMinutes;

    // 缓存内容 只展示需要的字段，不存整个AskResultVO

    /**
     * @param answer 问答内容
     * @param hitCount 命中次数
     * @param titles 文档标题列表
     * @param usedContext 是否使用上下文
     */
    public record CachedAnswer(String answer, int hitCount, List<String> titles, boolean usedContext) {}

    // 获取知识库版本号
    public String kbVersion(){
        String version = redis.opsForValue().get(VERSION_KEY);
        return version == null ? "0" : version;
    }
    // 知识库版本号递增，旧问答缓存全部失效
    public void bumpKbVersion(){
        Long version = redis.opsForValue().increment(VERSION_KEY);
        log.info("知识库版本号递增 -> {} （旧问答缓存全部失效）", version);
    }
    // 获取缓存中的问答，如果缓存中没有，返回null
    public CachedAnswer get(String question){
        String key = buildKey(question);
        // 从缓存中获取
        String json = redis.opsForValue().get(key);
        if(json == null){
            return null;
        }
        try{
            return objectMapper.readValue(json, CachedAnswer.class);
        }catch(Exception e){
            log.warn("缓存反序列化失败，当作未命中：{}", e.getMessage());
            redis.delete(key);
            return null;
        }
    }
    // 写入缓存, 如果answer为空，不写入, 如果缓存序列化失败，也跳过写入
    public void put(String question, CachedAnswer answer){
        if(answer == null || answer.answer() == null || answer.answer().isBlank()){
            return;
        }
        try{
            redis.opsForValue().set(buildKey(question), objectMapper.writeValueAsString(answer),
                    Duration.ofMinutes(ttlMinutes));
        }catch (JsonProcessingException e){
            log.warn("缓存序列化失败，跳过写入：{}", e.getMessage());
        }
    }

    // 构建缓存key
    private String buildKey(String question) {
        // sha256Hex 是 64 字符 hex，可读、可比对；
        // Arrays.toString(byte[]) 是调试表示，200 字符还带负号
        return KEY_PREFIX + DigestUtils.sha256Hex(kbVersion() + "|" + normalize(question));
    }

    // 对问题进行归一化处理，去空格，转小写
    private String normalize(String question){
        return question == null
                ? " "
                : question.strip().replaceAll("\\s+", " ").toLowerCase();
    }


}
