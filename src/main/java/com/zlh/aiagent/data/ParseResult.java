package com.zlh.aiagent.data;

import com.zlh.aiagent.data.code.CallRelation;
import com.zlh.aiagent.data.code.CodeSegment;
import com.zlh.aiagent.data.common.Relation;
import com.zlh.aiagent.data.common.Segment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 解析结果（通用版）。
 *
 * <p>所有领域的 {@code IDocumentParser} 都返回这个结构。
 *
 * <p>{@code segments} 和 {@code relations} 是通用接口，
 * code 领域填 {@link CodeSegment} / {@link CallRelation}，
 * law 领域填 {@code ClauseSegment} / {@code ContractRelation}。
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

    public int segmentCount() {
        return segments == null ? 0 : segments.size();
    }

    public int relationCount() {
        return relations == null ? 0 : relations.size();
    }

    public boolean isEmpty() {
        return segmentCount() == 0 && relationCount() == 0;
    }

    //endregion
}