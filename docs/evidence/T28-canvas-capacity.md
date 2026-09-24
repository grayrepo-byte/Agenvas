# T28 画布 300 卡片/600 关系浏览器测量

2026-09-24。使用隔离 `agenvas-canvas-perf-e2e` Compose 项目，从当前工作树构建的 server/web 与 PostgreSQL 17.11，Docker Desktop 分配 4 vCPU、8,232,325,120 字节内存；宿主为 Apple M2、16 GiB 内存。Google Chrome 153.0.8010.48 无头模式，1440×900 视口，本机回环网络。仅使用 Mock 模式，无外部模型/GPU。

可重复入口：隔离实例健康后设置 `AGENVAS_E2E_URL`、`AGENVAS_E2E_BOOTSTRAP_SECRET`、`AGENVAS_E2E_CANVAS_CAPACITY=1`，运行 `node frontend/e2e/manual-storyboard-browser.mjs`。脚本先完成 M1 手工三镜头路径，然后离开画布，经真实鉴权 API 添加 1 场景、2 角色、40 个 IMAGE Artifact/卡片与 250 个 SHOT Artifact/卡片，分三批执行画布放置命令。40 张不同的 480×270 PNG 经正式上传、解码、归档与缩略图路径产生，原文件 124,506–149,726 字节，平均约 144,953 字节。连同 M1 的卡片，项目共 300 卡片：2 场景、4 角色、253 镜头、40 图片、1 Agent。250 个新镜头固定场景和角色精确版本，其中 95 个再固定第二角色；加上原有可见关系，项目快照与浏览器 DOM 均核对到 600 条关系，而非 600 个空白元素。

最终一次完整运行退出码 0，输出：`snapshot 102 ms; refresh 791/890/855/865/961/864 ms; drag 59.0 FPS, p95 frame 16.7 ms, >33 ms 1/179; DOM 300 nodes/600 edges`。40 张图片均由浏览器 `<img>` 完成解码加载。`snapshot` 是浏览器同源 `fetch` 到解析 JSON 的单次时间；`refresh` 是从导航/强制刷新到最后一张镜头卡片进入 DOM 的六次墙钟时间，均低于主规格的 2 秒刷新目标。拖动通过真实 Chrome 指针事件触发，采样约 3 秒内的 `requestAnimationFrame` 间隔，结束后经项目快照确认该卡片坐标已持久变化；平均采样 FPS 超过 30 的目标。

这证明此机器与本地网络上的一个代表性混合项目可达到所测容量和交互目标；不是不同硬件/浏览器的性能保证。`requestAnimationFrame` 是主线程帧间隔近似，不是逐帧 GPU 合成耗时；只有 40 张图片媒体卡片，没有视频媒体卡片或大尺寸视频预览。API p95、事件可见延迟、任务队列和长期内存泄漏仍需独立测量，T28 总门禁不因此完成。本轮未改生产 API、OpenAPI 或数据库迁移；脚本语法检查与 `git diff --check` 通过，后端/前端全量测试未因这份压测脚本重跑。验收后仅对 `agenvas-canvas-perf-e2e` 执行 `down -v`，删除三个测试容器、网络和两只隔离卷；测试数据不可恢复，原有 `agenvas` 项目未操作。
