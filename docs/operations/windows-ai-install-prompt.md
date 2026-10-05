# 让 AI Agent 安装 Agenvas

把本文件发给 WorkBuddy 等能执行本机操作的 AI Agent，或复制下面的提示词发送。

## 安装提示词

请在我的 Windows 电脑上安装并启动 Agenvas，直接执行安装所需操作。需要我授权或重启时，告诉我下一步怎么做。

官方仓库：https://github.com/grayrepo-byte/Agenvas
部署文件：https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml

使用 Docker Desktop、Linux 容器和 Docker Compose v2，通过官方预构建镜像安装。无需 Git、Java、Node.js、GPU、`.env` 或模型 Key，也无需编译源码。

### 执行步骤

1. **检查环境和已有安装。** 检查 Windows 版本、处理器架构、Docker / Compose、WSL 和引擎状态。通过 `docker version` 确认服务端可连接，`docker info --format '{{.OSType}}'` 应返回 `linux`。命令必须在我的电脑上执行；只能聊天或在云端执行时，说明限制并指导我操作。
2. **补齐运行环境。** 缺少 Docker Desktop 或 WSL 时，按官方说明安装受支持的正式版本。软件协议、系统授权和重启由我处理，重启后继续检查并完成安装。
3. **准备部署文件。** 使用我选定的文件夹；未选定时使用用户目录下的 `agenvas` 文件夹。新安装下载官方部署文件，确认下载成功且不是 HTML 错误页。已有安装沿用原文件、项目名和数据；找不到原文件时让我定位，不覆盖。默认项目名为 `agenvas`，换文件夹不会隔离原数据。
4. **启动。** 检查 Web 端口，8088 被其他程序占用时选择空闲端口，只修改 Web 的宿主机端口。在安装目录先执行 `docker compose -f docker-compose.yml config --quiet`，通过后执行 `docker compose -f docker-compose.yml up -d --wait`，检查退出码。已有安装优先启动原容器；涉及更新或重建时先确认版本兼容、完成备份并征得我同意。
5. **验证。** 确认 `postgres`、`server`、`web` 三个服务均健康。本机访问实际端口的 `/setup` 和 `/api/v1/auth/setup-status`，后者须返回包含布尔字段 `setupRequired` 的 JSON。为 `true` 时让我在网页创建管理员，为 `false` 时打开 `/login`。失败时查看服务状态及最近日志，脱敏后诊断修复，再验证。

命令使用当前终端支持的语法，正确引用路径；原部署使用了 `-p` 时，所有 Compose 命令沿用同一项目名。

### 保留数据和凭据

保留已有数据库、素材、配置和密钥。禁止执行 `docker compose down -v`、删除存储卷、带卷清理的 `docker system prune` 或 Docker 恢复出厂设置；不停止其他项目，不关闭系统防火墙。

数据库密码和加密密钥由部署自动生成。旧库缺失密钥时保留现场，按官方恢复说明处理。管理员密码和模型 Key 由我在 Agenvas 网页填写，不要求我发到聊天中；日志仅输出必要的脱敏信息。

本次安装用于本机访问。默认 Web 监听所有网卡，请说明这一点并让我在受控网络完成初始化。无需端口转发或付费模型调用。

### 完成后告诉我

- 安装目录、项目名和浏览器地址。
- 实际验证结果，以及还需要我完成的操作；未验证项明确列出。
- 如何在 Docker Desktop 的 Containers 页面启动和停止 `agenvas`。
- 下一步在网页配置模型；服务启动不代表真实模型已接通。
- 更新或重装前备份数据库、素材和密钥，部署文件本身不包含作品备份。

### 按需查阅

- Windows 安装指南：https://github.com/grayrepo-byte/Agenvas/blob/main/docs/operations/windows-install.md
- Docker 安装：https://docs.docker.com/desktop/setup/install/windows-install/
- WSL 安装：https://learn.microsoft.com/zh-cn/windows/wsl/install
- 备份与恢复：https://github.com/grayrepo-byte/Agenvas/blob/main/docs/operations/backup-restore.md
