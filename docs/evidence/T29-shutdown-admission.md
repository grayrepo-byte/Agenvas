# T29 关闭时停止新 Run 与 Task 认领

`ShutdownGate` 在 Spring `ContextClosedEvent` 到来时设置关闭状态，并与短暂的 Run 写入及 Task 认领操作按读写锁排序。关闭后，新 Run 的服务/API 命令返回 `503 APPLICATION_STOPPING`，不会写入 Run；Agent 回合、Mock/ComfyUI 图片和视频、已受理 Provider 轮询、项目导出的全部认领入口均返回空。已有工作仍由各自的持久状态、租约与 fencing epoch 决定，不在关闭钩子里批量改成 READY。已经开始的工作允许完成正常的短事务或等待后续进程恢复。

`ShutdownGatePostgresIT` 在真实 PostgreSQL 中创建 Run 与 READY 图片 Task，模拟关闭事件，验证服务/API 503、十个认领入口均不取任务、Run 数量和 Task/attempt 状态不变。`ShutdownGateTest` 以并发短认领测试关闭事件等待已开始的认领结束，随后不执行新认领。OpenAPI 的创建 Run 响应新增 503，生成的 TS 类型已同步。没有迁移。

`ShutdownSignalPostgresIT` 进一步启动当前打包 JAR 的独立 JVM，使用隔离的 PostgreSQL Testcontainer，而非开发者的 Compose 数据库；确认应用完成启动后，向该子进程发送 SIGTERM。测试等待进程在 45 秒内退出，核对真实关闭事件写出的无 ID 日志标记，并确认原 READY 图片 Task、Run 数量和空 Provider attempt 未被停机钩子重写。子进程日志为测试临时文件，结束时删除。

检查：`cd backend && ./mvnw -q -Dit.test=ShutdownGatePostgresIT verify` 与完整 `./mvnw -q verify` 退出码均为 0；对应 Surefire/Failsafe 报告各 1 test、0 failures/errors，完整报告未见失败或错误。`frontend` 的 OpenAPI TS 生成、typecheck、ESLint、19 文件/71 项 Vitest、Vite build 均返回 0；`git diff --check` 通过。

定向 `cd backend && ./mvnw -q -Dit.test=ShutdownSignalPostgresIT verify` 退出码 0，Failsafe 报告 1 test、0 failures/errors。新增 SIGTERM 测试后再次运行完整 `cd backend && ./mvnw -q verify`，退出码 0；Surefire/Failsafe 报告未见失败或错误，`git diff --check` 通过。

这仍不等于已完成 Compose 容器停机、网络请求交错或活跃外部提交的全部时序演练；在关闭信号之前已经被认领、正在执行的外部提交仍须依赖提交检查点、租约和 UNKNOWN 核对。
