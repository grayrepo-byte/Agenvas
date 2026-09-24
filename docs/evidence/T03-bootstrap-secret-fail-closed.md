# T03 初始化密钥与数据库密码不再使用公开回退值

旧 Compose 配置在未读取/设置 `.env` 时回退到仓库中已知的数据库密码及管理员 bootstrap secret。当前本机 `docker compose -f deploy/compose.yaml` 不会自动读取仓库根目录 `.env`，使原 README 的隐式加载假设不成立。新配置对两个值使用 Compose [必填且非空插值](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)；`.env.example` 留空并要求用户填写。README、英文说明与恢复操作命令统一显式 `--env-file .env`。后端即使脱离 Compose 启动，也拒绝历史公开的 bootstrap secret 和数据库密码示例值，错误不回显传入值。该改动不会自动轮换已经运行的实例。

验证：缺少任一变量时 `docker compose ... config --quiet` 返回非零；显式设置两个测试值时返回 0，直接传入留空的 `.env.example` 也被拒绝。临时根目录 `.env` 的只读配置试验确认，不带 `--env-file` 仍拒绝，而加上该参数后 PostgreSQL 与 server 收到预期测试值；临时 `.env` 已删除。`IdentityPropertiesTest` 对两个公开示例值断言拒绝且错误不回显，`DatabasePasswordGuardTest` 对两种历史公开数据库密码做同样断言；`IdentityPostgresIT` 验证使用非示例测试密钥的新库初始化与 20 路并发仲裁。两项单元测试、定向 PostgreSQL 测试及最终代码的完整 `cd backend && ./mvnw -q verify` 均退出 0，最终 Surefire/Failsafe 报告未见失败或错误，`git diff --check` 通过。CI `compose-config` 作业新增缺值拒绝检查，工作流 YAML 本地解析成功；GitHub 托管运行未在本地验证。

只读检查当前本机默认 `agenvas` 容器的环境得到 `publicBootstrapDefault=true`、`publicDatabaseDefault=true`。该实例未被修改或重启；需要确认维护窗口后同步轮换 PostgreSQL 账户密码与 server 配置，不能只改 `.env`。生产 HTTPS/公网入口、已有卷迁移及凭据轮换后的完整恢复仍未验收。
