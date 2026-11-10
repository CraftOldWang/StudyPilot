# StudyPilot MinerU 2.5.4

运行 `docker compose -f deploy/mineru/compose.yml -p studypilot-mineru up -d --build` 启动本机服务。Compose 默认复用已有模型卷，可通过 `MINERU_MODELS_VOLUME` 指向其他卷。

在 Docker 内存约 8 GiB 的机器上，带多条公式的章节曾在公式识别阶段被 OOM kill。Compose 默认每批分析 1 页，每批识别 1 条公式，仍启用公式和表格。提高 `MINERU_MIN_BATCH_INFERENCE_SIZE` 和 `STUDYPILOT_MINERU_MFR_BATCH_SIZE` 可扩大两种批次。MinerU 2.5.4 仍会预先加载所有页图，长文档的总内存也随页数增长。
