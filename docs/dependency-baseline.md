# Agenvas 依赖基线

**基线日期：2026-09-26｜适用版本：0.1.0-SNAPSHOT**

本文件记录已经在当前项目解析、编译或构建验证的直接依赖。它是 `MVP-SPEC.md` 第 4.4 节所要求的 M0 基线，不表示后续业务模块或真实 Provider 已经实现。

## 工具链

| 项目 | 冻结版本 | 验证说明 |
|---|---:|---|
| Java | 21 | 本机 Corretto 21.0.9；容器 Temurin 21.0.9+10 |
| Maven Wrapper | 3.9.12 | `backend/mvnw` |
| Node.js | 24.21.0 | 前端构建镜像；`package.json` 接受同一 Node 24 LTS 系列的 24.12+ |
| pnpm | 12.5.1 | `packageManager`、engine、CI 和容器一致 |
| Trivy | 0.74.0 | CI action 固定到 v0.36.0 对应 commit；本机以同版本容器复验扫描命令 |
| PostgreSQL | 17.11 | 清理后的单个 Flyway V1 已在隔离 PostgreSQL 验证；67 表、567 列及全部数据库对象注释已落地，149 项后端定向回归及目标本地 Compose 空卷启动通过，详见开发清单 |

## 后端直接依赖

