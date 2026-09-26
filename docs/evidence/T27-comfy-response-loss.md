# T27 场景 3：ComfyUI 请求已接收但响应丢失

> 2026-09-26 注：本文最后“核对并恢复原请求”的一步已被移除；该任务现在保持 UNKNOWN，由用户在卡片上显式重试。前面“再次运行提交 worker 后提交次数不变、attempt 仍只有一条”的结论不受影响。

`ComfyUiImagePostgresIT` 使用 PostgreSQL 17.11 Testcontainers 和本地假 ComfyUI HTTP 服务。计划审批后，服务接收并记录第三笔 `/prompt` 的 `prompt_id`，随即关闭连接，不向应用返回响应。工作线程因此不能保存 `providerRequestId`，但事先提交的 `provider_attempt.candidateRequestId` 与假服务收到的 ID 相同。测试强制租约到期并运行恢复扫描，确认 Task 与 attempt 均进入 `UNKNOWN`；再次运行提交 worker 后，假服务收到的提交次数不变，attempt 仍只有一条。

检查：`cd backend && ./mvnw -q -Dit.test=ComfyUiImagePostgresIT verify`，Failsafe 报告 `tests=1, errors=0, failures=0`；修改后 `cd backend && ./mvnw -q verify` 退出码 0，Surefire/Failsafe XML 未见失败或错误；`git diff --check` 通过。这个测试覆盖真实 HTTP 响应丢失和持久数据库恢复，但没有在该窗口杀死 JVM；独立的进程中断与重启演练见 `T13-process-kill-smoke.md`，两者不能合称一次完整的“外部已接收时杀进程”端到端实验。未连接真实 ComfyUI。
