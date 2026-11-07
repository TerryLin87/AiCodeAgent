package com.zlh.aiagent.service.code.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析项目的 pom.xml，提取"项目 A 依赖项目 B"。
 *
 * <p>输入：子项目根目录
 * <p>输出：List<ProjectDependency>
 */
@Slf4j
@Service
public class ProjectDependencyParser {

    /** 扫描根目录下所有子项目的 pom.xml */
    public List<ProjectDependency> parseAll(String subProjectsPath) {
        if (subProjectsPath == null || subProjectsPath.isBlank()) {
            return List.of();
        }
        Path root = Paths.get(subProjectsPath);
        if (!Files.exists(root)) {
            return List.of();
        }

        List<ProjectDependency> result = new ArrayList<>();
        try {
            Files.list(root)
                    .filter(Files::isDirectory)
                    .forEach(projectDir -> {
                        String projectName = projectDir.getFileName().toString();
                        Path pom = projectDir.resolve("pom.xml");
                        if (Files.exists(pom)) {
                            result.addAll(parsePom(pom, projectName));
                        }
                    });
        } catch (IOException e) {
            log.error("扫描子项目目录失败", e);
        }
        return result;
    }

    private List<ProjectDependency> parsePom(Path pomPath, String projectName) {
        List<ProjectDependency> deps = new ArrayList<>();
        try {
            String content = Files.readString(pomPath);
            // 简单正则：<artifactId>xxx</artifactId> 在 <dependency> 块里
            // 完整解析应该用 XML 解析器，这里用正则足够
            Matcher depMatcher = Pattern.compile(
                    "<dependency>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>",
                    Pattern.DOTALL
            ).matcher(content);

            while (depMatcher.find()) {
                String artifactId = depMatcher.group(2).trim();
                // 只保留"项目内依赖"：artifactId 在子项目列表里
                // 这里简化：所有 artifactId 都记为候选，后续用 subProjects 过滤
                deps.add(ProjectDependency.builder()
                        .fromProject(projectName)
                        .toProject(artifactId)
                        .build());
            }
        } catch (IOException e) {
            log.warn("解析 pom.xml 失败: {}", pomPath, e);
        }
        return deps;
    }

    @lombok.Data
    @lombok.Builder
    public static class ProjectDependency {
        private String fromProject;
        private String toProject;
    }
}