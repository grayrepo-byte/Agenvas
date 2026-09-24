# T12 浏览器断线与过期游标恢复

2026-09-24。对应主规格 §22.2 场景 7。隔离 `agenvas-sse-expiry-e2e` Compose 项目使用独立 PostgreSQL/资产卷与本机 18381/18389 端口，从当前工作树构建 server/web。首次 Docker 构建因 Maven Central TLS 握手中断，原命令重试成功，三个服务健康。真实无头 Chrome 在该隔离项目登录并通过 M1 三镜头手工路径。

显式设置 `AGENVAS_E2E_SSE_EXPIRY=1` 和 `AGENVAS_E2E_COMPOSE_PROJECT=agenvas-sse-expiry-e2e` 后，`frontend/e2e/manual-storyboard-browser.mjs` 仅对该隔离项目执行故障注入：通过浏览器网络控制切断 SSE 与快照请求，用正常鉴权 API 从 React 外部创建场景 v3；仅在隔离 PostgreSQL 中删除该项目截至 v3 的旧事件，再创建 v4。页面在断线时仍未显示 v4。放通 SSE 后，以旧游标发起真实认证 HTTP 请求，得到 `409 EVENT_CURSOR_EXPIRED`；放通快照后，未刷新页面即显示 v4 和“实时同步”。随后再从 React 外部创建 v5，页面通过恢复后的直播事件自动显示 v5。最终输出 `T12 browser smoke passed: stale cursor 409, v4 snapshot and v5 live event`，进程退出码 0；脚本 `node --check` 通过。

浏览器应用在网络错误后会主动关闭旧 EventSource 并尝试一致性快照，因此不能声称它自身的旧连接一定收到了该 409；409 是同一登录浏览器发起的显式旧游标请求。验收证明断线、服务端已清理旧事件、快照恢复及后续直播更新的跨层结果。没有等待真实 30 天保留期，也没有长时间网络抖动或多用户并发压测。测试后仅对 `agenvas-sse-expiry-e2e` 执行 `down -v`，删除了三个测试容器、网络与两只隔离卷；测试数据不可恢复，原有 `agenvas` 项目未操作。无真实 Provider 调用。
