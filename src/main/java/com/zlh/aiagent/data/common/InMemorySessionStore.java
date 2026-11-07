package com.zlh.aiagent.data.common;

import com.zlh.aiagent.instances.ISessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnProperty(name = "agent.session.store", havingValue = "memory", matchIfMissing = true)
public class InMemorySessionStore implements ISessionStore {
    private final ConcurrentHashMap<String, AgentSession> sessions = new ConcurrentHashMap<>();

    @Override
    public AgentSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public void put(AgentSession session) {
        if (session != null && session.getSessionId() != null) {
            sessions.put(session.getSessionId(), session);
        }
    }

    @Override
    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    @Override
    public Set<String> getAllSessionIds() {
        return Set.copyOf(sessions.keySet());
    }

    @Override
    public int size() {
        return sessions.size();
    }

    @Override
    public void clearAll() {
        sessions.clear();
    }
}
