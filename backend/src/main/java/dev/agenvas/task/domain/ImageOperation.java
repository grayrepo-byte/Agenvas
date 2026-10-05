package dev.agenvas.task.domain;

/** Image post-processing semantics; the configured function binding selects the compatible processor. */
public enum ImageOperation {
    SMART_EDIT("智能编辑", true, true),
    RELIGHT("打光", true, false),
    OUTPAINT("扩图", true, false),
    THREE_VIEW("三视图", true, false),
    LAYER_SPLIT("图层分离", true, false),
    EXPRESSION_EDIT("表情调整", true, true),
    REMOVE_BACKGROUND("移除背景", true, false),
    OBJECT_REMOVE("局部擦除", true, true),
    VIEW_ANGLE("视角调整", true, false),
    DEPTH_MAP("深度图", false, false),
    UPSCALE("高清放大", false, false),
    CROP("裁剪", false, false),
    ROTATE("旋转", false, false),
    FLIP_HORIZONTAL("水平镜像", false, false),
    FLIP_VERTICAL("垂直镜像", false, false);

    private final String resultLabel;
    private final boolean cloud;
    private final boolean instructionRequired;

    ImageOperation(String resultLabel, boolean cloud, boolean instructionRequired) {
        this.resultLabel = resultLabel;
        this.cloud = cloud;
        this.instructionRequired = instructionRequired;
    }

    public boolean cloud() { return cloud; }

    /** Human-readable suffix used when naming a newly accepted result node. */
    public String resultLabel() { return resultLabel; }

    public boolean instructionRequired() { return instructionRequired; }
}
