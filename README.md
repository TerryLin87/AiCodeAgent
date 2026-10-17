# AiCodeAgent

一个基于 **Ollama + Spring Boot + LangChain4j + Milvus** 的智能 Java 代码助手 Agent。
支持代码语义检索、调用关系分析、多级记忆管理、子 Agent 协作、反思闭环与自我修正。

**架构特点**：领域无关的 Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动。

![Java](https://img.shields.io/badge/Java-17+-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen.svg)
![LangChain4j](https://img.shields.io/badge/LangChain4j-1.17.2-orange.svg)
![Milvus](https://img.shields.io/badge/Milvus-2.4+-blueviolet.svg)
![License](https://img.shields.io/badge/License-MIT-yellow.svg)

---

## ✨ 核心特性

- 🔍 **混合代码检索**：语义检索（Milvus）+ 关键词检索（Lucene BM25），RRF 融合排序
- 🧠 **分层记忆体系**：短期会话记忆 / 长期向量记忆 / 工作记忆，三层隔离
- 🗜️ **五级上下文压缩**：从剪枝到 LLM 摘要逐级降级，超阈值自动触发，永不过载
- 🤖 **多子 Agent 协作**：`explore`（探索）/ `verify`（验证）/ `plan`（规划），LLM 智能路由 + 关键词兜底
- 🔗 **跨项目调用分析**：AST 级别解析 Java 代码，构建方法级调用图，支持变更影响递归追踪
- 🔄 **反思闭环**：LLM 输出后自动评估，未达标触发自动修正（最多 3 轮），带时间上限
- 🛑 **终止条件**：单会话工具调用次数上限，防止 Agent 死循环
- 🏗️ **领域无关架构**：Agent 引擎 + 领域插件，支持按领域切换，引擎层零改动
- 🚦 **多级降级**：限流降级、压缩降级、路由降级、反思降级，每层都有 fallback

---

## 🏗️ 系统架构

### 分层架构

```text
┌─────────────────────────────────────────────────────────┐
│              接口层（TestController）                    │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              引擎层（领域无关，可复用）                   │
│  ┌─────────────────────────────────────────────────┐   │
│  │ CodeAgentLoopService（主循环）                    │   │
│  │  · 双层限流 · 会话管理 · 记忆装配 · 反思闭环      │   │
│  └─────────────────────────────────────────────────┘   │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐          │
│  │ 上下文压缩  │ │ 子 Agent   │ │ 工具调用    │          │
│  │ (5级降级)  │ │ 路由(3级)  │ │ 守卫        │          │
│  └────────────┘ └────────────┘ └────────────┘          │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐          │
│  │ 长期记忆    │ │ 自我评估    │ │ 自动修正    │          │
│  └────────────┘ └────────────┘ └────────────┘          │
└────────────────────────┬────────────────────────────────┘
                         │ 接口注入
┌────────────────────────▼────────────────────────────────┐
│              领域插件层（可替换）                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 当前：代码领域                                     │  │
│  │  · RetrievedCode / JavaCodeParser                │  │
│  │  · AgentTools / Explore/Verify/PlanSubAgent      │  │
│  └──────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ 可扩展：其他领域                                   │  │
│  │  · 实现同一套接口，按配置切换                       │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│              存储层                                      │
│  Milvus（代码向量 + 长期记忆） · Lucene（关键词索引）     │
│  codeCache（原文内存缓存）                               │
└─────────────────────────────────────────────────────────┘
```

### 主执行流程

```text
用户请求
    │
    ▼
① 双层限流（全局 10 / 会话 1）
    │
    ▼
② 会话与记忆准备（ChatMemory 按 sessionId 缓存）
    │
    ▼
③ 上下文压缩（五级降级：L1剪枝→L2合并→L3摘要→L4实体→L5截断）
    │
    ▼
④ 复杂任务规划（isComplexTask 判定 → PlanSubAgent）
    │
    ▼
⑤ 长期记忆召回（Milvus chat_memory，注入 System Prompt）
    │
    ▼
⑥ 调用 LLM Agent（AiServices 单实例 + 动态 System Prompt）
    │
    ├── 直接回答
    ├── 调工具（searchCode/readFile/getCallers...）
    └── 委托子 Agent（explore/verify/plan）
    │
    ▼
⑦ 反思闭环（评估 → 未达标 → 自动修正）
    │
    ▼
⑧ 写回记忆 + 审计 + 返回
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
| DeepSeek API | — | 用于 LLM 推理、评估、规划 |

### 1. 准备 Ollama 模型

```bash
ollama pull qwen3-embedding:0.6b
ollama serve
```

### 2. 配置 Milvus

本地部署（推荐用 Docker）：

```bash
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest standalone
```

或使用 [Zilliz Cloud](https://cloud.zilliz.com/) 免费实例。

### 3. 配置应用

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

search:
  hybrid:
    semantic-weight: 0.6
    keyword-weight: 0.4

project:
  base-path: /path/to/your/projects
```

> ⚠️ **安全提示**：请勿将 `application.yml` 提交到 Git。使用 `application.example.yml` 作为模板。

### 4. 启动应用

```bash
./mvnw clean package -DskipTests
java -jar target/ai-agent-demo-*.jar
```

启动后访问 `http://localhost:8001/test/health` 检查状态。

---

## 📡 API 使用

### 基础对话

```bash
curl -X POST http://localhost:8001/test/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我搜索用户登录相关的代码"}'
```

### 多轮会话

```bash
curl -X POST http://localhost:8001/test/chat/session \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "session-abc123", "message": "继续分析"}'
```

### 代码检索

```bash
# 语义检索
curl -X POST http://localhost:8001/test/retrieval/semantic \
  -H "Content-Type: application/json" \
  -d '{"query": "用户登录", "maxResults": 5}'

# 混合检索（语义 + BM25）
curl -X POST http://localhost:8001/test/retrieval/hybrid \
  -H "Content-Type: application/json" \
  -d '{"query": "Schema 数据库迁移", "maxResults": 5}'
```

### 调用关系分析

```bash
# 查询谁调用了某方法
curl "http://localhost:8001/test/call/callers?className=StorageService&methodName=deleteReferencesByFile"

# 分析影响范围
curl "http://localhost:8001/test/call/impact?className=StorageService&methodName=deleteReferencesByFile&depth=3"
```

### 子 Agent 委托

```bash
# 列出所有子 Agent
curl http://localhost:8001/test/subagent/list

# 显式委托
curl -X POST http://localhost:8001/test/subagent/delegate \
  -H "Content-Type: application/json" \
  -d '{"agentName": "plan", "task": "分析存储层设计，给出扩展方案"}'
```

### 自我评估

```bash
curl -X POST http://localhost:8001/test/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "content": "public class A { public int div(int a, int b) { return a / b; } }",
    "context": "实现安全的除法方法"
  }'
```

---

## 🧩 核心模块说明

### 引擎层（领域无关）

| 类 | 作用 | 亮点 |
|----|------|------|
| `CodeAgentLoopService` | Agent 主循环 | 双层限流、会话记忆缓存、反思闭环、终止条件 |
| `ContextCompressor` | 上下文压缩 | 五级降级，按角色合并，CJK 感知 token 估算 |
| `SubAgentRoutingService` | 子 Agent 路由 | LLM 结构化路由 + 关键词兜底，可审计 |
| `ToolCallGuard` | 工具调用守卫 | 按会话计数，防死循环；独立组件，无循环依赖 |
| `LongTermMemoryService` | 长期记忆 | 写入前去重（同会话+同角色+0.85 阈值） |
| `SelfEvaluationService` | 自我评估 | 5 维度评分，JSON 容错解析 |
| `AutoFixerService` | 自动修正 | 最多 3 轮修正循环 |
| `SessionManager` | 会话管理 | 会话级记忆隔离，定时清理过期会话 |
| `DeepSeekModel` | LLM 封装 | 支持 tool calling、thinking 模式 |

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

```java
@Component
public class MyTools {

    @AgentTool(
        name = "customAction",
        description = "描述这个工具做什么，什么时候用",
        readOnly = true,
        timeout = 10
    )
    public String customAction(
        @ToolParam(name = "input", description = "输入参数说明", required = true) String input
    ) {
        return "结果";
    }
}
```

工具由 `ToolRegistry` 扫描 `@AgentTool` 注解后自动注册到可调用列表。

### 切换领域

架构支持按领域切换：新增一个领域插件包，实现 `RetrievalResult` / `DocumentParser` / `RetrievalService` / `ToolPlugin` / `ISubAgent` 五个接口，修改 `application.yml` 中 `agent.domain.name` 即可。引擎层无需改动。

---

## ⚠️ 安全注意事项

- 🔐 **API Key 与 Token 通过环境变量注入**，切勿硬编码提交到仓库
- 🛡️ `ReadFileTool` 有路径越界检查，务必配置合理的 `project.base-path`
- 🚦 高风险工具建议加用户二次确认

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
- [ ] 接入 MCP（Model Context Protocol），工具动态发现
- [ ] 引入 Cross-Encoder 做检索精排
- [ ] API Key 鉴权
- [ ] Redis 会话持久化
- [ ] 可观测性（traceId + Micrometer）
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

上下文压缩策略受 [Claude Code](https://docs.anthropic.com/en/docs/claude-code) 启发。
