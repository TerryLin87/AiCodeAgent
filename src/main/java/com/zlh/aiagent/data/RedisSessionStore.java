package com.zlh.aiagent.data;

import com.zlh.aiagent.instances.ISessionStore;
import lombok.extern.slf4j.Slf4j;
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
    private static final String KEY_PREFIX = "agent:session:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, AgentSession> redisTemplate;

    public RedisSessionStore(RedisTemplate<String, AgentSession> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public AgentSession get(String sessionId) {
        if (sessionId == null) return null;
        String key = KEY_PREFIX + sessionId;
        AgentSession session = redisTemplate.opsForValue().get(key);
        if (session != null) {
            redisTemplate.expire(key, TTL);   // 刷新 TTL
        }
        return session;
    }

    @Override
    public void put(AgentSession session) {
        if (session == null || session.getSessionId() == null) return;
        redisTemplate.opsForValue().set(KEY_PREFIX + session.getSessionId(), session, TTL);
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
