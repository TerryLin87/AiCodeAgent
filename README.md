# AiCodeAgent

一个基于 **Ollama + Spring Boot + LangChain4j + Milvus** 的智能 Java 代码助手 Agent。
支持代码语义检索、调用关系分析、多级记忆管理、子 Agent 协作、反思闭环与自我修正。

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
- 🛠️ **自定义工具注解**：`@AgentTool` / `@ToolParam` 声明式注册，支持超时、只读、二次确认
- 🚦 **多级降级**：限流降级、压缩降级、路由降级、反思降级，每层都有 fallback

---

## 🏗️ 系统架构

```text
┌─────────────────────────────────────────────────────────────────┐
│                        用户请求 (HTTP)                          │
└────────────────────────────────┬────────────────────────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │  CodeAgentLoopService   │  ← 主循环
                    │  · 全局限流 (10)         │
                    │  · 会话级限流 (1)        │
                    └────────────┬────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
   ┌────▼────┐            ┌──────▼──────┐          ┌──────▼──────┐
   │ 记忆管理 │            │ 上下文压缩  │          │ 子 Agent    │
   │         │            │             │          │  路由       │
   │ ·短期    │            │ ·L1 剪枝    │          │ ·LLM 首选   │
   │ ·长期    │            │ ·L2 合并    │          │ ·关键词兜底 │
   │ ·工作    │            │ ·L3 摘要    │          │ ·默认 explore│
   └────┬────┘            │ ·L4 实体    │          └──────┬──────┘
        │                 │ ·L5 截断    │                 │
        │                 └─────────────┘                 │
        │                                                 │
   ┌────▼─────────────────────────┐         ┌─────────────▼────────────┐
   │   Milvus (chat_memory)       │         │  explore │ verify │ plan │
   │   · 语义去重                  │         │  (代码)  │(质量)  │(规划) │
   │   · 跨会话召回                │         └──────────┬───────────────┘
   └──────────────────────────────┘                    │
                                                       │
   ┌───────────────────────────────────────────────────▼──────────────┐
   │                       工具与检索层                               │
   │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
   │  │ 混合检索     │  │ 调用关系     │  │ 代码解析     │           │
   │  │ ·语义(Milvus)│  │ ·JavaParser  │  │ ·AST 抽取    │           │
   │  │ ·BM25(Lucene)│  │ ·跨项目图    │  │ ·类/方法片段 │           │
   │  └──────────────┘  └──────────────┘  └──────────────┘           │
   └─────────────────────────────────────────────────────────────────┘

        反思闭环（doExecute 内部）：
          输出 → 评估 → [未达标? 修正 → 再评估] → 返回

        终止条件（ToolCallGuard）：
          每次工具调用 → 计数+1 → [超上限? 拒绝调用]
```

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
# 拉取 Embedding 模型
ollama pull qwen3-embedding:0.6b

# 启动 Ollama 服务
ollama serve
```

### 2. 配置 Milvus

本地部署（推荐用 Docker）：

```bash
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:latest standalone
```

或使用 [Zilliz Cloud](https://cloud.zilliz.com/) 免费实例，记录 `uri` 和 `token`。

### 3. 配置应用

复制配置模板并填写你的密钥：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

编辑 `application.yml`：

```yaml
# ===== 代码库路径 =====
codebase:
  sub-projects-path: /path/to/your/projects
  exclude-dirs: [target, .git, .idea, node_modules]

# ===== Milvus 向量库 =====
agent:
  milvus:
    uri: ${MILVUS_URI}
    token: ${MILVUS_TOKEN}
    collection: bf_novel_code
    max-top-k: 16384
  embedding:
    dimension: 1024
  # ===== 上下文压缩 =====
  context:
    max-messages: 20
    max-tokens: 8000
    keep-recent: 5
    enable-summary: true
  memory:
    dedupe-threshold: 0.85
  # ===== 反思闭环 =====
  reflexion:
    enabled: true
    score-threshold: 7.0
    max-duration-ms: 30000
  # ===== 工具调用上限 =====
  tool:
    max-calls-per-session: 15

# ===== DeepSeek API =====
deepseek:
  apikey: ${DEEPSEEK_API_KEY}

# ===== 混合检索权重 =====
search:
  hybrid:
    semantic-weight: 0.6
    keyword-weight: 0.4

# ===== 文件读取沙箱 =====
project:
  base-path: /path/to/your/projects
```

> ⚠️ **安全提示**：请勿将 `application.yml` 提交到 Git。使用 `application.example.yml` 作为模板。

### 4. 启动应用

```bash
# 编译
./mvnw clean package -DskipTests

# 设置环境变量
$env:DEEPSEEK_API_KEY="sk-你的key"
$env:MILVUS_URI="https://..."
$env:MILVUS_TOKEN="..."

# 启动
java -jar target/ai-agent-demo-*.jar
```

启动后访问 `http://localhost:8080/test/health` 检查状态。

