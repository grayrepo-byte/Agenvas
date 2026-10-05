package dev.agenvas.provider.infrastructure;

/** ComfyUI 固定图生视频工作流使用的服务端模型文件配置。
 * @param diffusionModel ComfyUI 已安装的 Wan 2.1 扩散模型文件名
 * @param textEncoder 文本编码器文件名
 * @param vae 视频工作流使用的 VAE 文件名
 * @param clipVision 图像条件编码器文件名
 */
public record ComfyUiVideoProperties(String diffusionModel,
        String textEncoder, String vae, String clipVision) {}
