package com.example.aiagentdemo.data;

import lombok.Data;

@Data
public class FilePath {
    private final String fullPath;
    private final String relativePath;
    private final String projectName;
}
