# AiCodeAgent

一个基于 **Spring Boot + LangChain4j + DeepSeek + Ollama + Milvus** 的生产级智能 Java 代码助手 Agent。支持混合检索、无损长对话、多级熔断降级、模型分层路由、SSE 流式输出、请求取消、多子 Agent 协作与反思闭环。

**架构特点**：领域无关的 Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动。

![Java](https://img.shields.io/badge/Java-17+-blue.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen.svg) ![LangChain4j](https://img.shields.io/badge/LangChain4j-1.23.0-orange.svg) ![Milvus](https://img.shields.io/badge/Milvus-2.4+-blueviolet.svg) ![License](https://img.shields.io/badge/License-MIT-yellow.svg)

---

## ✨ 核心特性

- 🔍 **混合代码检索**：语义检索（Milvus）+ 关键词检索（Lucene BM25），RRF 融合排序
- 🧠 **分层记忆体系**：短期会话记忆（支持 Redis 共享）/ 长期向量记忆 / 工作记忆，三层隔离
- 🗜️ **无损上下文压缩**：五级降级压缩策略，配合 `HistoryStore` 归档被丢弃消息，支持历史原话精确回查
- 🎯 **状态追踪与指代消解**：内建 `UserStateTracker`，自动解析“按第二个来”、“看原来的”等复杂多轮指代
- 🤖 **多子 Agent 协作**：`explore`（探索）/ `verify`（验证）/ `plan`（规划），LLM 智能路由 + 关键词兜底
- 🎚️ **模型分层路由**：同一 `DeepSeekModel` 实例按 `CallType` 选档（主力 / 推理 / 廉价），每档独立熔断，推理档与廉价档熔断时自动降级到主力档
- ⚡ **SSE 流式输出**：`GET /api/chat/stream` 逐 token 推送，事件覆盖 `meta / token / plan / tool_calls / done / error / cancelled`，前端可实时渲染并中途停止
- 🛑 **请求取消**：协作式 `CancellationToken`，支持按 `requestId` 或 `sessionId` 取消，取消时主动断开 OkHttp 连接与 SSE 流，不空跑模型
- 🔗 **跨项目调用分析**：AST 级别解析 Java 代码，构建方法级调用图，支持变更影响递归追踪
- 🔄 **反思闭环**：LLM 输出后自动评估，未达标触发自动修正（最多 3 轮），带时间上限
- 🛡️ **生产级防御**：
  - 入口层：API Key 鉴权、DTO 参数校验、Resilience4j 限流
  - 执行层：AOP 切面统一管控工具调用（超时、截断、计数），全程轮询取消令牌
  - 依赖层：Milvus / LLM 熔断降级（DeepSeek 按模型独立熔断）
  - 停机层：优雅停机，拒绝新请求 + 等待在跑请求 + 超时统一取消 + 释放连接池与信号量
- 📊 **可观测性**：全链路 TraceId、Prometheus 指标暴露、Actuator 健康检查
- 🏗️ **领域无关架构**：Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动

---

## 🏗️ 系统架构

### 分层架构

```text
┌─────────────────────────────────────────────────────────┐
│     接口层（ChatController / DebugController 隔离）        │
│        · 同步对话 · 带引用对话 · SSE 流式 · 请求取消        │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                引擎层（领域无关，可复用）                  │
│ ┌─────────────────────────────────────────────────┐     │
│ │           CodeAgentLoopService（主循环）           │     │
│ │  · 双层限流 · 状态追踪 · 记忆装配 · 反思闭环        │     │
│ │  · CancellationToken 透传 · 优雅停机               │     │
│ └─────────────────────────────────────────────────┘     │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐            │
│ │  上下文压缩  │ │  子 Agent   │ │  工具调用   │            │
│ │ (含历史归档) │ │  路由(3级)  │ │  AOP 守卫   │            │
│ └────────────┘ └────────────┘ └────────────┘            │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐            │
│ │  长期记忆    │ │  自我评估   │ │ 熔断降级/分层│            │
│ └────────────┘ └────────────┘ └────────────┘            │
└────────────────────────┬────────────────────────────────┘
                         │ 接口注入
┌────────────────────────▼────────────────────────────────┐
│                  领域插件层（可替换）                      │
│ · 当前：代码领域 (JavaCodeParser / AgentTools 等)         │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                       存储层                             │
│   Milvus（向量） · Redis（会话） · Lucene（关键词）        │
│                  HistoryStore（历史归档）                 │
└─────────────────────────────────────────────────────────┘
```

### 主执行流程

```text
用户请求
    │
    ▼
① 入口层安全（API Key 鉴权 → 参数校验 → 限流）
    │
    ▼
② 会话与记忆准备（ChatMemory 按 sessionId 缓存，防并发冲突）
    │
    ▼
③ 状态追踪与指代消解（UserStateTracker 解析“第二个”等指代）
    │
    ▼
④ 上下文压缩与归档（五级降级 → 被丢弃消息存入 HistoryStore → 固化原话）
    │
    ▼
⑤ 复杂任务规划（isComplexTask 判定 → PlanSubAgent，走推理档模型）
    │
    ▼
⑥ 长期记忆召回（Milvus chat_memory，注入 System Prompt）
    │
    ▼
⑦ 调用 LLM Agent（AOP 切面拦截所有工具调用，超时/截断保护）
    │   · 全程轮询 CancellationToken，命中即抛 CancelledException
    │   · 同步返回，或经 SSE 逐 token 推送
    ├── 直接回答
    ├── 调工具（searchCode / readFile / getCallers...）
    └── 委托子 Agent（explore / verify / plan）
    │
    ▼
⑧ 反思闭环（评估 → 未达标 → 自动修正，走推理档模型）
    │
    ▼
⑨ 写回记忆 + 审计 + 返回（取消/异常路径统一释放令牌与信号量）
```

### 模型分层路由

同一 `DeepSeekModel` 实例内部按 `CallType` 选择模型档位，互不干扰：

| 档位 | 配置键 | 承接的 CallType | 熔断行为 |
|------|--------|----------------|---------|
| 主力（main） | `deepseek.chat.model.main` | `MAIN_CHAT` / `TOOL_CALL` | 最后一层，熔断直接抛 `ModelCircuitOpenException` |
| 推理（reasoning） | `deepseek.chat.model.reasoning` | `EVALUATION` / `PLAN` | 熔断自动降级到主力档 |
| 廉价（cheap） | `deepseek.chat.model.cheap` | `ROUTING` / `SUMMARY` | 熔断自动降级到主力档 |

- 熔断器按模型名各自独立（`CircuitBreakerRegistry` + 按模型名索引）
- 后两档未配置时自动回退到主力档，兼容老的 `deepseek.model-name` 单模型配置

### 领域切换能力

引擎层通过 **5 个抽象接口** 与领域解耦：

| 抽象接口 | 作用 |
|---------|------|
| `DomainConfig` | System Prompt / 领域标识配置化 |
| `RetrievalResult` | 通用检索结果接口，领域数据类实现它 |
| `DocumentParser` | 文档解析器插件，按领域和扩展名动态选择 |
| `ToolPlugin` | 工具集插件，按领域动态装配 |
| `ISubAgent.getDomain()` | 子 Agent 声明适用领域，调度器按领域过滤 |

**切换领域只需**：

1. 新增领域插件包（实现上述接口）
2. 修改 `application.yml` 中的 `agent.domain.name`
3. 引擎层零改动

---

## 🚀 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | JavaParser 需 Java 17 |
| Maven | 3.8+ | |
| Ollama | 最新版 | 本地运行 Embedding 模型 |
| Milvus | 2.4+ | 或使用 Zilliz Cloud |
| Redis | 6.0+ | 可选，用于会话持久化 |
| DeepSeek API | — | 用于 LLM 推理、评估、规划 |

### 1. 准备基础环境

```bash
# 1. 启动 Ollama 并拉取模型
ollama pull qwen3-embedding:0.6b
ollama serve

# 2. 启动 Milvus (Docker)
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest standalone

# 3. 启动 Redis (可选，用于持久化会话)
docker run -d --name redis -p 6379:6379 redis:latest
```

### 2. 配置应用

复制配置模板：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

编辑 `application.yml`：

```yaml
spring:
  application:
    name: AiAgentDemo
  data:
    redis:                       # 仅当 agent.session.store=redis 时需要
      host: localhost
      port: 6379
  lifecycle:
    timeout-per-shutdown-phase: 30s   # 优雅停机：最多等 30s 让在跑请求完成

server:
  port: 8001
  shutdown: graceful             # 开启优雅停机

deepseek:
  apikey: ${DEEPSEEK_API_KEY}
  chat:
    model:
      main: deepseek-chat        # 主对话 / 工具调用
      reasoning: deepseek-reasoner  # 规划 / 评估
      cheap: deepseek-flash      # 路由 / 摘要

codebase:
  sub-projects-path: /path/to/your/projects
  exclude-dirs: [target, .git, .idea, node_modules]

agent:
  api:
    keys: "test-api-key-123" # 默认的 X-API-Key
  session:
    store: memory # 可选 memory / redis
  domain:
    name: code
    display-name: Java 代码助手
    system-prompt: |
      你是一个智能的 Java 代码助手...
  milvus:
    uri: ${MILVUS_URI}
    token: ${MILVUS_TOKEN}
    collection: bf_novel_code
    max-top-k: 16384
  embedding:
    dimension: 1024
  context:
    max-messages: 20
    max-tokens: 8000
    keep-recent: 5
    enable-summary: true
  memory:
    dedupe-threshold: 0.85
  reflexion:
    enabled: true
    score-threshold: 7.0
    max-duration-ms: 30000
  tool:
    max-calls-per-session: 15
    per-tool-limits: "{'readFile':5,'searchCode':5,'delegateToSubAgent':3}"

resilience4j:
  circuitbreaker:
    instances:
      milvus:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        sliding-window-size: 10

search:
  hybrid:
    semantic-weight: 0.6
    keyword-weight: 0.4

project:
  base-path: /path/to/your/projects
```

> ⚠️ **安全提示**：请勿将 `application.yml` 提交到 Git。使用 `application.example.yml` 作为模板。
> `deepseek.chat.model.reasoning` / `cheap` 不配时会自动回退到 `main`；若沿用旧的单模型配置，写 `deepseek.model-name` 即可，会被当作主力档。

### 3. 启动应用

```bash
./mvnw clean package -DskipTests
java -jar target/ai-agent-demo-*.jar
```

启动后访问 `http://localhost:8001/actuator/health` 检查状态。

---

## 📡 API 使用

> **注意**：业务请求需携带 `X-API-Key` Header（默认 `test-api-key-123`）。
> 生产环境（`prod`）下，`/debug` 接口将被自动禁用。

### 基础对话（自动判定新老会话）

```bash
curl -X POST http://localhost:8001/api/chat \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-api-key-123" \
  -d '{"message": "帮我搜索用户登录相关的代码"}'
```

*返回体中会包含 `sessionId`，下次请求带上即可继续多轮对话。*

### 流式对话（SSE）

```bash
curl -N "http://localhost:8001/api/chat/stream?message=帮我搜索用户登录相关的代码&sessionId=session-abc123" \
  -H "X-API-Key: test-api-key-123"
```

服务端推送的事件类型：

| 事件名 | 含义 |
|--------|------|
| `meta` | 首帧，回传 `sessionId`，供前端后续取消 |
| `token` | 增量文本片段 |
| `plan` / `event` | 状态事件（规划、阶段提示等） |
| `tool_calls` | 本轮流式拼接完成的工具调用列表 |
| `done` | 全部完成 |
| `error` | 异常结束 |
| `cancelled` | 请求被取消 |

### 取消正在执行的请求

```bash
curl -X POST http://localhost:8001/api/chat/cancel \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-api-key-123" \
  -d '{"sessionId": "session-abc123"}'
```

*优先按 `requestId` 取消，其次按 `sessionId`；SSE 连接断开 / 超时 / 出错时服务端会自动取消对应后台执行。*

### 混合检索（Debug 环境）

```bash
curl -X POST http://localhost:8001/debug/retrieval/hybrid \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-api-key-123" \
  -d '{"query": "Schema 数据库迁移", "maxResults": 5}'
```

### 调用关系分析（Debug 环境）

```bash
curl "http://localhost:8001/debug/call/impact?className=StorageService&methodName=deleteReferencesByFile&depth=3" \
  -H "X-API-Key: test-api-key-123"
```

### 健康检查与监控（无需 API Key）

```bash
# 健康检查
curl http://localhost:8001/actuator/health

# Prometheus 指标
curl http://localhost:8001/actuator/prometheus
```

---

## 🧩 核心模块说明

### 引擎层（领域无关）

| 类 | 作用 | 亮点 |
|----|------|------|
| `CodeAgentLoopService` | Agent 主循环 | 双层限流、会话记忆缓存、状态追踪、反思闭环、取消令牌透传、优雅停机 |
| `ContextCompressor` | 上下文压缩 | 五级降级，按角色合并，CJK 感知 token 估算，配合归档 |
| `HistoryStoreService` | 历史归档 | 压缩前保存旧消息，支持精确回查历史原话 |
| `UserStateTracker` | 状态追踪 | 解析“第二个/原来的”等指代，维护多轮对话状态 |
| `SubAgentRoutingService` | 子 Agent 路由 | LLM 结构化路由 + 关键词兜底，可审计 |
| `ToolCallGuard` / `ToolCallAspect` | 工具调用守卫 | 按会话独立计数、超时拦截、返回截断、防死循环 |
| `LongTermMemoryService` | 长期记忆 | 写入前去重（同会话+同角色+0.85 阈值） |
| `SelfEvaluationService` | 自我评估 | 5 维度评分，JSON 容错解析 |
| `AutoFixerService` | 自动修正 | 最多 3 轮修正循环，带超时熔断 |
| `SessionManager` / `SessionStore` | 会话管理 | 会话级隔离，支持内存与 Redis 切换，分段锁并发保护 |
| `RedisConfig` / `ChatMessageCodec` | Redis 会话持久化 | 命名 Bean 的 ObjectMapper（含 JavaTimeModule），消息以 `role\0text` 拍平存储 |
| `DeepSeekModel` | LLM 封装 | tool calling、三档模型路由、SSE 流式输出、按模型独立熔断与降级、Token 统计 |
| `CancellationToken` | 请求取消 | 协作式取消令牌，全局注册表按 `requestId` 索引，支持按 requestId/sessionId 取消 |
| `DegradationLogger` | 降级日志 | 统一记录熔断与降级事件至 Prometheus |

### 领域插件层（当前：代码领域）

| 抽象接口 | 当前实现 | 说明 |
|---------|---------|------|
| `RetrievalResult` | `RetrievedCode` | 通用检索结果，领域数据类实现它 |
| `DocumentParser` | `JavaCodeParser` | 按领域和扩展名动态选择解析器 |
| `RetrievalService` | `CodeRetrievalService` | 领域检索服务 |
| `ToolPlugin` | `CodeToolPlugin` | 领域工具集 |
| `ISubAgent` | `ExploreSubAgent` / `VerifySubAgent` / `PlanSubAgent` | 领域子 Agent |

---

## 🔧 扩展指南

### 新增一个子 Agent

```java
@Component
@ConditionalOnProperty(name = "agent.domain.name", havingValue = "code", matchIfMissing = true)
public class RefactorSubAgent implements ISubAgent {

    @Override public String getName() { return "refactor"; }

    @Override public String getDomain() { return "code"; }

    @Override public String getCapability() {
        return "重构专家 - 提取方法、消除重复、优化设计模式";
    }

    @Override public List<String> getRoutingKeywords() {
        return List.of("重构", "提取方法", "消除重复");
    }

    @Override public SubAgentResult execute(String task, Map<String, Object> context) {
        // 你的实现
    }
}
```

**无需修改路由代码**——`SubAgentRoutingService` 启动时自动收集关键词。

### 新增一个工具

只需添加 LangChain4j 的 `@Tool` 注解，`ToolCallAspect` 切面会自动接管超时、计数与截断。

```java
@Component
public class MyTools {

    @Tool("描述这个工具做什么，什么时候用")
    public String customAction(
        @P("输入参数说明") String input
    ) {
        return "结果";
    }
}
```

### 切换领域

架构支持按领域切换：新增一个领域插件包，实现 `RetrievalResult` / `DocumentParser` / `RetrievalService` / `ToolPlugin` / `ISubAgent` 五个接口，修改 `application.yml` 中 `agent.domain.name` 即可。引擎层无需改动。

---

## ⚠️ 安全与生产注意事项

- 🔐 **API Key 与 Token 通过环境变量注入**，切勿硬编码提交到仓库
- 🛡️ `ReadFileTool` 有路径越界与符号链接检查，务必配置合理的 `project.base-path`
- 🚦 生产环境启动时加上 `--spring.profiles.active=prod`，所有 `/debug` 接口将被自动屏蔽
- ⚡ 所有外部依赖（Milvus、LLM）均配有 Resilience4j 熔断，DeepSeek 按模型档位独立熔断，避免单点故障拖垮主线程
- 🩺 支持 K8s 探针（`/actuator/health` 涵盖 Milvus、Lucene、LLM 连通性）
- ♻️ 开启优雅停机（`server.shutdown: graceful`）：收到 SIGTERM 后先拒绝新请求，再等待在跑请求完成，超时统一取消并释放连接池与信号量
- 🛑 请求取消基于协作式令牌（非 `Thread.interrupt()`），不会打断 OkHttp 连接池等第三方线程，取消时由调用方在 I/O 间隙主动断开连接

---

## 🗺️ Roadmap

- [x] 混合检索（语义 + BM25 + RRF 融合）
- [x] 五级上下文压缩
- [x] 三层记忆体系
- [x] 多子 Agent 协作与路由
- [x] 反思闭环（评估 + 自动修正）
- [x] 终止条件（工具调用上限）
- [x] 跨项目调用关系分析
- [x] 领域无关架构（支持按领域切换）
- [x] 历史消息归档与指代状态追踪（无损长对话）
- [x] API Key 鉴权与 DTO 参数校验
- [x] Redis 会话持久化
- [x] 工具调用 AOP 统一横切
- [x] 可观测性（traceId + Micrometer + Prometheus）
- [x] 熔断降级（Resilience4j）
- [x] 优雅停机与资源释放
- [x] 支持流式输出（SSE）
- [x] 模型分层路由（主力 / 推理 / 廉价，独立熔断与降级）
- [x] 请求取消（CancellationToken）
- [ ] 请求幂等去重
- [ ] 接入 MCP（Model Context Protocol），工具动态发现
- [ ] 引入 Cross-Encoder 做检索精排
- [ ] 补充单元测试与 CI/CD

---

## 📄 License

MIT License

---

## 🙏 参考

- [LangChain4j](https://github.com/langchain4j/langchain4j)
- [Milvus](https://milvus.io/)
- [Ollama](https://ollama.com/)
- [JavaParser](https://javaparser.org/)
- [DeepSeek](https://www.deepseek.com/)
