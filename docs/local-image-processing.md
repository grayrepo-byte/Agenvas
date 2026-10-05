# 本地图片处理配置

裁剪、旋转、镜像、插值放大和深度提取在 Compose 中均不需要额外配置。server 镜像在构建期下载并校验 **Depth Anything V2 Small INT8** ONNX，随后从只读镜像路径加载；运行时不访问模型站点。

约束：

- 只使用 Small 权重（Apache-2.0）；不要以 Base/Large/Giant 权重替换，因为其许可证不满足本项目默认开源分发边界。
- 模型必须具有一个 NCHW RGB 输入和至少一个单通道相对深度输出。服务端自行读取节点名与静态尺寸；动态尺寸按 518×518 推理。
- 模型路径只来自服务端配置，HTTP 请求不能指定路径、URL或模型。
- Docker 构建固定模型提交与 SHA-256，同时下载固定上游提交的 Apache-2.0 许可证并校验；模型不进入 Git 仓库。
- `AGENVAS_DEPTH_MODEL` 仅用于源码运行或有意替换为另一个已核验的兼容 Small 模型；Compose 默认不需要设置。

本次实现验证使用 `onnx-community/depth-anything-v2-small` 的固定提交
`c70d1ddbcd93c9bda8098268cc3554adf5e8dd4f`、文件 `onnx/model_int8.onnx`；下载后 SHA-256
为 `01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001`。该 ONNX 转换不是
上游作者直接发布的权重格式，部署者仍须自行核验模型卡与许可证；固定提交只是避免下载内容静默变化。

```bash
curl --fail --location \
  --output depth-anything-v2-small.onnx \
  https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/c70d1ddbcd93c9bda8098268cc3554adf5e8dd4f/onnx/model_int8.onnx
shasum -a 256 depth-anything-v2-small.onnx
```

不使用 Compose、直接运行源码时的示例：

```bash
export AGENVAS_DEPTH_MODEL=/absolute/path/depth-anything-v2-small.onnx
```

Compose 默认使用 `/opt/agenvas/models/depth-anything-v2-small-int8.onnx`；该路径位于只读镜像层，不会被 `asset-data` 卷遮挡。镜像中同时保留 `/opt/agenvas/licenses/depth-anything-v2-small/LICENSE`。首次构建需要访问上述固定下载地址；构建缓存命中和容器运行均不需要再次下载。显式覆盖到不存在的文件时，其他本地图片处理仍可用，深度任务会以 `LOCAL_DEPTH_MODEL_UNAVAILABLE` 阻断并保留可读错误。
