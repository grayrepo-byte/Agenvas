# ADR 0046：SMB 与 WebDAV 远程归档

状态：接受。用户要求在本地和 OSS 等对象存储之外增加 SMB 与 WebDAV。沿用 ADR 0023 的固定归档位置和 ADR 0040 的连接管理规则。

后台存储页增加 SMB、WebDAV 类型，并支持创建、编辑、默认切换、凭据轮换及未引用连接删除。SMB 地址为 `smb://host[:port]/share[/directory]`，默认端口 445；WebDAV 为现有目录的 HTTPS URL。用户名和密码分别复用 API 的 `accessKeyId`、`secretAccessKey` 写入字段及已有 AES-GCM 加密载荷，不新增明文用户名或密码列。SMB 域账户使用 `DOMAIN\username`。`region`、`bucket` 传空字符串，`pathStyle` 固定为 false；前端隐藏这些对象存储字段。切换协议须填写两项新凭据。

所有协议共用归档路由、已校验媒体记录、缩略图发布、本地处理缓存、鉴权下载及单段 Range。默认切换只影响新归档；已有文件与未完成归档继续读取原连接。恢复只补齐原归档，不重新生成或下载供应商结果。图片、视频和音频沿用既有媒体校验，不增加浏览器直传、历史迁移或任意文件浏览能力。

文件协议没有对象存储的 SHA-256 元数据，因此上传和恢复读取完整文件核对大小及 SHA-256。先写临时文件、校验，再使用 SMB 不替换重命名或 WebDAV `MOVE` 的 `Overwrite: F` 发布；已存在且内容相同则复用，内容不同返回 `STORAGE_OBJECT_CONFLICT`。GET/HEAD 前的可用性核对只读取大小，HEAD 不下载正文。WebDAV 校验 Range 响应；不支持 Range 的服务可以返回完整且大小正确的文件，由服务端流式跳过前缀并限制输出长度。SMB 直接按偏移读取。普通预览不填充磁盘缓存，生成参考按需物化并核对 SHA-256。

SMB 使用固定正式版 SMBJ 0.15.0，支持 SMB2/3 并要求消息签名，不支持 SMB1、匿名账户或 DFS 跳转。只对本次通过地址检查的 IP 建立连接。WebDAV 使用现有 OkHttp、HTTPS Basic 认证及默认证书校验，关闭重定向、环境代理和自动写请求重试。两者允许管理员配置私网，拒绝 loopback、link-local/云元数据、未指定及多播地址；地址不得带凭据、查询、片段或路径回退。协议错误返回稳定脱敏错误，网络调用不持数据库事务。

SMB/WebDAV 不提供限时公网签名，因此不能被选作媒体中继。它们的媒体在生成服务需要 URL 时使用独立的 OSS/COS/S3 中继，像本地归档一样物化并上传临时副本。正在作为中继的连接不能直接改为文件协议，须先关闭或改选中继。原对象存储媒体仍按历史连接直接签名。

代价：完整哈希检查增加远程读取流量；WebDAV 无 Range 时读取前缀会增加流量；每次 SMB 操作建立有界超时的会话。临时文件正常结束时清理，进程崩溃或远端删除失败可能留下 `.upload-` 文件，目前需要管理员清理，不把其视为已归档内容。真实 NAS、不同 WebDAV 产品及大文件吞吐须单独验收。

追加 V25 扩展存储类型约束并更新数据库注释，jOOQ 从一次性 PostgreSQL 生成；OpenAPI、生成 TypeScript 和四语言界面同步。前后端应一起升级；不搬迁已有数据，启用 SMB/WebDAV 后不能降级到不识别这两种存储类型的版本。

协议依据：[SMBJ 0.15.0](https://github.com/hierynomus/smbj/releases/tag/v0.15.0)、[WebDAV RFC 4918](https://www.rfc-editor.org/rfc/rfc4918.html)。测试结果及未验证范围记录在开发清单。
