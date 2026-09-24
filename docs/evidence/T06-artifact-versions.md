# T06 Artifact 与不可变版本证据

任务编号：T06  
变更行为：支持 TEXT、IMAGE、VIDEO、CHARACTER、SCENE、SHOT 六类 Artifact；创建首版、追加完整修订、读取历史、乐观选择历史版本；ArtifactVersion 内容和输入引用不可原地覆盖。  
合约/迁移影响：`contracts/openapi.yaml` 增加 Artifact API；`contracts/artifact-schemas/*-v1.schema.json` 固定六类内容 Schema；Flyway V5 增加 Artifact、不可变版本、规范化版本引用、语义关系表、组合外键和禁止版本 UPDATE/DELETE 的数据库触发器。  
执行环境：macOS / Java 21.0.9 / Node 24.12.0 / Docker Desktop / PostgreSQL 17.11 / Spring Boot 4.0.8。  
实际运行的检查：`backend/./mvnw verify`；前端 `corepack pnpm api:generate/typecheck/lint/test/build`；`docker compose -f deploy/compose.yaml up -d --build`；经 Nginx 的 Artifact API curl 流程。  
测试结果：10 个单元测试和 2 个 Testcontainers PostgreSQL 集成测试通过。Artifact 集成测试覆盖数据库不可变触发器、8 路并发同版本修订仅成功一次、版本号唯一递增、跨项目/错误类型引用拒绝、历史内容保留、共享 Scene 仅更新目标 Shot 引用。Compose 实测创建 201、修订 201、陈旧修订 409、历史两版内容完整、选择旧版 200、半截 Schema 400。前端 1 个组件测试通过，类型检查、lint 与生产构建通过。  
真实 Provider：未调用；IMAGE/VIDEO Schema 已冻结，但没有把 Mock 输出宣称为真实媒体生成。  
安全/一致性：嵌套资源先验证项目 owner；版本引用同时由应用类型检查和数据库同项目组合外键约束；受保护字段与过深/过大的内容拒绝入库。  
后续 T19 增量验证：IMAGE/VIDEO 的 `assetId` 已在应用服务中要求指向同项目、类型匹配且原文件可读的 Asset，PostgreSQL 测试覆盖缺失和跨项目拒绝。未验证项：`sourceTaskId` 仍只校验 UUID 形状，尚未将手工上传与任务生成来源区分；Artifact 归档与活动任务协调也依赖任务模块。
