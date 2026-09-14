# StudyAgent 技术设计方案

> 2026-09-14 对话式重构：大纲归知识库，`learning_sessions.plan_run_id` 关联大纲，`knowledge_points.outline_node_id` 关联原大纲叶子；每次新聊天创建独立会话/知识点 ID。已完成节点按同一大纲跨会话汇总，新聊天跳过已完成节点；在途测验、卡片、消息和摘要按会话隔离。`POST /plans/{id}/session?newConversation=true` 新建聊天，不带参数仍继续已有会话。模型每轮可读取知识库当前大纲；原聊天的在途学习路径不因重新生成大纲被悄悄重写。V17 只增加关联字段，不迁移旧实验进度。


> 2026-09-10 用户确认的大纲调整：每个资料库直接展示当前大纲，不再提供历史规划任务列表，也不兼容旧版卡片大纲。大纲保存为递归目录树，叶子节点是独立学习任务；父节点聚合后代完成状态，学习按树的深度优先顺序推进。大纲页只显示标题、只读完成状态和简短重点标记，删除课件依据、重点依据、长说明及时间估算。知识点讲解和资料溯源属于学习对话。旧大纲需重新生成，此规则覆盖下文旧版两层大纲与卡片展示描述。

**2026-09-09 对话流程修订：** 采用用户确认的“讲解/追问→测验→错题答疑→卡片草稿→用户确认全部→Anki→下一点”。题目与卡片为工具约束的 1–10 个；写卡时并行摘要、确认前保留原上下文、确认后替换整点并舍弃卡片阶段上下文。实现入口与真实验收见 [对话修复记录](../implementation/learning-dialogue-repair.md)。这些规则替代下文旧版固定五题三卡、生成卡片即完成的设计。

**版本** 2.0 · **日期** 2026-09-04 · **状态** 首个可运行里程碑架构已确认

**2026-09-08 架构扩展已确认：** M3–M8 的当前目标契约见 [002-简历目标与验收计划.md](002-简历目标与验收计划.md)。该文补充并调整下文 M0–M2 的规划、切块、工具推进、压缩、上传和同步 REST 边界；新增 SSE、Anki 与 ASR。存在这些阶段差异时按新契约实施，实际完成状态以 PROGRESS.md 为准；AgentScope 单 Runtime、服务端业务状态权威及既定分包原则继续有效。

产品范围见 [001-全局设计与范围.md](001-全局设计与范围.md)，实时状态见根 [PROGRESS.md](../../PROGRESS.md)。本文只描述当前目标架构与关键契约，不兼容已经废弃的旧分层或双 Runtime 方案。

## 1. 设计目标

首个里程碑只完成两条真实纵向链路：

1. `PDF → 对象存储 → Tika → 分块 → DashScope embedding → Elasticsearch → Agent RAG tool`。
2. `学习目标 → 计划 → 单知识点讲解/答疑 → 五题测验 → 三张卡片 → 完成`。

AgentScope Java 2.0.1 是唯一 Agent Runtime。Spring AI 代码与依赖全部删除；不保留 provider fallback、兼容层或第二套 Agent Loop。

## 2. 技术栈

| 层 | 选型 | 当前用途 |
|---|---|---|
| 后端 | Java 21、Spring Boot | HTTP、服务编排、事务与配置 |
| Agent Runtime | AgentScope Java 2.0.1 | Agent Loop、AgentTool、AgentState、Memory/Compaction |
| Chat Model | DeepSeek（AgentScope OpenAI-compatible 扩展） | hello、讲解、测验与卡片生成 |
| Embedding | 阿里云官方 `dashscope-sdk-java` | DOCUMENT/QUERY 两种 embedding |
| 数据库 | MySQL、MyBatis-Plus、Flyway | 学习业务事实与处理状态 |
| 检索 | Elasticsearch Java Client | BM25、向量检索、RRF 与父块回填 |
| 摄入 | S3-compatible 对象存储、Apache Tika | 原始 PDF 保存与文本解析 |
| 前端 | React 18、TypeScript、Vite | 知识库与学习闭环同步 REST 页面 |

