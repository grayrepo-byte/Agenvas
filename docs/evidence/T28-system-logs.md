# T28 系统日志验收（2026-09-30）

## 实现

侧栏新增“系统日志”及 `/settings/logs` 路由，读取管理员专用 `GET /api/v1/settings/system-logs`。提供 stdout / stderr 通道、字面关键词、最近 200 / 500 / 1000 行筛选，每 3 秒自动刷新、暂停、跟随输出。请求失败保留当前查询的旧快照并停止轮询，手动刷新可恢复；过期会话跳转登录，权限不足明确提示。输出按纯文本渲染。

main 在 Spring 初始化之前安装 Java stdout / stderr tee，原控制台字节继续输出；应用关闭或启动失败时恢复原流。UTF-8 分段写入在换行后形成一条记录。最多保留 2000 行，单行保留前 8192 字节，超长尾部丢弃至换行；接口返回进程身份、启动/读取时间、缓存/匹配/淘汰数量与截断标记。重启获得新进程身份；页面替换快照而非合并旧进程的序号。

缓存前移除 ANSI 颜色、脱敏常见凭证赋值、Authorization/Cookie、Bearer、可识别 Provider Key、URL 用户凭据及查询参数；搜索也只针对脱敏内容。凭证赋值隐藏该行余下内容，覆盖含空格和 JSON 转义的值。接口 `Cache-Control: no-store`，不读文件、不接受命令、不触发 Provider 调用、不写数据库、项目事件或项目导出。

改动包含后端 settings 的采集/缓存/接口、bootstrap 启动注册与安全匹配，前端页面/导航/客户端、`contracts/openapi.yaml` 与生成的 TypeScript，README、MVP-SPEC、开发清单及 ADR 0022。新增只读接口，无破坏性旧合约变更、数据库迁移或 jOOQ 重生成；无新增依赖。

## 实际运行

环境：本机 Corretto 21.0.9、Node 24.12.0、pnpm 12.5.1、Maven Wrapper 3.9.12、Docker 24.0.6。测试数据库为 Testcontainers PostgreSQL 17.11，Provider 为明确的 Mock，不进行真实云端请求。

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm exec vitest run src/features/settings/SystemLogsPage.test.tsx src/shared/ui/PageShell.test.tsx src/app/App.test.tsx
corepack pnpm build

cd ../backend
./mvnw -Dtest=SystemLogBufferTest,SystemLogCaptureTest,SystemLogsControllerTest -Dit.test=SystemLogsStartupPostgresIT verify
```

- 冻结锁文件安装和 OpenAPI 类型生成成功；重复生成字节一致。
- TypeScript、主题色检查及 ESLint 通过。
- 前端 3 个定向文件、15 项测试通过：页面与公共路由/导航、两通道、恶意 HTML 纯文本、截断/淘汰提示、服务端筛选参数、等待/空态、失败保留与手动恢复、401/403、轮询暂停和进程重启替换。
- Vite 生产构建成功，系统日志页面单独拆包约 5.70 kB（gzip 2.58 kB）。
- 后端编译及打包成功；Surefire 8 项与 Failsafe 1 项通过。覆盖并发缓存/序号、淘汰与最新匹配、字面搜索、参数边界、常见凭据脱敏、UTF-8 分段/CRLF、超长行与后续行、原始输出保留/流恢复；实际生产安全链验证匿名 401、普通角色 403、管理员 200 和 no-store。
- `SystemLogsStartupPostgresIT` 使用 `useMainMethod=ALWAYS` 与真实 PostgreSQL 完整初始化，证实接口注入的是启动前安装的同一缓存，真实启动日志及合成 stdout / stderr 夹具可读，合成凭据不会返回原值。HTTP 层使用 MockMvc，不声称完成浏览器或 Nginx 端到端验收。
- `git diff --check` 通过。后端保留既有 SpringAiChatGateway varargs/过时 API 编译警告；Mockito 自附加警告不构成本次测试失败。

## 限制与未验证

未运行全量测试、浏览器视觉验收、完整 Compose/Nginx 部署升级或真实 Provider 调用。未实测长期负载；有界缓存与单行限制由定向测试验证。

只采集 Java stdout / stderr 的完整行，不含 stdin、原生文件描述符写入、子进程与其他容器；没有换行的部分输出暂不显示。启动失败且 HTTP 服务未就绪时仍需使用部署控制台。日志重启清空，不提供历史持久化或恢复；不替代业务调用审计。

脱敏是额外保护，不能识别无标签的任意秘密，也不会修改原控制台输出。应用仍须遵守禁止输出密钥、完整 Prompt、原始媒体与模型私有推理的原有规则。

## 合并 main 补验（2026-10-01）

合并 main 的调用 debug 与音频扩展时，安全配置保留 system-logs、call-logs 详情与 settings/debug 的 ADMIN 规则，OpenAPI 类型重新生成。系统日志 ADR 因 main 已使用 0020、0021 改为 0022。调用日志集成测试的旧非法类型 AUDIO 改为 UNSUPPORTED，并明确验证 AUDIO 筛选返回 200，与音频扩展合约一致。

前端 TypeScript、主题色检查、ESLint、Vite 生产构建通过；定向 6 个文件、34 项测试通过（系统日志、调用日志/调试正文与设置开关、公共导航/路由）。画布包在合并音频后产生大于 500 kB 的构建提示，构建成功；本次未调整画布拆包。

后端合并后的定向检查命令：

```sh
./mvnw -Dtest=SystemLogBufferTest,SystemLogCaptureTest,SystemLogsControllerTest,DebugHttpCaptureTest,CallLogServiceTest -Dit.test=SystemLogsStartupPostgresIT,CallLogPostgresIT verify
```

后端编译与打包通过，Surefire 19 项单元测试及 Failsafe 10 项真实 PostgreSQL 集成测试全部通过，`git diff --check` 通过。全量测试、浏览器与真实 Provider 调用仍未运行。
