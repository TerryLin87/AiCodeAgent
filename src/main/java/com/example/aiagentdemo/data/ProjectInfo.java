package com.example.aiagentdemo.data;

import lombok.Data;

// 项目类
@Data
public class ProjectInfo {
    private String name;
    private String path;

    public ProjectInfo(String name, String path) {
        this.name = name;
        this.path = path;
    }
}
