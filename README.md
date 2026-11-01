# AiCodeAgent

一个基于 **Ollama + Spring Boot + LangChain4j + Milvus** 的生产级智能 Java 代码助手 Agent。支持混合检索、无损长对话、多级熔断降级、多子 Agent 协作与反思闭环。

**架构特点**：领域无关的 Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动。

![Java](https://img.shields.io/badge/Java-17+-blue.svg) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen.svg) ![LangChain4j](https://img.shields.io/badge/LangChain4j-1.17.2-orange.svg) ![Milvus](https://img.shields.io/badge/Milvus-2.4+-blueviolet.svg) ![License](https://img.shields.io/badge/License-MIT-yellow.svg)

---

## ✨ 核心特性

- 🔍 **混合代码检索**：语义检索（Milvus）+ 关键词检索（Lucene BM25），RRF 融合排序
- 🧠 **分层记忆体系**：短期会话记忆（支持 Redis 共享）/ 长期向量记忆 / 工作记忆，三层隔离
- 🗜️ **无损上下文压缩**：五级降级压缩策略，配合 `HistoryStore` 归档被丢弃消息，支持历史原话精确回查
- 🎯 **状态追踪与指代消解**：内建 `UserStateTracker`，自动解析“按第二个来”、“看原来的”等复杂多轮指代
- 🤖 **多子 Agent 协作**：`explore`（探索）/ `verify`（验证）/ `plan`（规划），LLM 智能路由 + 关键词兜底
- 🔗 **跨项目调用分析**：AST 级别解析 Java 代码，构建方法级调用图，支持变更影响递归追踪
- 🔄 **反思闭环**：LLM 输出后自动评估，未达标触发自动修正（最多 3 轮），带时间上限
- 🛡️ **生产级防御**：
  - 入口层：API Key 鉴权、DTO 参数校验、Resilience4j 限流
  - 执行层：AOP 切面统一管控工具调用（超时、截断、计数）
  - 依赖层：Milvus / LLM 熔断降级
- 📊 **可观测性**：全链路 TraceId、Prometheus 指标暴露、Actuator 健康检查
- 🏗️ **领域无关架构**：Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动

---

## 🏗️ 系统架构

### 分层架构

```text
┌─────────────────────────────────────────────────────────┐
│            接口层（ChatController / DebugController 隔离）              │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                引擎层（领域无关，可复用）                  │
│ ┌─────────────────────────────────────────────────┐     │
│ │           CodeAgentLoopService（主循环）           │     │
│ │   · 双层限流 · 状态追踪 · 记忆装配 · 反思闭环       │     │
│ └─────────────────────────────────────────────────┘     │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐            │
│ │  上下文压缩  │ │  子 Agent   │ │  工具调用   │            │
│ │ (含历史归档) │ │  路由(3级)  │ │  AOP 守卫   │            │
│ └────────────┘ └────────────┘ └────────────┘            │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐            │
│ │  长期记忆    │ │  自我评估   │ │  熔断降级   │            │
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
⑤ 复杂任务规划（isComplexTask 判定 → PlanSubAgent）
    │
    ▼
⑥ 长期记忆召回（Milvus chat_memory，注入 System Prompt）
    │
    ▼
⑦ 调用 LLM Agent（AOP 切面拦截所有工具调用，超时/截断保护）
    │
    ├── 直接回答
    ├── 调工具（searchCode / readFile / getCallers...）
    └── 委托子 Agent（explore / verify / plan）
    │
    ▼
⑧ 反思闭环（评估 → 未达标 → 自动修正）
    │
    ▼
⑨ 写回记忆 + 审计 + 返回
```

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

server:
  port: 8001

deepseek:
  apikey: ${DEEPSEEK_API_KEY}

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
      deepseek:
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s

search:
  hybrid:
    semantic-weight: 0.6
    keyword-weight: 0.4

project:
  base-path: /path/to/your/projects
```

> ⚠️ **安全提示**：请勿将 `application.yml` 提交到 Git。使用 `application.example.yml` 作为模板。

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
| `CodeAgentLoopService` | Agent 主循环 | 双层限流、会话记忆缓存、状态追踪、反思闭环 |
| `ContextCompressor` | 上下文压缩 | 五级降级，按角色合并，CJK 感知 token 估算，配合归档 |
| `HistoryStoreService` | 历史归档 | 压缩前保存旧消息，支持精确回查历史原话 |
| `UserStateTracker` | 状态追踪 | 解析“第二个/原来的”等指代，维护多轮对话状态 |
| `SubAgentRoutingService` | 子 Agent 路由 | LLM 结构化路由 + 关键词兜底，可审计 |
| `ToolCallGuard` / `ToolCallAspect` | 工具调用守卫 | 按会话独立计数、超时拦截、返回截断、防死循环 |
| `LongTermMemoryService` | 长期记忆 | 写入前去重（同会话+同角色+0.85 阈值） |
| `SelfEvaluationService` | 自我评估 | 5 维度评分，JSON 容错解析 |
| `AutoFixerService` | 自动修正 | 最多 3 轮修正循环，带超时熔断 |
| `SessionManager` / `SessionStore` | 会话管理 | 会话级隔离，支持内存与 Redis 切换，分段锁并发保护 |
| `DeepSeekModel` | LLM 封装 | 支持 tool calling、超时分级、熔断与 Token 统计 |
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
- ⚡ 所有外部依赖（Milvus、LLM）均配有 Resilience4j 熔断，避免单点故障拖垮主线程
- 🩺 支持 K8s 探针（`/actuator/health` 涵盖 Milvus、Lucene、LLM 连通性）

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
- [ ] 接入 MCP（Model Context Protocol），工具动态发现
- [ ] 引入 Cross-Encoder 做检索精排
- [ ] 支持流式输出（SSE）
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
