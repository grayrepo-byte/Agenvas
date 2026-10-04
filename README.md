<p align="center">
  <img src="frontend/src/assets/brand/agenvas-favicon.png" alt="Agenvas Logo" width="120" />
</p>

<h1 align="center">Agenvas</h1>

<p align="center"><strong>可自托管的 AI 创作画布</strong><br />文字、图片、视频、音频与 Agent，一个工作空间。</p>

<p align="center">
  <a href="README.en.md">English</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#本地开发">本地开发</a> ·
  <a href="#文档与贡献">文档与贡献</a> ·
  <a href="LICENSE">MIT</a>
</p>

> 当前为开发版本，面向单管理员自托管使用。真实模型兼容性与发布验收进度见[开发清单](docs/DEVELOPMENT-CHECKLIST.md)。

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

### 1. 启动

安装 Docker Engine / Docker Desktop 和 Docker Compose，将 `docker-compose.yml` 放入一个目录，在该目录执行：

```sh
docker compose up -d --wait
```

无需创建 `.env`，无需手动生成或填写密钥。首次启动自动生成数据库密码和凭证加密主密钥，并保存到 `credentials-data` 持久卷；重启、更新和普通 `down` 后继续使用原值。

默认 `docker-compose.yml` 直接拉取 `docker.io/grayrepo/agenvas-server:latest`、`docker.io/grayrepo/agenvas-web:latest` 和官方 `postgres:17.11-alpine`，不在本机编译。发布流水线配置为生成应用的 amd64 / arm64 镜像；需先成功发布含自动密钥入口的 `latest`。首次发布前可使用[容器源码构建](#容器源码构建)。启动和登录无需 GPU 或模型 Key。

每份 Compose 都完整列出环境、端口、卷、健康检查和资源限制；部署者直接修改所选文件，不需要其他配置文件。需要固定应用版本时，把 `latest` 改为已发布版本、提交标签或 digest。PostgreSQL 使用[官方固定版本镜像](https://hub.docker.com/_/postgres)，不自行构建或发布。

### 2. 创建管理员

打开 <http://127.0.0.1:8088/setup>，填写管理员登录名和密码，创建账号后登录。无需初始化密钥。

程序在数据库中永久记录初始化完成状态：并发请求只有一次成功，完成后再次提交返回 `409 SETUP_ALREADY_COMPLETED`。重启、停用或删除管理员不会重新开放初始化；初始化失败会回滚，仍可重试。先在本机完成初始化，再开放公网反向代理。

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

### 3. 配置模型，开始创作

- **文字与 Agent**：在“设置 → 模型配置”中添加 OpenAI 兼容端点、模型 ID 和 API Key。使用 Agent 前，管理员须运行工具调用诊断。
- **图片、视频与音频**：在“媒体配置”中创建连接、发布能力并设置默认模型。默认部署需完成真实模型配置后才能生成媒体。

创建项目，通过画布右键菜单添加卡片或上传素材。选择模型、填写提示词和参考输入，检查预计费用后运行；完成后可预览、选用结果或重新生成。Agent 卡片可绑定上下文并选择 Skill，媒体提案在对话中统一批准或拒绝。

Seedance 视频参考需要可公网访问的媒体中继，配置见[媒体中继说明](docs/media-relay-design.md)。

> 结果未知（UNKNOWN）时需显式重试，可能产生重复费用；取消不保证外部服务停止或退款。项目清单包含数据与素材元数据，不能替代数据库和媒体文件备份。

### 更新与停止

执行 `docker compose up -d --wait` 拉取并应用最新 `latest` 应用镜像。若已自行修改 Compose 中的标签或 digest，则使用所指定版本。停止服务并保留数据卷：

```sh
docker compose down
```

源码构建环境更新代码后运行 `./deploy/update-local.sh`；停止时使用 `docker compose -f docker-compose.local.yml down`。

默认端口为 Web `8088`、API `8080`、PostgreSQL `5432`，均仅绑定本机。直接修改所选 Compose 的 `ports`。

对外部署需配置 HTTPS、反向代理，并将 Compose 的 `AGENVAS_SECURE_COOKIES` 改为 `"true"`。升级前备份数据库、媒体、配置与加密密钥；旧 V1–V77 开发库的迁移限制见[备份与恢复说明](docs/operations/backup-restore.md)。

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

也可使用 `./deploy/update-local.sh`，先完成全部镜像构建，再更新容器并等待健康检查；完成后仅显示容器名、服务、状态和端口，避免完整启动命令撑宽状态表。源码构建无需 Docker Hub 登录；默认文字与媒体仍为 `configured`。原 `deploy/compose.yaml` 使用同一套源码构建配置。

镜像部署和源码构建默认项目名均为 `agenvas`，沿用相同数据库、素材和密钥卷，切换时须备份并保证版本兼容。它们是同一环境的两种启动方式；若要并行运行，需用 `-p` 指定独立项目名并修改端口。

### Mock 环境

直接启动，数据库密码和主密钥同样自动生成，无需外部模型账户：

```sh
docker compose -f deploy/compose.dev.yaml up -d --build --wait
```

文字与媒体使用 Mock，图片、视频、音频为合成演示素材，不代表真实模型效果。访问地址与初始化流程同上；停止时使用同一 Compose 文件执行 `down`。

默认部署与 Mock 环境使用独立数据卷，但默认端口相同；并行运行需设置不同端口。

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

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中配置：

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `DOCKERHUB_USERNAME` | 有权推送镜像的 Docker Hub 用户名 |
| Secret | `DOCKERHUB_TOKEN` | Docker Hub Access Token，具备目标仓库的读写权限 |

在 Docker Hub 准备 `grayrepo/agenvas-server`、`grayrepo/agenvas-web` 两个公开仓库，便于部署者匿名拉取。CI 发布地址直接使用 `grayrepo`；需要更换发布方时修改工作流和 Compose 中的地址。PostgreSQL 从官方仓库拉取，只做扫描。

`.github/workflows/ci.yml` 在推送 `main`、推送 `v*.*.*` 版本标签或手动运行时执行；只有推送 `v*.*.*` 版本标签会上传 Docker Hub，`main` 推送与手动运行只构建和扫描、不推送。前后端测试、Compose 检查、源码扫描及两个架构的应用与官方 PostgreSQL 镜像扫描通过后，发布已扫描应用镜像的多架构清单：

- 每次发布：`sha-<完整 40 位提交 SHA>`。
- `v0.1.0` 版本标签：额外发布去除前导 v 后的 `0.1.0`。
- 稳定版本标签（无预发布后缀，如 `v0.1.0`）：额外把 `latest` 指向该版本，供默认 Compose 使用；预发布版本（如 `v0.1.0-rc.1`）保留后缀，不更新 `latest`。

PR 只执行检查，不访问 Docker Hub 凭据或推送镜像；`main` 推送与在非版本标签触发的手动运行也只构建和扫描。每个服务和架构保留 SBOM 与许可证清单，成功发布的标签显示在 Actions 摘要中。`ci-<运行 ID>-<尝试次数>-<架构>` 为中间标签，部署使用 `latest` 或自行指定最终版本、提交标签或 digest。

发布实现参考 [Docker 多架构构建说明](https://docs.docker.com/build/ci/github-actions/multi-platform/)与 [Docker 镜像标签规则](https://github.com/docker/metadata-action)。

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
