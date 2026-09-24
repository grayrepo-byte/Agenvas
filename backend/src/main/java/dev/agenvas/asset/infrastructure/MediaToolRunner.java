package dev.agenvas.asset.infrastructure;

import dev.agenvas.asset.application.MediaToolsProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

/** 只执行服务端构造的参数数组，限制运行时间并丢弃可能含敏感信息的进程输出。 */
@Component
public class MediaToolRunner {

    /** 区分媒体输入无效与本地工具不可用、超时或中断。 */
    public static final class MediaToolException extends IllegalStateException {
        /** 非零退出码是否由不可解码或不支持的媒体输入造成。 */
        private final boolean invalidInput;

        /** 保存不含原始进程输出的稳定错误信息和媒体输入分类。 */
        public MediaToolException(String message, boolean invalidInput, Throwable cause) {
            super(message, cause);
            this.invalidInput = invalidInput;
        }

        /** 返回是否应将错误映射为媒体输入无效。 */
        public boolean invalidInput() {
            return invalidInput;
        }
    }

    /** 固定 ffmpeg/ffprobe 可执行文件路径与默认超时。 */
    private final MediaToolsProperties properties;
    /** 常规探测任务的本地工作目录，不从调用参数接收。 */
    private final Path defaultWorkDirectory;

    /** 校验默认临时工作目录存在，并保存固定媒体工具路径配置。 */
    public MediaToolRunner(MediaToolsProperties properties) {
        this.properties = properties;
        defaultWorkDirectory = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(defaultWorkDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Media tool work directory must exist");
        }
    }

    /** 通过固定可执行文件和参数数组运行 FFmpeg，不经过 Shell 或继承环境变量。 */
    public void ffmpeg(List<String> arguments) {
        execute(properties.ffmpeg(), arguments, null, null, properties.timeout(), null, null, true);
    }

    /** 在私有 scratch 目录运行长导出，循环检查取消状态并执行租约心跳回调。 */
    public void ffmpegExport(List<String> arguments, Path workDirectory, Duration timeout,
            BooleanSupplier shouldCancel, Runnable onTick) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(3)) > 0
                || workDirectory == null || !workDirectory.isAbsolute()
                || !Files.isDirectory(workDirectory, LinkOption.NOFOLLOW_LINKS)
                || shouldCancel == null || onTick == null) {
            throw new IllegalArgumentException("Invalid bounded export process settings");
        }
        execute(properties.ffmpeg(), arguments, workDirectory, null, timeout,
                shouldCancel, onTick, false);
    }

    /** 将 ffprobe 输出写入受大小限制的本地临时文件，不接受远端 URL 作为读取目标。 */
    public String ffprobe(List<String> arguments) {
        Path output;
        try {
            output = Files.createTempFile("agenvas-probe-", ".json");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create media probe output", exception);
        }
        try {
            execute(properties.ffprobe(), arguments, null, output, properties.timeout(), null, null,
                    true);
            if (Files.size(output) > 16_384) {
                throw new IllegalStateException("Media probe output exceeded the limit");
            }
            return Files.readString(output);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read media probe output", exception);
        } finally {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
                // An unreferenced probe file is never an archived asset.
            }
        }
    }

    /** 统一启动媒体子进程、清空环境并执行超时、取消及退出码检查。 */
    private void execute(Path executable, List<String> arguments, Path workDirectory, Path output,
            Duration timeout, BooleanSupplier shouldCancel, Runnable onTick,
            boolean nonzeroIsInvalidInput) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);
        Path selectedDirectory = workDirectory == null ? defaultWorkDirectory : workDirectory;
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(selectedDirectory.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(output == null ? ProcessBuilder.Redirect.DISCARD
                        : ProcessBuilder.Redirect.to(output.toFile()));
        // 媒体解码器不得继承初始化凭据、数据库凭据或 Provider 密钥。
        builder.environment().clear();
        builder.environment().put("TMPDIR", selectedDirectory.toString());
        Process process = null;
        try {
            process = builder.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (shouldCancel != null && shouldCancel.getAsBoolean()) {
                    throw new CancellationException("Media export canceled");
                }
                if (onTick != null) {
                    onTick.run();
                }
                if (System.nanoTime() >= deadline) {
                    throw new MediaToolException("Media tool timed out", false, null);
                }
            }
            if (process.exitValue() != 0) {
                throw new MediaToolException("Media tool failed with exit "
                        + process.exitValue(), nonzeroIsInvalidInput, null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaToolException("Media tool interrupted", false, exception);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new MediaToolException("Media tool could not start", false, exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new MediaToolException("Media tool did not stop after termination",
                                false, null);
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new MediaToolException("Media tool termination interrupted",
                            false, failure);
                }
            }
        }
    }
}
