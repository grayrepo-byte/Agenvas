<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas Logo" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><a href="README.md">English</a> | <strong>简体中文</strong> | <a href="README.ja.md">日本語</a> | <a href="README.ru.md">Русский</a></p>

<p align="center"><strong>可自托管的 AI 创作画布</strong><br />文字、图片、视频、音频与 Agent，一个工作空间。</p>

<p align="center">Agenvas 希望让个人用户能够使用最低的代价获得商业画布的体验，而不用绑定昂贵的会员或者顶尖的模型，将低价的选择权掌握在自己手中</p>

<p align="center">
  <a href="#快速开始">快速开始</a> ·
  <a href="#支持的第三方集成">第三方集成</a> ·
  <a href="#部署与维护">部署与维护</a> ·
  <a href="#本地开发">本地开发</a> ·
  <a href="#反馈与贡献">反馈与贡献</a> ·
  <a href="LICENSE">MIT</a>
</p>

<p align="center">作者：<a href="https://x.com/Grayrepo">X / Twitter @Grayrepo</a> · 邮箱：<a href="mailto:yoshioka8084806@gmail.com">yoshioka8084806@gmail.com</a></p>

<p align="center">
  <img src="docs/assets/wechat-official-account.jpg" alt="微信公众号二维码" width="180" /><br />
  扫码关注微信公众号
</p>

> 当前为开发版本，面向单管理员自托管使用。不保证后续版本的数据兼容。

## 功能

| 功能 | 说明 |
| --- | --- |
| 画布创作 | 拖拽、框选、连线、自动整理、命名/折叠分组、独立节点与整组复制；支持本次会话的展示编辑撤销/重做和上传本地媒体 |
| 多模态生成 | 文字、图片、视频、音频逐卡片运行，支持排队、取消、重新生成与历史版本 |
| Agent 协作 | 右下角导演聊天按需读取并操作当前画布，支持持久会话与 Skill；媒体生成提案经用户批准后执行 |
| MCP 接入 | 保留内置导演，通过用户级令牌向外部客户端开放 14 个创作工具（含本地媒体上传与智能配音合成）；授权的媒体调用直接受理，无逐次收费审批 |
| 素材复用 | 项目资源、个人资产库、提示词模板、媒体风格与混合参考输入 |
| 角色一致性 | 项目角色、多造型与图音参考；预览后应用到镜头草稿，跟踪修改影响并独立复核结果 |
| 图片处理 | 标注、裁剪、旋转、镜像、放大、深度提取，以及配置能力支持的 AI 编辑 |
| 自托管管理 | 模型配置、本地及 OSS/COS/S3 存储、任务与调用记录、项目清单导出 |

界面支持中文、英文、俄文和日文。

## 支持的第三方集成

以下为 Agenvas 当前已实现的接入。具体模型、参数和输入限制以管理员发布的能力及供应商账号权限为准。

### 模型与工作流

| 服务商 / 平台 | 模型 / 接入范围 | 可用功能 |
| --- | --- | --- |
| OpenAI 兼容端点 | Chat Completions API | 文字生成、导演对话与工具调用 |
| OpenAI | GPT Image 2 / Images API | 图片生成、参考图编辑、蒙版与透明背景 |
| Google | Nano Banana 2 / Gemini Flash Image | 图片生成与参考图编辑 |
| Google | Veo 3.1 / Gemini Omni | 视频生成；支持的模型可编辑、延长视频 |
| 火山方舟 | Seedance 2.0 | 文生视频、首尾帧、图片 / 视频 / 音频全能参考 |
| 火山方舟 | Seedream 5.0 Pro / Flash / Lite | 图片生成；支持的模型可交互编辑、图层分离 |
| MiniMax | 官方 H3 API | 文生视频、首尾帧与多模态参考 |
| 可灵 Kling | 图片、视频与 Omni 模型目录 | 图片 / 视频生成、扩图、Omni 编辑及对应模型的动作控制 |
| 阿里云百炼 / 通义万相 | 万相 2.7 图片 / 万相 3.0 视频 | 图片 / 视频生成与多素材参考 |
| 火山引擎 | Seed Audio 1.0 | 音频生成与参考音频输入 |
| ComfyUI | 自托管 API 工作流 | 导入、映射并发布图片 / 视频工作流及其参数 |
| RunningHub | V2 工作流 / AI 应用 | 映射输入、参数和输出，发布图片 / 视频 / 音频能力 |
| AutoDL | 托管 ComfyUI 视频工作流 | 发现或导入支持的视频工作流，发布输入与分辨率选项 |

