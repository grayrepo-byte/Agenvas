# Windows 安装指南

将 [安装提示词](windows-ai-install-prompt.md)发给 WorkBuddy 等能执行本机操作的 AI Agent，让它安装并启动 Agenvas。自行安装按下方步骤操作。

## 安装前准备

- 一台满足 [Docker Desktop 官方系统要求](https://docs.docker.com/desktop/setup/install/windows-install/#system-requirements)的 Windows 电脑，以及可用的网络连接。系统要求以官网为准。
- 一个固定保存安装文件的文件夹，例如 `D:\agenvas`；只有 C 盘也可以使用 `C:\agenvas`。下文路径只是示例，请使用你实际创建的文件夹。
- 首次安装需要下载 Docker Desktop 和应用镜像，预留下载时间及磁盘空间。

Docker Desktop 是让 Agenvas 运行的软件环境；WSL 2 是它在 Windows 上运行 Linux 容器的基础。你仍然在 Windows 中操作。Agenvas 使用 Linux 容器。

## 让 AI Agent 安装

1. 打开 WorkBuddy 或其他 AI Agent，选择本机工作文件夹。
2. 将 [安装提示词](windows-ai-install-prompt.md)作为附件发送，或复制其中的提示词，要求它执行安装。
3. 按提示处理系统授权或重启；安装完成后，打开 Agent 给出的地址，在网页里创建账号。

## 手动安装

### 1. 安装并打开 Docker Desktop

1. 用浏览器打开 [Docker Desktop 的 Windows 官方安装页面](https://docs.docker.com/desktop/setup/install/windows-install/)，选择与你电脑处理器相符的安装包。常见 Intel / AMD 电脑使用 `x86_64`；如不确定，在 Windows“设置 → 系统 → 关于”中查看系统类型，并对照官方要求选择正式支持的版本。
2. 下载后双击安装包。如出现后端选项，选择 **Use WSL 2 instead of Hyper-V**；其余按安装向导处理。
3. 安装完成后，从 Windows 开始菜单打开 **Docker Desktop**，阅读并自行接受适用的软件协议，按屏幕提示完成初始化。
4. 如提示需要安装 / 更新 WSL 或重启，按提示完成后再打开 Docker Desktop。等待引擎显示运行状态，再继续。[Docker 官方 WSL 说明](https://docs.docker.com/desktop/features/wsl/)介绍了相关配置；仅使用 Docker 时无需另装 Ubuntu 来执行本指南的命令。

如果提示硬件虚拟化未启用，参照电脑厂商说明或请熟悉电脑设置的人帮助处理。不要在不清楚用途时随意修改 BIOS / UEFI。

### 2. 创建安装文件夹，下载部署文件

1. 按 `Win + E` 打开文件资源管理器，进入你准备存放安装文件的位置，例如 D 盘。
2. 右键空白处，选择“新建 → 文件夹”，命名为 `agenvas`，双击打开。以后保留这个文件夹，用于启动、排查和更新。
3. 用浏览器打开 [docker-compose.yml 下载地址](https://raw.githubusercontent.com/grayrepo-byte/Agenvas/main/docker-compose.yml)。看到一页文字是正常的，这就是部署文件。
4. 按 `Ctrl + S`，把保存位置选到刚创建的文件夹。文件名填写 `docker-compose.yml`，保存类型选择“所有文件”（如果有该选项）。
5. 在资源管理器中显示文件扩展名，确认完整文件名是 `docker-compose.yml`。Windows 11 通常在“查看 → 显示 → 文件扩展名”；Windows 10 通常在“查看”中勾选“文件扩展名”。如果多出 `.txt`，将文件重命名为正确名称。

已有安装时使用原来的部署文件，保留原有配置。不要为了重装而删除 Docker 中的存储卷。

### 3. 复制一行命令，启动 Agenvas

1. 保持资源管理器打开在含有 `docker-compose.yml` 的文件夹。
2. 点击上方显示文件夹路径的**地址栏**（不是搜索框），输入 `cmd`，按回车。这会在正确的文件夹中打开“命令提示符”，无需自己输入切换目录的命令。
3. 只复制下面这一行，粘贴到命令窗口，按回车：

```cmd
docker compose up -d --wait
```

这一行的含义：

| 部分 | 作用 | 是否需要修改 |
| --- | --- | --- |
| `docker compose` | 读取当前文件夹里的部署文件 | 不需要 |
| `up` | 下载所需镜像并创建、启动服务 | 不需要 |
| `-d` | 让服务在后台运行 | 不需要 |
| `--wait` | 等待服务通过健康检查 | 不需要 |

首次启动会下载镜像，时间取决于网络。命令成功结束、重新出现可输入内容的提示符后，可以关闭该窗口。出现红色报错或 `unhealthy` 时，按下方“常见问题”处理。

不要把其他教程里的多行命令、`^`、反引号或 `&&` 拼进这一行。这里通过资源管理器直接打开 `cmd`，避免不同终端的语法差异。

### 4. 打开网页，创建管理员

1. 打开浏览器，在**地址栏**输入 [http://127.0.0.1:8088/setup](http://127.0.0.1:8088/setup)。这是你这台电脑上的 Agenvas 地址。
2. 在网页中填写管理员登录名和密码，保存自己的登录信息，创建后登录。
3. 已经创建过管理员时，使用 [登录页面](http://127.0.0.1:8088/login)，无需重新初始化。

数据库密码和用于保护模型 Key 的加密密钥会自动生成并保存，无需复制或手动设置。默认部署还允许同一网络中的设备通过电脑 IP 访问；先在受控网络创建管理员，本机使用无需配置路由器端口转发。

### 5. 配置模型，开始使用

- 文字和 Agent：进入“设置 → 模型配置”，填写模型服务提供的端点、模型 ID 和 API Key；使用 Agent 前运行工具调用诊断。
- 图片、视频和音频：在“媒体配置”中创建连接、发布能力并设置默认模型。
- 创建项目，添加卡片或上传素材，选择模型后开始创作。模型调用可能收费，运行前检查预计费用。

网页能打开代表安装已启动；真实生成能力需要另外完成模型配置。安装过程不会替你调用付费模型。

## 以后如何启动、停止和查看问题

在 Docker Desktop 的 **Containers（容器）** 页面找到 `agenvas` 项目，可以展开查看 `postgres`、`server`、`web` 三个服务。[Docker 官方容器界面说明](https://docs.docker.com/desktop/use-desktop/container/)介绍了项目操作和日志入口。

| 你想做什么 | 操作 |
| --- | --- |
| 平时打开 Agenvas | 打开 Docker Desktop，等待引擎运行；若项目未运行，在 `agenvas` 项目行点击 Start / 启动，再打开浏览器书签 |
| 暂时停止，保留作品 | 在 `agenvas` 项目行点击 Stop / 停止 |
| 查看启动错误 | 展开项目，点出问题的服务，再点 Logs / 日志；分享给 AI 前去掉可能出现的个人信息和凭据 |
| 找不到 `agenvas` 项目 | 回到安装文件夹，通过地址栏打开 `cmd`，重新执行上面的启动命令 |

Docker Desktop 需保持运行。仅关闭其界面窗口和从托盘退出 Docker Desktop 是不同操作；退出引擎后，Agenvas 将无法访问。

作品、素材和密钥主要保存在 Docker 存储卷中，**只备份 `agenvas` 文件夹不够**。更新、迁移电脑或重装 Docker 前，按 [备份与恢复](backup-restore.md)备份。不要删除 Volumes / 存储卷、执行 `docker compose down -v`，或通过恢复出厂设置解决启动错误。

## 常见问题

| 现象 | 下一步 |
| --- | --- |
| “docker 不是内部或外部命令” | 确认已安装 Docker Desktop；安装后关闭旧命令窗口，从安装文件夹重新打开 `cmd`，必要时按安装提示重启 |
| 无法连接 Docker / engine 未运行 | 从开始菜单打开 Docker Desktop，先处理它提示的 WSL、虚拟化或重启问题 |
| WSL 版本过旧 / 未安装 | 按 Docker 提示及 [微软 WSL 安装说明](https://learn.microsoft.com/zh-cn/windows/wsl/install)操作；如需管理员权限，由你本人授权，也可让安装 AI 协助 |
| `no configuration file provided` | 确认当前文件夹中有 `docker-compose.yml` 且没有 `.txt` 后缀，然后从该文件夹的地址栏重新打开 `cmd` |
| 部署文件下载失败 | 检查浏览器能否访问下载地址；也可在 [GitHub 文件页面](https://github.com/grayrepo-byte/Agenvas/blob/main/docker-compose.yml)使用原始文件下载功能，保存后核对文件名 |
| 镜像下载超时或 `manifest unknown` | 检查 Docker Hub 网络或镜像发布情况；把错误交给安装 AI 排查，保留原文件和数据 |
| 提示 8088 端口被占用 | 用记事本打开部署文件，将唯一的 `"0.0.0.0:8088:8080"` 改为 `"0.0.0.0:8089:8080"`，保存后重新执行启动命令；浏览器地址也改为 `http://127.0.0.1:8089/setup`，如 8089 仍占用则请 AI 协助选择可用端口 |
| `unhealthy` 或网页一直打不开 | 在 Docker Desktop 查看对应服务的 Logs；请安装 AI 检查三个服务的健康状态，不要通过删库、删卷重装处理 |
| 已创建过管理员但忘记密码 | 保留数据，向项目维护者咨询账号恢复方式；当前指南未提供密码重置步骤，不要通过删库重新初始化 |

如请 AI 帮助，说明安装文件夹、正在进行的步骤和错误现象。无需发送密码、API Key、完整环境变量或凭据文件。
