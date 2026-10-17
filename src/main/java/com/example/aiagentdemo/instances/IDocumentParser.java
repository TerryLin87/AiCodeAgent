package com.example.aiagentdemo.instances;

import com.example.aiagentdemo.data.FilePath;
import com.example.aiagentdemo.data.ParseResult;

import java.util.List;

/**
 * 文档解析器插件。
 *
 * <p>每个领域实现自己的解析器，索引流程按领域 + 文件扩展名动态选择。
 * 换领域时新增一个实现类 + 加 @ConditionalOnProperty，索引流程零改动。
 */
public interface IDocumentParser {

    /** 适用领域（对应 agent.domain.name） */
    String getDomain();

    /** 支持的文件扩展名（如 .java / .md / .pdf），带点 */
    List<String> getSupportedExtensions();

    /** 解析文件，返回片段 + 关系 */
    ParseResult parse(FilePath filePath, String content);
}