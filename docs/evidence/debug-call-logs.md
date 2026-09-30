# 调用日志 debug 模式

日期：2026-09-30。

## 实际行为

系统设置 `/settings/general` 新增默认关闭的 Debug 模式及隐私/存储风险说明。管理员显式保存开关，CAS 冲突不覆盖其他操作，失败保留选择。日志行展开后按需读取脱敏原始请求地址、请求正文、响应状态及响应正文；收起移除 Query 正文缓存，正文按可折叠文本展示，不执行其中的 HTML。

Debug 开关保存在 PostgreSQL，并在调用开始时固定。关闭只停止之后开始的调用采集，不清除历史、不补录旧记录。请求在发送前记录，响应随流读取记录；写入采集失败不丢 Provider 结果、不重提请求。OkHttp 共用受控传输覆盖 LLM、OpenAI 图片、Google 图片、Ark 视频及结果下载；ComfyUI JDK 传输加入同一采集作用域。Mock 没有真实 HTTP 交换，不伪造请求正文。

所有 HTTP header 都不保存，已知请求凭证、正文鉴权字段、签名查询值及模型私有推理脱敏。JSON 脱敏后序列化，二进制 Base64，Multipart 显示字段与文件内容。正文最多 64 MiB；超限/未读完标注，无法安全解析的 JSON、流式事件及一次性/duplex 请求体明确省略。使用者需注意提示词、素材和个人/业务数据仍可能持久保存在数据库及备份中。

## 文件与升级

- `V62__debug_call_logs.sql` 新增单行 `audit_debug_settings` 与独立 `call_log_debug` 表，旧数据无需回填；不修改已发布迁移。合并 main 前发现其未提交音频功能已占用 V61，本次新迁移改为 V62，避免版本号碰撞。
- audit 模块提供设置 GET/PUT 与本人项目的 debug 详情 GET，详情强制管理员、项目归属与 `Cache-Control: no-store`。既有分页日志只返回元数据。
- 权威 `contracts/openapi.yaml` 同步三个接口及正文类型；TypeScript 从合约重新生成，jOOQ 从一次性 PostgreSQL 17.11 数据库重新生成，没有手改生成文件或调整依赖版本。
- 前后端与 V62 配套部署；旧页面可以继续使用原元数据列表，新设置/详情需升级后的后端。不改变 Task、UNKNOWN、重试或项目导出格式。
- 产品决定与范围同步到 MVP-SPEC、开发清单和 ADR 0020。

## 已运行检查

- 后端专项测试：原 58 项通过，含 `CallLogServiceTest`、`DebugHttpCaptureTest`、`PinnedHttpClientsTest`、`SafeLlmTransportTest`、四类媒体 HTTP 客户端测试及 `CallLogPostgresIT`；补充签名地址/错误 MIME 私有推理测试后，`DebugHttpCaptureTest` 5 项及 `CallLogPostgresIT` 9 项复验通过。共 59 项不同测试，0 失败/错误/跳过。
- `CallLogPostgresIT` 9 项使用真实 PostgreSQL 17.11 Testcontainers，新增用例验证默认关闭、管理员/CSRF、CAS、实际假 HTTP 正文入库、正文不出现在列表、详情 no-store、跨账户 404、关闭停止新增、旧正文保留。其余旧审计测试继续通过。
- 前端 4 个文件、23 项组件测试通过：系统诊断、debug 开关及详情、日志列表。覆盖开启/关闭、风险说明、冲突后的重新读取及重试、失败/401、展开才读取、Mock/无正文、纯文本安全展示与 Query 正文缓存清理。
- TypeScript `tsc --noEmit`、本次修改 TS 文件 ESLint（0 warning）、Vite 生产构建通过；`git diff --check` 通过。
- Flyway 截至 V62 的迁移 与 jOOQ codegen 对隔离临时 PostgreSQL 执行成功，Java 编译通过。没有改用户正在运行的 PostgreSQL 或 Docker 服务。

检查命令：

```sh
# backend
./mvnw -Dtest=CallLogServiceTest,DebugHttpCaptureTest,PinnedHttpClientsTest,SafeLlmTransportTest,ComfyUiClientTest,OpenAiImage2ClientTest,GoogleNanoBananaClientTest,ArkSeedanceClientTest,CallLogPostgresIT test
./mvnw -Dtest=DebugHttpCaptureTest test
./mvnw -Pjooq-codegen -Djooq.codegen.jdbcUrl=jdbc:postgresql://localhost:55439/agenvas generate-sources

# frontend
pnpm install --frozen-lockfile
./node_modules/.bin/openapi-typescript ../contracts/openapi.yaml -o src/shared/api/schema.ts
./node_modules/.bin/tsc --noEmit
./node_modules/.bin/vitest run src/features/settings/DebugModeSection.test.tsx src/features/settings/CallDebugDetails.test.tsx src/features/settings/SystemDiagnosticsPage.test.tsx src/features/settings/CallLogsPage.test.tsx
./node_modules/.bin/vite build
```

未运行全量测试、浏览器端到端、64 MiB 极限及长日志压力测试、现有部署升级演练或真实 Provider 调用。本地假 HTTP 不代表外部模型协议兼容性已经实测；当前用户部署未更新。
