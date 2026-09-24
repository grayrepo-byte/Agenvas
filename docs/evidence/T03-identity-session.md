# T03 身份与会话证据

任务编号：T03  
变更行为：一次性管理员初始化、JSON 登录、JDBC Session、CSRF、退出、改密与其他会话失效、认证失败限流。  
合约/迁移影响：`contracts/openapi.yaml` 增加身份接口；Flyway V2 增加密码版本字段，V3 增加安装级初始化锁。  
执行环境：macOS / Java 21.0.9 / Docker Desktop / PostgreSQL 17.11 / Spring Boot 4.0.8。  
实际运行的测试命令：`backend/./mvnw verify`、`docker compose -f deploy/compose.yaml up -d --build`、经 Nginx 的 curl 身份流程。  
测试结果：8 个单元测试和 1 个 Testcontainers PostgreSQL 集成测试通过；20 路初始化竞态只创建一个管理员。Compose 实测初始化 201、登录 200、重启后 `/auth/me` 200、改密 204、旧会话 401、退出后 401、缺少 CSRF 403、重复初始化 409。  
真实 Provider：未调用；本任务不涉及模型或媒体 Provider。  
安全/成本影响：bootstrap secret 仅通过请求头单次提交；密码使用 Spring Security 版本化哈希；浏览器不持久化 secret；无外部费用。  
未验证项：公网 HTTPS 与反向代理部署、长时暴力测试属于 M6；认证限流为单实例内存窗口，符合当前单 server 部署边界。
