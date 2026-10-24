# 连接 Agenvas MCP

MCP 是 Agenvas 的附加创作入口。外部客户端负责自己的对话和推理，Agenvas 提供项目、画布、内容及媒体任务工具。现有内置导演、聊天、Skill、审批和结果续接继续使用原来的流程。

## 服务地址与传输

使用 **Streamable HTTP**，地址为部署站点的精确 `/mcp` 路径。例如，本机默认 Web 入口是 `http://localhost:8088/mcp`，公网入口应为 `https://agenvas.example.com/mcp`。不要添加尾斜杠，也不要使用 `/api/v1/mcp` 或旧 HTTP+SSE 的独立 SSE 地址。

客户端必须能访问该地址，并能在每次协议请求上发送 `Authorization: Bearer ...`。网页 Cookie 登录状态不能代替 MCP 令牌；MCP 令牌也不能调用管理账号、Provider 或令牌的 REST API。本版采用手工 Bearer 接入，没有 OAuth 授权服务器、动态客户端注册或登录发现；只接受 OAuth 连接方式的客户端不在本版接入范围。

协议适配使用 Spring AI 2.0.1 管理的官方 Java SDK 2.0.0，采用无状态 Streamable HTTP，最高协商版本为 `2025-11-25`。本版不提供旧 HTTP+SSE 传输，也未实现 `2026-07-28` 新传输。每个请求重新验证凭据；不依赖服务端 MCP 会话保留权限。

## 创建用户令牌

1. 登录 Agenvas，打开 **系统设置 → 账户安全 → 外部 Agent / MCP 访问令牌**。
2. 为客户端填写容易识别的名称，选择权限和有效期，点击“创建访问令牌”。有效期默认 30 天，允许 1–90 天。
3. 将本次显示的完整令牌保存到客户端的安全凭据设置，然后点击“我已保存，隐藏令牌”。完整令牌不会再次显示，列表只保留显示提示和元数据。

| 权限 | 能力 |
| --- | --- |
| `READ`，必选 | 读取当前用户有权访问的项目、画布、内容、已发布能力和任务结果 |
| `CONTENT_WRITE`，可选 | 创建或修改文字、调整节点、创建媒体节点和保存草稿 |
| `MEDIA_GENERATE`，可选 | 运行已有媒体草稿，直接受理生成任务 |

令牌属于用户，不属于某个项目。每次工具调用仍校验当前用户、项目权限及对象归属。创建草稿和生成媒体分别需要对应权限；只有 `CONTENT_WRITE` 不能生成，只有 `READ + MEDIA_GENERATE` 不能修改草稿。

授予 `MEDIA_GENERATE` 表示用户允许外部客户端直接提交媒体生成任务，**无需逐次收费审批**。已发布能力、输入校验、并发占用和用量约束仍生效。这不改变内置导演的媒体审批规则。

令牌到期或撤销后不能继续调用。撤销不会取消已经受理的 Task，也不会删除历史产物。若创建响应丢失、关闭展示前未保存或怀疑泄漏，在列表撤销该令牌后创建新令牌；不能通过查询找回原明文。

## 客户端配置模板

