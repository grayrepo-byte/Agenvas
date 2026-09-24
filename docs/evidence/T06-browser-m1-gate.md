# M1 手工三镜头浏览器验收

日期：2026-09-24。使用隔离的 `agenvas-m1-e2e` Compose 项目（独立 PostgreSQL/Asset 卷，本机 18081/18089 端口），从当前工作树构建并确认 postgres/server/web 健康。真实 Google Chrome 153.0.8010.48 以无头模式、1440×900 视口运行；`frontend/e2e/manual-storyboard-browser.mjs` 通过 Chrome DevTools 协议驱动页面。环境使用 Mock LLM/媒体，不调用外部模型或 ComfyUI。未使用已有 `agenvas` 项目数据库。

浏览器实际完成初始化、登录、创建横屏项目、创建一张场景与一名角色、创建按 1/2/3 排列的三个镜头。每个镜头经认证项目快照核对：`sceneVersionId` 均固定到场景 v1，`characterVersionIds` 均包含角色 v1。五张初始卡片均在 React Flow 中为可见状态。随后还创建第二名角色和一张无输入 Agent 卡片，用真实指针手势将角色连到第一个镜头、将原角色连到 Agent；快照中镜头新版本与 Agent 精确版本绑定落库，语义边从 6 条增至 7 条、蓝色输入边使总数增至 8 条。真实鼠标拖动场景卡片后，快照中布局坐标改变；通过卡片编辑器把场景地点改为 `Revised studio` 后显示 v2。刷新浏览器后，卡片、v2 内容、两条手工连线的业务关系与拖动坐标恢复，三个镜头仍引用旧场景 v1；画布仅显示可见当前版本的 5 条边，不把历史场景 v1 误连到场景 v2。项目没有活动 Run 或 Task。该路径满足 M1“不依赖真实 AI，手工完整操作三镜头项目并刷新恢复”的门禁。

复验命令：在隔离实例健康后，设置 `AGENVAS_E2E_URL` 与 `AGENVAS_E2E_BOOTSTRAP_SECRET`，运行 `node frontend/e2e/manual-storyboard-browser.mjs`。最新输出 `M1 browser smoke passed: three shots, pointer connections, drag, revision and reload`，退出码 0。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，19 个文件/69 项测试通过；本轮没有修改后端、OpenAPI 或 Flyway，后端本轮未重跑。

验收后仅对 `agenvas-m1-e2e` 执行 Compose `down -v`，删除其三个临时容器、网络和两只测试卷；原有 `agenvas` 项目的 postgres/server/web 再次核对均为 healthy。隔离测试数据随测试卷删除，不可恢复。

范围限制：这是 Mock 手工项目的浏览器黄金路径，不覆盖浏览器断线重试、300 节点性能、真实 Provider、媒体审批/局部重做/导出或发布门禁。对应能力需各自证据，不能从本用例推断。
