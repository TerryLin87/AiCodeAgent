package com.zlh.aiagent.data.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlh.aiagent.instances.ISessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "agent.session.store", havingValue = "redis")
public class RedisSessionStore implements ISessionStore {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    @Qualifier("agentSessionObjectMapper")
    private ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "agent:session:";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Override
    public AgentSession get(String sessionId) {
        if (sessionId == null) return null;
        String key = KEY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if(json == null){
            return null;
        }
        try {
            AgentSession session = objectMapper.readValue(json, AgentSession.class);
            session.rebuildChatMemory();
            redisTemplate.expire(key, TTL); // 刷新过期时间
            return session;
        } catch (JsonProcessingException e) {
            log.error("反序列化AgentSession失败 key={}", key, e);
            return null;
        }
    }

    @Override
    public void put(AgentSession session) {
        if (session == null || session.getSessionId() == null) return;
        String key = KEY_PREFIX + session.getSessionId();
        try {
            session.snapshotMessages();
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException e) {
            log.error("序列化AgentSession失败 sessionId={}", session.getSessionId(), e);
        }
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId != null) {
            redisTemplate.delete(KEY_PREFIX + sessionId);
        }
    }

    @Override
    public Set<String> getAllSessionIds() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null) return Set.of();
        return keys.stream()
                .map(k -> k.substring(KEY_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    @Override
    public int size() {
        return getAllSessionIds().size();
    }

    @Override
    public void clearAll() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