禁止把密钥输出到日志或实现文档。外部 provider 失败直接返回明确错误，不引入备用 provider 掩盖问题。

## 3. 总体架构

```text
React UI
  │ synchronous REST
  ▼
Spring Web ── identity scope ──────────────────────────────┐
  │                                                       │
  ├─ ingest ── S3 ── Tika ── chunk ── embedding ── ES    │
  │                                                       │
  └─ learning ── AgentScope main Agent                    │
                    ├─ knowledge_search AgentTool ── rag ─┘
                    ├─ state transition tools ── MySQL
                    ├─ ConversationCompactor ── AgentStateStore
                    └─ trace mapping ── trace timeline API
```

核心依赖方向：

```text
learning → agent → rag
ingest   → rag
learning → review
identity → all request paths
```

- `agent` 不依赖 `learning`，只提供 AgentScope 集成、工具治理、状态保存和 trace 映射。
- `learning` 拥有学习领域状态和流程，不能把 MySQL 业务事实塞进 AgentState。
- `rag` 读索引，`ingest` 写索引；二者通过稳定的 chunk/index 契约衔接。
- 包结构与编码纪律以根 [AGENTS.md](../../AGENTS.md) 为准。

## 4. 身份与权限 scope

首版以 `username='default-user'` 的数据库记录作为唯一用户，不建设注册、登录或 RBAC。身份仍必须由服务端解析并绑定，不能由模型提交。

`users` 表使用：

- `ENGINE=InnoDB`
- `DEFAULT CHARSET=utf8mb4`
- `COLLATE=utf8mb4_0900_ai_ci`

M1 尚未建立学习会话时，检索 HTTP 入口验证请求用户拥有 `knowledgeBaseId`，再把 `userId + knowledgeBaseId` 绑定进本次 `RuntimeContext`。M2 建立学习会话后，服务端从 `learningSessionId` 恢复同一个 user/knowledge-base scope 和当前 knowledge point，再构建运行上下文。模型可见的工具 schema 始终不包含 `userId`、`knowledgeBaseId` 或 `knowledgePointId`。

## 5. 知识库与摄入

### 5.1 最小知识库

首版建立 `knowledge_bases` 表，至少保存 `id`、`user_id`、`name`、创建和更新时间。它提供独立、可验证的知识库归属，不再用 documents 的存在性代替知识库实体。

HTTP 能力只包括：

- 创建知识库。
- 列出当前用户的知识库。
- 重命名当前用户的知识库。
- 查看一个知识库下的文档及处理状态。

不实现删除知识库，也不实现 MySQL、对象存储和 Elasticsearch 的级联清理。

### 5.2 PDF 摄入管道

```text
RECEIVED → STORED → PARSED → CHUNKED → EMBEDDED → INDEXED
```

- 上传接口只接收当前里程碑约束内的 PDF，并先验证知识库归属。
- 原始 PDF 写入对象存储；S3 endpoint 必须通过配置解析并在启动阶段验证。
- Tika 提取正文和可用结构信息。
- 分块生成 parent/child 关系和可追溯 provenance；不得只保存无法定位原文的纯文本。
- 每一步写明确状态；失败停在出错步骤并保存可诊断错误，不伪装为成功。

### 5.3 Embedding

`rag.embedding.EmbeddingService` 由阿里云官方 `dashscope-sdk-java` 实现，不经过 Spring AI，也不手写 DashScope HTTP client。

接口保留两种语义：

- `DOCUMENT`：摄入时为知识片段生成索引向量。
- `QUERY`：检索时为用户查询生成查询向量。

调用方必须显式选择语义，不能依赖默认值。批次大小、模型名和超时进入 `config/` 下的 `@ConfigurationProperties`。

## 6. RAG 检索与 AgentScope 接入

### 6.1 检索链

