package com.example.rag.service.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Redis 对话历史缓存服务
 * 以 userId 为 key，缓存最近 N 轮对话上下文（User + AI 成对）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatHistoryCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.chat-history.max-context-rounds:10}")
    private int maxContextRounds;

    @Value("${rag.chat-history.ttl-minutes:60}")
    private int ttlMinutes;

    private static final String KEY_PREFIX = "rag:chat:history:";

    /**
     * 添加一轮对话到缓存
     */
    public void addMessage(String userId, String role, String content) {
        String key = buildKey(userId);
        List<ChatMessage> history = getHistory(userId);
        history.add(new ChatMessage(role, content));

        // 只保留最近 N 轮（User + AI 为 1 轮，即 2 条消息）
        int maxMessages = maxContextRounds * 2;
        if (history.size() > maxMessages) {
            history = history.subList(history.size() - maxMessages, history.size());
        }

        try {
            String json = objectMapper.writeValueAsString(history);
            redisTemplate.opsForValue().set(key, json, ttlMinutes, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存对话历史序列化失败", e);
        }
    }

    /**
     * 获取用户对话历史
     */
    public List<ChatMessage> getHistory(String userId) {
        String key = buildKey(userId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {});
        } catch (JsonProcessingException e) {
            log.error("Redis 缓存对话历史反序列化失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 格式化历史对话为 Prompt 字符串
     */
    public String formatHistory(String userId) {
        List<ChatMessage> history = getHistory(userId);
        if (history.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : history) {
            sb.append(msg.getRole()).append("：").append(msg.getContent()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 清空用户缓存
     */
    public void clearHistory(String userId) {
        String key = buildKey(userId);
        redisTemplate.delete(key);
    }

    private String buildKey(String userId) {
        return KEY_PREFIX + userId;
    }

    /**
     * 内部消息类
     */
    public static class ChatMessage {
        private String role;
        private String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }
}
