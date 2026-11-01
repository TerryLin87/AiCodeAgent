package com.zlh.aiagent.instances;

import com.zlh.aiagent.data.AgentSession;

import java.util.Set;

/**
 * 会话存储抽象。
 * 生产环境用 RedisSessionStore，开发环境用 InMemorySessionStore。
 */
public interface ISessionStore {
    AgentSession get(String sessionId);

    void put(AgentSession session);

    void remove(String sessionId);

    Set<String> getAllSessionIds();

    int size();

    void clearAll();
}