1. 服务端注入 user/knowledge-base filter。
2. 为 query 生成 `QUERY` embedding。
3. 并行获得 BM25 与向量候选。
4. 用 RRF 合并排名。
5. 根据 child chunk 回填 parent chunk 内容和出处。
6. 输出受控数量的结果。

统一结果契约至少包含：

```text
chunkId
content
provenance
score
```

检索为空时返回明确的“没有资料依据”结果。Agent prompt 和工具返回都不得生成不存在的文档名、页码或 chunk。

### 6.2 Retrieval 应用服务与 AgentTool

- `rag/retrieval` 应用服务拥有检索编排和统一结果契约。
- 通过稳定的 AgentScope `AgentTool`/`Toolkit` 自定义 `knowledge_search`，内部调用该应用服务。
- 模型可见参数只有 query；权限 scope 由服务端在构造工具时绑定。
- 工具将统一 RAG 结果序列化给模型，同时保留 chunkId 供测验解释和卡片来源引用。

AgentScope 2.0.1 的整个 `io.agentscope.core.rag` package（包括 `Knowledge`）已标记 `@Deprecated(forRemoval=true, since="2.0.0")`。目标态不实现或依赖该 package，也不采用其中的 `KnowledgeRetrievalTools` 或 `GenericRAGHook`；稳定的 tool/toolkit 扩展点足以承载一个权限绑定清楚、返回契约可控的显式检索工具，同时避免把即将移除的 API 变成项目边界。

## 7. 学习会话与状态机

### 7.1 聚合边界

一个 `LearningSession` 固定绑定：

- 一个 user。
- 一个 learning goal。
- 一个 knowledge base。
- 一个 AgentScope session。
- 同一时刻一个 active knowledge point。

客户端只用 `learningSessionId` 恢复。服务端据此恢复业务聚合和对应最新 AgentState；不能信任客户端重复提交的身份、知识库或当前知识点。

### 7.2 状态转换

```text
NEW → EXPLAINING → QUIZZING → CARD_GENERATING → COMPLETED
```

- Agent 只能通过受控工具提出状态推进请求。
- 服务端注入 user 和当前 knowledge point，并验证只能走相邻转换。
- 任何一步失败都留在当前状态并记录错误；不提前推进后回滚成不确定状态。
- QUIZZING 中允许用户继续追问，回答后仍保持 QUIZZING，不退回 EXPLAINING。
- 一个知识点完成后，学习计划再激活下一个知识点；首版不并行学习多个知识点。

### 7.3 测验与卡片

- 每次测验固定五题，题目作为一份 JSON 聚合持久化。
- 用户整份提交答案后评分，并对错题给出解释。
- 首版没有及格门槛；提交并完成评分即可进入卡片生成。
- 每个知识点生成三张复习卡片并持久化。
- 卡片可保存来源 chunkId，但首版不调用 AnkiConnect。

讲解、测验和卡片生成都在主 Agent 流程内完成。首个里程碑不启用学习 subagent，避免在闭环尚未稳定时引入额外会话、失败和委派状态。后续若启用，每个 child agent 必须有工具白名单和独立 ReAct loop。

## 8. 持久化与上下文

### 8.1 MySQL 是业务事实来源

首个里程碑的最小业务表：

| 表 | 事实 |
|---|---|
| `users` | 用户身份 |
| `knowledge_bases` | 知识库归属与名称 |
| `documents` | 原始资料、对象位置和处理状态 |
| `document_chunks` | chunk/parent/provenance 与索引映射 |
| `learning_sessions` | 目标、KB、AgentScope session、当前状态 |
| `learning_plans` | 会话的知识点顺序 |
| `knowledge_points` | 单知识点内容、顺序和状态 |
| `quizzes` | 五题 JSON、提交答案和评分结果 |
| `review_cards` | 三张卡片及来源 |

具体列由对应实现批次按最小契约确定。旧自建 Runtime 的 run/step/message/checkpoint 表不迁移为目标 schema；trace 时间线的查询投影只在 trace 模块实现时确定，不在本文预设新的持久化表。

