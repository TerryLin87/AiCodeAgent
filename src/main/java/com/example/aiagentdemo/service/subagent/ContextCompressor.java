package com.example.aiagentdemo.service.subagent;

import com.example.aiagentdemo.model.DeepSeekModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
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
 * 上下文压缩器
 * 五级压缩策略（参考 Claude Code）：
 * Level 1: 丢弃最旧的非关键消息（保留系统提示和最近 N 条）
 * Level 2: 合并连续的短消息（将相邻的短消息合并为一条）
 * Level 3: 用 LLM 生成摘要（将中间部分对话压缩为摘要）
 * Level 4: 提取关键实体和事实（结构化信息保留）
 * Level 5: 截断到最大长度（最后的保底策略）
 * 使用方式：
 * <pre>
 * ContextCompressor compressor = new ContextCompressor();
 * compressor.setMaxMessages(20);
 * compressor.setMaxTokens(8000);
 * List<ChatMessage> compressed = compressor.compress(messages);
 * </pre>
 */
@Slf4j
@Component
public class ContextCompressor {

    @Autowired
    @Qualifier("DeepSeekModel")
    private DeepSeekModel deepSeekModel;

    // ==================== 可配置参数 ====================

    // 触发压缩的最大消息数
    @Getter
    @Setter
    @Value("${agent.context.max-messages:20}")
    private int maxMessages = 20;

    // 触发压缩的最大 Token 数（估算）
    @Getter
    @Setter
    @Value("${agent.context.max-tokens:8000}")
    private int maxTokens = 8000;

    // 压缩后保留的最近消息数
    @Getter
    @Setter
    @Value("${agent.context.keep-recent:5}")
    private int keepRecent = 5;

    // 是否启用 LLM 摘要压缩
    @Setter
    @Value("${agent.context.enable-summary:true}")
    private boolean enableSummary = true;

    // 短消息阈值（字符数），低于该长度的相邻同角色消息会被合并
    private static final int SHORT_THRESHOLD = 100;

    // 单条消息截断上限（字符数）
    private static final int MAX_MESSAGE_CHARS = 2000;

    //region 核心方法

