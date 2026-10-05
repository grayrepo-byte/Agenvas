package dev.agenvas.provider.infrastructure;

/** 固定图片工作流依赖的管理员安装模型文件。
 * @param checkpoint ComfyUI 模型目录中的 checkpoint 文件名
 */
public record ComfyUiImageProperties(String checkpoint) {}
