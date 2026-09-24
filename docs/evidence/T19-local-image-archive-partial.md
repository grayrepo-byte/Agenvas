# T19 本地图片归档：阶段性证据

- 变更行为：管理员在所属项目上传 PNG/JPEG，服务端按字节大小、实际解码格式与像素数校验，不信任文件名或声明 MIME；临时文件经 SHA-256 和原子移动成为稳定私有文件，再创建 READY Asset 元数据及同事务 `asset.ready` 项目事件。V21 起同时从实际解码图片生成最长边 480 像素的 PNG 缩略图，两份文件安装成功后才插入 READY 元数据；失败不插入 READY 行。
- 读取：原图 GET/HEAD 要求会话鉴权和项目所有权；单段 Range 按文件偏移流式传输，非法范围返回 416 和 `Content-Range: bytes */size`。缩略图 GET 复用项目鉴权，按记录大小限流读取。API 不返回文件系统路径。画布默认只加载缩略图，原图须显式打开。
- 内容引用：创建或修订 IMAGE/VIDEO 产物、提交 Task 媒体版本时，服务端在实际归档中核对 `assetId` 属于同项目、原文件可读且媒体类型一致。PostgreSQL 测试覆盖缺失、跨项目与 IMAGE/VIDEO 类型不匹配引用；相关 Task 集成 fixture 使用真实归档 PNG，不再以随机 UUID 代替媒体字节。
- 合约/迁移：Flyway V20 新增 `asset` 表，V21 只追加缩略图文件键、大小和 SHA-256 元数据；OpenAPI 加法新增私有缩略图 GET，前端 TS 类型通过生成器重建。Compose 的资产卷用于 `/opt/agenvas/data/assets`；未进行 V21 Compose 升级验证。
- 实际检查：`AssetPostgresIT` 使用 PostgreSQL 17.11 Testcontainers 与 HTTP 测试文件字节、Range、错误格式、大小超限、跨项目与未登录边界，以及缩略图尺寸和私有读取。`./mvnw verify -q` 通过（29 个单元测试、20 个 PostgreSQL 集成测试）；OpenAPI 类型重新生成，前端 TypeScript 类型检查、ESLint、14 个测试及 Vite 构建通过。未进行 V21 Compose 或真实媒体 Provider 调用。

2026-09-23 补充：素材 GET 改为请求线程内的有界 `InputStreamResource` 读取，缩略图改为最多 20 MiB 的同步读取；Range 和私有授权 HTTP 语义不变。原异步 `StreamingResponseBody` 在 Spring MockMvc/Security 响应头提交时出现可复现的 `ConcurrentModificationException`，使素材测试偶发 500。`AssetPostgresIT` 已同步断言同步响应并在真实 PostgreSQL 上重新通过。该变更不修改 OpenAPI、数据库迁移或外部下载格式；完整后端校验仍须以本轮最终结果为准。
- 2026-09-23 续：加入固定版本的 TwelveMonkeys WebP ImageIO reader，沿用原有 20 MiB/40 MP 限制和实际解码校验；WebP 原字节保持不可变并生成 PNG 缩略图。`AssetPostgresIT` 用固定 VP8 WebP 字节验证声明 MIME/文件名误导、HTTP 上传/私有读取、缩略图、伪造与截断 WebP 不产生额外 Asset。OpenAPI 加法新增 `image/webp` 响应媒体类型并重新生成 TS 类型；既有 PNG/JPEG 客户端响应语义不变。没有数据库迁移。
- 本轮检查：`./mvnw -q -Dtest=AssetPostgresIT test` 与 `./mvnw -q verify` 通过；后端 Surefire 报告共 70 项测试、0 失败/错误。前端使用已安装的工具执行 OpenAPI 类型生成、`tsc --noEmit`、ESLint、Vitest（21 项）及 Vite 生产构建，均通过。机器上的 pnpm 12.5.1 shim 无法启动，因此本轮未使用 `pnpm` 命令；Vite 仍提示主包超过 500 kB。尚未在 Compose 运行镜像内复测 WebP。
- 2026-09-23 续：用户上传参考图新增 IMAGE 内容 `sourceType: UPLOAD` 分支，真实 Asset ID 经同项目核验后创建不可变 ArtifactVersion；Agent/Task 不能冒充用户上传来源。前端添加受 CSRF 保护的 multipart 表单，并在上传、Artifact 创建、画布放置三步成功后才清空文件/标题；失败保留输入。相同文件和标题的重试复用本页面已确认的 Asset、Artifact 与画布卡片 ID。上传后可用现有精确版本绑定按钮连接到 Agent。旧生成图片形状保持有效，见 `docs/adr/0001-upload-image-provenance.md`。定向 PostgreSQL/HTTP 测试覆盖上传、内容来源、跨项目引用、画布放置与 Agent 精确版本绑定；前端页面测试覆盖上传成功、拒绝保留文件、画布冲突后的重试复用。`./mvnw -q verify` 通过（70 项、0 失败/错误）；增补 Agent 绑定断言后单独运行 `AssetPostgresIT` 通过。前端 `tsc --noEmit`、ESLint、Vitest（24 项）和 Vite 构建通过；构建仍提示主包超过 500 kB。
- 未完成：归档崩溃孤儿文件核对、请求结果不明时的跨刷新恢复、存储满故障注入和端到端真实浏览器上传仍缺失。V20 已存 Asset 无回填缩略图，访问新预览接口返回 404。未调用真实媒体 Provider；不能把此切片视为 T19 或 MVP 完成。