---

## 📡 API 使用

### 基础对话

```bash
curl -X POST http://localhost:8080/test/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我搜索用户登录相关的代码"}'
```

### 多轮会话（带 sessionId）

```bash
curl -X POST http://localhost:8080/test/chat/session \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "session-abc123",
    "message": "继续分析这个类的调用链"
  }'
```

### 代码检索

```bash
# 语义检索
curl -X POST http://localhost:8080/test/retrieval/semantic \
  -H "Content-Type: application/json" \
  -d '{"query": "用户登录", "maxResults": 5}'

# 混合检索（语义 + BM25）
curl -X POST http://localhost:8080/test/retrieval/hybrid \
  -H "Content-Type: application/json" \
  -d '{"query": "Schema 数据库迁移", "maxResults": 5}'
```

### 调用关系分析

```bash
# 查询谁调用了某个方法
curl "http://localhost:8080/test/call/callers?className=StorageService&methodName=deleteReferencesByFile"

# 分析变更影响范围
curl "http://localhost:8080/test/call/impact?className=StorageService&methodName=deleteReferencesByFile&depth=3"
```

### 子 Agent 委托

```bash
# 列出所有子 Agent
curl http://localhost:8080/test/subagent/list

# 显式委托给 plan
curl -X POST http://localhost:8080/test/subagent/delegate \
  -H "Content-Type: application/json" \
  -d '{"agentName": "plan", "task": "分析存储层设计，给出扩展方案"}'
```

### 自我评估

```bash
curl -X POST http://localhost:8080/test/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "content": "public class A { public int div(int a, int b) { return a / b; } }",
    "context": "实现一个安全的除法方法，需要处理除零"
  }'
```

---

## 🧩 核心模块说明

### 模块一览

| 包路径 | 职责 |
|--------|------|
| `config` | Spring 配置（Milvus、Ollama、ChatMemory 等） |
| `controller` | REST 入口（`TestController`） |
| `service` | 核心业务：主循环、检索、记忆、评估、修正、工具守卫 |
| `service.subagent` | 子 Agent 实现（explore / verify / plan）及路由 |
| `tools` | LangChain4j 工具（`AgentTools` / `ProjectStructureTool` 等） |
| `instances` | 注解与接口定义（`@AgentTool`、`ISubAgent`） |
| `model` | 自定义模型（`DeepSeekModel` 实现 ChatModel 接口） |
| `data` | DTO / 实体类 |

### 关键类说明

| 类 | 作用 | 亮点 |
|----|------|------|
| `CodeAgentLoopService` | Agent 主循环 | 双层限流、会话级记忆缓存、反思闭环、终止条件 |
| `ContextCompressor` | 上下文压缩 | 五级降级，按角色合并，CJK 感知 token 估算 |
| `SubAgentRoutingService` | 子 Agent 路由 | LLM 结构化路由 + 关键词兜底，可审计 |
| `PlanSubAgent` | 任务规划 | LangChain4j 原生 `ResponseFormat` + JSON Schema |
| `ExploreSubAgent` | 代码探索 | 意图识别 + 混合检索 + 调用关系查询 |
| `VerifySubAgent` | 质量验证 | 调 `SelfEvaluationService` 打分 |
| `HybridSearchService` | 混合检索 | RRF 融合语义与关键词结果 |
| `JavaCodeParser` | 代码解析 | AST 提取类/方法 + 调用关系，import 上下文推断 |
| `CallRelationService` | 调用关系 | 读写锁，跨项目影响分析 |
| `LongTermMemoryService` | 长期记忆 | 写入前去重（同会话+同角色+0.85 阈值） |
| `SelfEvaluationService` | 自我评估 | 5 维度评分，JSON 容错解析 |
| `AutoFixerService` | 自动修正 | 最多 3 轮修正循环 |
| `ToolCallGuard` | 工具调用守卫 | 按会话计数，防死循环；独立组件，无循环依赖 |

---

## 🔧 扩展指南

### 新增一个子 Agent

实现 `ISubAgent` 接口：

```java
@Component
public class RefactorSubAgent implements ISubAgent {
    @Override
    public String getName() { return "refactor"; }

    @Override
    public String getCapability() {
        return "重构专家 - 当需要重构代码结构时使用我：\n- 提取方法\n- 消除重复";
    }

    @Override
    public SubAgentResult execute(String task, Map<String, Object> context) {
        // 你的实现
    }
}
```

**无需修改路由代码**——`SubAgentRoutingService` 启动时会自动收集所有 `ISubAgent` Bean 并把能力加入 Prompt。

### 新增一个工具

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

工具会被 LangChain4j 自动注册到 `AgentTools` 可调用列表。

---

## ⚠️ 安全注意事项

- 🔐 **API Key 与 Token 必须通过环境变量注入**，切勿硬编码提交到仓库
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
