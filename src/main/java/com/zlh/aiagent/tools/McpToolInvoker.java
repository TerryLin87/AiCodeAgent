package com.zlh.aiagent.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.zlh.aiagent.instances.IMcpToolInvoker;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * MCP 工具调用器实现：通过 HTTP 直连 MCP Server 的 {@code tools/call}。
 *
 * <p>一个类搞定所有工具，新增 MCP 工具不需要改这个类。
 *
 * <p>协议：JSON-RPC 2.0 over Streamable HTTP。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "agent.mcp.enabled", havingValue = "true", matchIfMissing = false)
public class McpToolInvoker implements IMcpToolInvoker {

    @Autowired
    private ToolCallTrace toolCallTrace;

    //region 配置与客户端

    @Value("${agent.mcp.url:http://localhost:8082/mcp}")
    private String mcpServerUrl;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(5))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    //endregion

    //region 核心方法

    @Override
    public String invoke(String toolName, Map<String, Object> arguments) {
        //region 参数校验
        if (toolName == null || toolName.isBlank()) {
            return "工具名为空";
        }
        //endregion

        try {
            //region 构造 JSON-RPC 请求
            JSONObject request = new JSONObject();
            request.put("jsonrpc", "2.0");
            request.put("id", System.currentTimeMillis());
            request.put("method", "tools/call");

            JSONObject params = new JSONObject();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Map.of());
            request.put("params", params);
            //endregion

            //region 发 HTTP 请求
            Request httpRequest = new Request.Builder()
                    .url(mcpServerUrl)
                    .post(RequestBody.create(
                            request.toJSONString(),
                            MediaType.parse("application/json; charset=utf-8")
                    ))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json, text/event-stream")
                    .build();

            long start = System.currentTimeMillis();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                long cost = System.currentTimeMillis() - start;
                String body = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.warn("MCP {} HTTP {} ({}ms)", toolName, response.code(), cost);
                    return "MCP " + toolName + " 失败: HTTP " + response.code();
                }

                log.info("MCP {} 调用成功 ({}ms, {} 字符)", toolName, cost, body.length());
                String result = parseResponse(body, toolName);

                // 记 trace
                String sessionId = ToolCallGuard.getCurrentSession();
                if (sessionId != null) {
                    toolCallTrace.record(sessionId, ToolCallTrace.Record.builder()
                            .toolName(toolName)
                            .arguments(arguments != null ? arguments.toString() : "{}")
                            .resultSummary(result.length() > 200 ? result.substring(0, 200) + "..." : result)
                            .success(!result.startsWith("MCP " + toolName + " 失败"))
                            .costMs(cost)
                            .timestamp(LocalDateTime.now())
                            .build());
                }
                return result;
            }
            //endregion

        } catch (Exception e) {
            log.error("MCP {} 调用失败", toolName, e);
            return "MCP " + toolName + " 调用失败: " + e.getMessage();
        }
    }

    //endregion

    //region 响应解析

    /**
     * 解析 MCP 响应。
     *
     * <p>兼容两种格式：
     * <ul>
     *   <li>纯 JSON：{@code {"jsonrpc":"2.0","result":{"content":[{"text":"..."}]}}}</li>
     *   <li>SSE：{@code data: {...}}</li>
     * </ul>
     */
    private String parseResponse(String body, String toolName) {
        //region 提取 JSON（兼容 SSE）
        String json = body;
        if (body.startsWith("event:") || body.startsWith("data:")) {
            json = body.lines()
                    .filter(l -> l.startsWith("data:"))
                    .map(l -> l.substring(5).trim())
                    .findFirst().orElse("");
        }
        if (json.isBlank()) {
            return "MCP " + toolName + " 返回空响应";
        }
        //endregion

        try {
            JSONObject jsonObj = JSONObject.parseObject(json);

            //region 错误响应
            if (jsonObj.containsKey("error")) {
                JSONObject error = jsonObj.getJSONObject("error");
                String msg = error != null ? error.getString("message") : "unknown";
                log.warn("MCP {} 返回错误: {}", toolName, msg);
                return "MCP " + toolName + " 错误: " + msg;
            }
            //endregion

            //region 成功响应：提取 result.content[0].text
            JSONObject result = jsonObj.getJSONObject("result");
            if (result != null && result.containsKey("content")) {
                JSONArray contentArray = result.getJSONArray("content");
                if (contentArray != null && !contentArray.isEmpty()) {
                    JSONObject first = contentArray.getJSONObject(0);
                    String text = first.getString("text");
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
            //endregion

            //region 兜底：直接返回 result 的字符串形式
            if (result != null) {
                return result.toJSONString();
            }
            return "MCP " + toolName + " 返回格式异常: "
                    + json.substring(0, Math.min(200, json.length()));
            //endregion

        } catch (Exception e) {
            log.warn("MCP {} 响应解析失败: {}", toolName, e.getMessage());
            return "MCP " + toolName + " 响应解析失败: " + e.getMessage();
        }
    }

    //endregion

    //region 健康检查

    @Override
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder().url(mcpServerUrl).head().build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("MCP Server 不可用: {}", e.getMessage());
            return false;
        }
    }

    //endregion
}