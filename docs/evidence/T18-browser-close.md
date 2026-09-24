# T18：关闭浏览器后的后台 Run

2026-09-24。新增可重复的 `frontend/e2e/background-run-browser-close.mjs`，要求独立 Compose 项目、空闲本地端口和测试 bootstrap secret。脚本用真实 Chrome 通过会话/CSRF API 创建管理员、项目、Agent 和 Mock Run，确认创建响应为 `QUEUED`；随后关闭 CDP 连接并等待 Chrome 进程退出，才通过隔离 PostgreSQL 只读查询观察后台结果。整个等待期间没有保留浏览器会话或前端 SSE 连接。

当前源码 `agenvas-t18-browser-20260924` 隔离 Compose（三服务均 healthy）上，Google Chrome 153.0.8010.48 发起的 Run `faa8340f-e210-4062-af44-a67d99e56645` 在浏览器退出后到达 `WAITING_APPROVAL`，共有 3 个成功的 `AGENT_TURN` Task、0 个未成功的回合 Task，且只持久化 1 份执行计划。第一次脚本运行已见同样的后端推进，但最后误将 Mock 回合数断言为 1 而失败；修正为核对“至少一回合、没有未完成回合”后重跑退出码 0。没有进行图片批准、视频生成或真实模型调用。

演练结束执行精确项目名的 `docker compose ... down -v`，只删除该隔离项目的三个测试容器及其 PostgreSQL/资产测试卷；默认 `agenvas` 三服务复查仍 healthy。这个单独的浏览器关闭用例证明浏览器离开不阻止已入库的 Mock 规划到达人工审批；它本身不证明服务进程崩溃恢复、长时间断网或真实 Provider 后台完成。无 OpenAPI、Flyway 或生成类型变化。

## 跨进程账本收尾补验

同一脚本增加可选 `AGENVAS_E2E_RESTART_RECOVERY=true`。在另一套隔离 Compose `agenvas-t18-restart-20260924` 上，Chrome 关闭后 Run `c7ef0b18-1623-4d8f-8bf9-7ab2da63734d` 已到 `WAITING_APPROVAL`、3 个模型回合已成功。脚本先记录最后回合的模型响应摘要、工具账本数、计划数、产物版本数和租约 epoch，再停止服务容器；仅在这个隔离数据库中把已完成的最后一个 `AGENT_TURN` Task 置回租约已过期的 `RUNNING`，构造“副作用和响应已持久化、任务完成标记尚未提交”的崩溃窗口。随后启动新服务进程，不保留 Chrome 连接。

新进程接管后，原 Task 再次为 `SUCCEEDED`、租约 epoch 严格递增，Run 仍为 `WAITING_APPROVAL`；模型响应摘要、工具执行条数、执行计划条数和 ArtifactVersion 条数均与重启前相同。脚本退出码 0，三服务复查 healthy。该注入覆盖一个关键持久化收尾窗口，证明此窗口不会从原始指令重建第二份计划/产物；没有在模型 HTTP 调用的随机时刻强杀进程，也不能替代真实 Provider 的提交核对。演练后只删除这套隔离项目的容器及数据库/资产测试卷；默认 `agenvas` 不受影响。
