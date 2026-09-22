# 文档入口

理解当前产品只需从前三项开始；历史实验是追溯材料，不是日常开发必读清单。

| 目的 | 入口 |
| --- | --- |
| 展示、截图、本机启动 | [根 README](../README.md) |
| 整体架构、三条主链路、LRU 数据例子、代码定位 | [当前技术设计](design/StudyAgent-技术设计方案.md) |
| 用户已确认的产品行为和范围 | [产品范围](design/001-全局设计与范围.md) |
| 当前待办和完成记录 | [PROGRESS](../PROGRESS.md) |
| 领域术语 | [CONTEXT](../CONTEXT.md) |
| 跨会话学习记忆 | [记忆设计](implementation/learning-memory.md) |
| 口令保护的云端演示配置 | [部署说明](../deploy/demo/README.md) |
| 脚本用途 | [脚本导航](../scripts/README.md) |

## 简历数据来源

- [当前对话流程的压缩实验](implementation/current-learning-compaction.md)：5 个知识点、31 轮，包含摘要调用；有损压缩，不等于费用下降 62%。
- [OS 人工 28 题与父块预算实验](implementation/os-rag-human-28.md)：包含 4096/8192 对照及逐题判读限制。
- [上传实验](implementation/m6-upload-performance-report.md)：本机环境和并发参数。

`eval/` 保存实验输入与汇总，`docs/evidence/` 保存阶段记录。不要为了整理目录而重新调用模型、重建索引或覆盖历史结果。

## 历史材料怎么读

`design/002-*`、`design/003-*`、`implementation/m*-*`、旧开发复盘及 UI 概念稿记录的是当时的目标和实现；其中固定题数、旧状态、旧 API 等描述不代表当前版本。当前行为以产品范围和技术设计为准。

2026-09-22 已移除 `implementation/phase-0` 至 `phase-3` 的早期逐任务说明及重复的旧架构导读。完整历史可从 Git 查回，不再保留另一份“当前架构”。
