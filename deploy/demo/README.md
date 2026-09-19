# 口令保护的 StudyPilot 演示站

**状态：已准备配置与部署步骤，尚未创建服务器或公开上线。** 当前缺少服务器连接信息和域名。用户确认先做共享演示站，所有持有口令的访客会看到同一份资料、聊天和学习记忆；这里没有独立账号或私人空间。

## 服务器怎么选

2026-09-19 查阅官方资料。完整应用需要 Spring Boot、MySQL、Redis、Elasticsearch、RocketMQ 和 RustFS。建议先考虑 **Linux x86_64、4 vCPU、8–16 GB RAM、60 GB 以上磁盘**；这是部署起点估算，不是实测容量结论。这里不安装 Kibana、MQ Dashboard、Canal 或本地 ASR 模型。8 GB 比较紧，构建与运行不要同时做重任务。

| 路径 | 对本项目的判断 | 官方教程 |
|---|---|---|
| OCI Always Free Ampere A1 | 候选免费服务器，但要有可分配容量；ARM，需确认全套容器镜像兼容，当前没有完成 ARM 启动。当前官方文档写 1,500 OCPU 小时、9,000 GB 小时/月，Always Free 租户等效 2 OCPU / 12 GB；不要直接照搬旧 4核24GB 教程 | [免费额度](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)、[创建 Linux 实例](https://docs.oracle.com/en-us/iaas/Content/Compute/tutorials/first-linux-instance/overview.htm) |
| 阿里云 ECS 免费试用 | 更适合先找足够内存的 x86 试用规格；有资格、额度、期限和流量条件，不等于永久免费。以账号控制台当时显示为准 | [试用攻略](https://help.aliyun.com/zh/ecs/user-guide/ecs-free-trial)、[最新试用规则](https://help.aliyun.com/zh/user-center/product-overview/learn-about-free-trials) |
| Render 免费 Web | 免费实例资源和持久化限制不适合直接放下当前全套中间件；不为获得免费托管重写项目 | [免费实例限制](https://render.com/docs/free)、[规格](https://render.com/pricing) |
| 本机 + Cloudflare 命名 Tunnel | 节省云主机，但电脑必须开机，也需账号及域名配置。不是把服务搬到服务器；本轮没有配置。临时 Quick Tunnel 明确不支持 SSE，不能用来演示当前流式聊天 | [Quick Tunnel 限制](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/) |

先拿到符合资格的服务器，再执行下文。没有合适免费规格时，等用户决定付费预算，不自动创建按量资源。

## 部署结构

```text
浏览器 → HTTPS + 访问口令 → Caddy（静态前端、SSE反向代理）
                              ↓ 内部 Docker 网络
                           Spring Boot
                              ↓
              MySQL / Redis / ES / MQ / RustFS
```

只映射 80/443。不要给 API 8080、MySQL、Redis、ES、MQ、对象存储控制台添加公网端口。Caddy 会覆盖 `X-User-Id` 为共享用户 1，并移除访问口令的 Authorization 头；路径白名单只开放产品接口，评测、Trace、Hello、普通/Agent 检索测试和 Anki API 不开放。登录保护在 Caddy，因此不能绕过它直接发布 backend。

前端构建仅传公开标志 `VITE_DEMO_MODE=true`，模型 Key 只注入后端运行环境。Docker 构建上下文使用白名单，`some_apiKey`、`.env`、Git、日志、本地课程与实验数据都不进入镜像。

## 1. 准备主机与域名

安装 [Docker Engine 与 Compose 插件](https://docs.docker.com/engine/install/ubuntu/)。将域名的 A 记录指向服务器，放行 TCP 80/443；SSH 只供自己管理。Caddy 根据域名申请 HTTPS 证书，不需要手工保存证书。第一次部署建议使用 DNS 直连，避免另外一层代理影响流式响应。

Elasticsearch 所需宿主配置（Linux）：

```bash
sudo sysctl -w vm.max_map_count=262144
echo 'vm.max_map_count=262144' | sudo tee /etc/sysctl.d/99-studypilot.conf
```

克隆本项目到服务器；私有仓库使用自己配置的 SSH 或 GitHub 登录，不把访问令牌拼在 Git URL 中。

```bash
git clone git@github.com:CraftOldWang/StudyPilot.git
cd StudyPilot/deploy/demo
cp .env.example .env
chmod 600 .env
```

## 2. 只在服务器填密钥

编辑 `.env`：

- `DOMAIN`：只写域名，例如 `study.example.com`，无协议、端口或尾部斜线。
- `DEMO_USER` 与 `DEMO_PASSWORD_HASH`：浏览器访问口令。
- MySQL / Redis / RustFS 密码：分别生成随机值，例如用 `openssl rand -hex 24`，不沿用本地默认密码。
- `DEEPSEEK_API_KEY`、`AI_DASHSCOPE_API_KEY`：填独立的演示用 Key；模型名称默认与当前开发环境相同，可按账号权限修改。

生成 Caddy 口令哈希时交互输入密码，避免将明文放进 shell 历史：

```bash
docker run --rm -it caddy:2-alpine caddy hash-password
```

将结果放进 `.env` 的单引号中，保持 `$` 原样，例如 `DEMO_PASSWORD_HASH='生成的完整哈希'`。不要把密钥填进 `VITE_` 环境变量、Dockerfile、README 或提交记录。`.env` 仅对服务器管理员保密，主机 root / Docker 管理员仍然能够读取容器环境。

## 3. 启动

在 `deploy/demo` 目录执行（**不用根目录的本地开发 compose**）：

```bash
docker compose --env-file .env -f compose.yml up -d --build
docker compose --env-file .env -f compose.yml logs --tail=80 api web
```

依赖首次启动可能稍慢；API 使用 demo profile 和独立持久化卷，不加载 local/eval 数据。RustFS 或 MQ 尚未就绪时，API 的失败会出现在日志中，由容器重启策略重新启动。完成后访问 `https://你的域名`，输入访问口令，上传少量你有权分享的课件，再生成大纲并学习。首次启动自动执行数据库迁移；不会自动上传本机 OS 课件、试题或旧聊天。

正式使用前仍需在目标服务器走一次“上传 → 大纲 → 对话 → 交卷 → 确认卡片”的真实流程；本轮只完成 Java 打包和前端构建，没有声称这套 Docker 配置已经云端运行成功。

## 演示版的明确边界

- 仅支持 PDF / PPTX / TXT / Markdown，单文件 50 MiB，分片 8 MiB。未部署 ASR worker，因此音视频入口关闭且后端拒绝初始化媒体上传。
- 确认卡片后保存在本站、完成摘要替换并进入下一知识点；不自动写入访客电脑的 Anki，不显示“导出成功”。本地 profile 的 Anki 行为不变。
- 偏好与学习记忆属于共享演示用户，不适合保存个人隐私。需要独立账号时，须再实现登录、用户创建与权限隔离，不能靠前端换 `X-User-Id`。
- 全站每分钟最多 60 次写请求（包括分片），内存计数、单实例有效、重启重置；它是防误操作节流，不是严格费用账单。
- 复用已有调用记账器，`MODEL_MAX_CALLS=500` 是 `/data/model-calls.jsonl` 对应的**累计聊天模型调用次数**，重启继续计数，包含规划和摘要调用。失败尝试也占额度。达到上限需管理员主动调高后重建 API 容器，不自动重置；它不限制 embedding 费用，也不等于 token / 人民币上限。
- 给模型账户设置适合演示的余额/预算告警，不公开传播口令。免费主机不代表模型推理、域名和所有网络流量都免费。

## 更新与停止

```bash
git pull --ff-only
docker compose --env-file .env -f compose.yml up -d --build
# 暂停演示，保留资料与数据库卷
docker compose --env-file .env -f compose.yml stop
```

不要使用 `down -v`，它会删除资料和数据库。变更数据库密码时，现有 MySQL 数据卷不会自动同步新密码；应先在数据库内修改。发布新镜像前保留数据库/对象存储备份。当前没有迁移个人数据到公网站点，也没有购买、开通或扣费。

SSE 设置参考 [Caddy reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)；口令设置参考 [basic_auth](https://caddyserver.com/docs/caddyfile/directives/basic_auth)。
