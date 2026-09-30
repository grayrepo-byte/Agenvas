# 个人资产库设计相关现有功能测试

2026-10-01。用户要求运行功能测试，本轮检查资产库设计依赖的现有上传、项目资源、节点版本、媒体输入、权限与事件行为。个人资产库本身尚未实现，本证据不表示分类保存或账号级跨项目资产复用已经可用。

## 结果与修改

| 检查 | 实际结果 |
| --- | --- |
| 前端 9 个选定测试文件 | 150 个测试通过，0 失败、0 跳过 |
| 后端 16 个选定测试类 | 26 个测试通过，0 failure / error / skipped；BUILD SUCCESS |
| 前端 TypeScript | `pnpm typecheck` 通过 |
| 修改的两个测试文件 ESLint | 通过，0 warning |
| `git diff --check` | 通过 |

前端首次执行已有 150 个测试全部通过，但日志出现 6 次 MSW 未匹配请求错误。定位为图片预览测试缺少 Asset 元数据响应，以及音频对话测试缺少音频内容响应。补齐了局部测试夹具，没有关闭全局严格 MSW 检查；音频场景明确模拟内容请求失败，并断言波形暂不可用时仍可打开绑定精确版本的对话。修改后复跑同一批 9 个文件，150 个测试再次通过，最终日志没有 stderr 或 MSW 未匹配请求错误。

只修改 `frontend/src/features/canvas/MediaCanvasCard.test.tsx` 和 `frontend/src/features/canvas/ProjectWorkspacePage.test.tsx` 的测试夹具/断言。没有修改生产逻辑、OpenAPI、生成类型、依赖版本、Flyway 或 jOOQ。前端缺少本地 node_modules 时执行了 `pnpm install --frozen-lockfile`，全部依赖从本机缓存复用，锁文件未改变。

## 前端验证范围

| 文件 | 通过数 |
| --- | ---: |
| ArtifactVersionEditing.test.tsx | 8 |
| CanvasRelationDeletion.test.tsx | 3 |
| ContentCanvasCard.test.tsx | 8 |
| MediaCanvasCard.test.tsx | 56 |
| MediaCardUpload.test.tsx | 4 |
| MediaDraftEditor.test.tsx | 30 |
| MediaVersionPicker.test.tsx | 8 |
| ProjectWorkspacePage.test.tsx | 21 |
| projectEvents.test.ts | 12 |

覆盖既有资源放置、不可变版本选用、上传失败重试、草稿保存/CAS、媒体展示、引用删除与事件处理。组件测试使用 MSW/测试数据，不是真实 Provider 或浏览器端到端测试。

```sh
# frontend/
pnpm exec vitest run \
  src/features/canvas/ProjectWorkspacePage.test.tsx \
  src/features/canvas/ContentCanvasCard.test.tsx \
  src/features/canvas/MediaVersionPicker.test.tsx \
  src/features/canvas/MediaCardUpload.test.tsx \
  src/features/canvas/MediaDraftEditor.test.tsx \
  src/features/canvas/ArtifactVersionEditing.test.tsx \
  src/features/canvas/CanvasRelationDeletion.test.tsx \
  src/features/canvas/projectEvents.test.ts \
  src/features/canvas/MediaCanvasCard.test.tsx \
  --reporter=default --reporter=json \
  --outputFile=/tmp/agenvas-asset-library-review-frontend-20261001.json
pnpm typecheck
pnpm exec eslint src/features/canvas/ProjectWorkspacePage.test.tsx \
  src/features/canvas/MediaCanvasCard.test.tsx --max-warnings=0
```

## 后端验证范围

实际使用 Java 21.0.9、Docker Desktop 与 Testcontainers PostgreSQL 17.11，运行了 12 个数据库集成测试类和 4 个其他测试类。覆盖实际媒体解码/私有 Range 与缩略图、磁盘满、项目/身份隔离、不可变内容与幂等、节点独立草稿、节点历史与并发选择、精确媒体引用、音频生命周期、项目导出和真实 HTTP SSE 补发。

```sh
# backend/
./mvnw -B -Dtest=AssetControllerStreamingTest,LocalAssetStorageScratchTest,ArtifactContentValidatorTest,CanvasTaskResultSelectionTest,AssetPostgresIT,AssetDiskFullPostgresIT,ArtifactPostgresIT,ArtifactCreateIdempotencyPostgresIT,CanvasPostgresIT,CanvasMediaContextPostgresIT,CanvasMediaVersionsPostgresIT,VersionedMediaInputPostgresIT,ResourceScopePostgresIT,AudioMediaPostgresIT,ProjectExportManifestPostgresIT,ProjectEventStreamPostgresIT test
```

Surefire XML 对上述 16 类逐一核对，共 26 个测试，所有失败/错误/跳过计数均为 0。`CanvasMediaVersionsPostgresIT` 有 7 个测试，两类领域校验/选择单测各 3 个，其余每类 1 个。音频涉及 Mock 输出与被拦截的传输，未调用云端 Provider。

原始报告位于本机 `backend/target/surefire-reports/TEST-*.xml`；前端 JSON 与前后端终端日志位于 `/tmp/agenvas-asset-library-review-*-20261001.*`。这些是临时产物，后续测试可能覆盖，本文保留此次命令与核对结果。

## 验证限制

这是定向回归，全量测试、前端生产构建、完整 lint、浏览器端到端、压力测试和真实 Provider 调用均未运行。个人资产库的分类保存、账号级权限、独立归档、跨项目导入、回收站和本地转存恢复还没有实现及对应测试，相关开发清单仍未勾选。通过现有功能测试不能保证未来新功能没有问题。
