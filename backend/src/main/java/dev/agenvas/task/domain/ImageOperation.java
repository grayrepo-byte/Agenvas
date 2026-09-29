package dev.agenvas.task.domain;

/** Image-only post-processing commands; cloud operations must use OpenAI or Google. */
public enum ImageOperation {
    SMART_EDIT(true, true),
    RELIGHT(true, false),
    OUTPAINT(true, false),
    THREE_VIEW(true, false),
    LAYER_SPLIT(true, false),
    EXPRESSION_EDIT(true, true),
    BRUSH_MARKUP(true, true),
    REMOVE_BACKGROUND(true, false),
    OBJECT_REMOVE(true, true),
    VIEW_ANGLE(true, false),
    DEPTH_MAP(false, false),
    UPSCALE(false, false),
    CROP(false, false),
    ROTATE(false, false),
    FLIP_HORIZONTAL(false, false),
    FLIP_VERTICAL(false, false);

    private final boolean cloud;
    private final boolean instructionRequired;

    ImageOperation(boolean cloud, boolean instructionRequired) {
        this.cloud = cloud;
        this.instructionRequired = instructionRequired;
    }

    public boolean cloud() { return cloud; }

    public boolean instructionRequired() { return instructionRequired; }
}
