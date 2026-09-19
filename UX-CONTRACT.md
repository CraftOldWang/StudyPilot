# StudyPilot UX Contract

2026-09-10 多层待办大纲（覆盖旧版入口约定）：每库一份当前大纲，不提供历史规划列表、不兼容旧版卡片数据。生成接口返回递归 nodes（id/title/priority/children），只有叶子是独立学习任务，父节点聚合完成情况。LearningOutline 负责可折叠缩进列表、只读完成框和简短重点标记；叶子完成由卡片确认后的业务状态驱动，不允许仅在前端打勾绕过学习流程。大纲页删除来源链接、依据、时长、长说明；学习对话中的 RAG 来源功能保留。会话列表按 updated_at 倒序，只打开会话不更新该时间。用户要求禁止本轮验证，不运行测试、构建检查或浏览器/API 验收。

2026-09-09 滚动布局：应用固定视口高度；左侧资料库、知识点计划、对话分别滚动。对话输入区固定底部，自动跟随只作用于对话滚动容器，向上阅读时暂停，点击“返回最新消息”恢复。资料页及学习准备页保留独立内容滚动；仅验收桌面端。

## 当前学习交互（2026-09-09 用户确认，覆盖下方旧里程碑约定）

仅验收桌面。自然对话依次经过讲解/追问、1–10道选择题、练习后答疑、1–10张卡片草稿。ToolCalls 拥有默认折叠的工具参数/结果展示，测验工具参数不向前端泄露标准答案。CardDrafts 复用 Field/Feedback，允许编辑整组或对话重写；用户明确确认全部卡片才写入 Anki、完成知识点。确认失败保留卡片及错误，可重试，不自动跳过。

进入卡片阶段并行摘要此前学习过程；卡片编辑与重写期间继续原上下文。全部确认后以摘要替换本知识点的整段模型上下文，包括舍弃卡片阶段记录；数据库历史和最终卡片保留。取消默认全量/窄屏验证，按本次改动执行相关测试和一次真实流程。

## Product context

中文计算机课程学习与备考工具。主要任务为组织课件/习题、形成重点计划、连续学习与恢复。界面 locale 为 zh-CN，课程正文可中英混合；无多语言切换和日本市场流程。后端当前 eval JVM 输出 UTC 的 LocalDateTime；需要展示时间时按明确 UTC 解释并使用 Asia/Shanghai 格式化，不把无时区字符串交给浏览器猜测。目标为 WCAG 2.2 AA 基础键盘、标签、对比度和窄屏可用性。

## Business-context sources

| Scope | Source | Reviewed |
|---|---|---|
| 产品边界、计划/测验/三卡/恢复 | `docs/design/002-简历目标与验收计划.md` | 2026-09-09 |
| 领域术语 | `CONTEXT.md` | 2026-09-09 |
| 规划阶段与来源依据 | `docs/implementation/m5-planning.md`、LearningPlanningController | 2026-09-09 |
| 消息、去重、压缩与 SSE | `docs/implementation/m5-conversation.md`、LearningController、LearningStreamController | 2026-09-09 |
| 服务端身份与资源归属 | IdentityScope、LearningTurnPersistence、KnowledgeBaseService | 2026-09-09 |

## Visual contract

`DESIGN.md` 定视觉意图，`frontend/src/styles.css` 是语义 token 的运行时所有者。此次 M6 迁移中同时更新两者，禁止以屏幕局部样式创建平行 token 系统。只支持浅色主题。用户已授权改进界面，常规视觉取舍不再重复询问。

## Canonical UI Map

以下是本次实现的唯一归属，新增文件需随 M6 一起落地并验证。

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Form | `frontend/src/components/ui/Field.tsx` 的 Field/MultilineInput 与原生 form noValidate | 本文 | 资料命名、目标、恢复、聊天 | 字段标签/错误保留测试 |
| Scrollbar | `frontend/src/styles.css` | DESIGN.md | 文档、课程列表、聊天几何例外 | computed style + 窄屏 |
| Toast | `frontend/src/components/ui/Feedback.tsx` | 本文 | 持久 inline status/alert；当前无瞬时 toast 队列 | live region + 错误恢复 |
| CRUD | `frontend/src/api.ts`、App、KnowledgeBaseSidebar | 已有 API | 创建后选中、重命名原处更新 | 真实 API + 组件测试 |
| Table Selection | `frontend/src/components/PlanningStart.tsx` | 规划 API | 逐份文档选择“课件/习题/不使用” | 键盘、角色互斥、不可检索禁用 |
| File Upload | `frontend/src/components/UploadWidget.tsx`、`frontend/src/upload` | 原生 multipart API | 课件及MP4/M4A/MP3/WAV；单文件、8MiB/4并发、Worker哈希、暂停/取消/恢复 | 生产API重启验收、400MB浏览器刷新续传；媒体链路另验收 |

