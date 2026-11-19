package com.zlh.aiagent.tools;

import com.zlh.aiagent.data.law.RetrievedClause;
import com.zlh.aiagent.service.law.LawRetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "law")
public class LawTools {

    @Autowired
    private LawRetrievalService lawRetrievalService;

    @Tool("查询相关法律法规。当需要审查合同条款是否合法、或需要引用法律依据时使用。")
    public String findRelatedLaws(
            @P("查询词，如：劳动合同试用期期限、违约金上限") String query) {

        log.info("Tool: findRelatedLaws, query={}", query);
        List<RetrievedClause> clauses = lawRetrievalService.searchClauses(query, 5, 0.4);

        if (clauses.isEmpty()) {
            return "未找到相关的法律法规。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到以下相关法律条款：\n\n");
        for (int i = 0; i < clauses.size(); i++) {
            RetrievedClause c = clauses.get(i);
            sb.append("【").append(i + 1).append("】")
                    .append(c.getLawName()).append(" ")
                    .append(c.getArticleNo() != null ? c.getArticleNo() : "").append("\n");
            sb.append(c.getContent()).append("\n");
            if (c.getSourceUrl() != null && !c.getSourceUrl().isEmpty()) {
                sb.append("来源: ").append(c.getSourceUrl()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}