### 8.2 AgentState 只保存最新短期上下文

AgentState 保存恢复对话所需的最新状态，不保存 checkpoint 历史，不实现 fork/replay。业务状态以 MySQL 为准；恢复时二者通过 learning session 与 AgentScope session 映射衔接。

知识点完成后，在产生完成响应的当前 Agent turn 结束处，由薄适配器执行一次不依赖日常 middleware 阈值的强制压缩：

1. 读取该会话当前 Memory。
2. 构造 one-off `CompactionConfig`：`triggerMessages(1)`、`keepMessages(1)`，并设置 StudyAgent 定制 `summaryPrompt`。Prompt 必须保留 AgentScope 用来注入待压缩消息的 `{messages}` 占位符，同时要求摘要保留学习目标、已掌握点、易错点、关键出处和下一知识点所需上下文。
3. 调用 public `ConversationCompactor.compactIfNeeded(...)`。
4. 处理返回的 `Optional<List<Msg>>`：有值时用压缩后的消息替换同一个 `AgentState.contextMutable()` 中的会话上下文；预期应压缩却返回 empty 时显式失败，不能把一次表面调用记成已压缩。
5. 将修改后的同一个 AgentState 保存到 AgentStateStore；保存成功后才能完成本次知识点收尾。

`triggerMessages(1)` 让该 one-off 配置在知识点完成时立即进入压缩判断，`keepMessages(1)` 为摘要留出非空前缀；这条完成路径不依赖常规长会话阈值。调用或保存失败必须暴露，不得把未压缩/未保存状态标成成功；首版不保留压缩前 checkpoint。

## 9. Trace API

后端为每次 Agent 请求生成 traceId，把 AgentScope 可获得的运行事件映射为稳定的产品时间线。最小事件包含顺序/时间、阶段、事件类型、摘要、成功或失败状态；敏感配置和完整密钥不能进入事件。

提供按 traceId 查询时间线的 JSON API。首版不做 trace UI、不做 replay，也不宣称底层日志等同于产品 trace。

## 10. HTTP 与前端

前端删除现有实现后，以 React 18 + TypeScript + Vite 全新实现；不背负旧组件兼容。

首版页面能力：

- 知识库创建、列表、重命名。
- PDF 上传、文档处理状态、知识库检索演示。
- 学习目标输入、学习计划和当前知识点。
- 讲解与 QUIZZING 中答疑。
- 五题测验提交、评分和错题解释。
- 三张卡片与学习状态展示。

所有交互使用同步 REST。首版不做 SSE、WebSocket、trace UI、画像页或 Anki 页面。

## 11. 失败与事务边界

- Controller 只做协议转换；业务校验和事务位于服务层。
- 学习状态转换与对应业务事实写入处于同一明确事务边界。
- 对象存储、embedding 和 Elasticsearch 等外部调用失败必须保留管道步骤和错误信息。
- 工具调用拒绝越权 scope 或非法状态转换，并把失败明确返回给 Agent；不吞异常、不静默降级。
- 不为首版添加备用 provider、通用插件层或假想扩展点。

## 12. 验证边界

模块完成时由长期实现 agent 一次性完成相关单元/集成测试、`mvn compile` 和必要的 `mvn test`，再由独立 verifier 异步审查提交和关键逻辑。

首个里程碑至少用真实环境验证：

- DeepSeek hello 成功且配置错误时明确失败。
- 真实 PDF 可上传、解析、分块、embedding、索引并检索到出处。
- 跨 user/KB 的检索被服务端拒绝或隔离。
- 无结果不生成伪造来源。
- 学习状态只走合法相邻转换，失败和 QUIZZING 追问符合约定。
- 五题测验、评分解释、三张卡片及恢复链路可完成。
- 知识点完成后的 compact 状态可再次恢复。
- traceId 能查询到标准化时间线。

实现状态只更新 [PROGRESS.md](../../PROGRESS.md)；实现细节与测试证据在模块完成后写入 `docs/implementation/`。
