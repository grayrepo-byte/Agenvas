# Seedance 视频参考与媒体中继

2026-10-03。永久归档与媒体中继分别选择；安装默认使用本地归档、中继关闭。决定见 [ADR 0023 后续决定](adr/0023-per-asset-object-storage.md)。

签名协议分别采用 OSS V4 与 S3 SigV4；COS 使用其 [S3 兼容签名支持](https://intl.cloud.tencent.com/document/product/436/34688?lang=en)。

管理员先在存储设置添加 OSS、COS 或 S3 连接，填写 HTTPS Endpoint、Region、Bucket、前缀及凭证，再在“媒体中继”选择该连接。添加连接或选择中继不会启用云端永久存储；只有“设置为默认存储”才影响新素材归档。已有云端视频固定原连接，直接签名原对象，无需另外配置中继。

桶可以保持私有，最终对象域名需要从公网通过 HTTPS 访问。内网 MinIO 可作为永久存储，但其地址无法供 Seedance 读取，本次不自动搬运私网云对象。中继凭证需 PUT、GET 和 DELETE 对应前缀的权限；已有云对象的签名凭证需 GET 权限。桶生命周期规则不能提前删除仍有效的参考。签名仅授权 GET、签 Host，支持服务方读取 Range，无需额外认证头。对象存储真实可达性和桶权限需部署者验证，DNS 公网检查不能证明供应商能读取。

创作者在 Seedance 全能参考模式选择项目视频、画布视频连线、个人资产视频或上传 MP4。最多 3 段，每段 2–15 秒、200 MiB，合计最多 15 秒；视频编码 H.264/H.265、24–60 FPS，附带音轨仅接受 AAC/MP3；边长 300–6000、宽高比 0.4–2.5、总像素 407696–8295044。图片、视频、音频分别编号和计数，仍受卡片总输入上限约束。音频参考需要至少一个图片或视频。视频缩略图供选择预览，默认不自动播放；切换首尾帧或纯文本需清理不兼容输入。

本地视频的任务受理时冻结中继连接；提交前先验证实际编码/帧率，再以随机对象键上传到连接前缀的 `media-relay/`。清理记录在上传前写入数据库，上传失败或进程重启不会丢失回收身份。已有云视频按原存储路由和对象键签名，预检读取的校验缓存不等于再次上传。签名有效期 72 小时，Ark `execution_expires_after` 显式设为 48 小时。签名链接只存在于服务端生成请求内；任务、版本来源、SSE 和项目导出不保存它，debug 采集对 S3/COS/OSS 签名查询参数脱敏。

中继副本保留 7 天，默认每小时清理最多 32 个过期对象；清理失败保留数据库记录以后重试。只清理本系统登记的临时副本，不清理原 Asset，不发出生成请求。上传/签名失败且未提交 Ark create 时任务明确失败；Ark create 的受理状态不明时才遵守原 UNKNOWN 规则，禁止自动重提。

V4 追加独立 `relayProfileId` 和 `media_relay_object`，保留原默认存储、历史配置与资源。OpenAPI 增加 `PUT /api/v1/settings/storage/relay`（管理员、CSRF、expectedVersion/CAS、no-store），状态返回可空 relayProfileId；能力返回必填 maxReferenceVideos，设置允许 0..3，个人资产参考角色增加 VIDEO_REFERENCE。任务输入 Schema 5 冻结连接身份，旧的无视频任务继续读取。前后端、迁移和生成 jOOQ/TS 需一起发布；迁移不移动媒体文件。

验证使用真实 PostgreSQL/FFmpeg、合成视频与明确标注的假 Ark/对象存储 HTTP。AWS 公开签名向量、OSS 独立计算向量、前端输入和设置交互通过定向测试。真实 Seedance、OSS/COS/S3 桶调用、全量测试及部署未运行。

协议来源：[Ark 视频任务](https://docs.volcengine.com/docs/ark/create-video-generation-task-api?lang=zh)、[S3 SigV4 查询签名](https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html)、[OSS URL 签名](https://www.alibabacloud.com/help/en/oss/developer-reference/add-signatures-to-urls)。
