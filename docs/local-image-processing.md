# 本地图片处理配置

裁剪、旋转、镜像和插值放大不需要额外配置。深度提取需要部署者安装 **Depth Anything V2 Small** 的 ONNX 文件，并通过 `AGENVAS_DEPTH_MODEL` 指向只读模型路径。

约束：

- 只使用 Small 权重（Apache-2.0）；不要以 Base/Large/Giant 权重替换，因为其许可证不满足本项目默认开源分发边界。
- 模型必须具有一个 NCHW RGB 输入和至少一个单通道相对深度输出。服务端自行读取节点名与静态尺寸；动态尺寸按 518×518 推理。
- 模型路径只来自服务端配置，HTTP 请求不能指定路径、URL或模型。
- 仓库和镜像不自动下载权重。上线前记录来源提交、文件 SHA-256、许可证和一次真实图片推理结果。

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

本地运行示例：

```bash
export AGENVAS_DEPTH_MODEL=/absolute/path/depth-anything-v2-small.onnx
```

Compose 使用现有 `asset-data` 卷，默认查找 `/opt/agenvas/data/models/depth-anything-v2-small.onnx`。文件不存在时其他本地图片处理仍可用，深度任务会以 `LOCAL_DEPTH_MODEL_UNAVAILABLE` 阻断并保留可读错误。
