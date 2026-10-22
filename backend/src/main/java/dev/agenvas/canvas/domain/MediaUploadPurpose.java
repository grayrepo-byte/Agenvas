package dev.agenvas.canvas.domain;

/** User-edited bytes are archived directly, without a model or generation task. */
public enum MediaUploadPurpose {
    UPLOAD("上传"),
    BRUSH_MARKUP("画笔标注"),
    DIRECTOR_3D("3D 导演台");

    private final String resultLabel;

    MediaUploadPurpose(String resultLabel) { this.resultLabel = resultLabel; }

    public String resultLabel() { return resultLabel; }
}
