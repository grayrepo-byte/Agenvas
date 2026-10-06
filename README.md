<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas Logo" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><strong>可自托管的 AI 创作画布</strong><br />文字、图片、视频、音频与 Agent，一个工作空间。</p>

<p align="center">Agenvas 希望让个人用户能够使用最低的代价获得商业画布的体验，而不用绑定昂贵的会员或者顶尖的模型，将低价的选择权掌握在自己手中</p>

<p align="center">
  <a href="README.en.md">English</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#部署与维护">部署与维护</a> ·
  <a href="#本地开发">本地开发</a> ·
  <a href="#文档与贡献">文档与贡献</a> ·
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
| 画布创作 | 拖拽、框选、连线与自动整理，支持上传本地媒体 |
| 多模态生成 | 文字、图片、视频、音频逐卡片运行，支持排队、取消、重新生成与历史版本 |
| Agent 协作 | 读取绑定上下文、编辑文字、摆放卡片；媒体生成提案经用户批准后执行 |
| 素材复用 | 项目资源、个人资产库、提示词模板、媒体风格与混合参考输入 |
| 图片处理 | 标注、裁剪、旋转、镜像、放大、深度提取，以及配置能力支持的 AI 编辑 |
| 自托管管理 | 模型配置、本地及 OSS/COS/S3 存储、任务与调用记录、项目清单导出 |

界面支持中文、英文、俄文和日文。媒体适配器包括 GPT Image、Google Nano Banana、火山方舟 Seedance、Seed Audio、ComfyUI 导入并映射发布的 API 工作流和 RunningHub V2 工作流 / AI 应用；可用操作取决于管理员发布的能力。

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

**Windows：让 AI 协助 安装**

把 [安装提示词](docs/operations/windows-ai-install-prompt.md)发给 WorkBuddy 等能执行本机操作的 AI Agent，让它检查环境、安装并启动 Agenvas。完成后，打开它给出的地址创建账号。

自行安装见 [Windows 安装指南](docs/operations/windows-install.md)。

以上步骤会准备 `agenvas` 目录和 Compose 文件、拉取镜像，并等待三个服务健康。保留该目录，后续管理在其中执行。已有 Compose 文件时，直接在文件所在目录运行 `docker compose up -d --wait`。

数据库密码和凭证加密密钥首次启动时自动生成并持久保存，无需填写。首次下载镜像可能需要几分钟。

### 2. 创建管理员

本机打开 **<http://127.0.0.1:8088/setup>**；从其他设备访问时使用 `http://<服务器IP>:8088/setup`。填写管理员登录名和密码，创建账号后登录。先在受控网络完成初始化，再开放公网访问。

### 3. 配置模型，开始创作

- **文字与 Agent**：在“设置 → 模型配置”中添加 OpenAI 兼容端点、模型 ID 和 API Key。使用 Agent 前，管理员须运行工具调用诊断。
- **图片、视频与音频**：在“媒体配置”中创建连接、发布能力并设置默认模型。默认部署需完成真实模型配置后才能生成媒体。

创建项目，通过画布右键菜单添加卡片或上传素材。选择模型、填写提示词和参考输入，检查预计费用后运行；完成后可预览、选用结果或重新生成。Agent 卡片可绑定上下文并选择 Skill，媒体提案在对话中统一批准或拒绝。

Seedance 视频参考需要可公网访问的媒体中继，配置见[媒体中继说明](docs/media-relay-design.md)。

> 结果未知（UNKNOWN）时需显式重试，可能产生重复费用；取消不保证外部服务停止或退款。项目清单包含数据与素材元数据，不能替代数据库和媒体文件备份。

## 部署与维护

以下命令在 `docker-compose.yml` 所在目录执行（一键启动创建的 `agenvas` 目录）：

| 操作 | 命令 |
| --- | --- |
| 启动 / 更新到配置中的镜像版本 | `docker compose up -d --wait` |
| 停止并保留数据 | `docker compose down` |
| 查看服务状态 | `docker compose ps --format "table {{.Service}}\t{{.Status}}\t{{.Ports}}"` |
| 查看最近的启动日志 | `docker compose logs --tail=100 server postgres` |

> 更新前备份数据库、媒体、配置与加密密钥。**不要执行 `docker compose down -v`**，它会删除数据和密钥。详见[备份与恢复](docs/operations/backup-restore.md)。

