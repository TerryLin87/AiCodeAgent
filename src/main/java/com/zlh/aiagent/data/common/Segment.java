package com.zlh.aiagent.data.common;

import java.util.Map;

/**
 * 通用文本片段接口。
 *
 * <p>所有领域的"片段"都实现这个接口：
 * <ul>
 *   <li>code 领域：{@link com.zlh.aiagent.data.code.CodeSegment}（类/方法片段）</li>
 *   <li>law 领域：{@code ClauseSegment}（条款片段）</li>
 *   <li>finance 领域：{@code TransactionSegment}（交易记录片段）</li>
 * </ul>
 *
 * <p>接口只暴露"通用"字段：内容、类型、来源、路径、元数据。
 * 领域特有字段（如 className/methodName）放在 {@link #getMetadata()} 里。
 */
public interface Segment {

    /** 片段内容（用于向量化） */
    String getContent();

    /** 原始内容（原始代码/原文） */
    String getRawContent();

    /** 片段类型（class / method / clause / transaction） */
    String getSegmentType();

    /** 来源标识（项目名 / 文档名 / 合同编号） */
    String getSource();

    /** 文件路径 / 文档路径 */
    String getPath();

    /** 附加元数据（领域特有字段放这里） */
    Map<String, String> getMetadata();
}