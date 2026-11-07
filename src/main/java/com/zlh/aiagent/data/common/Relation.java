package com.zlh.aiagent.data.common;

import com.zlh.aiagent.data.code.CallRelation;

import java.util.Map;

/**
 * 通用关系接口。
 *
 * <p>所有领域的"关系"都实现这个接口：
 * <ul>
 *   <li>code 领域：{@link CallRelation}（方法调用）</li>
 *   <li>law 领域：{@code ContractRelation}（合同引用）</li>
 *   <li>finance 领域：{@code ShareholdingRelation}（持股）</li>
 * </ul>
 */
public interface Relation {

    /** 起点实体 ID */
    String getFromId();

    /** 终点实体 ID */
    String getToId();

    /** 关系类型（method_call / 引用 / 持股） */
    String getRelationType();

    /** 起点实体类型（method / clause / company） */
    String getFromType();

    /** 终点实体类型（method / regulation / company） */
    String getToType();

    /** 附加元数据（行号、金额、时间等） */
    Map<String, String> getMetadata();
}