### 存储与外部客户端

| 服务 / 协议 | 在 Agenvas 中的用途 |
| --- | --- |
| 阿里云 OSS | 媒体归档与媒体中继 |
| 腾讯云 COS | 媒体归档与媒体中继 |
| Amazon S3 / S3 兼容对象存储 | 通过可配置的 S3 端点进行媒体归档与中继 |
| SMB2/3 / HTTPS WebDAV | 远程媒体归档；媒体中继使用对象存储 |
| MCP 兼容客户端 | 外部 Agent 通过 Streamable HTTP 和用户令牌连接 Agenvas，调用画布、文字与媒体工具 |

## 快速开始

### 1. 一键启动

通过下面的命令启动前，先安装并启动 **Docker Engine / Docker Desktop**，确认包含 **Docker Compose v2**。启动和登录无需 Git、本地编译环境、GPU、`.env` 或模型 Key。

首次安装，在 **macOS / Linux** 终端复制执行整段命令：

```sh
mkdir agenvas && cd agenvas && \
  curl -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml \
    -o docker-compose.yml && \
  docker compose up -d --wait
```

**Windows：一键命令**

首次安装，打开 CMD 或 PowerShell，复制对应的整段命令执行；两种方式选一种即可。

<details>
<summary><strong>CMD（命令提示符）</strong></summary>

```cmd
mkdir agenvas && cd agenvas && curl.exe -fL https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml -o docker-compose.yml && docker compose up -d --wait
```

</details>

<details>
<summary><strong>PowerShell（Windows PowerShell 5.1 / PowerShell 7）</strong></summary>

```powershell
& {
  $ErrorActionPreference = 'Stop'
  New-Item -ItemType Directory -Path agenvas | Out-Null
  Set-Location agenvas
  Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml' -OutFile docker-compose.yml -UseBasicParsing
  docker compose up -d --wait
}
```

</details>

**Windows：让 AI 协助安装**

也可让 WorkBuddy 等具备本机操作能力的 AI Agent 按上面的 Windows 命令完成安装。安装后打开 **<http://127.0.0.1:8088/setup>** 创建管理员账号。

以上步骤会准备 `agenvas` 目录和 Compose 文件、拉取镜像，并等待三个服务健康。保留该目录，后续管理在其中执行。已有 Compose 文件时，直接在文件所在目录运行 `docker compose up -d --wait`。

数据库密码和凭证加密密钥首次启动时自动生成并持久保存，无需填写。首次下载镜像可能需要几分钟。

### 2. 创建管理员

本机打开 **<http://127.0.0.1:8088/setup>**；从其他设备访问时使用 `http://<服务器IP>:8088/setup`。填写管理员登录名和密码，创建账号后登录。先在受控网络完成初始化，再开放公网访问。

### 3. 配置模型，开始创作

- **文字与 Agent**：在“设置 → 模型配置”中添加 OpenAI 兼容端点、模型 ID 和 API Key。使用 Agent 前，管理员须运行工具调用诊断。
- **图片、视频与音频**：在“媒体配置”中创建连接、发布能力并设置默认模型。默认部署需完成真实模型配置后才能生成媒体。
- **外部 Agent / MCP**：在“系统设置 → 账户安全”创建用户令牌。客户端使用 Streamable HTTP 连接站点的 `/mcp`（本机为 `http://127.0.0.1:8088/mcp`），并配置 `Authorization: Bearer <token>` 请求头。

