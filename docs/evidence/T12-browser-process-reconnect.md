# T12/T13 浏览器进程中断与 SSE 重连验收

2026-09-24。使用独立 `agenvas-sse-browser-e2e` Compose 项目、独立 PostgreSQL/Asset 卷及本机 18080/18088 端口，从当前源码构建并确认三个服务健康；原有 `agenvas` 项目未操作。浏览器在 1440×900 视口登录隔离管理员，创建横屏项目与 Creator Agent，页面显示“实时同步”。

保持项目页打开，执行 `docker compose -p agenvas-sse-browser-e2e -f deploy/compose.yaml stop server`。浏览器无需刷新即显示“正在恢复项目快照…”。随后执行同项目 `start server` 并确认服务健康；同一标签页自动恢复“实时同步”，没有重新登录或手动刷新。接着在该页启动 Mock 三镜头 Run：运行状态从 QUEUED 变为 RUNNING，文字、场景、三个镜头卡片陆续显示，随后“关键帧图片计划待确认”及三个精确输入版本自动出现。数据库核对仅有 3 个已完成的 `AGENT_TURN` Task，没有图片/视频 Task 提前提交。重启后服务日志未见 `AsyncContext` 或未捕获线程异常。

这证明浏览器在该受控进程中断后可恢复 SSE 并接收新的项目事件；没有证明服务停机期间仍有其他进程提交事件、超过 30 天保留期后的浏览器恢复，或高并发长时间连接稳定性。使用的是 Mock Agent，没有真实 LLM/ComfyUI。验收后仅清理隔离项目的容器、网络与两只测试卷；这些测试数据不可恢复。

最终检查：前端 `tsc --noEmit`、ESLint、Vitest（12 文件、37 测试）和 Vite build 均退出码 0，构建仍提示主 JS chunk 超过 500 kB；`git diff --check` 退出码 0。当前轮没有改 Java 或数据库迁移，后端完整 `./mvnw -q verify` 是上一轮通过，本轮未重跑。清理后 `docker compose ls` 只剩原有 `agenvas` 项目运行三个服务。