当前没有日期输入或 authored Select/Listbox，使用原生数字输入、radio 和 checkbox；因此不引入对应弹层组件。

来源抽屉由 `SourceDrawer.tsx` 统一拥有，使用原生 dialog.showModal 的焦点隔离、Escape 与焦点归还，关闭时恢复页面滚动。来源只经当前会话/计划绑定资料库读取，失败明示，正文按纯文本显示；旧资料仅记录字符区间时，不冒充原课件页码。普通检索复用来源位置格式化，不直接显示位置JSON；音视频的时间戳保留在正文。

已保存讲解、对话和流式正文共用`components/ui/MessageContent.tsx`，支持Markdown及美元符号包裹的数学公式。KaTeX保持trust=false，模型HTML和远程图片不执行；不完整或无效公式仍保留可读内容，不影响其余消息。独立长公式的溢出归公式区域，不撑开页面；来源原文抽屉仍为纯文本，不改写证据。

## Flow ledger

学习记忆页复用 Field / MultilineInput、Feedback、apiRequest 与 page-scroll。偏好草稿在工作区切换时保留；进入页面重新读取当前知识库的最近 50 条测验记录。排除/重新纳入只改变后续跨会话召回，不删除原聊天，成功后更新状态。关闭记忆同时停止新测验记录与偏好/历史注入，已有数据保留。历史已注入的聊天上下文不会追溯擦除。

| Operation | Pending | Success | Failure recovery |
|---|---|---|---|
| 新建知识库 | 阻止重复提交，保留名称 | 插入列表并选中，清空输入 | 保留名称与字段错误，不伪装成功 |
| 重命名 | 原表单保持，禁用提交 | 原处更新并关闭编辑 | 编辑框保持打开，不丢输入 |
| 上传资料 | 本地校验/已保存字节/合并校验分别显示，仍可切换资料库 | 文档进入列表，处理成功后可选作规划资料 | 暂停只断开传输；取消调用后端并等待确认；结果不明时查询原会话 |
| 创建计划 | 显示持久化阶段 | 展示大纲/重点/知识点任务，用户开始学习 | 保存 runId，查询/恢复原任务；不悄悄新建替代 |
| 开始学习 | 提交已有成功规划 | 进入会话，展示当前知识点 | 保留规划，重复提交依后端返回同一会话 |
| 发消息/快捷操作 | 同一 requestId，临时消息与阶段提示 | 使用 result.turn 的最终记录、产物与 session | 区分失败与连接中断；先查询状态再重试原请求 |
| 恢复 | 保留当前只读内容与输入 | 加载会话和完整回合列表 | 保留恢复 ID，显示错误；不将本地空列表覆盖服务器状态 |
| 导出 Anki | 每张卡独立提交，保留正文与来源，禁用重复点击 | 后端返回笔记 ID 后显示已导出 | 显示持久化错误；重试先查询稳定标识，刷新读取服务器状态 |

Anki 导出由 `AnkiCardExport.tsx` 统一拥有，旧会话卡片与聊天产物复用；使用 `apiRequest`、原生按钮和 `Feedback`。单向导出到本机 AnkiConnect，页面离开不取消后端导出；不自动重试、不拉取复习记录。每张卡的成功/失败独立展示，不能用一张成功推断三张全部成功。再次导出复用既有笔记，不覆盖用户在 Anki 中编辑过的正文。

## Navigation and desktop acceptance

2026-09-09用户明确调整：本应用仅用于桌面网页使用、演示和测试，不再将窄屏/移动端适配作为开发或验收要求。保留已有响应式CSS，不为移动端继续增加工作；后续浏览器验收以桌面阅读、公式、来源与交互为重点。

