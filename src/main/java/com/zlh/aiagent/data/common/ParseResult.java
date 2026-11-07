package com.zlh.aiagent.data.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用解析结果。
 *
 * <p>所有领域的 {@code IDocumentParser} 都返回这个结构：
 * <ul>
 *   <li>{@code segments}：解析出的文本片段（类/方法/条款/交易）</li>
 *   <li>{@code relations}：片段之间的关系（调用/引用/持股）</li>
 * </ul>
 *
 * <p>领域标识放在 {@code domain} 字段，便于调用方判断"这是哪个领域的数据"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    /** 解析出的片段列表（通用接口） */
    private List<? extends Segment> segments;

    /** 片段之间的关系列表（通用接口） */
    private List<? extends Relation> relations;

    /** 领域标识：code / law / finance */
    private String domain;

    /** 解析是否成功 */
    private boolean success;

    /** 错误信息（success=false 时） */
    private String errorMessage;

    //region 便捷方法

    /** 片段数量 */
    public int segmentCount() {
        return segments == null ? 0 : segments.size();
    }

    /** 关系数量 */
    public int relationCount() {
        return relations == null ? 0 : relations.size();
    }

    /** 是否为空 */
    public boolean isEmpty() {
        return segmentCount() == 0 && relationCount() == 0;
    }

    //endregion
}