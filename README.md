<p align="center">
  <img src="frontend/src/assets/brand/agenvas-square.png" alt="Agenvas Logo" width="120" />
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

界面支持中文、英文、俄文和日文。媒体适配器包括 GPT Image、Google Nano Banana、火山方舟 Seedance、Seed Audio、ComfyUI 固定模板和 RunningHub V2 工作流 / AI 应用；可用操作取决于管理员发布的能力。

## 快速开始

### 1. 配置环境

安装 Docker Engine / Docker Desktop 和 Docker Compose，在仓库根目录执行：

```sh
cp .env.example .env
```

编辑 `.env`，填写以下配置（不要提交真实凭据）：

| 配置 | 用途 |
| --- | --- |
| `AGENVAS_DB_PASSWORD` | 必填，独立随机数据库密码 |
| `AGENVAS_BOOTSTRAP_SECRET` | 必填，至少 24 字符的一次性管理员初始化密钥 |
| `AGENVAS_CREDENTIAL_MASTER_KEY` | 32 字节随机密钥的 Base64 编码，保存模型或存储凭证、完整 ComfyUI 地址前必填 |

### 2. 启动并登录

```sh
./deploy/update-local.sh
```

脚本构建镜像并启动服务，保留数据库与素材卷。启动和登录无需 GPU 或模型 Key。

打开 <http://127.0.0.1:8088/setup>，输入初始化密钥并创建管理员，然后登录。

### 3. 配置模型，开始创作

- **文字与 Agent**：在“设置 → 模型配置”中添加 OpenAI 兼容端点、模型 ID 和 API Key。使用 Agent 前，管理员须运行工具调用诊断。
- **图片、视频与音频**：在“媒体配置”中创建连接、发布能力并设置默认模型。默认部署需完成真实模型配置后才能生成媒体。

创建项目，通过画布右键菜单添加卡片或上传素材。选择模型、填写提示词和参考输入，检查预计费用后运行；完成后可预览、选用结果或重新生成。Agent 卡片可绑定上下文并选择 Skill，媒体提案在对话中统一批准或拒绝。

Seedance 视频参考需要可公网访问的媒体中继，配置见[媒体中继说明](docs/media-relay-design.md)。

> 结果未知（UNKNOWN）时需显式重试，可能产生重复费用；取消不保证外部服务停止或退款。项目清单包含数据与素材元数据，不能替代数据库和媒体文件备份。

### 更新与停止

更新代码后，再次运行 `./deploy/update-local.sh`。停止服务并保留数据卷：

```sh
docker compose --env-file .env -f deploy/compose.yaml down
```

默认端口为 Web `8088`、API `8080`、PostgreSQL `5432`，均仅绑定本机。可在 `.env` 中通过 `AGENVAS_WEB_PORT`、`AGENVAS_API_PORT`、`AGENVAS_DB_PORT` 修改。

对外部署需配置 HTTPS、反向代理和 `AGENVAS_SECURE_COOKIES=true`。升级前备份数据库、媒体、配置与加密密钥；旧 V1–V77 开发库的迁移限制见[备份与恢复说明](docs/operations/backup-restore.md)。

## 本地开发

| 层 | 技术栈 |
| --- | --- |
| 前端 | Vite · React · TypeScript · React Flow · TanStack Query · Zustand |
| 后端 | Java 21 · Spring Boot · Spring AI · jOOQ |
| 数据与部署 | PostgreSQL · Flyway · Docker Compose · REST / SSE |

工具链：JDK 21、Node 24 LTS（24.12+）、pnpm 12.5.1。完整版本见[依赖基线](docs/dependency-baseline.md)。

### Mock 环境

填写 `.env` 中的数据库密码与初始化密钥后启动，无需外部模型账户：

```sh
docker compose --env-file .env -f deploy/compose.dev.yaml up -d --build
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

后端需提供 `AGENVAS_DB_URL`、`AGENVAS_DB_USER`、`AGENVAS_DB_PASSWORD` 和 `AGENVAS_BOOTSTRAP_SECRET`，不会自动加载根目录 `.env`。源码运行默认使用 Mock，媒体处理需安装 FFmpeg / FFprobe；深度提取见[本地图片处理配置](docs/local-image-processing.md)。

Vite 默认运行于 `5173`，将 `/api` 代理到 `localhost:8080`。

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