| 依赖 | 版本来源/版本 |
|---|---:|
| Spring Boot Parent / Maven Plugin | 4.0.8 |
| Tomcat Embed | 11.0.25（Boot 4.0 管理系列内的安全修复覆盖） |
| Spring AI BOM / `spring-ai-client-chat` / `spring-ai-starter-model-openai` | 2.0.1 |
| OkHttp（LLM 出站固定目标与 DNS/重定向控制） | 4.12.0；与 Spring AI 2.0.1 当前解析版本一致 |
| TwelveMonkeys ImageIO WebP reader | 3.15.2；用于实际 WebP 解码，见 [项目仓库](https://github.com/haraldk/TwelveMonkeys) |
| ONNX Runtime Java CPU | 1.30.0；仅用于服务端本地 Depth Anything V2 Small 推理，Maven 包包含 Linux/macOS x64/aarch64 与 Windows x64 原生库；Compose server 镜像内置固定提交、SHA-256 校验的 27.3 MB INT8 ONNX |
| jOOQ（Boot 4 starter + codegen 插件） | 3.19.37（Boot 4.0.8 依赖管理）；生成源码提交在 `backend/src/jooq/java`，见 [ADR 0012](adr/0012-jooq-persistence.md) |
| springdoc OpenAPI WebMVC UI | 3.0.3（按规格保持 3.0.x） |
| Spring MVC / Security / Session JDBC starter / Actuator / jOOQ starter / Validation | Boot 4.0.8 依赖管理 |
| Flyway / PostgreSQL JDBC | Boot 4.0.8 依赖管理 |
| Testcontainers PostgreSQL / JUnit Jupiter | 2.0.5（仅集成测试） |

本地 Testcontainers PostgreSQL 仅用于测试且不启用 TLS；Maven Surefire/Failsafe 的测试进程固定 JDBC `sslmode=disable`，避免驱动在 Docker Desktop 端口代理上进行不必要的 SSL 协商。此设置不进入 Spring Boot 生产运行配置，也不改变部署数据库的 TLS 策略。

jOOQ 生成源码（139 个文件，包 `dev.agenvas.db`）提交在 `backend/src/jooq/java`，由 `build-helper-maven-plugin` 加为源码根，因此普通构建、CI 与部署镜像都不需要数据库。重新生成走 `jooq-codegen` profile：先对一次性 PostgreSQL 17 执行 Flyway，再反向生成；该 profile 不是默认构建的一部分（原因为何不采用构建期 codegen，见 [ADR 0012](adr/0012-jooq-persistence.md)）。CI 的 backend job 对同一一次性数据库重跑该 profile 并断言生成结果与提交内容一致。

Spring AI 2.0 不再提供旧教程常见的 `spring-ai-core` 直接模块名；本项目使用 BOM 管理的 `spring-ai-client-chat` 与 OpenAI 兼容模型 starter，避免混入 1.x API。默认禁用 Spring AI 的所有外部模型自动配置；独立 JVM 的默认 Mock 与开发 Compose 的显式 Mock 加载应用自有确定性 `ChatGateway` 和 `GenerationGateway`。默认部署 Compose 使用 `configured`，管理员配置数据库模型或部署者配置候选聊天适配器的端点、模型与 Key 后才会创建真实聊天客户端。两种模式仍经过同一持久化 Runtime/Task 路径。

## 前端直接依赖

2026-10-02 shadcn 迁移新增精确版本：`radix-ui` 1.6.7、`class-variance-authority` 0.7.1、`cmdk` 1.1.1、`cn` 0.4.0。组件源码位于 `frontend/src/shared/ui/primitives`；CLI 为开发时工具，不加入生产 Node 服务。`components.json` 保持 Radix 与 Phosphor，未升级已有依赖的大版本。验证范围为相关前端组件/页面测试、TypeScript、lint、Vite 构建与隔离 Mock 浏览器；详见 迁移验证（开发记录不随源码公开）。

运行依赖：React/React DOM 19.3.0、React Router 7.18.4、TanStack Query 5.103.2、Zustand 5.0.15、React Flow 12.11.6、React Hook Form 7.88.0、Zod 4.6.5、Phosphor React 2.1.10（画布线性图标，MIT）。

构建与测试：Vite 8.3.0、`@vitejs/plugin-react` 6.1.1、TypeScript 5.9.3、Tailwind CSS + `@tailwindcss/vite` 4.3.3、Vitest 5.0.1、Testing Library React 16.3.3、MSW 2.15.0、openapi-typescript 7.13.0、ESLint 10.11.0、typescript-eslint 8.70.1。Vitest 通过 `vite.config.ts` 复用同一插件，并按 `tsconfig.json` 的 `jsx: "react-jsx"` 转换测试文件；`@vitejs/plugin-react` 同时为 Vite dev server 提供 React Fast Refresh。Tailwind 走 `@tailwindcss/vite` 插件，不再需要 `postcss.config.mjs`。

没有采用当时最新的 TypeScript 7.0.2，因为 `typescript-eslint` 8.70.1 的正式兼容范围小于 6.1；选择 5.9.3 是经过 peer dependency 核对的稳定组合。React Router 保持 7.x，以支持构建时未知的项目 UUID 路径。

精确解析结果与完整传递依赖见 `frontend/pnpm-lock.yaml` 和 Maven effective dependency tree；生成的 API 类型来自 `contracts/openapi.yaml`。

## 容器镜像

| 用途 | 精确镜像 | 多架构 digest |
|---|---|---|
| 前端构建 | `node:24.21.0-alpine` | `sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1` |
| 后端构建 | `maven:3.9.12-eclipse-temurin-21-noble` | `sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46` |
| 后端运行 | `eclipse-temurin:21.0.9_10-jre-noble` | `sha256:d3eb69add1874bc785382d6282db53a67841f602a1139dee6c4a1221d8c56567` |
| Web/Nginx | `nginx:1.28.0-alpine` | `sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284` |
| 数据库基础镜像 | `postgres:17.11-alpine` | `sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24` |

所有运行容器使用非 root 用户；server 和 web 使用只读根文件系统及受限 tmpfs。默认宿主端口只绑定 `127.0.0.1`。

Web 与数据库运行镜像在固定 Alpine 基础镜像上执行 `apk upgrade --no-cache`；server 使用固定 Ubuntu Noble/Temurin glibc 镜像并执行 `apt-get upgrade`，因为 Maven 发布的 ONNX Runtime Linux 原生库依赖 glibc，不能在 Alpine/musl 上可靠加载。实际 OS 包版本由每次镜像 SBOM 记录，不能仅凭基础镜像 digest 推断。数据库镜像额外用 Alpine `su-exec` 替换官方入口脚本所调用的 `gosu`；本机已验证初始化和 `pg_isready`。Trivy 0.74.0 仍会读到底层镜像中已被替换的旧 `gosu`，CI 仅对 PostgreSQL 镜像的 `usr/local/bin/gosu` 路径做精确排除，其余路径不排除；此例外的负责人、证据和到期日见 `docs/security-exceptions.md`。

## 初始基线验证（历史记录）

以下命令结果记录的是建立 M0 基线时的执行快照，不代表当前版本的全量测试计数或最新部署验收；后续行为与回归结果以 开发记录（不随源码公开） 和开发清单为准。

```text
frontend: ./node_modules/.bin/openapi-typescript ../contracts/openapi.yaml -o src/shared/api/schema.ts
frontend: ./node_modules/.bin/tsc --noEmit
frontend: ./node_modules/.bin/eslint . --max-warnings=0
frontend: ./node_modules/.bin/vitest run   # 25 tests passed
frontend: ./node_modules/.bin/vite build   # Vite production build succeeded
backend:  ./mvnw verify             # 本轮 Surefire 共 70 tests，0 failures/errors
root:     docker compose config --quiet
root:     docker compose up -d --build
```

本轮直接使用已安装的前端工具，因为本机 pnpm 12.5.1 shim 无法启动；这不替代 CI 对锁文件安装的验证。

Compose 在真实 PostgreSQL 17.11 执行 Flyway V1–V15 且服务健康，反代的 setup-status 接口成功返回。身份与项目闭环、Artifact 不可变版本、CanvasItem 持久布局、Agent 配置与精确绑定，以及 Run 创建/精确重放/异参冲突/活动槽位冲突/取消释放槽位均通过 Nginx 实际请求验证。Task 的竞争认领、租约过期接管、fencing epoch、无长事务网络执行、提交 checkpoint 崩溃分类、取消晚到结果、UNKNOWN 快照可见性、Mock 结果的 Artifact CAS，以及全新输出槽位的完成/取消隔离由真实 PostgreSQL Testcontainers 集成测试验证。模型回合完整响应、工具账本与文本产物原子创建由假模型及真实 PostgreSQL Testcontainers 集成测试验证。项目事件写入失败回滚、并发序号无缺口、一致性快照，以及 SSE 历史补发、Last-Event-ID 优先级、未授权和过期游标均通过 PostgreSQL 集成测试。受保护接口未登录返回 HTTP 401，缺少 CSRF 的写请求返回 HTTP 403。

V20 本地 Asset 上传、原子文件归档、项目事件与私有 GET/HEAD/Range，以及 V21 私有 PNG 缩略图生成、鉴权读取和画布新输出放置，已由 PostgreSQL Testcontainers 和 MockMvc 测试；媒体产物版本的真实同项目 Asset 引用也由 PostgreSQL 测试验证。这些不是 Compose 端到端或真实 Provider 测试。画布图片卡片改为加载归档原图，缩略图仍归档但不由卡片加载，由 Vitest 组件测试验证。
Mock 图片后台调度、实际 PNG 归档、同步成功/拒绝/UNKNOWN fixture，以及错误配置在提交前失败，由 `MockImageSchedulerPostgresIT` 在 PostgreSQL Testcontainers 中验证；尚未执行浏览器端到端或真实模型黄金路径。
Run 前模型与输入预览、Agent 版本钉住由 `AgentRunPostgresIT` 和前端组件测试验证；开发 Mock 模式报告明确标记的演示模型；默认部署 Compose 使用 configured，未配置真实模型时不产生演示生成。无外部账户的演示剧本（三镜头、分阶段审批、关键帧选择）已随 [ADR 0013](adr/0013-contract-to-direct-generation.md) 移除；Mock 模式仍可脱离外部模型启动并跑通直连文本、图片与视频。这不表示真实 ChatModel 或媒体 Provider 已接通。`AssetPostgresIT` 还验证 MP4 的私有 Range/HEAD、封面和错误媒体拒绝。容器镜像已成功构建，并实测其中 `libx264` 可编码。

## 尚未验证或不在本基线范围

- 上述初始 Compose 验证发生于旧 V15；旧 V35 曾在隔离空卷 Compose 中构建、初始化、登录及停机重启，见 开发记录（不随源码公开）。从旧 V15 原位升级到旧 V35 当时未演练；这些记录属于基线重建前的开发历史。
- T04 已加入 fork PR 可运行且不注入 Provider/部署密钥的 Trivy 源码密钥与依赖扫描、三个运行镜像的 HIGH/CRITICAL 漏洞门禁，以及每镜像的 CycloneDX SBOM/许可证清单工件。2026-09-24 本机用 Trivy 0.74.0 验证：源码密钥与依赖扫描均为 0；后端运行镜像的许可证 JSON 和 CycloneDX 输出成功；Web 原镜像有 37 项 HIGH/CRITICAL，Alpine 安全更新后为 0；后端原镜像的 Tomcat 11.0.24 命中 CVE-2026-68525，固定到 11.0.25 后的运行镜像 OS 和 JAR 均为 0；PostgreSQL 派生镜像精确排除已被 `su-exec` 替换的底层旧 `gosu` 文件后为 0。后端 `./mvnw verify` 实际通过（Surefire 50、Failsafe 41），Docker 内构建通过（Surefire 50）；三张运行镜像均成功构建，PostgreSQL 派生镜像初始化并通过 `pg_isready`，Nginx 配置测试通过。工作流 YAML 已解析且无 `secrets.*` 引用，Compose 配置检查通过；GitHub Actions 托管运行尚未在本工作区验证。源码离线扫描无法完整解析 Maven 父 BOM 的传递依赖，后端镜像扫描补足了运行 JAR 覆盖。
- Spring AI 2.0.1 的受控 ChatClient 工具往返经假模型测试；OpenAI 兼容 starter 的实际 `ChatModel` 又经假 HTTP Chat Completions 端点与真实 PostgreSQL 上下文验证工具 ID、下一回合 tool reply 和 Token 元数据。完整响应 checkpoint、持久工具结果的下一回合消息重建与剩余工具（读取上下文、创建与修改文字、摆放卡片）的业务执行经真实 PostgreSQL + 假 ChatGateway 或保存的假模型响应测试。尚未验证特定真实 Provider 对恢复后元数据的要求；没有真实 LLM 或视觉调用。
- ComfyUI `image-v1` 候选模板已接入图片提交、原 prompt_id 状态跟踪与 Asset 归档；V56 移除 V23 的全局单槽，假 HTTP 服务与 PostgreSQL 集成测试验证活动请求可重叠提交。尚未以真实 ComfyUI/模型验证图片。默认部署 Compose 为 configured，开发 Compose 显式启用 Mock。PNG/JPEG/WebP 上传已由 PostgreSQL＋HTTP 验证；真实 Provider 的归档失败恢复、并发资源表现和固定视频模板现场兼容性尚未完成。

## FFmpeg 分发说明

server 运行镜像安装 Ubuntu Noble 的系统 `ffmpeg` 6.1.1-3ubuntu5；当前 ARM64 镜像显示 `--enable-gpl`、`--enable-libx264`，且编码器列表包含 `libx264`/`libx264rgb`。该系统二进制并非 Agenvas 的 Apache-2.0 代码；依据 [FFmpeg 官方许可证说明](https://ffmpeg.org/doxygen/trunk/md_LICENSE.html)，分发前必须单独核对许可证文本、对应源码与构建信息。此前 Alpine 6.1.2-r2 的验证只属于历史镜像，不能代替当前 Noble 镜像审核。

媒体集成测试不再固定 macOS Homebrew 路径，使用服务端固定路径发现（`/usr/bin`、`/opt/homebrew/bin`、`/usr/local/bin`）；Ubuntu CI 后端 job 显式安装 `ffmpeg`/`ffprobe` 所在系统包。当前主机定向测试已运行，GitHub Ubuntu job 尚未在此工作区验证。
- SSE 通过 Testcontainers 中真实 Tomcat HTTP 和 Nginx 配置验证；浏览器全链路弱网压测仍属于发布前门禁。

## 2026-10-01 对象存储验证范围

对象存储复用现有 OkHttp 4.12.0 和 JDK SHA-256/HMAC，不引入云 SDK 或新运行依赖。固定 PUT/HEAD/GET Range/DELETE 协议及签名根据 [AWS SigV4](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html)、[COS 的 S3 兼容说明](https://intl.cloud.tencent.com/document/product/436/34688?lang=en)和 [OSS V4](https://www.alibabacloud.com/help/en/oss/developer-reference/recommend-to-use-signature-version-4)实现；限制目标与操作的传输保持应用的 DNS、重定向和重试控制。AWS 官方示例签名有定向单元测试，OSS 规范化示例以独立 HMAC 计算交叉核验。真实云账户兼容性与吞吐尚未实测，假 HTTP 服务不代表真实云验收。jOOQ 由隔离 PostgreSQL 17.11 执行 V64 后重新生成。

## 2026-10-01 RunningHub 验证范围

RunningHub 固定 V2 协议复用现有 OkHttp、Jackson、Spring MVC、任务内核与媒体归档，没有引入 Go SDK、脚本运行时或新依赖。第三方 Go SDK 仅作只读参考，不代表 Provider 协议保证。jOOQ 初始由隔离 PostgreSQL 17.11 执行 V65 后重新生成；合并 main 后保留 AutoDL V65，RunningHub 改为 V66 并重新生成，普通构建继续不连接生成数据库；OpenAPI 生成 TypeScript。动态表单复用现有 React 控件与类型，没有增加前端服务端。

固定 HTTP 协议、输入契约、归档恢复与动态表单经定向测试；准确命令、计数与未验证事项见 RunningHub 证据（开发记录不随源码公开）。没有真实 RunningHub Key 或付费调用，也没有运行全量测试或浏览器端到端。
