# M3 运行与测量基础

本文解释实现和验证边界；实时任务状态只在 [PROGRESS.md](../../PROGRESS.md)。

## 启动配置

`some_apiKey` 使用 properties 格式，Spring 自行导入。`bailian_for_embedding` 映射 embedding key，`new_deepseek_apiKey` 映射聊天 key；环境变量仍可覆盖。启动脚本仅验证字段存在，不把 key 当作命令参数传递。已移除 application.yml 的 embedding 明文 key 默认值。

百炼工作区的 OpenAI 兼容地址保留主机，仅将 `/compatible-mode/v1` 转换为 Java TextEmbedding SDK 所需的 `/api/v1`。原生 URL 原样使用；具体模型在该账户上的可用性仍必须以真实调用验证。

模型和接口参考：[百炼文本 embedding API](https://www.alibabacloud.com/help/en/model-studio/text-embedding-synchronous-api)、[DeepSeek API](https://api-docs.deepseek.com/)。访问于 2026-09-08。官方模型目录和本账户实际可调用性分别记录，不能混同。

前端开发代理与后端统一使用 8080。`eval` profile 选择计划中的新模型，但使用独立 MySQL 数据库 `study_agent_eval`、Redis DB 1、RustFS bucket、ES 索引/别名、MQ topic/group 和 `.eval/agentscope` 状态目录。旧 embedding 索引不复用。

Docker 启动示例（前提：依赖已启动且独立数据库已创建、应用用户已获授权）：

```powershell
docker compose -f docker-compose.yml -f docker-compose.eval.yml run --rm --no-deps eval-app mvn -q test
docker compose -f docker-compose.yml -f docker-compose.eval.yml run --rm --no-deps eval-app mvn -q -DskipTests package
docker compose -f docker-compose.yml -f docker-compose.eval.yml up -d --no-deps eval-app
```

内存较小时按上面顺序串行运行。应用直接启动已打包 JAR，不常驻 Maven JVM；代码变更后先验证并重新打包再启动。Compose 将 Maven heap 限制为 256 MiB，应用/测试 JVM 默认 heap 限制为 384 MiB。学习 skill 使用原仓库目录的只读挂载，AgentState 单独持久化。

宿主机启动可用 `scripts/run-with-local-keys.ps1 -Profile eval`；须自行满足 profile 指定的依赖网络条件。仓库 broker 广播地址是 Docker 网络名，因此本次真实验收使用容器应用，不声称仅执行宿主机脚本就一定完成 MQ 连通。

## 调用账本

所有通过主 Model bean 的调用经过 `ObservedModel`，涵盖直接规划、主 Agent、检索 Agent 和共用该 Model 的压缩器。每次订阅先持久化 STARTED，再调用 provider；外部 retry 的新订阅单独计数，完成/错误/取消分别记录终态。

`ModelUsageRecorder` 写入 `.eval/model-calls.jsonl`。默认上限 2000 次，启动时从 STARTED 事件恢复计数；无法记录账本时不得继续当作正常完成。此文件由本地单实例应用拥有，不应同时启动多个实例写同一账本。

账本记录模型、服务端 traceId、HTTP operation、attemptNumber、消息数、工具列表、工具选择参数、终态、耗时及 SDK usage。它不保存凭据、异常原文或 prompt/response 正文。HTTP `X-Trace-Id`、业务 trace 与 Reactor 中的模型 trace 关联；跨线程通过 Reactor Context 传播。

流式 usage 取最后一次非空累计值，不逐块相加。输入 token 包含缓存输入；总量只加输入和输出。SDK 缓存字段为零也可能表示未报告，缺失整份 usage 明确标为 unknown。异常请求、取消或进程退出前仅 STARTED 的调用不会被统计脚本悄悄视作零消耗。

```powershell
python scripts/summarize-model-usage.py .eval/model-calls.jsonl
```

输出 `usageComplete=false` 时，known totals 只是已知消耗下界。当前账本是调用计数和 usage 基础；区分自动阈值摘要与知识点摘要、embedding usage、完整工具轨迹和正式实验清单仍需继续补齐，不能据此宣称 M3 全部完成。

## 基线与烟测

优化前源码/参数证据见 [source-baseline-20260908.json](../evidence/m3/source-baseline-20260908.json)。它记录旧上传合并路径、压缩配置、切块参数、Git SHA 与文件哈希；尚不包含性能数字。原配置曾含凭据，因此不复制原始 yml 到实验包。

用户最新 Goal 的输入路径为 `D:\Download\BDNetdisk\_DL`；2026-09-08 实际核实该路径不存在，课程资料位于 `D:\Download\BDNetdisk_DL`。后续以实际文件清单与 SHA-256 固定输入，原文件只读。

`scripts/smoke-api.py` 通过生产 HTTP API 按阶段运行 ready/create/search/hello/upload/status/plan/explain/quiz/submit/cards/session，保存业务 HTTP 响应、耗时、traceId 和资料哈希到指定 run-dir。**2026-09-22：此处是历史运行记录，脚本的旧学习阶段已删除；当前学习走大纲创建会话和对话入口。**ready 仅对知识库列表执行最多 45 秒的启动等待；业务 mutation 不自动重试。创建的知识库 ID 可跨阶段复用，避免观察超时后重新创建测试对象。Python 依赖见 `scripts/requirements-eval.txt`；本轮在独立 `.eval/python-env` 安装，完整依赖版本记录于 `.eval/python-requirements.lock`，不修改共享 Python 环境。

```powershell
python scripts/smoke-api.py create --run-dir .eval/runs/m3-smoke
python scripts/smoke-api.py search --run-dir .eval/runs/m3-smoke
python scripts/smoke-api.py hello --run-dir .eval/runs/m3-smoke
```

create/status 不调用 LLM；search 调用 embedding；hello/plan 使用真实 LLM 并计入账本。扩大到资料上传前先验证账户与模型端点可用。

## 本轮验证与环境事件

- 初始 Linux 定向配置验证：3 tests，0 failure/error/skip。独立 eval 数据库成功执行六项 Flyway migration，知识库列表 API 返回 code=0、空列表。
- 上述 API 检查发生在接入新观测层之前；新观测层与真实 provider 链路尚不能沿用该结果。
- 首次完整 Linux 测试期间 Docker Engine 返回 500，WSL 查询失败；Docker Desktop 正常重启超时。定向终止 `docker-desktop` 后重新启动，原先四个其它项目容器恢复。测试进程最终 `unexpected EOF`，不算通过。内存压力是待证实原因，不是已定位根因。
- 本机独立源码快照与当时 209 个源码/资源文件哈希一致；汇总 148 tests，0 failure、1 error、3 skipped。唯一 error 是既有 ElasticsearchConfigurationTest 的 Windows selector 创建失败。新增 5 项 ObservedModel 测试全部通过。报告见 [host-tests-20260908.json](../evidence/m3/host-tests-20260908.json)。
- 快照起初遗漏 `.agentscope/workspace/skills`，补齐后仅重跑受影响的两项学习 skill 契约测试通过；此修正没有变更业务实现。
- usage 统计脚本已验证“部分请求缺失 usage”和“缓存输入不重复相加”的计算语义。

- 恢复后使用受限堆内存串行执行 Linux 全套测试：148 tests、0 failure、0 error、3 skipped，通过。只统计本次运行新生成的报告，排除 target 中旧 modules 包的三份遗留报告，见 [linux-tests-20260908.json](../evidence/m3/linux-tests-20260908.json)。Windows selector 对应用例在 Linux 通过。

- 实际账户烟测已通过：qwen3.7-text-embedding + 1024 维经原生 SDK 完成 QUERY embedding 和空知识库检索；DeepSeek v4 flash 经生产 `/api/agent/hello` 返回真实响应。初始证据见 [initial-live-smoke-20260908.json](../evidence/m3/initial-live-smoke-20260908.json)。
- 初次 hello 有两次模型调用，共输入 3418、输出 218 token，两次均有完整 usage 且与 HTTP traceId 一致。源码/字节码核查发现 SDK 默认 MemoryFlushMiddleware 仍会提取跨会话长期记忆；禁用 memory tools 不等于禁用这些 hooks。当前目标不包含此能力，因此增加 `disableMemoryHooks()`。保留初始账本，不把消除范围外调用包装成知识点摘要优化指标。
- 关闭 hooks 后，3 项定向测试通过：四次普通调用恰好加一次阈值摘要，知识点 one-off 摘要和状态保存仍有效。实际重启后的调用次数继续核验。
- 实测账本暴露全局 Jackson 将 Long 序列化成字符串；统计脚本已兼容数值字符串，并用真实账本确认总 token=3636，不改变数据库 ID 的 wire contract。
- 重启后的 hello 只新增一次模型调用，账本 attemptNumber 从 3 继续，没有清零；这一受控变化与禁用长期记忆 hooks 一致。
- 真实 `Chap01.pdf` 已完成上传、MQ 消费、Tika 解析、490 块向量化及 ES 索引；中文问题检索到英文稳定匹配定义和文件来源。SQL 验证 245 个 child 均与 parent 哈希相同，平均 44.4 个字符，是后续短段合并和只向量化 child 的基线证据。见 [pdf-baseline-smoke-20260908.json](../evidence/m3/pdf-baseline-smoke-20260908.json)。
- 真实计划已创建；首个讲解请求在完成工具检索后收到 DeepSeek 400：`Thinking mode does not support this tool_choice`。两次失败调用均在账本保留为 unknown usage，知识点保持原状态。按照 [官方 thinking 参数说明](https://api-docs.deepseek.com/guides/thinking_mode/) 显式冻结常规链路 `thinking.type=disabled`、temperature=0.2，并补上配置传递；相关三项配置/压缩测试通过，原会话恢复正在验收。M5 仍按计划移除强制 tool choice，不能以此配置修复代替对话主导编排。

- 非思考模式下原会话第二次 explain 的 provider 调用均正常返回，但 Agent 连续检索到迭代上限，没有请求状态推进，生产 API 返回 400，traceId=`ab53d268-024e-4881-bb05-08a1d19fa31a`。截至该请求，账本共 21 次调用；不能把接口返回文本或 provider 成功等同于业务成功。新增请求工具列表、消息数与 toolChoice 元数据以及真实 Runtime 定向测试，继续定位。

### 2026-09-09 追加烟测与诊断

- 学习请求 `623a2990-e0bc-4ac3-be27-043d7a92a57d` 再次未提交状态。模型边界账本包含 `Specific[toolName=learning_state_transition]`，但对应模型输出仍为 `knowledge_search`；本地 HTTP 契约测试验证 SDK 正确发送 `tool_choice.function.name`、thinking、temperature 和 max_tokens。真实 provider 与该会话组合为何不遵循指定工具尚未确定，不将推测写成 SDK 缺陷。
- 真实 Runtime 工具往返测试、既有 Hook 与观测测试共 10 项通过；HTTP 参数契约及文件上传测试共 15 项通过。新 Runtime 测试最初的夹具未附流式 JSON content，导致 target 校验失败；按 SDK 参数契约修正后通过，该失败不代表线上状态工具失效。
- 截至上述请求共记录 42 次 Model 订阅，40 次成功、2 次失败，缺失 usage 为 2 次，已知累计输入 213389、输出 11226 token。保存 [调用账本](../evidence/m3/model-calls-through-20260909.jsonl)。这不是成功学习任务的成本指标；SDK 内层重试是否发生不能单靠旧外层账本判定。
- 当前 ObservedModel 强制单次 provider 尝试，重试必须从其外层重新订阅，每次计入持久化上限。使用本地 HTTP 503 注入校验实际请求数与账本计数，避免 SDK 内部重试绕过测量边界；这是计数机制修正，不是失败降级。
- 已开放 TXT、Markdown、PDF、PPTX 上传；拒绝 DOCX/ZIP 和不匹配 MIME。真实 PPTX 首次上传暴露 `content_type VARCHAR(64)` 无法容纳标准 MIME，事务回滚、文档列表为空；V7 将 documents/upload_sessions 字段扩至 255，保留历史迁移不改写。数据库查询确认两个字段均为 255。
- 原 PPTX 重试成功：241 个 parent、241 个 child，消费流水线耗时 86505 ms，最终 INDEXED；生产检索 API 返回 P/V 操作语义及文件来源。见 [PPTX 烟测](../evidence/m3/pptx-baseline-smoke-20260909.json)。该单样本耗时不作为性能基准；尚未实现结构打包与阶段产物复用。
- 当前 Linux 全套报告为 160 tests、0 failure、0 error、3 skipped，含真实 Runtime 往返与本地 HTTP 503 重试计数测试；排除三份旧模块报告。组合命令在测试后执行 Spring Boot repackage 时因 `Cannot allocate memory` 返回 1，因此分别记录[测试报告](../evidence/m3/linux-tests-20260909.json)和构建结果，不把整个命令写成通过。
- 独立 Maven 进程跳过已通过测试、强制重建 JAR 后打包成功，退出码 0；JAR ZIP CRC 检查通过。第一次独立命令因 PowerShell 拆分未加引号的 `-Dmaven.jar.forceCreation=true` 参数失败，正确引用后成功。可运行产物哈希见[检查点打包](../evidence/m3/checkpoint-package-20260909.json)；后续保持构建、测试和应用串行，带点的 Maven 参数加引号。
- 最终 JAR 启动就绪，真实 hello 返回成功且仅新增一次模型调用，attemptNumber=43、输入 2761/输出 77 token，证明账本未因重启清零；见[检查点启动烟测](../evidence/m3/checkpoint-runtime-20260909.json)。该证据不表示学习状态链路已恢复。

后续已补齐 embedding SDK 调用账本：记录 DOCUMENT/QUERY、模型/维度、内容哈希、trace、provider requestId、耗时及 provider usage；不保存明文输入或密钥，usage 缺失保留未知。独立汇总见 [embedding 账本快照](../evidence/m3/embedding-usage-20260909.json)，该快照包含 20 次调用；后续恢复实验另有新增调用。进程中断后缺少终态 usage 的情况已在 M4 实测保留，详见 [M4 实现与证据](m4-ingest-recovery.md)。

以上为初期诊断记录。2026-09-09后续已完成真实五点自然学习、摘要/工具/状态全链trace与持久恢复验收，见`docs/evidence/m5/conversation-pilot-v2-review.json`和`formal-v1-gate-review.json`；M3与M4当前验收完成，历史失败及源码基线仍保留。正式压缩对照尚未完成，不从功能试跑推算收益。
