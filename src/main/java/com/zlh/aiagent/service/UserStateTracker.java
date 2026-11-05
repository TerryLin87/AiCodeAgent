package com.zlh.aiagent.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class UserStateTracker {
    @Data
    public static class StateChange {
        private int sequence;
        private String originalText;
        private String targetState;
        private LocalDateTime timestamp;
    }

    // sessionId -> 状态变更历史
    private final Map<String, List<StateChange>> stateHistory = new ConcurrentHashMap<>();

    // 简易规则：匹配 “改成A”、“用B”、“按C来”、“就看D” 等模式
    private static final Pattern STATE_PATTERN = Pattern.compile(
            "(?:改成|换成|改为|换为|切换到|调整为|变更成|变成)\\s*"
                    + "([a-zA-Z0-9\\u4e00-\\u9fa5_\\-]{2,10})");
    /**
     * 提取并记录状态变更
     */
    public void recordState(String sessionId, String userMessage) {
        Matcher m = STATE_PATTERN.matcher(userMessage);
        if (m.find()) {
            String target = m.group(1);
            List<StateChange> history = stateHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());

            StateChange change = new StateChange();
            change.setSequence(history.size() + 1);
            change.setOriginalText(userMessage);
            change.setTargetState(target);
            change.setTimestamp(LocalDateTime.now());

            history.add(change);
            log.info("记录状态变更: session={}, 序号={}, 目标={}", sessionId, change.getSequence(), target);
        }
    }

    /**
     * 解析指代（如“第二个”、“之前的”）
     */
    public String resolveReference(String sessionId, String userMessage) {
        List<StateChange> history = stateHistory.getOrDefault(sessionId, List.of());
        if (history.isEmpty()) return null;

        // 匹配“第二个”、“第2个”
        Matcher numMatcher = Pattern.compile("第\\s*([0-9一二三四五]+)\\s*个").matcher(userMessage);
        if (numMatcher.find()) {
            int index = parseChineseNumber(numMatcher.group(1));
            if (index > 0 && index <= history.size()) {
                return history.get(index - 1).getTargetState();
            }
        }

        // 匹配“原来的”、“上一次的”
        if (userMessage.contains("原来") || userMessage.contains("之前") || userMessage.contains("上一次")) {
            if (history.size() >= 2) {
                // 返回倒数第二次的状态
                return history.get(history.size() - 2).getTargetState();
            } else if (history.size() == 1) {
                return history.get(0).getTargetState();
            }
        }
        return null;
    }

    private int parseChineseNumber(String s) {
        return switch (s) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            case "五", "5" -> 5;
            default -> -1;
        };
    }
}
