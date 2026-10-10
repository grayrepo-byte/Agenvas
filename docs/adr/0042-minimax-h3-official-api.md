# ADR 0042：MiniMax H3 官方视频 API

状态：接受。用户要求新增 MiniMax H3，并确认使用官方 API。

新增 `MINIMAX` 连接与固定 `MINIMAX_H3` 视频适配器，复用版本化媒体能力、CanvasItem 草稿、媒体审批和持久 Task。连接默认使用中国站 `https://api.minimax.cn`，也可明确选择国际站 `https://api.minimax.io`；不接受代理或任意 API 地址。API Key 继续由服务端加密、随连接版本固定。

此次使用 `model=MiniMax-H3`、`POST /v2/video_generation` 和 `GET /v2/query/video_generation/{task_id}`。支持 4–15 个整数秒、文生视频、首帧及可选尾帧、图片/视频/音频全能参考；最多 9 张图片、3 段视频和 3 段音频，音频可以独立作参考。保留 H3 原生输出音轨。输出分辨率使用草稿既有 `videoResolution`，`768p` / `1440p` 分别映射官方 `768P` / `2K`，默认 `768p`。画幅沿用既有 AUTO/16:9/9:16/1:1；文生 AUTO 使用项目比例，首尾帧固定提交 adaptive，全能参考 AUTO 提交 adaptive。

图片发送精确归档字节，支持项目已有 PNG/JPEG/WebP；尺寸为 256–5760 px、比例 0.4–2.5、单文件不超过 30 MB。音频限定 MP3/WAV、15 MB、每段 2–15 秒且总计不超过 15 秒；视频限定已支持的 MP4、H.264/H.265、AAC/MP3、50 MB、23.976–60 fps、每段 2–15 秒且总计不超过 15 秒，尺寸和比例同图片。视频复用固定连接的媒体中继/原对象签名，图片与音频使用 data URI，序列化请求不超过 64 MB；输入和请求限制按十进制字节计，避免超过官方 MB 边界。运行前和提交前分别核对元数据与实际字节；准备失败不成为收费提交 UNKNOWN。

成功提交持久化原 task_id。响应丢失、5xx、协议异常保留 UNKNOWN，HTTP 明确拒绝为失败；不自动重新 POST。查询、重启恢复与结果地址刷新只使用原 ID。官方查询保留 7 天，过期阻断。下载只接受官方文档示例中的 `cdn.hailuoai.com` 与 MiniMax 官方 `file.cdn.minimax.io` HTTPS 主机，经固定 DNS 和 ADR 0007 的地址校验且禁止重定向；不携带 API Key，限制 MP4 类型和 500 MiB，并由现有资产归档验证媒体。新增实际返回主机需按正式证据更新固定白名单。

双向切换沿用现有原子草稿与连线事务：保留兼容素材、结构化标签与普通参数；目标支持的分辨率继续保留，否则使用目标默认值或移除。H3 可保存不完整草稿，缺少必填输入或素材不满足运行要求时阻止运行。Seedance 的音频搭配视觉限制仍由其自身验证。

不接入 H3 Max、单独尾帧模式、额外画幅、回调、远程取消、Context-IR 或重生成接口。取消只停止本系统编排，不保证供应商停止或退款。V18 增量扩展平台约束，不改已有行；前后端和生成 API 类型须一起升级，jOOQ 从一次性 PostgreSQL 重生成。

协议依据：[创建任务](https://platform.minimax.cn/docs/api-reference/video-generation-v2-create)、[查询任务](https://platform.minimax.io/docs/api-reference/video-generation-v2-query)。验证结果记录在开发清单；假 HTTP 和合成媒体不代表真实供应商、生成质量或账单已验收。
