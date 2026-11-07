package com.zlh.aiagent.service.common;

import com.zlh.aiagent.instances.IHistoryStoreService;
import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版历史归档服务：把被上下文压缩丢弃的消息按会话暂存到内存中，供后续追溯。
 *
 * <p>功能分区：
 * <ul>
 *   <li>【A】存储结构</li>
 *   <li>【B】写入：归档被丢弃的消息</li>
 *   <li>【C】读取：获取历史消息</li>
 * </ul>
 */
@Slf4j
@Service
public class InMemoryHistoryStoreService implements IHistoryStoreService {

    //region 【A】存储结构

    /** 会话 ID → 被丢弃消息列表，使用 ConcurrentHashMap 保证并发安全 */
    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    //endregion

    //region 【B】写入：归档被丢弃的消息

    @Override
    public void saveDropped(String sessionId, List<ChatMessage> droppedMessages) {
        if (droppedMessages == null || droppedMessages.isEmpty()) return;

        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).addAll(droppedMessages);
        log.info("归档 {} 条被丢弃的消息到历史库, session={}", droppedMessages.size(), sessionId);
    }

    //endregion

    //region 【C】读取：获取历史消息

    @Override
    public List<ChatMessage> getHistory(String sessionId, int limit) {
        List<ChatMessage> all = store.getOrDefault(sessionId, new ArrayList<>());
        int start = Math.max(0, all.size() - limit);
        return new ArrayList<>(all.subList(start, all.size()));
    }

    //endregion
}
