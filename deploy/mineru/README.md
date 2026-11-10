# StudyPilot MinerU 2.5.4

此适配器复用本机已有 `know-engine-mineru:latest` 基础镜像及 `know-engine_mineru-models` 模型卷。运行 `docker compose -f deploy/mineru/compose.yml -p studypilot-mineru up -d --build` 启动服务；可通过 `MINERU_MODELS_VOLUME` 指向其他卷。服务只监听本机18001端口，`GET /health` 查看状态。

后端启动前设置 `STUDY_AGENT_INGEST_MINERU_ENABLED=true`，默认连接 `http://localhost:18001`，地址可由 `STUDY_AGENT_INGEST_MINERU_ENDPOINT` 覆盖。PDF直接解析，PPTX/DOCX经LibreOffice转PDF；TXT/Markdown仍走文本解析。产物包含Markdown、UTF-16正文偏移、页码/章节/bbox及原图，上传与失败重试沿现有文档流水线。

一般图示说明可设置 `STUDY_AGENT_INGEST_VISION_ENABLED=true`，默认百炼 `qwen3-vl-plus`，凭据使用 `STUDY_AGENT_INGEST_VISION_API_KEY` 或已有百炼环境变量，模型及地址可配置。说明单独标记为模型生成内容并缓存；识图失败显示 FAILED/PARSING，可重试复用成功结果。公式仍由MinerU提取LaTeX，应抽查识别质量。

在 Docker 内存约 8 GiB 的机器上，带多条公式的章节曾在公式识别阶段被 OOM kill。Compose 默认每批分析 1 页，每批识别 1 条公式，仍启用公式和表格。提高 `MINERU_MIN_BATCH_INFERENCE_SIZE` 和 `STUDYPILOT_MINERU_MFR_BATCH_SIZE` 可扩大两种批次。MinerU 2.5.4 仍会预先加载所有页图，长文档的总内存也随页数增长。
