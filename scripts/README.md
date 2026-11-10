# 脚本导航

启动网页演示不需要依次运行这些脚本。正常启动按根 [README](../README.md) 操作，测试工具也可直接从前端独立页面进入。

## 日常/可选运行

| 入口 | 用途 |
| --- | --- |
| `run-with-local-keys.ps1` | 本地环境密钥辅助启动 |
| `local-asr-worker.py`、`requirements-asr.txt` | 可选音视频转写；仅使用文档时不需要 |
| `smoke-api.py` | 基础服务、知识库、上传、检索、Hello 探查；旧按钮式学习阶段已移除 |

## 当前指标对应脚本

| 入口 | 用途 |
| --- | --- |
| `run-current-learning-comparison.py` | 当前大纲/对话流程的 NONE 与 LOCAL 压缩对照，仍保留结构化交卷 API |
| `report-learning-usage.py`、`summarize-model-usage.py` | 读取已记录的调用用量，不必再调用模型 |
| `os-pilot-10.py`、`os-remaining-18.py` | 用户 OS 28 道问题检索 |
| `os-parent-8192.py` | 同排名、8192 token 预算下的父子上下文对照 |
| `run-rag-cases.py` | 用真实检索 API 跑新题，保存原始响应和页命中、原文证据命中 |
| `build-os-chunk-review.py`、`os-chunk-review-template.html` | 展示已保存结果，供人工查看证据 |
| `benchmark-upload.py`、`summarize-upload-benchmark.py` | 上传耗时实验与汇总 |

这些脚本多含当时的资料范围、运行目录或本地服务假设，复用前先看参数与文件开头。历史指标只能按当时记录解释，脚本存在不表示已经针对最新代码重跑。

### 新题检索

将每份已索引样本映射到实际上传的物理文档 ID 和知识库 ID，例如
`{"csapp-address-translation":{"documentId":"101","knowledgeBaseId":"20"}}`。一道题标注的来源须在同一知识库，包括无答案题的 `checkedScope`。

```powershell
python scripts/run-rag-cases.py --cases eval/rag/improvement-v2-cases.json --source-map output/rag-reference/source-map.json --split dev --mode BM25 --context-mode CHILD --out output/rag-reference/dev-bm25.jsonl --resume
```

用 `--case` 单跑指定题，用 `--rerank` 启用重排。JSONL 保存请求、完整响应和耗时，失败保留错误并在重跑时重试。续跑会重新汇总已保存响应，语料重新索引后用新输出文件。页命中比较来源页元数据和 `samplePages`，缺页元数据会单列。原文证据按标注引号内片段匹配，无引号时用整条，归一 Unicode 和空白。覆盖率按匹配片段数计算，无答案题留人工复核。

## 研究与历史脚本

- `run-rag-evaluation`、`run-chunk-config`、`run-parent-comparison`、`draft/freeze-rag-gold` 等：更完整的 RAG 数据集和参数实验。
- `run-compaction-experiment`、`prepare-learning-replicas`、`freeze-compaction-scripts` 等：早期压缩实验流程，不能替代当前 `run-current-learning-comparison` 的结果。
- `verify-*`、`run-*-smoke`：当时的单功能/失败恢复探查，部分依赖旧规划参数和本地进程；不作为每次修改都要运行的清单。
- `inventory-course-text`、`EstimateChunkMatrix`、`audit-*`：一次性调查工具。

保留这些工具与实验记录是为了追溯已有结果，不将它们挂入产品运行链路。新改动只做所需的编译或针对性检查，不冻结整套环境、不生成多套重复报告。