使用 **Ollama 等本地 OpenAI 兼容服务**时，请按[本地模型连接指南](docs/ollama-local.md)配置。HTTP 需要部署者明确允许指定主机和端口；Docker 中的 `127.0.0.1` 指向容器自身。忽略 API Key 的服务可填写至少八个字符的占位值，如 `ollama-local`。模型服务实际分配的上下文与[导演设置中的预算](docs/agent-context-safety.md)须分别配置并匹配；工具诊断通过不代表完整 Agent 请求一定放得下。

模型及 ComfyUI 设置提供本机服务发现入口：主动检查标准服务后，明确选择才填入允许的地址，手动配置保留。固定探测范围与平台限制见[本机服务发现](docs/local-service-discovery.md)。

创建项目，通过画布右键菜单添加卡片或上传素材。选择模型、填写提示词和参考输入，检查预计费用后运行；完成后可预览、选用结果或重新生成。打开右下角导演聊天即可使用项目助手，按需读取并操作当前画布；选中节点可突出本次任务重点。Skill 的必需输入从画布内容中明确选择并固定精确版本，媒体提案在对话中统一批准或拒绝；无需添加 Agent 节点或绑定素材。

媒体中继可按功能独立控制 OpenAI 兼容 LLM 图片输入与 OpenAI Images 参考图/蒙版；未启用时保持原传输。Seedance 和 MiniMax H3 本地视频参考需要可公网访问的中继。在存储设置中添加可从公网通过 HTTPS 访问的 OSS、COS 或 S3 连接，再将其选为媒体中继；永久媒体归档仍可使用本地存储。

> 结果未知（UNKNOWN）时需显式重试，可能产生重复费用；取消不保证外部服务停止或退款。项目清单包含数据与素材元数据，不能替代数据库和媒体文件备份。

## 部署与维护

以下命令在 `docker-compose.yml` 所在目录执行（一键启动创建的 `agenvas` 目录）：

| 操作 | 命令 |
| --- | --- |
| 启动 / 更新到配置中的镜像版本 | `docker compose up -d --wait` |
| 停止并保留数据 | `docker compose down` |
| 查看服务状态 | `docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"` |
| 查看最近的启动日志 | `docker compose logs --tail=100 server postgres` |

> 更新前备份数据库、媒体、配置与加密密钥。**不要执行 `docker compose down -v`**，它会删除数据和密钥。

第三方提示词同步在新安装和升级后都**默认关闭**。在第三方模板的来源管理中开启自动同步并保存后，启用的来源每 24 小时从 `raw.githubusercontent.com` 和 `imagepromptgallery.com` 下载公开内容。关闭后不再领取新的定时同步来源，已经受理的下载可能完成；现有来源配置和缓存保留，仍可明确手动同步。部署者也可在 server 的 Compose 环境中用 `AGENVAS_PROMPT_SYNC_ENABLED: "false"` 停止定时同步。说明见[提示词来源与网络访问](docs/prompt-sources.md)。

<details>
<summary><strong>端口、镜像版本与公网部署</strong></summary>

- 默认 `docker-compose.yml` 仅将 Web `8088` 映射到 `0.0.0.0`，通过服务器 IP 访问；API `8080` 和 PostgreSQL `5432` 仅在容器内部网络使用，不映射到宿主机。源码和 Mock Compose 仍保留仅绑定本机的三个端口，方便开发排查。Web 端口被占用时，修改 `ports` 中的宿主机端口。
- 默认拉取 `docker.io/grayrepo/agenvas-server:latest`、`docker.io/grayrepo/agenvas-web:latest` 和官方 `postgres:17.11-alpine`。应用镜像支持 amd64 / arm64，无需本机编译。
- 应用镜像每次 `up` 都会拉取；需要固定版本时，把 `latest` 改为已发布版本、提交标签或 digest。
- 每份 Compose 完整列出环境、端口、卷、健康检查和资源限制，直接修改所选文件即可，无需其他配置文件。
- 对外部署需配置 HTTPS、反向代理，并将 `AGENVAS_SECURE_COOKIES` 改为 `"true"`。
- 源码构建环境更新代码后运行 `./deploy/update-local.sh`；停止时使用 `docker compose -f docker-compose.local.yml down`。

