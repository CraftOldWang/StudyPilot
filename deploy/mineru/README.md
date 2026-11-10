# StudyPilot MinerU 2.5.4

此适配器复用本机已有 `know-engine-mineru:latest` 基础镜像及 `know-engine_mineru-models` 模型卷。运行 `docker compose -f deploy/mineru/compose.yml -p studypilot-mineru up -d --build` 启动服务；可通过 `MINERU_MODELS_VOLUME` 指向其他卷。服务只监听本机18001端口，`GET /health` 查看状态。

后端启动前设置 `STUDY_AGENT_INGEST_MINERU_ENABLED=true`，默认连接 `http://localhost:18001`，地址可由 `STUDY_AGENT_INGEST_MINERU_ENDPOINT` 覆盖。PDF直接解析，PPTX/DOCX经LibreOffice转PDF；TXT/Markdown仍走文本解析。产物包含Markdown、UTF-16正文偏移、页码/章节/bbox及原图，上传与失败重试沿现有文档流水线。

一般图示说明可设置 `STUDY_AGENT_INGEST_VISION_ENABLED=true`，默认百炼 `qwen3-vl-plus`，凭据使用 `STUDY_AGENT_INGEST_VISION_API_KEY` 或已有百炼环境变量，模型及地址可配置。说明单独标记为模型生成内容并缓存；识图失败显示 FAILED/PARSING，可重试复用成功结果。公式仍由MinerU提取LaTeX，应抽查识别质量。

在 Docker 内存约 8 GiB 的机器上，公式密集章节曾触发 OOM。适配器默认每 4 页调用一次 MinerU，以限制 2.5.4 预加载的页图数量。`STUDYPILOT_MINERU_PAGE_WINDOW` 可配置窗口大小。Compose 默认每个推理批次分析 1 页、每个公式批次识别 1 条公式，可用 `MINERU_MIN_BATCH_INFERENCE_SIZE` 和 `STUDYPILOT_MINERU_MFR_BATCH_SIZE` 调整。公式、表格保持启用。窗口间保留全局页码和标题路径，跨窗口的段落和表格会在各自窗口内解析。
