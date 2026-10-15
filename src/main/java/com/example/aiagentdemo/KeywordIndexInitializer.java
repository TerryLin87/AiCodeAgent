package com.example.aiagentdemo;

import com.example.aiagentdemo.service.HybridSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class KeywordIndexInitializer {
    @Autowired
    private HybridSearchService hybridSearchService;

    private static final Logger log = LoggerFactory.getLogger(KeywordIndexInitializer.class);

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        try {
            log.info("开始初始化关键词索引...");
            hybridSearchService.buildKeywordIndex();
            log.info("关键词索引初始化完成");
        } catch (Exception e) {
            log.error("关键词索引初始化失败", e);
        }
    }
}