以下是支持 `mcpServers`、HTTP、自定义请求头和环境变量展开的客户端配置示例。具体字段及变量展开方式以客户端文档为准；环境变量不是所有 MCP 客户端共有的协议功能。Claude Code 对 `.mcp.json` 中 HTTP 地址和请求头的环境变量引用有[官方说明](https://code.claude.com/docs/en/mcp#environment-variable-expansion-in-mcpjson)。

在客户端进程的安全环境中设置 `AGENVAS_MCP_URL` 和 `AGENVAS_MCP_TOKEN`，配置文件只保留变量引用：

```json
{
  "mcpServers": {
    "agenvas": {
      "type": "http",
      "url": "${AGENVAS_MCP_URL}",
      "headers": {
        "Authorization": "Bearer ${AGENVAS_MCP_TOKEN}"
      }
    }
  }
}
```

`AGENVAS_MCP_URL` 是完整 `/mcp` 地址；`AGENVAS_MCP_TOKEN` 是本次创建的用户令牌。客户端重启或重连时也需要取得这些变量。不要把实际令牌填入 JSON、URL、命令历史、Prompt、日志或 Git，不要启用会打印认证头的调试输出。若客户端支持安全凭据存储，可使用其官方凭据引用功能替代环境变量。

安全页面的命令示例使用单引号保留 `${AGENVAS_MCP_TOKEN}` 字面引用，避免 shell 提前展开并把完整令牌写进配置。运行前确认所用客户端版本支持该引用方式；缺少变量时先修复环境，再重连，不能用把令牌写到 URL 中的方式绕过认证。

连接后先执行工具发现，再调用 `list_projects`。工具列表按令牌权限过滤；服务器在执行时还会重新鉴权。客户端自己的工具确认或工作区信任提示由客户端控制，Agenvas 不增加每次媒体调用的收费审批。

## 当前 13 个工具

工具输入、输出的权威 Schema 是 [`contracts/mcp/tools.json`](../contracts/mcp/tools.json)，运行时通过 MCP 工具发现获取。除 `list_projects` 外均必填 `projectId`；所有写工具都必填稳定的 `commandId`。

| 工具 | 权限 | 用途与关键输入 |
| --- | --- | --- |
| `list_projects` | `READ` | 发现本人项目；支持 `cursor`、`limit`、`includeArchived` 和名称过滤 |
| `read_project_summary` | `READ` | 读取项目名称、画幅、状态和内容数量 |
| `read_canvas` | `READ` | 分页读取节点、布局版本、内容版本引用和媒体草稿；完整草稿设置 `includeDrafts: true` |
| `read_artifacts` | `READ` | 按 `versionIds` 读取精确内容版本；图片附受控预览，视频和音频返回元数据 |
| `create_text` | `CONTENT_WRITE` | 原子创建文字产物与画布节点；输入 `title`、`text`，可选 `format` 和 `placement` |
| `revise_artifact` | `CONTENT_WRITE` | 为文字追加不可变版本；输入 `artifactId`、当前 `expectedVersion` 和 `text` |
| `manage_canvas_node` | `CONTENT_WRITE` | `RENAME`、`MOVE`、`RESIZE`；输入节点 ID、当前节点 `expectedVersion` 及动作字段 |
| `create_media_node` | `CONTENT_WRITE` | 创建 `IMAGE`、`VIDEO` 或 `AUDIO` 空节点与初始草稿；不会开始生成 |
| `update_media_draft` | `CONTENT_WRITE` | 输入 `canvasItemId`、当前 `expectedDraftVersion` 和 `patch`，保存提示词、能力、参数及精确素材引用 |
| `list_media_capabilities` | `READ` | 按 `kind` 和分页发现已发布能力及支持参数；不返回 Provider 地址、密钥或原始工作流 |
| `run_media` | `MEDIA_GENERATE` | 按节点和当前草稿版本直接受理 Task，返回本次受理的全部 Task |
| `compose_video` | `MEDIA_GENERATE` | 根据分镜/台词匹配有序视频与配音精确版本，后台利用声音活动窗口自动排布并本地合成；返回 Task |
| `read_task_status` | `READ` | 按 `taskIds` 查询同项目任务及精确结果版本；不启动生成 |

不要猜测 ID、能力参数或版本号。先读取当前项目，再使用返回的 ID 和 CAS 版本。文字内容使用产物的 `expectedVersion`，布局使用节点的 `expectedVersion`，媒体草稿使用 `mediaDraft.version`；三者是独立的并发边界。

### 推荐调用顺序

读取已有项目：

```text
list_projects
→ read_project_summary(projectId)
→ read_canvas(projectId, includeDrafts=true)
→ read_artifacts(projectId, versionIds=[明确选取的内容版本])
```

创建、修改文字和布局：

```text
create_text(projectId, commandId=A, title, text)
→ revise_artifact(projectId, commandId=B, artifactId, expectedVersion, text)
→ manage_canvas_node(projectId, commandId=C, canvasItemId, action=MOVE, expectedVersion, x, y)
```

生成媒体：

```text
list_media_capabilities(projectId, kind=IMAGE)
→ create_media_node(projectId, commandId=D, kind=IMAGE, title)
→ update_media_draft(projectId, commandId=E, canvasItemId, expectedDraftVersion,
                     patch={prompt, capabilityId, parameters})
→ run_media(projectId, commandId=F, canvasItemId, expectedDraftVersion=更新后的草稿版本)
→ read_task_status(projectId, taskIds=本次返回的全部任务ID)
→ read_artifacts(projectId, versionIds=任务返回的精确结果版本)
```

视频和音频沿相同流程使用对应能力。`patch` 的参数、视频模式、角色、mentions 和素材引用按能力及 Schema 填写；浏览器当前选中项不会自动成为外部客户端的输入。批量生成可能受理多个 Task，必须保留并查询完整集合。结果会通过原有项目事件更新网页画布；外部客户端通过查询继续自己的工作，不向内置导演会话注入消息。

### 重放、冲突与 UNKNOWN

- **每次新的写操作先生成一个稳定 `commandId`**，建议使用 UUID。保留工具名、完整参数和原命令 ID，直到收到确定结果。响应丢失时以相同命令、相同参数重放，服务器返回该操作已提交的结果，不重复放置节点或受理媒体任务。
- **同一命令不能换参数。** 同键异参返回冲突。遇到 CAS 冲突，先重新读取对象并确认如何合并，再以新的 `commandId` 提交新的写操作。不要简单递增或猜测版本号。
- **节点被其他 Task 占用时返回 `CANVAS_ITEM_BUSY`。** 这不是本次任务已受理；读取当前画布或已知 Task，等待用户处理占用后再发起新的操作。
- **HTTP 断线不等于生成失败。** 先重放原 `run_media` 命令找回已受理任务，再查询这些 Task。只读查询不会提交新任务。
- **`UNKNOWN` 表示外部 Provider 的受理结果不确定。** 保留原 Task，使用查询和网页的原有核对入口处理。不得自动换 `commandId` 再次调用生成；那会成为可能再次收费的新操作。本版 MCP 不提供 UNKNOWN 重试工具。
- **取消、失败和晚到结果仍按现有 Task 规则处理。** Task 成功才表示媒体产物已完成；`run_media` 调用成功只表示本地受理成功。凭据撤销或 MCP 断连不撤回已受理任务。

## 关闭服务与排障

四份 Compose 的 `server.environment.AGENVAS_MCP_ENABLED` 默认是 `"true"`。关闭时在正在使用的 Compose 文件中改为 `"false"`，然后用同一份文件重建服务容器，例如：

```sh
docker compose -f docker-compose.yml up -d --no-deps server
```

源码或非 Compose 部署可设置进程环境变量 `AGENVAS_MCP_ENABLED=false` 后重启应用。Compose 文件显式声明该值，单独修改宿主机同名环境变量不会覆盖它。关闭后新 MCP 调用被拒绝；网页、内置导演及已经受理的媒体 Task 继续按现有流程工作。

| 现象 | 核对事项 |
| --- | --- |
| `401` | Bearer 是否存在、环境变量是否可见、令牌是否过期/撤销、所属用户是否仍有效；网页登录 Cookie 不适用于 MCP |
| `403` 或工具不可见 | 令牌权限是否包含目标工具要求的 scope，当前项目和资源是否归属该用户；客户端可能缓存工具列表，需要重新发现 |
| 返回 HTML 页面 | 地址应为精确 `/mcp`，检查部署使用的 Nginx 是否包含 `location = /mcp` 反代 |
| `413` | 后端实际请求体上限 300 KiB，工具参数上限 256 KiB；Nginx 外层上限 2 MiB。减少批量输入并按工具分页，MCP 不能当作文件上传入口 |
| `429` | 同一用户跨令牌共用最多 4 个在途请求，进程总计最多 64 个；按 `Retry-After: 1` 等待后重放原命令，不生成新 commandId |
| 带 Origin 的请求被拒绝 | 首版面向本地原生客户端，拒绝带 Origin 的浏览器跨域调用；没有 CORS 接入入口 |
| 恢复模式只读 | `AGENVAS_RECOVERY_MODE=true` 时 MCP 读取仍可用，写入和生成被拒绝；不能只因 MCP 使用 HTTP POST 就把读取当作写入 |
| 连接失败或工具错误 | 分别检查 HTTP 状态、MCP 协议错误及工具返回的 `isError`；业务错误提供 `code`、`status`、`detail`、`retryable`，不要把错误文本当成受理成功 |

默认 Nginx 对 `/mcp` 透传认证和 MCP 协议请求头，关闭缓存及代理缓冲；在外部 HTTPS 代理上也应保留相同行为，不缓存私有工具响应或记录认证头。

## 已验证的客户端范围

官方 Java SDK 2.0.0 的实际 HTTP 客户端已逐一调用 12 个工具并验证输出 Schema；MCP Inspector 2.12.0 通过实际 Nginx 反代完成发现、文字写入/重放、画布、直接 Mock 图片任务、查询与图片读取。Claude Code 2.1.296 的环境变量 Bearer 健康连接通过，未运行其模型驱动对话。上述初始客户端兼容验证使用隔离账号和 Mock Provider。另有显式启用的 `McpRealServiceIT` 已复用现有第三方配置，完成一份真实 GPT Image 2 兼容图片能力的全部 12 工具闭环及单张生成。另一次真实创作验收通过 Python HTTP JSON-RPC 客户端提交同一身份参考驱动的 6 段视频和 10 句 SeedAudio 配音，全部成功；本地剪辑为 60 秒并通过普通上传 API 回写测试项目。原服务数据与待核对任务保持不动；其他未列 Provider 及 Claude 模型驱动对话未实测，不代表所有客户端兼容。视频抽帧未见明显人物替换，但存在口型和门链细节瑕疵；未连续观看或听验，不能将解码成功等同于成片质量验收。

## 运行真实服务验收

`McpRealServiceIT` 不启动 Mock 或 Testcontainers，只连接已运行的 MCP 服务；默认不执行。测试会创建文字及图片节点，并真实提交 **一张图片**。先在媒体配置中核对目标是已接通的真实 IMAGE 能力，不能选择 Mock；配置只读令牌和完整令牌，准备同一用户有权访问的专用空项目。测试不会修改 Provider 设置、索取 Provider Key 或额外收费审批。

将以下配置保存到仓库外权限 `0600` 的私有文件；实际凭据不放入命令行、URL、Git 或日志。证据目录须为仓库外新的绝对路径，测试按 `0700/0600` 保存回执和预览。

```json
{
  "url": "https://agenvas.example.com/mcp",
  "token": "<用户 READ + CONTENT_WRITE + MEDIA_GENERATE 令牌>",
  "readOnlyToken": "<同一用户的 READ 令牌>",
  "projectId": "<专用空项目 UUID>",
  "capabilityId": "<真实 IMAGE 能力 UUID>",
  "parameters": {
    "aspectRatio": "1:1",
    "resolution": "1K",
    "quality": "low",
    "transparentBackground": false,
    "generationCount": 1
  },
  "evidenceDirectory": "/private/path/mcp-real-evidence"
}
```

参数必须符合所选能力；上例用于兼容的标准图片能力，工作流/其他原生协议不能直接套用。仅本机回环地址可在测试时使用 HTTP，远程令牌使用 HTTPS。

在 `backend/` 运行：

```sh
./mvnw -Pci-integration -Dit.test=McpRealServiceIT \
  -Dagenvas.mcp.real-test=true \
  -Dagenvas.mcp.real-config-file=/private/path/mcp-real-config.json verify
```

该用例验证原有 12 工具、只读权限和输出 Schema；新增 `compose_video` 由配音合成专项另行验证。用例只对同一生成 `commandId` 重放，并查询至结果归档、实际解码返回图片。遇到 UNKNOWN、BLOCKED、FAILED、取消或超时即失败，不创建新生成命令。失败后先核对已受理 Task；重跑整个用例需要另一个明确授权的新操作及专用空项目，不能将重新运行测试当作对未知受理结果的安全重试。

若已有测试部署仍处旧迁移版本，应先核对升级影响。使用配置副本验收时，必须清除副本的旧 Task、个人/项目后台队列与远端清理记录，禁用旧同步/发布/LLM 调度，使用新归档目录；不能直接启动含旧 READY/UNKNOWN/清理任务的完整副本。


## 智能配音合成

先用 `read_artifacts` 读取剧本和固定媒体版本；音视频返回实际时长及 `generationPrompt`，作为镜头/台词匹配依据。客户端 Agent 判断镜头顺序和每镜的配音顺序，再调用 `compose_video(projectId, commandId, composition)`。`composition` 与 OpenAPI `VideoCompositionRequest` 一致；`shots` 中每项指定 `videoVersionId`、可选 `sourceStartMs/durationMs` 和 `lines[{audioVersionId,startMs?}]`。不要从文件名或画布位置臆造剧情对应关系。

省略 `startMs` 时后台利用原片声音活动和配音时长自动定位；明确的镜内剧情锚点优先。原片没有声音或活动窗口不足时回退时长排布并记录警告。声音能量不是语音识别，音乐/音效可能干扰判断。默认 `MUTE`，`BACKGROUND` 保留 15% 原声，原对白也会被保留。配音最多 1.15 倍变速，无法完整容纳时失败；最多 12 镜、30 句、60 秒，输出 1280×720/30fps。每镜实际时长向下对齐视频整帧。

返回成功仅表示排队。查询原 Task，成功后读取精确结果版本的 `composition`，可见实际落点、配音裁切/速度、声音活动窗口和回退警告。这是已有素材的本地合成，不调用云媒体模型，也不增加审批。内置导演同样提供此工具；停止导演不取消已受理合成，在成片节点单独取消。

### 视频后期扩展

`compose_video` 继续接受精确版本，现支持空 `lines` 的纯视频剪辑、每镜 `speed`（0.5–2）及 `fadeMs`（0–500），`originalAudioMode=KEEP` 保留原声；`MUTE` 和15%原声的 `BACKGROUND` 保持。单源最长10分钟，成片仍最多12镜、30句、60秒，向下对齐30fps完整帧。

可选 `output={resolution:"1080P",aspectRatio:"9:16"}`（另支持720P及16:9/1:1）、`music={audioVersionId,sourceStartMs,volume}` 和 `subtitles=[{text,startMs,endMs}]`。音乐同样授权和冻结为精确版本，可自然结束或在成片末尾结束；配音仍不得被静默截断。字幕为最终成片烧录文本；ASS控制符显示为全角字面字符。完整限制以 OpenAPI `VideoCompositionRequest` 为准。输出始终是后台Task和独立结果节点，查询归档成功后才能称为已完成；原素材和源节点选用保持。

内置导演另有 `read_media_sample` 按需读取实际画面和短音频；MCP的 `read_artifacts` 仍返回音视频版本及元数据，不把这些元数据描述成客户端已经观看或听过媒体。
