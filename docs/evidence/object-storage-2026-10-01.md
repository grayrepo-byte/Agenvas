# 可选对象存储（2026-10-01）

实现位于当前工作区，未部署到现有服务。依据 [ADR 0023](../adr/0023-per-asset-object-storage.md)。规格与验收任务同步到 [GitHub #23](https://github.com/grayrepo-byte/Agenvas/issues/23)。

默认本地不变。保存云连接不会启用；显式选择新默认只影响新归档，本地旧文件继续读本地，云端旧文件继续读原连接。位置配置固定，凭证可轮换，历史连接保留。图片、视频、音频、缩略图及视频封面通过同一归档边界，使用实际媒体校验。云资源完成后清理中转文件，播放通过鉴权流代理，处理引用使用可清理的私有缓存。

## 合约与升级

- 新增 V64：storage_profile、storage_settings、asset_storage_route。既有 Asset 字段及数据不修改；存储路由默认本地。
- 隔离 PostgreSQL 17.11 执行最新迁移后重新生成 jOOQ。没有手改生成源码。
- OpenAPI 新增管理员存储设置 GET、连接 POST、默认存储 PUT、凭证轮换 PUT，以及实际校验的 MP4 上传；媒体归档与读取补充 409/503 错误，生成 TS 同步，无删除旧 API。
- 升级需一起发布后端、前端和迁移。凭证复用部署主密钥，备份时保留数据库、凭证主密钥、原本地卷和所有已使用 Bucket；缓存无需作为归档备份。

## 定向验证

`backend/./mvnw test -Dtest=AssetDiskFullPostgresIT,ConfiguredAssetStorageTest,ObjectStoragePostgresIT,StorageSettingsServiceTest,ObjectStorageClientTest,AssetControllerStreamingTest,LocalAssetStorageScratchTest,CredentialCipherTest`：17 个测试通过，0 失败/错误。新增归档测试使用真实 PostgreSQL 及明确的假对象 HTTP 服务，覆盖 OSS/COS/S3 配置、混合读回、Range/HEAD、缩略图、凭证加密与轮换、CAS、CSRF/管理员/跨项目授权、上传失败继续同目标归档，以及图片、MP4/WAV 字节往返和实际视频上传 API。不会调用真实云服务或真实生成 Provider。

`AssetPostgresIT`、`DirectMediaGenerationPlacementPostgresIT`、`MockImageSchedulerPostgresIT` 3 个既有定向集成用例已通过。磁盘失败注入的测试首次遇到存储接口新增后的双 Primary 注入冲突；改为显式选择归档路由服务、内部继续使用故障存储后，`AssetDiskFullPostgresIT` 已复跑通过。

`MultipartWorkspaceConfigurationTest` 2 个新增单元用例通过，验证私有卷中的 multipart 中转目录、上传配置和符号链接拒绝。与 `AssetControllerStreamingTest` 一起在 128 MiB JVM 堆限制下运行，3 个测试通过；既有 400 MiB 稀疏文件分段读取不需要整文件驻留内存。以上定向检查累计覆盖 22 个不同后端测试。最终再运行 `ObjectStoragePostgresIT,MultipartWorkspaceConfigurationTest`（3 个）和受影响的配置/缓存单元测试（6 个），均通过。

`frontend/pnpm exec vitest run src/features/settings/StorageSettingsPage.test.tsx src/features/settings/SystemDiagnosticsPage.test.tsx src/shared/ui/PageShell.test.tsx src/features/canvas/ProjectWorkspacePage.test.tsx`：36 个测试通过。覆盖默认本地、添加不启用、双向切换、凭证不进入 Query 缓存、失败清空凭证并保留非秘密草稿、冲突版本、凭证轮换与失效会话，以及 MP4 导入创建上传视频节点、封面预览与不自动播放。设置页最终 5 个测试复跑通过。

`pnpm typecheck`、`pnpm lint`、`pnpm build` 通过。构建仍报告既有画布包超过 500 kB 的提示。依赖安装使用 frozen lockfile，没有修改依赖版本或锁文件。

隔离 Spring Boot + PostgreSQL + Vite 中使用浏览器完成桌面/390px 移动端设置页操作：保存假 S3 连接后默认仍本地、显式切云再切本地、成功后凭证输入清空，无页面异常及横向溢出。未向该假连接上传资源，不产生外部云请求。

Nginx 1.28.0 Alpine 容器中的 `nginx -t` 通过；媒体上传独立放宽至 501 MiB、禁用请求缓冲，媒体读取禁用响应缓冲，其他 API 仍保留原限制。实际 500 MiB 视频上传及完整反代链路未压测。`git diff --check` 通过。

## 合并 main 验证

将 main 的媒体聚焦和画布连线显示设置整合进存储分支，开发清单的追加冲突保留双方记录。合并后的 `ProjectWorkspacePage`、`CanvasDisplaySettings`、`CanvasSelectionClearing`、`MediaDraftEditor`、`StorageSettingsPage` 5 个定向文件共 85 个测试通过；类型检查、lint、构建和差异格式检查通过。构建仍有既有画布大包提示，测试环境报告未实现的媒体 pause 提示，但无失败。后端及迁移未因合并变化，本轮未重复后端测试；未运行全量测试，未部署或使用真实云账号。

## 未验证与限制

真实云账户连接、供应商策略差异、大文件吞吐和弱网压力未实测，假 HTTP 不是云端验收。全量测试未完成：首次前端调用因参数分隔符误启了全量运行，发现后立即中止；之后使用明确文件过滤执行上述定向测试。未进行真实生成 Provider 调用。

云模式仍需上传校验、解码、FFmpeg 和恢复中转所需的本地可写目录与空间。云故障不会自动退回本地或发起新生成。闲置处理副本默认 24 小时后清理（最短可配 1 小时），不保证瞬时磁盘占用为零；未提供上传直传、历史搬迁和孤立云对象的自动清理。

协议来源：[AWS SigV4](https://docs.aws.amazon.com/AmazonS3/latest/developerguide/sig-v4-header-based-auth.html)、[COS S3 兼容](https://intl.cloud.tencent.com/document/product/436/34688?lang=en)、[OSS V4](https://www.alibabacloud.com/help/en/oss/developer-reference/recommend-to-use-signature-version-4)。AWS 官方签名例子匹配。OSS 文档规范化示例的 SHA-256 正确，但展示的签名密钥与文字里的 secret 不一致；测试期望使用相同规范化请求并以独立 Python HMAC 计算交叉验证。
