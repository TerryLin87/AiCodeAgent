package com.zlh.aiagent.utils;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/** ChatMessage 与字符串互转，格式：role\0text */
public final class ChatMessageCodec {

    private static final char SEP = '\u0000';

    private ChatMessageCodec() {
    }

    public static String encode(ChatMessage m) {
        if (m instanceof AiMessage ai) return "ai" + SEP + nz(ai.text());
        if (m instanceof SystemMessage sys) return "system" + SEP + nz(sys.text());
        if (m instanceof UserMessage user) return "user" + SEP + nz(user.singleText());
        return "user" + SEP + nz(m.toString());
    }

    public static ChatMessage decode(String s) {
        if (s == null) return null;
        int i = s.indexOf(SEP);
        if (i < 0) return UserMessage.from(s);
        String role = s.substring(0, i);
        String text = s.substring(i + 1);
        return switch (role) {
            case "ai" -> AiMessage.from(text);
            case "system" -> SystemMessage.from(text);
            default -> UserMessage.from(text);
        };
    }

    public static List<String> encodeAll(List<ChatMessage> list) {
        List<String> r = new ArrayList<>();
        if (list != null) {
            for (ChatMessage m : list) {
                if (m != null) r.add(encode(m));
            }
        }
        return r;
    }

    public static List<ChatMessage> decodeAll(List<String> list) {
        List<ChatMessage> r = new ArrayList<>();
        if (list != null) {
            for (String s : list) {
                ChatMessage m = decode(s);
                if (m != null) r.add(m);
            }
        }
        return r;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