2026-09-24 数据库故障补验：`AssetPostgresIT` 在隔离 PostgreSQL 的 `asset` INSERT 上安装临时拒绝触发器，精确制造“原图与缩略图已安装、READY 元数据尚未提交”的失败。用户上传失败后，项目目录文件列表恢复原样；任务键归档失败后，数据库无新 READY 行或 `asset.ready` 事件，但稳定文件可按哈希核对。移除故障后，同一任务重新归档不再次下载，恢复原文件并只发布一条 READY 元数据/事件。`./mvnw -q -Dit.test=AssetPostgresIT verify` 通过。此注入是数据库失败而非真实磁盘 ENOSPC；后者及进程崩溃后的随机上传孤儿清扫仍未验收。

2026-09-24 本地可靠性补充：移除五组媒体 PostgreSQL 测试中写死的 macOS Homebrew FFmpeg/FFprobe 路径，改用应用现有的固定本地二进制发现规则；Ubuntu CI 后端 job 显式安装 `ffmpeg`。`AssetPostgresIT` 新增有效 IHDR/CRC 的 49 MP 小 PNG，验证服务端在实际解码前按 40 MP 上限拒绝；模拟上传流写入临时字节后中断，验证无 READY 行且 `.ingest-` 临时文件被清理。五组受影响的媒体集成测试与更新后的 Asset 定向测试在当前 macOS 主机通过；尚未在 GitHub Ubuntu runner 实跑，也未完成真实磁盘满故障注入，因此 T19 验收仍未勾选。

2026-09-24 路径边界补验：`LocalAssetStorage.checkedPath` 除拒绝词法 `..` 外，还拒绝已有的符号链接父目录；上传图片、视频归档、任务锁和导出临时目录在创建文件前统一检查项目目录不是符号链接。`AssetPostgresIT` 在归档卷内构造指向卷外临时目录的项目符号链接，验证四条写入路径均拒绝且卷外未产生文件；原测试继续覆盖伪造 MIME/SVG/WebP、49 MP PNG、未认证/跨项目原图与缩略图读取。该检查不承诺防御拥有本地卷写权限的进程在检查后并发替换目录；P0 部署仍须限制卷写权限。无 API 合约或迁移变化。

本次最终源码的 `./mvnw --batch-mode --no-transfer-progress -Dit.test=AssetPostgresIT verify -q` 和完整 `./mvnw --batch-mode --no-transfer-progress verify -q` 均退出码 0；`git diff --check` 退出码 0。没有前端行为或合约变化，本轮未重跑前端检查。未做真实磁盘耗尽、跨进程目录替换或真实媒体 Provider 验证。
