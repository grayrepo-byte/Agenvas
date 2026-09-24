# T06/M1 手工结构化分镜阶段性证据

日期：2026-09-24。状态：阶段性，M1 门禁未验收。

## 行为

工作区侧栏新增 `ManualStoryboardPanel`：可不调用 Agent/LLM 创建 CHARACTER、SCENE、SHOT 并放到画布。镜头表单要求可见场景，固定所选场景和可选角色的当前 ArtifactVersion ID；顺序 1–6、时长 100–30000 ms 和各文本字段遵循现有内容 Schema。创建成功后由服务端 CanvasItem 投影刷新画布；内容创建成功但放置失败时，同一表单内容重试沿用已确认的 Artifact ID 与预生成卡片 ID，避免重复创建。未收到创建确认时可用同一幂等键重试创建（见下方补充），错误提示仍要求先核对画布。没有新增执行计划、Run 或媒体 Task。

## 检查

`ManualStoryboardPanel.test.tsx` 通过 MSW 核对 CHARACTER 与 SCENE 的完整内容、放置失败后重试只创建一次 Artifact、两次放置沿用同一卡片 ID；核对 SHOT 的场景/角色精确版本与顺序递增；无场景时禁用镜头提交。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，18 个测试文件/63 项测试通过。未改 OpenAPI、后端和 Flyway；本轮未运行后端测试。

## 未验收

尚未用真实浏览器手工创建三个镜头并刷新验证；创建请求的服务端幂等键已在后续补充中加入，但不能把网络未知结果自动视作未创建。真实 Provider 与本路径无关。

## 2026-09-24 编辑与历史选择补充

Artifact 卡片现对 TEXT、CHARACTER、SCENE 提供受控内容编辑：使用完整 Schema 与 `expectedVersion` 创建新版本；角色和场景编辑保留当前精确参考图版本，修改共享场景不会改写其他镜头钉住的旧版本。SHOT 仍走已有 `ShotRedoEditor`，避免通用表单绕过媒体选用清理和局部重做语义。所有类型的卡片可按需读取历史版本，并以当前 Artifact CAS 选用旧版，历史本体不覆盖。版本列表不在未展开时请求。

`ArtifactVersionEditing.test.tsx` 用 MSW 验证文字、角色修订请求与引用保留、场景 409 后保留编辑草稿、版本列表按需读取及精确版本 CAS 选用。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，19 个测试文件/67 项测试通过。未改后端、OpenAPI、Flyway；未运行后端回归。真实浏览器测试尝试使用当前 Chrome 控制接口，两次返回 `Unable to load browser request-header policy`，未能打开本地页面，不能视为浏览器验收。

## 2026-09-24 创建请求幂等补充

手工创建 TEXT、IMAGE、CHARACTER、SCENE、SHOT 时，前端为同一未确认内容保留 `Idempotency-Key`；响应丢失后以原键重试。后端在同一事务中保留键、创建 Artifact/首版本与项目事件，并保存首次响应快照。同键同内容重放返回原响应及 `Idempotency-Replayed: true`，即使产物随后被修订；同键不同内容返回 409。已确认 Artifact 后的画布放置仍沿用原卡片 ID。图片上传本身不在这次创建幂等范围内。

契约变更：`POST /api/v1/projects/{projectId}/artifacts` 现在要求该请求头；旧客户端不带头会收到 400，需要更新。响应新增可选读取的重放头。复用既有 `idempotency_record` 表，没有 Flyway 迁移；生成的 TypeScript API 类型已同步。此前“创建响应丢失时没有服务端幂等键”的限制已解除，但未在真实浏览器做断线演练，故 M1 门禁仍未验收。

`ArtifactCreateIdempotencyPostgresIT` 在真实 PostgreSQL 上覆盖修订后重放原响应、不同 payload 冲突、8 路并发只创建一份产物、HTTP 重放头和缺失请求头 400；`AssetPostgresIT` 的原上传/产物闭环已适配新契约。`./mvnw -q -Dtest=ArtifactCreateIdempotencyPostgresIT,AssetPostgresIT test` 及补充单跑 `ArtifactCreateIdempotencyPostgresIT` 均退出码 0，各 1 项测试无失败；后端全套 `./mvnw -q verify` 退出码 0。`ManualStoryboardPanel.test.tsx` 与 `ProjectWorkspacePage.test.tsx` 覆盖响应不明时稳定重试键。前端 `npm run typecheck && npm run lint && npm test && npm run build` 退出码 0，19 个文件/69 项测试通过。
