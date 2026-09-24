package dev.agenvas.provider.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 固定图片工作流依赖的管理员安装模型文件。
 * @param checkpoint ComfyUI 模型目录中的 checkpoint 文件名
 */
@ConfigurationProperties(prefix = "agenvas.provider.comfyui.image")
public record ComfyUiImageProperties(String checkpoint) {}