    /**
     * 检查是否需要压缩。
     */
    public boolean needsCompression(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        // 条件1：消息数量超过阈值
        if (messages.size() > maxMessages) {
            log.info("消息数量 {} > {}, 需要压缩", messages.size(), maxMessages);
            return true;
        }

        // 条件2：估算 Token 数超过阈值
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens > maxTokens) {
            log.info("估算 Token {} > {}, 需要压缩", estimatedTokens, maxTokens);
            return true;
        }
        return false;
    }

    /**
     * 压缩对话历史。
     *
     * @param messages 原始消息列表
     * @return 压缩后的消息列表
     */
    public List<ChatMessage> compress(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        if (!needsCompression(messages)) {
            return messages;
        }

        log.info("开始上下文压缩，原始消息数: {}", messages.size());
        long startTime = System.currentTimeMillis();

        try {
            List<ChatMessage> result = messages;

            // ===== Level 1: 丢弃最旧的非关键消息 =====
            result = level1PruneOldMessages(result);
            log.debug("Level 1 压缩后消息数: {}", result.size());

            // 如果已经满足条件，提前返回
            if (!needsCompression(result)) {
                log.info("压缩完成 (Level 1)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 2: 合并连续的短消息（保持角色） =====
            result = level2MergeShortMessages(result);
            log.debug("Level 2 压缩后消息数: {}", result.size());
            if (!needsCompression(result)) {
                log.info("压缩完成 (Level 2)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 3: LLM 摘要压缩 =====
            if (enableSummary && result.size() > keepRecent * 2) {
                result = level3Summarize(result);
                log.debug("Level 3 压缩后消息数: {}", result.size());
                if (!needsCompression(result)) {
                    log.info("压缩完成 (Level 3)，耗时: {}ms", System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // ===== Level 4: 提取关键实体和事实 =====
            result = level4ExtractKeyInfo(result);
            log.debug("Level 4 压缩后消息数: {}", result.size());
            if (!needsCompression(result)) {
                log.info("压缩完成 (Level 4)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 5: 截断到最大长度 =====
            result = level5Truncate(result);
            log.info("压缩完成 (Level 5)，最终消息数: {}, 耗时: {}ms",
                    result.size(), System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.error("上下文压缩失败，降级处理", e);
            return fallbackCompress(messages);
        }
    }

    /**
     * 压缩对话历史，返回摘要文本（用于替换原有消息）。
     */
    public String compressToSummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        if (!needsCompression(messages)) {
            return formatMessagesAsText(messages);
        }

        log.info("生成对话摘要，原始消息数: {}", messages.size());
        try {
            int systemIdx = indexOfSystemMessage(messages);
            List<ChatMessage> toSummarize = new ArrayList<>();
            List<ChatMessage> recent = new ArrayList<>();

            int endIdx = Math.max(0, messages.size() - keepRecent);
            for (int i = 0; i < endIdx; i++) {
                if (i != systemIdx) {
                    toSummarize.add(messages.get(i));
                }
            }
            for (int i = endIdx; i < messages.size(); i++) {
                if (i != systemIdx) {
                    recent.add(messages.get(i));
                }
            }

            String summary = generateSummary(toSummarize);
            String recentText = formatMessagesAsText(recent);

            return "【对话摘要】\n" + summary + "\n\n【最近对话】\n" + recentText;
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return formatMessagesAsText(messages);
        }
    }

    /**
     * 将消息列表格式化为文本（用于降级场景）。
     */
    private String formatMessagesAsText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                continue;
            }
            String role = msg instanceof UserMessage ? "用户"
                    : msg instanceof AiMessage ? "助手" : "系统";
            String content = getMessageContent(msg);
            if (content != null && !content.isEmpty()) {
                sb.append("[").append(role).append("] ").append(content).append("\n");
            }
        }
        return sb.toString();
    }

    //endregion

    //region 五级压缩策略

    /**
     * Level 1: 丢弃最旧的非关键消息。
     * 保留：系统提示（仅第一条）+ 最近 keepRecent 条。
     */
    private List<ChatMessage> level1PruneOldMessages(List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();

        // 1. 保留系统提示（只保留第一条）
        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) {
            result.add(messages.get(systemIdx));
        }

        // 2. 保留最近 keepRecent 条消息（跳过系统消息，避免重复）
        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                continue;
            }
            result.add(msg);
        }

        // 3. 结果太少时，从历史中补充用户消息（用户意图优先级高于助手输出）
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

    /**
     * Level 2: 合并连续的短消息。
     * <p>关键修复：只合并**相邻且同角色**的短消息，系统消息一律不参与合并，
     * 从而不会再出现"AiMessage 被改写成 UserMessage"的角色污染。
     */
    private List<ChatMessage> level2MergeShortMessages(List<ChatMessage> messages) {
        if (messages.size() <= 1) {
            return messages;
        }

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

    private void flushBuffer(List<ChatMessage> buffer, List<ChatMessage> out) {
        if (buffer.isEmpty()) {
            return;
        }
        out.add(mergeBuffer(buffer));
        buffer.clear();
    }

    private boolean sameRole(ChatMessage a, ChatMessage b) {
        return (a instanceof UserMessage && b instanceof UserMessage)
                || (a instanceof AiMessage && b instanceof AiMessage);
    }

    /**
     * Level 3: LLM 摘要压缩。
     * <p>修复：原实现只在 {@code messages.get(0)} 是 SystemMessage 时才提取系统提示，
     * 否则会生成一条内容为空前缀的 SystemMessage。这里改为全表扫描定位系统消息。
     */
    private List<ChatMessage> level3Summarize(List<ChatMessage> messages) {
        if (messages.size() <= keepRecent + 1) {
            return messages;
        }

        int systemIdx = indexOfSystemMessage(messages);
        String systemPrompt = systemIdx >= 0 ? getMessageContent(messages.get(systemIdx)) : "";

        List<ChatMessage> toSummarize = new ArrayList<>();
        List<ChatMessage> recent = new ArrayList<>();

        int endIdx = Math.max(0, messages.size() - keepRecent);
        for (int i = 0; i < endIdx; i++) {
            if (i != systemIdx) {
                toSummarize.add(messages.get(i));
            }
        }
        for (int i = endIdx; i < messages.size(); i++) {
            if (i != systemIdx) {
                recent.add(messages.get(i));
            }
        }

        if (toSummarize.size() <= 2) {
            return messages;
        }

        log.info("生成摘要，消息数: {}", toSummarize.size());
        String summary = generateSummary(toSummarize);
        log.debug("摘要长度: {} 字符", summary.length());

        List<ChatMessage> result = new ArrayList<>();
        String combinedSystem = (systemPrompt == null ? "" : systemPrompt)
                + "\n\n【对话摘要】\n" + summary;
        result.add(SystemMessage.from(combinedSystem.trim()));
        result.addAll(recent);
        return result;
    }

    /**
     * Level 4: 提取关键实体和事实。
     * <p>注意：这一级只做"信息固化"，本身不减消息数；若调用方仍未满足阈值，
     * 会继续走 Level 5。
     */
    private List<ChatMessage> level4ExtractKeyInfo(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>(messages);
        Set<String> extractedEntities = new HashSet<>();

        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            if (content == null) {
                continue;
            }
            extractedEntities.addAll(extractClassNames(content));
            extractedEntities.addAll(extractMethodNames(content));
        }

        if (!extractedEntities.isEmpty()) {
            String entitySummary = "【关键实体】" + String.join(", ", extractedEntities);
            if (result.get(0) instanceof SystemMessage) {
                String oldContent = getMessageContent(result.get(0));
                result.set(0, SystemMessage.from(
                        (oldContent == null ? "" : oldContent) + "\n\n" + entitySummary));
            } else {
                result.add(0, SystemMessage.from(entitySummary));
            }
        }
        return result;
    }

    /**
     * Level 5: 截断到最大长度（最终保底）。
     * <p>修复：原条件 {@code !(msg instanceof SystemMessage) || !result.contains(msg)}
     * 在"结果里还没有系统消息"时会把最近窗口内的系统消息再次加入，造成重复；
     * 这里改为系统消息由第 1 步统一保留，最近窗口内跳过。
     */
    private List<ChatMessage> level5Truncate(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();

        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) {
            result.add(messages.get(systemIdx));
        }

        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                continue;
            }
            result.add(msg);
        }

        // 逐条截断超长内容，并保持原角色
        List<ChatMessage> truncated = new ArrayList<>(result.size());
        for (ChatMessage msg : result) {
            String content = getMessageContent(msg);
            if (content != null && content.length() > MAX_MESSAGE_CHARS) {
                String cut = content.substring(0, MAX_MESSAGE_CHARS) + "\n... (内容已截断)";
                if (msg instanceof SystemMessage) {
                    truncated.add(SystemMessage.from(cut));
                } else if (msg instanceof UserMessage) {
                    truncated.add(UserMessage.from(cut));
                } else if (msg instanceof AiMessage) {
                    truncated.add(AiMessage.from(cut));
                } else {
                    truncated.add(msg);
                }
            } else {
                truncated.add(msg);
            }
        }
        return truncated;
    }

    //endregion

    //region 辅助方法

    /**
     * 估算整个消息列表的 Token 数（保守：宁可高估）。
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimateTokens(getMessageContent(msg));
        }
        return total;
    }

    /**
     * 估算单段文本的 Token 数。
     * <p>经验值（保守上界）：CJK 字符约 1 token/字，其余字符约 0.3 token/字符。
     * 原实现用"字符数/3"，对中文低估约 3 倍，会导致压缩触发过晚。
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isCjk = (c >= 0x4E00 && c <= 0x9FFF)   // 中日韩统一表意文字
                    || (c >= 0x3000 && c <= 0x303F)         // CJK 标点
                    || (c >= 0xFF00 && c <= 0xFFEF);        // 全角字符
            if (isCjk) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + (int) Math.ceil(other * 0.3);
    }

    /**
     * 获取消息内容。
     */
    private String getMessageContent(ChatMessage message) {
        if (message == null) {
            return null;
        }
        if (message instanceof SystemMessage) {
            return ((SystemMessage) message).text();
        } else if (message instanceof UserMessage) {
            return ((UserMessage) message).singleText();
        } else if (message instanceof AiMessage) {
            return ((AiMessage) message).text();
        }
        return null;
    }

    /**
     * 定位第一条系统消息，找不到返回 -1。
     */
    private int indexOfSystemMessage(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 合并缓冲区中的短消息，**保持原角色**。
     * 缓冲区按角色分组构造，因此这里要么全是 UserMessage，要么全是 AiMessage。
     */
    private ChatMessage mergeBuffer(List<ChatMessage> buffer) {
        if (buffer.size() == 1) {
            return buffer.get(0);
        }
        String merged = buffer.stream()
                .map(this::getMessageContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" | "));

        if (buffer.get(0) instanceof AiMessage) {
            return AiMessage.from("[合并] " + merged);
        }
        return UserMessage.from("[合并] " + merged);
    }

    /**
     * 生成 LLM 摘要。
     */
    private String generateSummary(List<ChatMessage> messages) {
        String content = messages.stream()
                .map(msg -> {
                    String role = msg instanceof UserMessage ? "用户"
                            : msg instanceof AiMessage ? "助手" : "系统";
                    return role + ": " + getMessageContent(msg);
                })
                .collect(Collectors.joining("\n"));

        String prompt = String.format("""
                请将以下对话浓缩为一份简洁的摘要，保留关键信息：
                - 用户的主要需求和问题
                - 已经采取的行动和工具调用
                - 重要发现和结论
                - 当前状态和未完成的工作

                要求：
                1. 摘要长度控制在 300 字以内
                2. 使用中文
                3. 只保留最关键的信息

                对话内容：
                %s

                摘要：
                """, content);

        try {
            String summary = deepSeekModel.chat(prompt);
            if (summary.length() > 500) {
                summary = summary.substring(0, 500);
            }
            return summary;
        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return "【摘要生成失败，请查看原始对话】";
        }
    }

    /**
     * 提取类名。
     */
    private List<String> extractClassNames(String text) {
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([A-Z][a-zA-Z0-9]+(Service|Controller|Manager|Util|Helper|Repository|Entity|DTO|VO|DAO))\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
        return results;
    }

    /**
     * 提取方法名。
     */
    private List<String> extractMethodNames(String text) {
        List<String> results = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-z][a-zA-Z0-9]*(ById|ByName|ByUser|find|get|set|create|update|delete|save|list)\\w*)\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
        return results;
    }

    /**
     * 降级压缩：只保留系统提示 + 最近 N 条。
     */
    private List<ChatMessage> fallbackCompress(List<ChatMessage> messages) {
        log.warn("使用降级压缩方案");
        List<ChatMessage> result = new ArrayList<>();

        int systemIdx = indexOfSystemMessage(messages);
        if (systemIdx >= 0) {
            result.add(messages.get(systemIdx));
        }

        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage) {
                continue;
            }
            result.add(msg);
        }
        return result;
    }

    //endregion
}
