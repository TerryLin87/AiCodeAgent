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
import tools.jackson.databind.ObjectMapper;

import java.util.*;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 可配置参数 ====================
    /**
     * 触发压缩的最大消息数
     */
    @Getter
    @Setter
    @Value("${agent.context.max-messages:20}")
    private int maxMessages = 20;

    /**
     * 触发压缩的最大 Token 数（估算）
     */
    @Getter
    @Setter
    @Value("${agent.context.max-tokens:8000}")
    private int maxTokens = 8000;

    /**
     * 压缩后保留的最近消息数
     */
    @Getter
    @Setter
    @Value("${agent.context.keep-recent:5}")
    private int keepRecent = 5;

    /**
     * 是否启用 LLM 摘要压缩
     */
    @Setter
    @Value("${agent.context.enable-summary:true}")
    private boolean enableSummary = true;

    /**
     * 每条消息的平均 Token 数（估算用）
     */
    private static final int AVG_TOKENS_PER_CHAR = 3;

    //region 核心方法

    /**
     * 检查是否需要压缩
     */
    public boolean needsCompression(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }

        // 条件1：消息数量超过阈值
        if (messages.size() > maxMessages) {
            log.info("📊 消息数量 {} > {}, 需要压缩", messages.size(), maxMessages);
            return true;
        }

        // 条件2：估算 Token 数超过阈值
        int estimatedTokens = estimateTokens(messages);
        if (estimatedTokens > maxTokens) {
            log.info("📊 估算 Token {} > {}, 需要压缩", estimatedTokens, maxTokens);
            return true;
        }

        return false;
    }

    /**
     * 压缩对话历史
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

        log.info("🔄 开始上下文压缩，原始消息数: {}", messages.size());
        long startTime = System.currentTimeMillis();

        try {
            List<ChatMessage> result = messages;

            // ===== Level 1: 丢弃最旧的非关键消息 =====
            result = level1PruneOldMessages(result);
            log.debug("Level 1 压缩后消息数: {}", result.size());

            // 如果已经满足条件，提前返回
            if (!needsCompression(result)) {
                log.info("✅ 压缩完成 (Level 1)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 2: 合并连续的短消息 =====
            result = level2MergeShortMessages(result);
            log.debug("Level 2 压缩后消息数: {}", result.size());

            if (!needsCompression(result)) {
                log.info("✅ 压缩完成 (Level 2)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 3: LLM 摘要压缩 =====
            if (enableSummary && result.size() > keepRecent * 2) {
                result = level3Summarize(result);
                log.debug("Level 3 压缩后消息数: {}", result.size());

                if (!needsCompression(result)) {
                    log.info("✅ 压缩完成 (Level 3)，耗时: {}ms", System.currentTimeMillis() - startTime);
                    return result;
                }
            }

            // ===== Level 4: 提取关键实体和事实 =====
            result = level4ExtractKeyInfo(result);
            log.debug("Level 4 压缩后消息数: {}", result.size());

            if (!needsCompression(result)) {
                log.info("✅ 压缩完成 (Level 4)，耗时: {}ms", System.currentTimeMillis() - startTime);
                return result;
            }

            // ===== Level 5: 截断到最大长度 =====
            result = level5Truncate(result);
            log.info("✅ 压缩完成 (Level 5)，最终消息数: {}, 耗时: {}ms",
                    result.size(), System.currentTimeMillis() - startTime);

            return result;

        } catch (Exception e) {
            log.error("❌ 上下文压缩失败", e);
            // 降级：只保留系统提示和最近 N 条消息
            return fallbackCompress(messages);
        }
    }

    /**
     * 压缩对话历史，返回摘要文本（用于替换原有消息）
     *
     * @param messages 原始消息列表
     * @return 压缩后的摘要文本
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
            // 提取需要摘要的部分（跳过系统提示，保留最近 keepRecent 条）
            List<ChatMessage> toSummarize = new ArrayList<>();
            List<ChatMessage> recent = new ArrayList<>();

            int idx = 0;
            // 跳过系统提示
            if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
                idx = 1;
            }

            int endIdx = Math.max(idx, messages.size() - keepRecent);
            for (int i = idx; i < endIdx; i++) {
                toSummarize.add(messages.get(i));
            }
            for (int i = endIdx; i < messages.size(); i++) {
                recent.add(messages.get(i));
            }

            // 生成摘要
            String summary = generateSummary(toSummarize);

            // 格式化最近消息
            String recentText = formatMessagesAsText(recent);

            // 组合最终结果

            return "【对话摘要】\n" +
                    summary +
                    "\n\n【最近对话】\n" +
                    recentText;

        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return formatMessagesAsText(messages);
        }
    }

    /**
     * 将消息列表格式化为文本（用于降级场景）
     */
    private String formatMessagesAsText(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            // 跳过系统消息
            if (msg instanceof SystemMessage) {
                continue;
            }
            String role = msg instanceof UserMessage ? "用户" :
                    msg instanceof AiMessage ? "助手" : "系统";
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
     * Level 1: 丢弃最旧的非关键消息
     * 保留：系统提示 + 最近 N 条消息 + 用户的关键问题
     */
    private List<ChatMessage> level1PruneOldMessages(List<ChatMessage> messages) {
        if (messages.size() <= maxMessages) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();

        // 1. 保留系统提示
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                result.add(msg);
                break;
            }
        }

        // 2. 保留最近 keepRecent 条消息
        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            // 避免重复添加系统消息   
            if (!(msg instanceof SystemMessage) || result.contains(msg)) {
                result.add(msg);
            }
        }

        // 3. 如果结果太少，补充一些中间的重要消息
        if (result.size() < keepRecent + 1 && messages.size() > keepRecent + 1) {
            // 从中间补充一些用户消息
            for (int i = 1; i < messages.size() - keepRecent && result.size() < maxMessages; i++) {
                ChatMessage msg = messages.get(i);
                if (msg instanceof UserMessage && !result.contains(msg)) {
                    result.add(msg);
                }
            }
        }

        return result;
    }

    /**
     * Level 2: 合并连续的短消息
     * 将相邻的短消息（长度 < 100 字符）合并为一条
     */
    private List<ChatMessage> level2MergeShortMessages(List<ChatMessage> messages) {
        if (messages.size() <= 1) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();
        List<ChatMessage> buffer = new ArrayList<>();
        int bufferLength = 0;
        int SHORT_THRESHOLD = 100;

        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            boolean isShort = content != null && content.length() < SHORT_THRESHOLD;

            if (isShort) {
                buffer.add(msg);
                bufferLength += content.length();
            } else {
                // 遇到长消息，先处理缓冲区
                if (!buffer.isEmpty()) {
                    result.add(mergeBuffer(buffer));
                    buffer.clear();
                    bufferLength = 0;
                }
                result.add(msg);
            }
        }

        // 处理剩余的缓冲区
        if (!buffer.isEmpty()) {
            result.add(mergeBuffer(buffer));
        }

        return result;
    }

    /**
     * Level 3: LLM 摘要压缩
     * 将中间部分对话压缩为摘要
     */
    private List<ChatMessage> level3Summarize(List<ChatMessage> messages) {
        if (messages.size() <= keepRecent + 1) {
            return messages;
        }

        // 提取需要摘要的部分（跳过系统提示，保留最近 keepRecent 条）
        String systemPrompt = "";
        List<ChatMessage> toSummarize = new ArrayList<>();
        List<ChatMessage> recent = new ArrayList<>();

        int idx = 0;
        // 提取系统提示
        if (!messages.isEmpty() && messages.get(0) instanceof SystemMessage) {
            systemPrompt = getMessageContent(messages.get(0));
            idx = 1;
        }

        // 计算需要摘要的范围
        int endIdx = Math.max(idx, messages.size() - keepRecent);
        for (int i = idx; i < endIdx; i++) {
            toSummarize.add(messages.get(i));
        }
        for (int i = endIdx; i < messages.size(); i++) {
            recent.add(messages.get(i));
        }

        // 如果摘要部分为空或太少，直接返回
        if (toSummarize.size() <= 2) {
            return messages;
        }

        log.info("📝 生成摘要，消息数: {}", toSummarize.size());

        // 调用 LLM 生成摘要
        String summary = generateSummary(toSummarize);
        log.debug("摘要长度: {} 字符", summary.length());

        // 构建压缩后的消息列表
        List<ChatMessage> result = new ArrayList<>();

        // 1. 系统消息（包含原有系统提示和摘要）
        String combinedSystem = systemPrompt + "\n\n【对话摘要】\n" + summary;
        result.add(SystemMessage.from(combinedSystem));

        // 2. 最近的消息
        result.addAll(recent);

        return result;
    }

    /**
     * Level 4: 提取关键实体和事实
     * 从对话中提取关键信息（类名、方法名、项目名等）
     */
    private List<ChatMessage> level4ExtractKeyInfo(List<ChatMessage> messages) {
        // 这是一个简化的实现
        // 实际可以使用实体识别 NLP 或正则提取
        // 当前实现：从消息中提取看起来像类名/方法名的内容

        List<ChatMessage> result = new ArrayList<>();
        Set<String> extractedEntities = new HashSet<>();

        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            if (content == null) {
                result.add(msg);
                continue;
            }

            // 提取类名（简单正则）
            List<String> classNames = extractClassNames(content);
            extractedEntities.addAll(classNames);

            // 提取方法名
            List<String> methodNames = extractMethodNames(content);
            extractedEntities.addAll(methodNames);

            result.add(msg);
        }

        // 在第一条消息前添加提取的实体摘要
        if (!extractedEntities.isEmpty()) {
            String entitySummary = "【关键实体】" + String.join(", ", extractedEntities);
            // 插入到第一条消息之后（如果有系统消息则作为系统消息的一部分）
            if (result.get(0) instanceof SystemMessage) {
                String oldContent = getMessageContent(result.get(0));
                String newContent = oldContent + "\n\n" + entitySummary;
                result.set(0, SystemMessage.from(newContent));
            } else {
                // 在开头添加一条实体消息
                result.add(0, SystemMessage.from(entitySummary));
            }
        }

        return result;
    }

    /**
     * Level 5: 截断到最大长度
     */
    private List<ChatMessage> level5Truncate(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();

        // 保留系统提示
        String systemContent = "";
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                systemContent = getMessageContent(msg);
                break;
            }
        }

        // 保留最近 keepRecent 条消息
        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (!(msg instanceof SystemMessage) || !result.contains(msg)) {
                result.add(msg);
            }
        }

        // 如果结果还是太长，截断每条消息的内容
        List<ChatMessage> truncated = new ArrayList<>();
        for (ChatMessage msg : result) {
            String content = getMessageContent(msg);
            if (content != null && content.length() > 2000) {
                content = content.substring(0, 2000) + "\n... (内容已截断)";
                if (msg instanceof SystemMessage) {
                    truncated.add(SystemMessage.from(content));
                } else if (msg instanceof UserMessage) {
                    truncated.add(UserMessage.from(content));
                } else if (msg instanceof AiMessage) {
                    truncated.add(AiMessage.from(content));
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
     * 估算 Token 数
     */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            String content = getMessageContent(msg);
            if (content != null) {
                total += content.length() / AVG_TOKENS_PER_CHAR;
            }
        }
        return total;
    }

    /**
     * 获取消息内容
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
     * 合并缓冲区中的短消息
     */
    private ChatMessage mergeBuffer(List<ChatMessage> buffer) {
        String merged = buffer.stream()
                .map(this::getMessageContent)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" | "));
        return UserMessage.from("[合并] " + merged);
    }

    /**
     * 生成 LLM 摘要
     */
    private String generateSummary(List<ChatMessage> messages) {
        String content = messages.stream()
                .map(msg -> {
                    String role = msg instanceof UserMessage ? "用户" :
                            msg instanceof AiMessage ? "助手" : "系统";
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
            // 清理可能的多余内容
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
     * 提取类名
     */
    private List<String> extractClassNames(String text) {
        List<String> results = new ArrayList<>();
        // 简单正则：匹配驼峰命名且以大写字母开头
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([A-Z][a-zA-Z0-9]+(Service|Controller|Manager|Util|Helper|Repository|Entity|DTO|VO|DAO))\\b"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
        return results;
    }

    /**
     * 提取方法名
     */
    private List<String> extractMethodNames(String text) {
        List<String> results = new ArrayList<>();
        // 简单正则：匹配小写开头的方法名模式
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "\\b([a-z][a-zA-Z0-9]*(ById|ByName|ByUser|find|get|set|create|update|delete|save|list)\\w*)\\b"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(matcher.group(1));
        }
        return results;
    }

    /**
     * 降级压缩
     */
    private List<ChatMessage> fallbackCompress(List<ChatMessage> messages) {
        log.warn("使用降级压缩方案");
        List<ChatMessage> result = new ArrayList<>();

        // 保留系统提示
        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage) {
                result.add(msg);
                break;
            }
        }

        // 保留最近的消息
        int start = Math.max(0, messages.size() - keepRecent);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (!(msg instanceof SystemMessage) || result.contains(msg)) {
                result.add(msg);
            }
        }

        return result;
    }

    //endregion
}
