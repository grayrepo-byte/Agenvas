# 媒体产品并发门禁移除

日期：2026-09-28。

## 行为变化

- 删除每项目三个媒体任务、能力 `maxConcurrent` 与 ComfyUI 全局单槽三层共享容量门禁。
- READY 媒体任务只等待有界 Worker；队列 API 只返回 `WAITING_WORKER` 或 `NOT_QUEUED`，前端不再显示项目、能力或 ComfyUI 容量原因。
- 管理员媒体设置不再读取或修改能力并发上限；OpenAPI、Java DTO、客户端类型与界面同步删除该字段和端点。
- 同一卡片未终结任务互斥、HTTP/业务幂等、数据库租约、fencing epoch、CAS 和进程内有界执行器保持不变。

## 数据与升级

Flyway V56 删除 `media_capability.max_concurrent`、对应检查约束及旧 `provider_dispatch_gate` 表；jOOQ 源码已从迁移后的空 PostgreSQL 重新生成。该变更会移除管理员之前保存的能力并发数，升级后不再有等价产品设置。

## 已运行验证

- 后端编译与测试源码编译通过。
- PostgreSQL 17.11 Testcontainers 定向集成测试 5 项通过：`MediaDraftPostgresIT` 验证同项目第四个任务及另一项目同能力任务继续认领，`ComfyUiImagePostgresIT` 验证首个活动请求不阻塞第二个提交，另覆盖媒体设置、UNKNOWN 替代任务与队列指标；`MediaExecutionSchedulerTest`、`TaskQueueMetricsTest` 两项相关单元测试通过。
- 前端 Vitest 实际运行 36 个文件、244 项全部通过；TypeScript 类型检查与 ESLint 通过。
- OpenAPI TypeScript 类型重新生成，`git diff --check` 通过。

## 未验证限制

未运行后端全量测试、浏览器端到端或真实媒体 Provider 调用；真实 ComfyUI/云 Provider 在并发负载下的吞吐、限流与资源占用仍需部署方按实际环境验证。