<details>
<summary><strong>端口、镜像版本与公网部署</strong></summary>

- 默认 `docker-compose.yml` 仅将 Web `8088` 映射到 `0.0.0.0`，通过服务器 IP 访问；API `8080` 和 PostgreSQL `5432` 仅在容器内部网络使用，不映射到宿主机。源码和 Mock Compose 仍保留仅绑定本机的三个端口，方便开发排查。Web 端口被占用时，修改 `ports` 中的宿主机端口。
- 默认拉取 `docker.io/grayrepo/agenvas-server:latest`、`docker.io/grayrepo/agenvas-web:latest` 和官方 `postgres:17.11-alpine`。应用镜像发布配置覆盖 amd64 / arm64，无需本机编译。
- 应用镜像每次 `up` 都会拉取；需要固定版本时，把 `latest` 改为已发布版本、提交标签或 digest。
- 每份 Compose 完整列出环境、端口、卷、健康检查和资源限制，直接修改所选文件即可，无需其他配置文件。
- 对外部署需配置 HTTPS、反向代理，并将 `AGENVAS_SECURE_COOKIES` 改为 `"true"`。旧 V1–V77 开发库的迁移限制见[备份与恢复](docs/operations/backup-restore.md)。
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

密钥不会写入 Compose、Git 或启动日志。备份时须另外加密保管 `credentials-data`；不要执行 `down -v`，它会删除数据和密钥。已有数据库升级到此入口时，须先恢复密钥卷或通过仓库外私有 Compose 配置导入原数据库密码和主密钥；缺失时启动会停止，避免旧凭证无法解密。详见[备份与恢复](docs/operations/backup-restore.md)。

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

工具链：JDK 21、Node 24 LTS（24.12+）、pnpm 12.5.1。完整版本见[依赖基线](docs/dependency-baseline.md)。

### 容器源码构建

在仓库根目录执行，编译当前 server、web 源码并启动三个服务，PostgreSQL 直接使用官方固定版本镜像：

```sh
docker compose -f docker-compose.local.yml up -d --build --wait
```

也可使用 `./deploy/update-local.sh`，先完成全部镜像构建，再更新容器并等待健康检查；后端仅执行 `package -DskipTests`，不自动运行 `mvn verify`，完整测试按需手动执行或由 CI 执行。完成后仅显示容器名、服务、状态和端口，避免完整启动命令撑宽状态表。源码构建无需 Docker Hub 登录；默认文字与媒体仍为 `configured`。原 `deploy/compose.yaml` 使用同一套源码构建配置。

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

独立 JVM 需自行提供 `AGENVAS_DB_URL`、`AGENVAS_DB_USER`、`AGENVAS_DB_PASSWORD`；变量参考 `.env.example`，JVM 不会自动加载此文件。Docker 自动密钥流程仅用于容器入口。源码运行默认使用 Mock，媒体处理需安装 FFmpeg / FFprobe；深度提取见[本地图片处理配置](docs/local-image-processing.md)。

Vite 默认运行于 `5173`，将 `/api` 代理到 `localhost:8080`。

</details>

## Docker Hub 自动发布

<details>
<summary><strong>镜像发布配置（维护者）</strong></summary>

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `DOCKERHUB_USERNAME` | 有权推送镜像的 Docker Hub 用户名 |
| Secret | `DOCKERHUB_TOKEN` | Docker Hub Access Token，具备目标仓库的读写权限 |

在 Docker Hub 准备 `grayrepo/agenvas-server`、`grayrepo/agenvas-web` 两个公开仓库，便于部署者匿名拉取。CI 发布地址直接使用 `grayrepo`；需要更换发布方时修改工作流和 Compose 中的地址。PostgreSQL 从官方仓库拉取，CI 保留其 SBOM 与许可证清单。

`.github/workflows/ci.yml` 在 PR、main 推送、`v*.*.*` 版本标签推送和手动运行时执行，测试与镜像构建并行：

| 触发 | 验证 | 镜像与清单 |
| --- | --- | --- |
| 代码 PR | 前端、后端单测、全部四个 PostgreSQL IT 分片、jOOQ、Compose、密钥扫描 | server/web amd64 构建 |
| 纯文档 PR | 规划检查、密钥扫描、CI Gate | 跳过重任务 |
| main / 手动运行 | 完整验证 | server/web amd64 + arm64 构建 |
| 版本标签推送 | 完整验证 | 双架构应用构建与官方 PostgreSQL 清单，CI Gate 通过后发布 |

