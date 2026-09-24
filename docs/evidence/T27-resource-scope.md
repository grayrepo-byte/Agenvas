# T27 故障场景 13：资源 ID 越权

2026-09-24。对应主规格 §22.2 场景 13。`ResourceScopePostgresIT` 在 PostgreSQL 17.11 上建立一个有私有正文的项目、IMAGE Asset、Agent Run 和另一个同所有者项目，经 Spring Security、Controller 与真实应用服务的 HTTP 路径核对：合法所有者能读到目标 Artifact/Asset/Run；合成外来身份以目标项目及已知精确 ID 请求 Artifact 当前版/版本历史、Asset 元数据/原文件/缩略图、Run 详情和 SSE，均返回 404 且响应不含私有正文、标题或存储键；匿名请求均返回 401。把目标 Artifact/Asset/Run ID 放到同一所有者的另一项目路径下也返回 404。另一项目的 SSE 对该所有者本来合法，故不把它误判为越权。

定向命令 `./mvnw -q -Dit.test=ResourceScopePostgresIT verify` 与后端全量 `./mvnw -q verify` 均退出码 0，Surefire/Failsafe XML 报告未发现失败或错误，`git diff --check` 无输出。该测试使用 Spring Security MockMvc 合成外来主体，以隔离资源归属校验；不代表系统开放第二个管理员账号，也不是浏览器 Cookie/反向代理测试。真实网络 SSE 的有效订阅与游标过期另由 `ProjectEventStreamPostgresIT` 覆盖。未新增生产 API、合约或迁移，前端检查未重跑；T27 全部安全/故障门禁仍需分别验收。
