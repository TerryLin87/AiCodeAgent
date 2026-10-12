package com.example.aiagentdemo.data;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    private List<CodeSegment> segments;
    private List<CallRelation> callRelations;

    // 如果 segmentType 没有设置，可以用默认方法
    public String getSegmentType() {
        return "class";  // 默认值，根据实际情况调整
    }
}
