package com.zlh.aiagent.service.common;

import com.zlh.aiagent.config.DomainConfig;
import com.zlh.aiagent.enums.CallType;
import com.zlh.aiagent.instances.IHistoryStoreService;
import com.zlh.aiagent.model.DeepSeekModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上下文压缩器：按成本分层，逐级降级止损。
 */
@Slf4j
@Component
public class ContextCompressor {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    @Autowired
    private IHistoryStoreService historyStore;

    @Autowired
    private DomainConfig domainConfig;

    @Getter
    @Setter
    @Value("${agent.context.max-messages:20}")
    private int maxMessages = 20;

    @Getter
    @Setter
    @Value("${agent.context.max-tokens:8000}")
    private int maxTokens = 8000;

    @Getter
    @Setter
    @Value("${agent.context.keep-recent:5}")
    private int keepRecent = 5;

    @Setter
    @Value("${agent.context.enable-summary:true}")
    private boolean enableSummary = true;

    private static final int SHORT_THRESHOLD = 100;
    private static final int MAX_MESSAGE_CHARS = 2000;

    // ==================== 核心入口 ====================

    /**
     * 检查是否需要压缩。
     */
    public boolean needsCompression(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        if (messages.size() > maxMessages) {
            log.info("会话 {} 消息数量 {} > {}, 需要压缩", sessionId, messages.size(), maxMessages);
            return true;
        }
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens > maxTokens) {
            log.info("会话 {} 估算 Token {} > {}, 需要压缩", sessionId, estimatedTokens, maxTokens);
            return true;
        }
        return false;
    }

    /**
     * 压缩对话历史。
     */
    public List<ChatMessage> compress(String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return messages;
        if (!needsCompression(sessionId, messages)) return messages;

        log.info("会话 {} 开始上下文压缩，原始消息数: {}", sessionId, messages.size());
        long startTime = System.currentTimeMillis();

        try {
            List<ChatMessage> result = messages;

            // ===== Level 1 =====
            result = level1PruneOldMessages(sessionId, result);
            log.debug("Level 1 压缩后消息数: {}", result.size());
            if (!needsCompression(sessionId, result)) {
                log.info("压缩完成 (Level 1)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 2 =====
            result = level2MergeShortMessages(result);
            log.debug("Level 2 压缩后消息数: {}", result.size());
            if (!needsCompression(sessionId, result)) {
                log.info("压缩完成 (Level 2)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 3 =====
            if (enableSummary && result.size() > keepRecent * 2) {
                result = level3Summarize(result);
                log.debug("Level 3 压缩后消息数: {}", result.size());
                if (!needsCompression(sessionId, result)) {
                    log.info("压缩完成 (Level 3)，耗时: {}ms", System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // ===== Level 4 =====
            result = level4ExtractKeyInfo(result);
            log.debug("Level 4 压缩后消息数: {}", result.size());
            if (!needsCompression(sessionId, result)) {
                log.info("压缩完成 (Level 4)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 5 =====
            result = level5Truncate(result);
            log.info("压缩完成 (Level 5)，最终消息数: {}, 耗时: {}ms",
                    result.size(), System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("上下文压缩失败，降级处理", e);
            return fallbackCompress(messages);
        }
    }

    // ==================== Level 1 ====================

    /**
     * Level 1: 丢弃最旧的非关键消息，同时归档并固化用户原话。
     */
    private List<ChatMessage> level1PruneOldMessages(String sessionId, List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) return messages;

        List<ChatMessage> result = new ArrayList<>();
        List<ChatMessage> dropped = new ArrayList<>();
        List<String> droppedUserTexts = new ArrayList<>(); // 用于固化用户原话

        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) {
            result.add(messages.get(systemIdx));
        }

        int start = Math.max(0, messages.size() - keepRecent);

        // 收集被丢弃的消息
        for (int i = 0; i < start; i++) {
            if (i != systemIdx) {
                ChatMessage msg = messages.get(i);
                dropped.add(msg);
                if (msg instanceof UserMessage) {
                    String text = getMessageContent(msg);
                    if (text != null && !text.isBlank()) {
                        // 截断，防止固化内容过长
                        if (text.length() > 200) text = text.substring(0, 200) + "...";
                        droppedUserTexts.add(text);
                    }
                }
            }
        }

        // 1. 归档所有丢弃的消息
        historyStore.saveDropped(sessionId, dropped);

        // 2. 固化用户原话（最多保留最近的 3 条）
        if (!droppedUserTexts.isEmpty()) {
            int fromIdx = Math.max(0, droppedUserTexts.size() - 3);
            List<String> recentUserTexts = droppedUserTexts.subList(fromIdx, droppedUserTexts.size());
            String fixedPrompt = "\n\n【历史用户原话（已压缩出窗口，供指代回查）】\n- "
                    + String.join("\n- ", recentUserTexts);

            // 拼接到第一条 SystemMessage 中
            if (systemIdx >= 0) {
                String oldSys = getMessageContent(result.get(0));
                result.set(0, SystemMessage.from(oldSys + fixedPrompt));
            } else {
                result.add(0, SystemMessage.from(fixedPrompt.trim()));
            }
            log.debug("已固化 {} 条历史用户原话到系统提示中", recentUserTexts.size());
        }

        // 保留最近的消息
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) continue;
            result.add(msg);
        }

        // 结果太少时，补充一些旧用户消息
        if (result.size() < keepRecent + 1 && messages.size() > keepRecent + 1) {
            int from = systemIdx == 0 ? 1 : 0;
            for (int i = from; i < messages.size() - keepRecent && result.size() < maxMessages; i++) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage && !result.contains(msg)) {
                    result.add(msg);
                }
            }
        }

        return result;
    }

    // ==================== 其他 Level ====================

    private List<ChatMessage> level2MergeShortMessages(List<ChatMessage> messages) {
        if (messages.size() <= 1) return messages;

        List<ChatMessage> result = new ArrayList<>();
        List<ChatMessage> buffer = new ArrayList<>();

        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            boolean mergeable = !(msg instanceof SystemMessage)
                    && content != null
                    && content.length() < SHORT_THRESHOLD;

            if (mergeable && (buffer.isEmpty() || sameRole(buffer.get(0), msg))) {
                buffer.add(msg);
                continue;
            }

            flushBuffer(buffer, result);
            if (mergeable) {
                buffer.add(msg);
            } else {
                result.add(msg);
            }
        }
        flushBuffer(buffer, result);
        return result;
    }

    private List<ChatMessage> level3Summarize(List<ChatMessage> messages) {
        if (messages.size() <= keepRecent + 1) return messages;

        int systemIdx = indexOfSystemMessage(messages);
        String systemPrompt = systemIdx >= 0 ? getMessageContent(messages.get(systemIdx)) : "";

        List<ChatMessage> toSummarize = new ArrayList<>();
        List<ChatMessage> recent = new ArrayList<>();

        int endIdx = Math.max(0, messages.size() - keepRecent);
        for (int i = 0; i < endIdx; i++) {
            if (i != systemIdx) toSummarize.add(messages.get(i));
        }
        for (int i = endIdx; i < messages.size(); i++) {
            if (i != systemIdx) recent.add(messages.get(i));
        }

        if (toSummarize.size() <= 2) return messages;

        log.info("生成摘要，消息数: {}", toSummarize.size());
        String summary = generateSummary(toSummarize);
        log.debug("摘要长度: {} 字符", summary.length());

        List<ChatMessage> result = new ArrayList<>();
        String combinedSystem = (systemPrompt == null ? "" : systemPrompt) + "\n\n【对话摘要】\n" + summary;
        result.add(SystemMessage.from(combinedSystem.trim()));
        result.addAll(recent);
        return result;
    }

    private List<ChatMessage> level4ExtractKeyInfo(List<ChatMessage> messages) {
        if (messages.isEmpty()) return messages;

        List<ChatMessage> result = new ArrayList<>(messages);
        Set<String> extractedEntities = new HashSet<>();

        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            if (content == null) continue;
            extractedEntities.addAll(extractClassNames(content));
            extractedEntities.addAll(extractMethodNames(content));
        }

        if (!extractedEntities.isEmpty()) {
            String entitySummary = "【关键实体】" + String.join(", ", extractedEntities);
            if (result.get(0) instanceof SystemMessage) {
                String oldContent = getMessageContent(result.get(0));
                result.set(0, SystemMessage.from((oldContent == null ? "" : oldContent) + "\n\n" + entitySummary));
            } else {
                result.add(0, SystemMessage.from(entitySummary));
            }
        }
        return result;
    }

    private List<ChatMessage> level5Truncate(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();

        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) result.add(messages.get(systemIdx));

        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) continue;
            result.add(msg);
        }

        List<ChatMessage> truncated = new ArrayList<>(result.size());
        for (ChatMessage msg : result) {
            String content = getMessageContent(msg);
            if (content != null && content.length() > MAX_MESSAGE_CHARS) {
                String cut = content.substring(0, MAX_MESSAGE_CHARS) + "\n... (内容已截断)";
                if (msg instanceof SystemMessage) truncated.add(SystemMessage.from(cut));
                else if (msg instanceof UserMessage) truncated.add(UserMessage.from(cut));
                else if (msg instanceof AiMessage) truncated.add(AiMessage.from(cut));
                else truncated.add(msg);
            } else {
                truncated.add(msg);
            }
        }
        return truncated;
    }

    // ==================== 辅助方法 ====================

    public String compressToSummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        if (!needsCompression(null, messages)) return formatMessagesAsText(messages);

        log.info("生成对话摘要，原始消息数: {}", messages.size());
        try {
            int systemIdx = indexOfSystemMessage(messages);
            List<ChatMessage> toSummarize = new ArrayList<>();
            List<ChatMessage> recent = new ArrayList<>();

            int endIdx = Math.max(0, messages.size() - keepRecent);
            for (int i = 0; i < endIdx; i++) {
                if (i != systemIdx) toSummarize.add(messages.get(i));
            }
            for (int i = endIdx; i < messages.size(); i++) {
                if (i != systemIdx) recent.add(messages.get(i));
            }

            String summary = generateSummary(toSummarize);
            String recentText = formatMessagesAsText(recent);
            return "【对话摘要】\n" + summary + "\n\n【最近对话】\n" + recentText;
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return formatMessagesAsText(messages);
        }
    }

    private String formatMessagesAsText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) continue;
            String role = msg instanceof UserMessage ? "用户" : msg instanceof AiMessage ? "助手" : "系统";
            String content = getMessageContent(msg);
            if (content != null && !content.isEmpty()) {
                sb.append("[").append(role).append("] ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    private void flushBuffer(List<ChatMessage> buffer, List<ChatMessage> out) {
        if (buffer.isEmpty()) return;
        out.add(mergeBuffer(buffer));
        buffer.clear();
    }

    private boolean sameRole(ChatMessage a, ChatMessage b) {
        return (a instanceof UserMessage && b instanceof UserMessage)
                || (a instanceof AiMessage && b instanceof AiMessage);
    }

    private ChatMessage mergeBuffer(List<ChatMessage> buffer) {
        if (buffer.size() == 1) return buffer.get(0);
        String merged = buffer.stream().map(this::getMessageContent)
                .filter(Objects::nonNull).collect(Collectors.joining(" | "));
        if (buffer.get(0) instanceof AiMessage) return AiMessage.from("[合并] " + merged);
        return UserMessage.from("[合并] " + merged);
    }

    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) total += estimateTokens(getMessageContent(msg));
        return total;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3000 && c <= 0x303F) || (c >= 0xFF00 && c <= 0xFFEF)) {
                cjk++;
            }
        }
        return cjk + (int) Math.ceil((text.length() - cjk) * 0.3);
    }

    private String getMessageContent(ChatMessage message) {
        if (message == null) return null;
        if (message instanceof SystemMessage) return ((SystemMessage) message).text();
        if (message instanceof UserMessage) return ((UserMessage) message).singleText();
        if (message instanceof AiMessage) return ((AiMessage) message).text();
        return null;
    }

    private int indexOfSystemMessage(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) return i;
        }
        return -1;
    }

    private String generateSummary(List<ChatMessage> messages) {
        String content = messages.stream()
                .map(msg -> {
                    String role = msg instanceof UserMessage ? "用户" : msg instanceof AiMessage ? "助手" : "系统";
                    return role + ": " + getMessageContent(msg);
                })
                .collect(Collectors.joining("\n"));

        String template = (domainConfig != null && domainConfig.getSummaryPromptTemplate() != null
                && !domainConfig.getSummaryPromptTemplate().isBlank())
                ? domainConfig.getSummaryPromptTemplate()
                : """
              请将以下对话浓缩为一份简洁的摘要，保留关键信息：
              对话内容：
              {content}
              摘要：
              """;

        String prompt = template.replace("{content}", content);

        try {
            ChatRequest req = ChatRequest.builder().messages(List.of(UserMessage.from(prompt))).build();
            ChatResponse resp = deepSeekModel.doChat(req, CallType.SUMMARY, null);
            String summary = resp.aiMessage().text();
            if (summary != null && summary.length() > 500) summary = summary.substring(0, 500);
            return summary;
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return "【摘要生成失败，请查看原始对话】";
        }
    }

    private List<String> extractClassNames(String text) {
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([A-Z][a-zA-Z0-9]+(Service|Controller|Manager|Util|Helper|Repository|Entity|DTO|VO|DAO))\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) results.add(matcher.group(1));
        return results;
    }

    private List<String> extractMethodNames(String text) {
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-z][a-zA-Z0-9]*(ById|ByName|ByUser|find|get|set|create|update|delete|save|list)\\w*)\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) results.add(matcher.group(1));
        return results;
    }

    private List<ChatMessage> fallbackCompress(List<ChatMessage> messages) {
        log.warn("使用降级压缩方案");
        List<ChatMessage> result = new ArrayList<>();
        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) result.add(messages.get(systemIdx));

        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) continue;
            result.add(msg);
        }
        return result;
    }
}