package dev.agenvas.task.domain;

/** Video tools always pin a source version and write to an independent result node. */
public enum VideoOperation {
    DEPTH_MAP("深度视频", Task.Kind.VIDEO_GENERATION),
    EXTRACT_AUDIO("音频分离", Task.Kind.AUDIO_GENERATION),
    UPSCALE("视频高清", Task.Kind.VIDEO_GENERATION);

    private final String resultLabel;
    private final Task.Kind taskKind;

    VideoOperation(String resultLabel, Task.Kind taskKind) {
        this.resultLabel = resultLabel;
        this.taskKind = taskKind;
    }

    public String resultLabel() { return resultLabel; }
    public Task.Kind taskKind() { return taskKind; }
}