知识库/学习为工作区导航，浏览器标题为“资料库 / 学习 — StudyPilot”。已提交资源 ID 可进入 URL，学习目标和消息正文不能进入 URL。会话切换前保留或提示未发送草稿；工作区切换若组件仍挂载并保留草稿则无需反复确认。页面刷新依已保存会话 ID 恢复，不能在刷新时自动发起新模型请求。

桌面保留侧栏；窄屏课程选择与计划可展开，正文自然流动。表格横向溢出归表格自己，侧栏列表不能决定整个 grid 的最小宽度。当前项可从键盘访问，完整文档名可通过可展开内容查看；不能只靠 hover 才显示重命名动作。

## Async and recovery

业务状态采用后端确认后更新。消息生成期间保持导航与阅读可用，防止同一会话多次发送；SSE 关闭不取消服务器回合，因此按钮只能叫“断开显示/查询结果”，不能声称“停止生成”。暂不提供服务器取消、消息重放或离线排队。

新请求生成一次 requestId，重试保持相同 ID 和消息。接口返回 RUNNING 时显示处理中并查询；FAILED 时保留错误和原请求重试；SUCCEEDED 才显示已保存。消息状态码以 body.code 判定，现有 BusinessException HTTP 状态可能统一为 400。

生成阶段失败且后端释放回合执行权后，允许修改消息重新发送；产物已提交但上下文未完成时，只恢复原回合。未知结果保留请求编号。会话和计划草稿在当前页面内存中随导航保留；成功响应只清理刚发送的草稿，不能清空处理期间输入的下一条消息。

服务器自动持久化完整回合；模型上下文由后端摘要管理，前端每次只发送当前消息，不上传全量聊天。这条业务契约覆盖通用 streaming 示例中的“每次发送全历史/仅点击保存才持久化”建议。流式解析必须缓存跨网络块的 SSE 行与 JSON，不丢弃不完整分片。文本通过安全渲染，不执行模型 HTML。

上传任务固定绑定创建时的资料库，导航切换不更改目标。localStorage 只保存恢复所需的文件元数据、哈希与上传编号，不保存文件内容；刷新时先查询真实已保存字节，用户重新选择同一文件并验证哈希后继续。合并阶段查询或重试原会话，不把 100% 传输进度当作完整上传成功。文件格式由前后端共同验证，当前仅 TXT/Markdown/PDF/PPTX；成功上传和后续解析完成分别显示。

请求失效通过 AbortController 或 generation ID 处理；旧知识库/旧会话响应不得覆盖新选择。服务器在断线后可能继续执行，不能把网络异常写成确定的生成失败。前端可保留恢复所需的 sessionId/requestId，消息正文以服务器记录为准，不新增第三方分析或遥测。

## Validation and feedback

原生标签、noValidate、aria-invalid/aria-describedby 与首错聚焦。IME 输入时 Enter 只确认中文，不发消息；Shift+Enter 换行。textarea 禁止手动拖拽，提供足够高度和可滚动/增长区域。错误保留在相关表单/回合中，常规进度用 polite status，不逐 token 重复朗读全部正文。

同一操作维持同一名称，常规取消/返回不使用浏览器 confirm/prompt/alert。未保存且确会丢失的输入使用应用拥有的确认或明确保留，不增加无必要确认。未实现的删除、批量破坏动作、付款和权限修改不进入本次 UI。

## Verification

运行 `npm run typecheck`、`npm test`、`npm run build`、DESIGN.md lint、premium strict audit 与 token 映射检查。浏览器覆盖桌面、390px 窄屏、键盘、长文档名、空资料、失败/重复请求与恢复、只读真实已保存会话。真实课程模型动作受当前审批限制，不能以浏览器入口绕过；必要的本地组件测试明确标注测试数据，不声称 provider 验收。

当前发现的迁移问题：旧侧栏在390px撑开根文档至约4840px；知识库创建/改名父组件吞错使子表单错误清空；旧学习页只显示最新回复、缺少重点与完整聊天；旧控件缺少统一 hover/窄屏行为。按上述共享归属修正，并比较资料页与学习页。
