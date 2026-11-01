package com.example.aiagentdemo.service;

import com.example.aiagentdemo.instances.IHistoryStoreService;
import dev.langchain4j.agent.tool.CompensateFor;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class InMemoryHistoryStoreService implements IHistoryStoreService {
    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void saveDropped(String sessionId, List<ChatMessage> droppedMessages) {
        if (droppedMessages == null || droppedMessages.isEmpty()) return;

        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(droppedMessages);
        log.info("归档 {} 条被丢弃的消息到历史库, session={}", droppedMessages.size(), sessionId);
    }

    @Override
    public List<ChatMessage> getHistory(String sessionId, int limit) {
        List<ChatMessage> all = store.getOrDefault(sessionId, new ArrayList<>());
        int start = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(start, all.size()));
    }
}
