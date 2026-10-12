package com.example.aiagentdemo.tools.newtools;

import com.example.aiagentdemo.instances.AgentTool;
import com.example.aiagentdemo.instances.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.PathMatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Component
public class FileSystemTools {
    private final PathMatcher pathMatcher;

    public FileSystemTools(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    @AgentTool(
            name = "readFile",
            description = "读取指定路径的文件内容，适用于查看代码文件、配置文件、日志文件等。",
            readOnly = true,
            timeout = 10
    )
    public String readFile(
            @ToolParam(
                    name = "path",
                    description = "文件的完整路径或相对路径",
                    required = true,
                    example = "src/main/java/com/example/App.java"
            ) String path){
        try{
            Path filePath = Paths.get(path);
            if(!Files.exists(filePath)){
                return "文件不存在："+filePath;
            }
            if(!Files.isReadable(filePath)){
                return "文件不可读："+path;
            }
            String content = Files.readString(filePath);
            //限制返回长度，避免token问题
            if(content.length()> 5000){
                return content.substring(0,5000)+"\n...(内容过长，已截断)";
            }
            return content;
        }catch (Exception e){
            log.error("读取文件失败:{}",path,e);
            return "读取文件失败："+e.getMessage();
        }
    }

    @AgentTool(
            name = "listFiles",
            description = "列出指定目录下的文件和子目录",
            readOnly = true,
            timeout = 10
    )
    public String listFiles(
            @ToolParam(
                    name = "path",
                    description = "目录路径",
                    required = true,
                    example = "scr/main/java"
            ) String path,
            @ToolParam(
                    name = "recursive",
                    description = "是否递归出子目录",
                    required = false,
                    defaultValue = "false"
            ) boolean recursive
    ){
        try{
            Path dirPath = Paths.get(path);
            if(!Files.exists(dirPath) || !Files.isDirectory(dirPath)){
                return "目录不存在或不是目录："+dirPath;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("目录:").append(path).append("\n");

            if(recursive){
                Files.walk(dirPath)
                        .limit(100)
                        .forEach(p->{
                            String indent = "   ".repeat(p.getNameCount() - dirPath.getNameCount());
                            String type = Files.isDirectory(p)?"--dir":"--file";
                            sb.append(indent).append(type).append(" ").append(p.getFileName()).append("\n");
                        });
            } else {
                Files.list(dirPath)
                        .forEach(p->{
                            String type = Files.isDirectory(p)?"--dir":"--file";
                            sb.append(type).append(" ").append(p.getFileName()).append("\n");
                        });
            }
            return sb.toString();
        }catch (Exception e){
            return "列出目录失败："+e.getMessage();
        }
    }
}
