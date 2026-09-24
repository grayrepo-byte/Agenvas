# T28 本机隔离 API 与事件可见延迟

2026-09-24。使用隔离 `agenvas-api-perf-e2e` Compose 三服务，Docker Desktop 4 vCPU/8,232,325,120 字节内存，宿主 Apple M2/16 GiB；Chrome 153 无头、1440×900、同机回环网络。后端为当前工作树 JAR，前端为当前 `dist`，装入此前已构建、含 FFmpeg/Nginx 的相同运行时基础镜像；默认 Mock LLM/媒体，无 GPU、外部模型或付费请求。普通 `docker compose up --build` 首次因 Maven 仓库读取超时失败，随后采用 `node frontend/e2e/api-performance-compose.mjs --local-artifacts` 从本地构建产物组装隔离运行镜像。运行器拒绝复用同名容器/卷，使用一次性凭据，测试后删除仅此隔离项目的容器、网络、卷及临时镜像；未触碰原有默认 Compose 实例。

可重复脚本在真实 Chrome 中先完成 M1 手工三镜头/绑定/刷新路径，再顺序测量：项目列表和项目快照各 10 次预热＋100 次取 JSON；30 次带运行前配置/提示词版本的 Run 创建受理（每次随后取消，取消不计入受理时间）；30 次场景新版本写入到对应画布卡片显示唯一标记。全部通过正式同源 API、会话/CSRF 与 SSE。分位数按升序第 `ceil(0.95*n)` 个样本取值，浏览器 `performance.now()` 计时；读数含回环网络及 JSON 解析，Run 计时到 202 响应 JSON，事件计时从写请求发出到 DOM 可见，因此最后一项比“事务提交→前端显示”更保守，但不单独量出服务端提交时刻。

| 场景 | 样本 | p50 | p95 | 最大 | 本机目标 |
|---|---:|---:|---:|---:|---:|
| 项目列表 | 100 | 3.4 ms | 4.4 ms | 12.1 ms | 300 ms |
| 项目快照（7 张业务卡片） | 100 | 6.3 ms | 7.8 ms | 10.8 ms | 300 ms |
| Run 受理 | 30 | 9.5 ms | 26.6 ms | 31.2 ms | 500 ms |
| 场景写入到画布可见 | 30 | 24.8 ms | 29.5 ms | 39.2 ms | 1 秒 |

首次同样的隔离实测发现事件可见 p95 约 1,026.7 ms，超过目标；当时 `ProjectEventHub` 每项目仅以 1 秒周期从数据库补发。修正后，业务事件写入事务内只发布不含 payload 的内存提示，`AFTER_COMMIT` 才唤醒共享单线程读取持久 `project_event`，同项目提示合并并限制单次连读；原 1 秒周期仍为进程重启、漏提示和跨实例的可靠补发路径。`ProjectEventHubTest` 证明提交提示在 500 ms 内触发数据库读取，`ProjectEventStreamPostgresIT` 证明原 SSE 路径仍可用。第二次隔离脚本退出 0，第三次记录上述完整数值。没有用降低阈值代替修复。

限制：仅是本机低并发/顺序样本，不等于高并发、长时间稳定性或生产 p95；快照只有 M1 的少量卡片，300 卡片容量另见 `T28-canvas-capacity.md`。尚未量测任务队列完成时长/重启恢复、长时间 SSE 连接/预览内存、跨机器网络或外部 Provider。T28 总门禁继续未完成。

回归：`node --check` 检查两个浏览器/部署脚本、`git diff --check`、定向 `./mvnw -q -Dtest=ProjectEventHubTest -Dit.test=ProjectEventStreamPostgresIT verify` 与最终 `./mvnw -q verify` 均退出 0；最终 Surefire/Failsafe XML 未检出失败或错误。前端应用代码本轮未变，未重跑前端单元测试；浏览器脚本在当前 `dist` 上完成实测。
