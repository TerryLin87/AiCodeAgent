package com.zlh.aiagent.data;

import java.util.Map;

/**
 * 通用检索结果接口。
 * 引擎层只依赖本接口，不依赖具体领域的数据模型。
 */
public interface RetrievalResult {

    /** 文本内容 */
    String getContent();

    /** 标题（类名 / 文档标题） */
    String getTitle();

    /** 来源（文件路径 / URL） */
    String getSource();

    /** 分类（class / method / clause / case） */
    String getCategory();

    /** 相似度分数 */
    double getScore();

    /**
     * 获取指定 key 的元数据值。
     * <b>具体有哪些 key 由实现类决定，接口不预设。</b>
     *
     * @param key 元数据 key
     * @return 值，不存在时返回 null
     */
    default String getMetadata(String key) {
        return null;
    }

    /**
     * 获取全部元数据（调试 / 序列化 / 通用展示用）。
     * 默认返回空 Map，实现类可覆盖。
     */
    default Map<String, String> getAllMetadata() {
        return Map.of();
    }

    /**
     * 稳定引用 ID，用于去重和前端定位。
     *
     * <p>默认实现基于 {@code projectName:filePath:className:methodName} 的 hash。
     * 子类可覆盖提供更精确的 ID。
     *
     * @return 十六进制 hash 字符串，永不为 null
     */
    default String getSourceId() {
        String project = getMetadata("projectName");
        String filePath = getMetadata("filePath");
        String className = getMetadata("className");
        String methodName = getMetadata("methodName");

        String key = String.join(":",
                project == null ? "" : project,
                filePath == null ? "" : filePath,
                className == null ? "" : className,
                methodName == null ? "" : methodName);

        return Integer.toHexString(key.hashCode());
    }
}