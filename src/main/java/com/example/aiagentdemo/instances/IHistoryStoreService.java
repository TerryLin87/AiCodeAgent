package com.example.aiagentdemo.instances;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

public interface IHistoryStoreService {
    /**
     * 保存被上下文压缩丢弃的消息
     */
    void saveDropped(String sessionId, List<ChatMessage> droppedMessages);

    /**
     * 按时间顺序获取历史消息（用于回查）
     */
    List<ChatMessage> getHistory(String sessionId, int limit);
}
