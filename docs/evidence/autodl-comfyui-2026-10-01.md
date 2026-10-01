# AutoDL ComfyUI 验证证据（2026-10-01）

本次新增 AUTODL 连接、14 个受审查 H3 工作流声明、固定提交/查询适配器及配置入口。图片、音频按冻结版本读取并发送带 MIME 的 base64；获取 task_id 后由持久 Worker 异步查询并归档。规格见 MVP 6.12，决定见 ADR 0024，操作与范围见 [接入说明](../autodl-comfyui.md)，跟踪见 [Issue #25](https://github.com/grayrepo-byte/Agenvas/issues/25)。本文件记录开发及合并验证，尚未部署。

## 合约与迁移

- OpenAPI 新增 AUTODL 平台、workflowId、videoResolution、seed，TypeScript 从合约生成。
- AutoDL 独立工作区原为 Flyway V64；合并时因 main 已有对象存储 V64 改为 V65，仅扩展媒体连接平台检查约束；使用隔离 PostgreSQL 17.11 执行 V1–V64 并重新生成 jOOQ，只有 MediaProviderConnection 检查约束源码变化。
- 既有连接、能力、任务与素材保持原样；前后端须同时升级，不自动创建默认 AutoDL 能力。未新增依赖。

## 本地协议、状态与界面测试

从仓库根目录实际执行：

```sh
./backend/mvnw -f backend/pom.xml -Dtest=AutoDlClientTest,AutoDlWorkflowsTest,MediaAdapterRegistryVideoModesTest,MediaCapabilityConfigurationTest,ImageOperationSpecTest,DebugHttpCaptureTest test -q
./backend/mvnw -f backend/pom.xml -Dit.test=AutoDlVideoPostgresIT,MediaCapabilitySettingsPostgresIT,MediaCapabilityConfigurationPostgresIT test-compile failsafe:integration-test failsafe:verify -q
./backend/mvnw -f backend/pom.xml -Dit.test=AutoDlVideoPostgresIT test-compile failsafe:integration-test failsafe:verify -q
```

6 个后端单元测试类共 **37 例通过**，失败、错误与跳过均为 0。3 个 PostgreSQL 集成测试类共 **3 例通过**；AutoDL 综合用例增加首尾帧、缺失必填音频与排队取消断言后再次通过。报告在 backend/target/surefire-reports 与 backend/target/failsafe-reports。

假 HTTP 测试验证原始 Authorization、data URL、任务 ID 核对、未知状态、响应丢失/503 无提交重试、权限拒绝、安全下载与拒绝重定向。PostgreSQL 故障注入验证冻结素材字节/参数、受理幂等、Key 轮换后按原连接版本查询、下载失败后只恢复查询/归档、过期地址刷新、音轨保留、草稿编辑与取消后结果不覆盖当前选择、UNKNOWN 的显式独立重试，以及首尾帧字段映射。已外部受理后的取消保护使用数据库取消意图故障注入，不能据此宣称新增了外部取消接口。

在 frontend/ 实际执行：

```sh
pnpm api:generate
pnpm test src/features/settings/MediaSettingsPage.test.tsx src/features/canvas/MediaDraftEditor.test.tsx src/features/canvas/taskErrorMessages.test.ts
pnpm typecheck
pnpm lint
pnpm build
```

3 个前端文件共 **53 例通过**，覆盖 AutoDL 连接/工作流配置、必填音频、比例限制与错误提示。类型检查、主题约束、ESLint 和生产构建通过；Vite 保留现有大块体积警告。前后端 14 条声明逐字段一致，git diff --check 通过。

## 真实 Provider 调用

使用用户授权的临时 Token，共两次真实生成。首个普通 Token 请求返回 403，未获得任务 ID；更换具有 ComfyUI 权限的 Token 后成功。凭据仅通过仓库外的临时文件传入，未写入源码、报告或导出；测试后删除文件。签名下载地址也未纳入本证据。

两次均使用 minimax_h3_z0903、480p 横屏、请求 1 秒、1 张 512×512 PNG 和 1 条 1 秒 WAV。测试素材为程序生成的色块/渐变与正弦音，用于验证输入协议和音轨，不验证人脸、口型或语音质量。

| 路径 | 原始供应商 task_id | 输出 | SHA-256 |
|---|---|---|---|
| 官方 API 提交/轮询，随后生产 Java Client 按原 ID 查询下载 | ba78857b-1e1a-446b-aeee-a94f9d000f91 | 232692 字节；864×480；H.264 + AAC；1625 ms | af47cc93efd248f263aaf18e575c064d2d602011ed6d7da3064f651bc76f18e9 |
| 正式 DirectMediaTaskService + Worker + 隔离 PostgreSQL + 真实 AutoDL | 39512335-80f5-4172-a237-f946972440b2 | 114699 字节；864×480；含音轨；1625 ms | 3cb54b6dde4f048678798f7d98fa48154f803def85278e58fbbeeebbd6332bf6 |

第二条由 AutoDlRealProviderPostgresIT 实际执行，**1 例通过、无失败/错误/跳过**，验证受理 ID 入库、原 ID 轮询、SUCCEEDED、解码归档、节点版本与自动选用。实际执行命令如下；文件路径为本地临时路径，不包含 Token 值：

```sh
./backend/mvnw -f backend/pom.xml -Dagenvas.autodl.real-test=true -Dagenvas.autodl.credential-file=/tmp/agenvas-autodl-test-key -Dagenvas.autodl.result-file=/tmp/agenvas-autodl-worker-video.mp4 -Dit.test=AutoDlRealProviderPostgresIT test-compile failsafe:integration-test failsafe:verify -q
```

该付费测试默认关闭，后续执行需主动设置开关并提供新的临时 ComfyUI Token 文件。测试数据库由 Testcontainers 销毁；应用未保存测试连接。供应商真实返回时长与请求不同，应用保留原始结果，不裁剪。

## 未验证范围

- 其余 13 个已声明工作流未逐一付费实测；完整分辨率、参考槽位与种子组合未实测。
- 自动音频时长、视频动作迁移、IndexTTS、图片输出及任意工作流图未实现。
- 浏览器端到端、生产部署/升级演练、长时间压力、完整测试套件未运行。
- 未查询 AutoDL 实际账单；管理员价格仅用于应用估算。

官方来源：[ComfyUI API 文档](https://autodl.art/docs/comfyui_api/)与[工作流目录](https://www.autodl.art/large-model/comfyui)。固定声明来自本次公开目录/详情接口与前端字段校验；base64 限制按用户说明落实，并由上述真实调用验证。

## 合并 main 验证

与 main 的对象存储、画布连线改动集成，保留双方任务清单。因 main 已使用 V64 / ADR 0023，AutoDL 改用 V65 / ADR 0024。上文付费生成来自调整编号前的独立工作区，合并后未重复付费生成。

按合并后 V1–V65 对隔离 PostgreSQL 17.11 重新执行 Flyway / jOOQ codegen，生成源码一致；按合并后的 OpenAPI 重新生成 TS，结果一致。类型检查、lint、53 个前端测试和生产构建再次通过。

第一次增量集成测试因 target/classes 残留改名前的 AutoDL V64 资源而启动失败；清理构建目录后实际执行以下定向检查，37 个单元测试、4 个 PostgreSQL 集成用例全部通过，失败、错误和跳过均为 0：

```sh
./backend/mvnw -f backend/pom.xml clean -Dtest=AutoDlClientTest,AutoDlWorkflowsTest,MediaAdapterRegistryVideoModesTest,MediaCapabilityConfigurationTest,ImageOperationSpecTest,DebugHttpCaptureTest -Dit.test=AutoDlVideoPostgresIT,MediaCapabilitySettingsPostgresIT,MediaCapabilityConfigurationPostgresIT,ObjectStoragePostgresIT test failsafe:integration-test failsafe:verify -q
```

已核对实际构建目录仅有对象存储 V64 与 AutoDL V65，没有旧 AutoDL V64。全量测试与生产部署未运行。
