# T23 阶段性证据：Mock 图生视频与 MP4 归档

这是演示路径，不是 T23 真实 ComfyUI 图生视频验收。默认 Mock ChatGateway 在三张 IMAGE Task 成功且每个镜头由用户选定精确关键帧后，使用账本中的用户选择提出三步骤 VIDEO 计划。计划仍需独立人工审批；审批前没有 VIDEO Task。批准后视频 Task 输入钉住 shotVersionId、imageArtifactId 和 imageVersionId，视频 Worker 从该历史图片版本对应的已归档 Asset 读取字节，不从模型自由文本选择图片或文件路径。

Mock 视频 Worker 与图片 Worker 的认领域分离，沿用提交前 requestKey、Provider attempt、租约/epoch 和取消后的历史结果规则。固定 FFmpeg 参数把图片编码成无声 H.264 MP4；生成文件再由 FFprobe 检查媒体格式、尺寸、时长并解码首帧，按最大 500 MiB 限制归档原视频和 PNG 封面。VIDEO ArtifactVersion 新增可选的精确 `keyframeVersionId` 输入引用，Mock 新版本会填写。画布默认只取封面，用户点击后才加载私有视频；原文件复用项目鉴权的 GET、HEAD 和 Range。

`MockStoryboardPostgresIT` 已在真实 PostgreSQL 中验证三镜头图片审批、三次人工选择、第二次视频审批、三段实际 H.264 MP4、视频输入引用和最终 Run；`AssetPostgresIT` 验证无效视频不登记 READY，以及实际 MP4 的私有 Range/HEAD 与封面。前端组件测试验证默认封面和显式播放。此次变更无 Flyway 迁移；`contracts/artifact-schemas/video-v1.schema.json` 加法增加可选字段，`contracts/openapi.yaml` 的外部引用保持不变，已重新生成 TypeScript 类型。

实际检查：定向 `MockStoryboardPostgresIT`、`AssetPostgresIT`、`MockImageSchedulerPostgresIT` 均通过；完整 `backend/./mvnw verify -q` 在拆分调度开关后通过（29 个单元测试、21 个 PostgreSQL 集成测试），随后补充视频输出关键帧一致性检查后再跑定向 Mock/Asset 测试通过。前端 `typecheck`、`lint`、15 个 Vitest 测试与 Vite 构建通过（仍有大于 500 kB 单包警告）；`docker compose config --quiet` 与 server 镜像构建通过。未运行 Compose 整体浏览器端到端路径。

容器构建安装 Alpine `ffmpeg` 6.1.2-r2。实测镜像内 `ffmpeg -version` 的 configure 同时包含 `--enable-gpl` 与 `--enable-version3`，镜像也实测可用 `libx264` 编码。因而不能把这份 FFmpeg 二进制当作项目 Apache-2.0 代码分发；发布镜像前需完成第三方许可证文本、对应源码与构建信息的合规审核。参见 [FFmpeg 官方许可证说明](https://ffmpeg.org/doxygen/trunk/md_LICENSE.html)。

限制：尚未接入真实 ComfyUI 模板、异步 Provider 受理/核对、归档失败后只重试归档、视频生成期间的长期租约续期、浏览器实际解码端到端、局部重做与顺序导出。FFmpeg 调用使用固定服务端参数和超时，但当前 Mock 路径只针对 5 秒、640×360 演示素材验证；不把它宣称为任意 Provider 视频的生产稳态处理。

2026-09-23 补充：视频产物归档现按 Task ID 派生固定 Asset ID，与用户上传和图片任务键分离。原 MP4 已落盘但 READY 行尚未提交时，可核验 MP4 并重建缺失的封面，再补写唯一 Asset；READY 后重放不重新下载。共享卷文件锁序列化同一视频 Task 的归档。Mock 视频 Worker 已走此路径；`AssetPostgresIT` 验证文件/数据库崩溃窗口、封面恢复、重复调用和同进程双 Worker 只下载一次。这仍不代表真实 ComfyUI 视频模板已接通，跨独立进程故障注入尚未做。