</details>

<details>
<summary><strong>自动密钥、管理员初始化与恢复</strong></summary>

程序在数据库中永久记录初始化完成状态：并发请求只有一次成功，完成后再次提交返回 `409 SETUP_ALREADY_COMPLETED`。重启、停用或删除管理员不会重新开放初始化；初始化失败会回滚，仍可重试。先在受控网络完成初始化，再开放公网反向代理。

数据库密码和凭证主密钥均已自动配置，文件位于 server 容器的 `/run/agenvas/credentials/installation/`，普通使用不需要读取或填写：

| 配置 | 自动生成内容 | 保存文件 |
| --- | --- | --- |
| `AGENVAS_DB_PASSWORD` | 32 字节随机值转为 64 字符十六进制数据库密码 | `database-password` |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | 32 字节随机密钥的 Base64 编码 | `credential-master-key` |

需要查看数据库密码时，在 Compose 文件所在目录、`postgres` 容器运行期间执行：

```sh
docker compose exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

服务名为 `postgres`。若启动时使用了 `-f` 或 `-p`，查看时也须使用相同参数，例如源码构建环境：

```sh
docker compose -f docker-compose.local.yml exec -u 0 postgres cat /run/agenvas/credentials/installation/database-password
```

若提示 `no configuration file provided: not found`，请切换到 Compose 文件所在目录，或用 `-f` 指定正确的文件路径。

密钥不会写入 Compose、Git 或启动日志。备份时须另外加密保管 `credentials-data`；不要执行 `down -v`，它会删除数据和密钥。已有数据库升级到此入口时，须先恢复密钥卷或通过仓库外私有 Compose 配置导入原数据库密码和主密钥；缺失时启动会停止，避免旧凭证无法解密。

</details>

<details>
<summary><strong>启动遇到问题</strong></summary>

| 现象 | 处理方式 |
| --- | --- |
| Docker 无法连接 / Compose 命令不可用 | 启动 Docker Desktop 或 Docker Engine，确认 `docker compose version` 可执行 |
| 提示 `agenvas` 目录已存在 | 已安装时进入该目录直接运行 `docker compose up -d --wait`；首次下载时改用一个未占用的目录名 |
| Compose 下载失败 | 检查能否访问 `raw.githubusercontent.com`，也可手动下载 [docker-compose.yml](docker-compose.yml) |
| 提示 `manifest unknown` / 镜像不可用 | 检查 Docker Hub 网络与已发布标签；尚无可用发布时使用[容器源码构建](#容器源码构建) |
| 端口已被占用 | 修改 Compose 的 `ports` 中宿主机端口后重新启动；修改 `8088` 后，浏览器地址也使用新端口 |
| 健康检查失败 | 使用上表日志命令查看原因，处理后重新执行启动命令 |

</details>

## 本地开发

| 层 | 技术栈 |
| --- | --- |
| 前端 | Vite · React · TypeScript · React Flow · TanStack Query · Zustand |
| 后端 | Java 21 · Spring Boot · Spring AI · jOOQ |
| 数据与部署 | PostgreSQL · Flyway · Docker Compose · REST / SSE |

工具链：JDK 21、Node 24 LTS（24.12+）、pnpm 12.5.1。

### 容器源码构建

在仓库根目录执行，编译当前 server、web 源码并启动三个服务，PostgreSQL 直接使用官方固定版本镜像：

```sh
docker compose -f docker-compose.local.yml up -d --build --wait
```

也可使用 `./deploy/update-local.sh`，先完成镜像构建，再更新容器并等待健康检查，分别显示构建与更新耗时。默认复用基础镜像，`./deploy/update-local.sh --pull` 显式刷新；只改后端或前端时，可运行 `./deploy/update-local.sh server` 或 `./deploy/update-local.sh web`（首次部署先构建两者）。源码 Compose 使用独立的 BuildKit Maven 缓存，即使 `pom.xml` 变化也保留已下载包；首次填充或清理 Docker 缓存后仍需下载。本机 `~/.m2` 不会自动共享给构建容器。后端执行 `package -Dmaven.test.skip=true`，跳过测试编译与执行，不自动运行 `mvn verify`；完整测试按需手动执行或由 CI 执行。完成后仅显示容器名、服务、状态和端口。源码构建无需 Docker Hub 登录；默认文字与媒体仍为 `configured`。原 `deploy/compose.yaml` 使用同一套源码构建配置。

镜像部署和源码构建默认项目名均为 `agenvas`，沿用相同数据库、素材和密钥卷，切换时须备份并保证版本兼容。它们是同一环境的两种启动方式；若要并行运行，需用 `-p` 指定独立项目名并修改端口。

### Mock 环境

直接启动，数据库密码和主密钥同样自动生成，无需外部模型账户：

```sh
docker compose -f deploy/compose.dev.yaml up -d --build --wait
```

文字与媒体使用 Mock，图片、视频、音频为合成演示素材，不代表真实模型效果。访问地址与初始化流程同上；停止时使用同一 Compose 文件执行 `down`。

默认部署与 Mock 环境使用独立数据卷，但默认 Web 端口相同；并行运行需设置不同端口。

<details>
<summary><strong>从源码运行</strong></summary>

前端（独立终端）：

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm dev
```

