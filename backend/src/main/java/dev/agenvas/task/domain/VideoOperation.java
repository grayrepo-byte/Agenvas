package dev.agenvas.task.domain;

import dev.agenvas.shared.i18n.ApiMessage;

/** Video tools always pin a source version and write to an independent result node. */
public enum VideoOperation {
    DEPTH_MAP("creation.video.depth-map", Task.Kind.VIDEO_GENERATION),
    EXTRACT_AUDIO("creation.video.extract-audio", Task.Kind.AUDIO_GENERATION),
    UPSCALE("creation.video.upscale", Task.Kind.VIDEO_GENERATION);

    public static final String SILENT_VIDEO_TARGET = "silentVideoTarget";
    public static final String SILENT_VIDEO_RESULT = "SILENT_VIDEO";
    public static final ApiMessage SILENT_VIDEO_LABEL = ApiMessage.of("creation.video.silent");

    private final String resultLabelKey;
    private final Task.Kind taskKind;

    VideoOperation(String resultLabelKey, Task.Kind taskKind) {
        this.resultLabelKey = resultLabelKey;
        this.taskKind = taskKind;
    }

    public ApiMessage resultLabel() { return ApiMessage.of(resultLabelKey); }
    public Task.Kind taskKind() { return taskKind; }
}
