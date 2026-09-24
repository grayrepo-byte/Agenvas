# T27：媒体归档中途写满（阶段性证据）

2026-09-24。对应主规格 §22.2 场景 11 与 T19 存储故障门禁。`LocalAssetStorage` 的有界拷贝和图片缩略图编码统一经可替换的归档输出流打开点；生产实现仍直接打开本地临时文件。`AssetDiskFullPostgresIT` 在独立 PostgreSQL 17.11 与本地卷上，仅替换测试上下文的写入流：先写入 16 字节，再抛出 `IOException("No space left on device")`，模拟媒体写入中途耗尽空间，而不是伪造下载失败。

测试从已受理、带原 `providerRequestId` 的图片任务发起持久轮询。首次写入故障后，Task 仍为 `WAITING_PROVIDER`、无输出；数据库没有 READY Asset 或 `asset.ready` 事件，磁盘没有临时原图或稳定 PNG。再次按原 ID 轮询后，归档和 Task 成功；Provider attempt 仍只有一条。定向 `./mvnw --batch-mode --no-transfer-progress -Dit.test=AssetDiskFullPostgresIT verify -q` 通过。

同一测试还在普通图片上传的缩略图编码、普通视频上传的 MP4 临时写入分别注入相同错误；每次故障后的项目文件清单与 Asset 行数均不变，随后用相同有效图片/本地 FFmpeg 生成的 MP4 成功归档。视频测试覆盖文件与 READY 边界，不覆盖已受理视频 Task 的状态机。

这不是对物理磁盘实际写满、MP4 海报编码阶段、进程强杀或跨机器共享卷的完整演练；T19、T27 总验收保持未勾选。

2026-09-24 导出补验：扩展同一 `AssetDiskFullPostgresIT`，先用真实 FFmpeg 生成并归档一段 MP4，创建其精确 VIDEO ArtifactVersion，再提交本地顺序导出。导出编码实际运行，只有目标 MP4 向资产临时文件写入时注入一次“先写 16 字节、再抛 IOException”的 ENOSPC 等价故障。真实 PostgreSQL 中原导出 Task 为 `FAILED`/`EXPORT_FAILED`、输出为 JSON null，不新增 READY Asset 或 `asset.ready` 事件、不结算用量而只释放一次预留；项目目录中没有残留 `.export-*`、`.video-ingest-*` 或稳定坏文件。解除注入后，以新幂等键提交同一精确版本与区间，导出成功。首次运行受 Testcontainers Ryuk 握手超时阻断，重试运行到测试后发现 JSON null 与 Java null、任务锁文件保留两处断言错误，修正断言后定向 `./mvnw --batch-mode --no-transfer-progress -q -Dit.test=AssetDiskFullPostgresIT verify` 和全量 `./mvnw --batch-mode --no-transfer-progress -q verify` 均退出码 0。这仍是受控中途写入故障，不声称真实物理卷写满或杀进程恢复已验证。
