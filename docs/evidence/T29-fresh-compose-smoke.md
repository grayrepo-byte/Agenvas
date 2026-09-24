# T29 隔离空卷 Compose 启动与停机演练

2026-09-24 在本机 Docker 环境使用当前工作树构建三服务镜像。演练项目为 `agenvas-t29-fresh-20260924`，使用独立的新建网络、PostgreSQL 卷、资产卷和本机端口 18084/18085；没有操作已有的默认 `agenvas` Compose 项目。Mock 模式不需要 LLM Key、ComfyUI 或作者账户。

步骤与实际结果：

1. `docker compose -p agenvas-t29-fresh-20260924 -f deploy/compose.yaml up -d --build` 返回 0，PostgreSQL、server、web 均达到 healthy。构建阶段后端单元测试通过；独立的完整后端 `./mvnw -q verify` 已见 `T29-shutdown-admission.md`。
2. 经 web 反代请求 `/api/v1/auth/setup-status` 得到 `200 {"setupRequired":true}`。获取 CSRF token 后，以演练用 bootstrap secret 创建管理员返回 201；登录返回 200，持会话 cookie 的 `/api/v1/auth/me` 返回 200 且身份为 `ADMIN`。
3. `docker compose ... stop server` 返回 0。server 容器日志出现 `Shutdown gate closed: new Runs and Task claims disabled` 和 Hikari 关闭完成；命令在本机测得小于 1 秒完成，不能据此推断有活跃任务时的停机耗时。
4. `docker compose ... start server` 后，server 再次 healthy；原会话 cookie 的 `/api/v1/auth/me` 返回 200、管理员 ID 不变，`setup-status` 为 `false`；三服务均 healthy。紧接启动时的首个请求曾收到 502，健康后重试成功，这是服务尚未就绪期间的预期窗口。

这证明当前镜像在本机全新隔离卷的初始化、登录、优雅停机及会话恢复链路；**不是**全新机器按 README 独立安装、真实 Provider 活跃请求停机或生产容量/RPO/RTO 验收。演练用本地口令未用于其他系统。记录后以精确项目名 `down -v` 删除演练容器、网络和两卷，并删除本次构建的 server/web 镜像与临时会话文件；原有 `agenvas` 项目仍为 `running(3)`。
