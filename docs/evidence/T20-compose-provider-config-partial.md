# T20 候选 Provider 的 Compose 配置入口：阶段性证据

- 行为：`deploy/compose.yaml` 保留默认 `AGENVAS_LLM_MODE=mock`、`AGENVAS_PROVIDER_MODE=mock`，但允许部署者显式设为 `configured`/`comfyui`。同时传入服务端加密主密钥、ComfyUI 精确 IPv4 地址、固定图像/视频模板模型名、视频启用标志与递增的 `AGENVAS_PROVIDER_CONFIG_VERSION`。后端 `application.yaml` 由该变量绑定 Provider 版本，避免每次只能记录硬编码的版本 1。容器 Web/API 端口默认仍只绑定本机。
- 文档：`.env.example` 列出可选项；中英文 README 明确当前仍是假服务验证的候选、容器内 `127.0.0.1` 不代表宿主机、不可为测试放宽内网地址，以及旧活动/UNKNOWN 任务不能静默切换配置。
- 检查：默认 `docker compose -f deploy/compose.yaml config --quiet` 成功；给定测试值后，`docker compose config --format json` 经 `jq` 断言 server 环境中的 LLM 模式、Provider 模式与版本、精确端点、固定图像模型名和 secure-cookie 开关均与输入相符。该断言已加入 `.github/workflows/ci.yml` 的 Compose 配置作业（CI 尚未在本轮远端运行）。`./mvnw -q -DskipTests package` 通过；CI YAML 本地解析与 `git diff --check` 通过。
- 限制：未在本轮启动真实 ComfyUI 或真实 LLM；不证明模型文件存在、模板兼容、私有地址可从容器连通、HTTPS 生产部署或真实生成。T20/T21/T23/MVP 门禁保持未完成。
