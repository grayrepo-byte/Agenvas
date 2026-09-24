package dev.agenvas.asset.application;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运维配置的固定媒体二进制路径；模型和用户请求均不能指定可执行文件。
 * @param ffmpeg 固定 FFmpeg 可执行文件绝对路径
 * @param ffprobe 固定 ffprobe 可执行文件绝对路径
 * @param timeout 每次媒体工具调用的最大等待时长
 */
@ConfigurationProperties(prefix = "agenvas.media.tools")
public record MediaToolsProperties(Path ffmpeg, Path ffprobe, Duration timeout) {

    /** 在 Bean 初始化时补齐部署默认值并拒绝无界或相对路径。
     * @param ffmpeg 配置的 FFmpeg 路径；为空时查找受限默认目录
     * @param ffprobe 配置的 ffprobe 路径；为空时查找受限默认目录
     * @param timeout 配置的超时；为空时使用 20 秒
     */
    public MediaToolsProperties {
        ffmpeg = ffmpeg == null ? defaultBinary("ffmpeg") : ffmpeg;
        ffprobe = ffprobe == null ? defaultBinary("ffprobe") : ffprobe;
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        if (!ffmpeg.isAbsolute() || !ffprobe.isAbsolute()
                || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("Media binaries must be absolute and timeout bounded");
        }
    }

    /** 应用启动时仅从三个固定本地目录中解析媒体工具路径。 */
    private static Path defaultBinary(String name) {
        for (String directory : new String[] {"/usr/bin", "/opt/homebrew/bin", "/usr/local/bin"}) {
            Path candidate = Path.of(directory, name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return Path.of("/usr/bin", name);
    }
}