`CI Gate` 始终生成，失败、取消或非预期跳过都会阻断；配置分支必需检查时可选择它。Buildx 缓存按服务和架构隔离，PR 只读。server 打包跳过整套测试，CI 每个原生架构仍保留一项深度模型/JNI 冒烟。

只有版本标签推送上传 Docker Hub。发布镜像扫描一次，再将 JSON 转换为 CycloneDX；所有检查通过后从短期工件加载并推送同一镜像。失败报告保留 7 天、待发布镜像保留 1 天、SBOM/许可证证据保留 90 天。

- 每次发布：`sha-<完整 40 位提交 SHA>`。
- `v0.1.0` 版本标签：额外发布去除前导 v 后的 `0.1.0`。
- 稳定版本标签（无预发布后缀，如 `v0.1.0`）：额外把 `latest` 指向该版本，供默认 Compose 使用；预发布版本（如 `v0.1.0-rc.1`）保留后缀，不更新 `latest`。

PR、main 推送和手动运行不访问 Docker Hub 凭据、不推送镜像。发布时每个服务和架构保留 SBOM 与许可证清单，成功发布的标签显示在 Actions 摘要中。`ci-<运行 ID>-<尝试次数>-<架构>` 为中间标签，部署使用 `latest` 或自行指定最终版本、提交标签或 digest。

server/web 双架构镜像全部发布成功后，流水线自动创建对应标签的 GitHub Release。发布说明包含版本镜像地址、源码提交、上个版本到当前标签的实际提交列表，以及 GitHub 自动生成的 PR/贡献者/完整变更链接；稳定版本从上个稳定标签比较，预发布版本可以从上个预发布标签比较。预发布标记为 prerelease，不成为 Latest；补发旧稳定版本也不替换较新的 Latest。重复运行保留已有说明，只补齐空白说明；已有草稿保留并要求手动发布。Release job 单独授予 `contents: write`，只在版本标签推送、CI Gate 和 Docker Hub 发布成功后执行，不重新构建镜像。

历史标签不会因为这次修改而自动补发。确认该标签的 CI 和两种架构镜像已发布后，可先预览说明，再明确补建 Release：

```sh
python3 .github/scripts/release.py notes v0.0.2 --repo grayrepo-byte/Agenvas --output release-notes.md
python3 .github/scripts/release.py publish v0.0.2 --repo grayrepo-byte/Agenvas
```

本地命令复用已登录的 GitHub CLI，不把 Token 写入命令行；`notes` 只生成说明，`publish` 才写入 GitHub。脚本核对远端标签和源码提交，不新建或移动标签。自动生成能力使用 [GitHub Release Notes API](https://docs.github.com/en/rest/releases/releases#generate-release-notes-content-for-a-release)。

发布实现参考 [Docker 多架构构建说明](https://docs.docker.com/build/ci/github-actions/multi-platform/)与 [Docker 镜像标签规则](https://github.com/docker/metadata-action)。

</details>

## 文档与贡献

| 文档 | 内容 |
| --- | --- |
| [产品规格](docs/MVP-SPEC.md) | 功能范围与行为约定 |
| [开发清单](docs/DEVELOPMENT-CHECKLIST.md) | 实施进度与验收状态 |
| [开发约束](AGENTS.md) | 架构、编码与测试要求 |
| [备份与恢复](docs/operations/backup-restore.md) | 升级、数据备份与灾备恢复 |
| [安全政策](SECURITY.md) | 安全报告与支持范围 |

欢迎提交 Issue 和 Pull Request。贡献前请阅读产品规格与开发约束，功能变更需运行对应测试。

```text
frontend/       前端页面与画布交互
backend/        业务 API、Agent Runtime 与任务处理
contracts/      权威 OpenAPI 与内容 Schema
configs/        Agent、Skill 与受信媒体工作流配置
deploy/         Compose、Nginx 与容器构建
docs/           规格、设计与开发文档
```

## 许可证

采用 [MIT License](LICENSE)。第三方依赖、模型权重、工作流与 FFmpeg 等组件保留各自的许可证。