后端（独立终端，先配置 PostgreSQL 和环境变量）：

```sh
cd backend
./mvnw spring-boot:run
```

独立 JVM 需自行提供 `AGENVAS_DB_URL`、`AGENVAS_DB_USER`、`AGENVAS_DB_PASSWORD`；变量参考 `.env.example`，JVM 不会自动加载此文件。Docker 自动密钥流程仅用于容器入口。源码运行默认使用 Mock，媒体处理需安装 FFmpeg / FFprobe。

导演支持按 Token 预算自动整理上下文和持久长任务续接，临时模型故障默认可恢复 24 小时。模型窗口、摘要与重试预算在后台“导演设置”中保存，无需重启。

Vite 默认运行于 `5173`，将 `/api` 代理到 `localhost:8080`。

</details>

## 反馈与贡献

欢迎通过 [Issues](https://github.com/grayrepo-byte/Agenvas/issues) 反馈问题和建议，并提供版本及复现步骤；也欢迎通过 [Pull Requests](https://github.com/grayrepo-byte/Agenvas/pulls) 贡献改进。修改代码前请阅读 [AGENTS.md](AGENTS.md)，并运行相关检查。安全问题请按[安全政策](SECURITY.md)报告。

```text
frontend/       前端页面与画布交互
backend/        业务 API、Agent Runtime 与任务处理
contracts/      权威 OpenAPI 与内容 Schema
configs/        Agent、Skill 与受信媒体工作流配置
deploy/         Compose、Nginx 与容器构建
docs/           规格、设计与开发文档
```

## 开源鸣谢

Agenvas 内置的短剧创作 Skills 来源于 [zenstory-ai/drama-skills](https://github.com/zenstory-ai/drama-skills)。感谢该项目的作者与贡献者开源分享创作技能与工作流。

视频创作与提示词方法另参考 [MiniMax-H3/skills](https://github.com/MiniMax-AI/MiniMax-H3/tree/main/skills)，已适配为画布工具和媒体审批工作流；其第三方许可见下文。

## 许可证

采用 [MIT License](LICENSE)。第三方依赖、模型权重、工作流与 FFmpeg 等组件保留各自的许可证。

内置 [MiniMax H3 Skill 集合](backend/src/main/resources/builtin-skills/minimax-h3/manifest.json) 的原始材料及适配内容受 [MiniMax H3 Community License](backend/src/main/resources/builtin-skills/minimax-h3/LICENSE) 约束，包含地域、商业使用与再分发条件，不重许可为 MIT；来源及改写说明见 [NOTICE](backend/src/main/resources/builtin-skills/minimax-h3/NOTICE)。原 Drama Skills 保留其 [MIT 许可证](backend/src/main/resources/builtin-skills/drama-skills/LICENSE)。
