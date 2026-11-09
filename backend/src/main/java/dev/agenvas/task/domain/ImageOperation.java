package dev.agenvas.task.domain;

import dev.agenvas.shared.i18n.ApiMessage;

/** Image post-processing semantics; the configured function binding selects the compatible processor. */
public enum ImageOperation {
    SMART_EDIT("creation.image.smart-edit", true, true),
    COSTUME_EXTRACT("creation.image.costume-extract", true, false),
    RELIGHT("creation.image.relight", true, false),
    OUTPAINT("creation.image.outpaint", true, false),
    THREE_VIEW("creation.image.three-view", true, false),
    LAYER_SPLIT("creation.image.layer-split", true, false),
    EXPRESSION_EDIT("creation.image.expression-edit", true, true),
    REMOVE_BACKGROUND("creation.image.remove-background", true, false),
    OBJECT_REMOVE("creation.image.object-remove", true, true),
    VIEW_ANGLE("creation.image.view-angle", true, false),
    DEPTH_MAP("creation.image.depth-map", false, false),
    UPSCALE("creation.image.upscale", false, false),
    RESIZE("creation.image.resize", false, false),
    CROP("creation.image.crop", false, false),
    ROTATE("creation.image.rotate", false, false),
    FLIP_HORIZONTAL("creation.image.flip-horizontal", false, false),
    FLIP_VERTICAL("creation.image.flip-vertical", false, false);

    private final String resultLabelKey;
    private final boolean cloud;
    private final boolean instructionRequired;

    ImageOperation(String resultLabelKey, boolean cloud, boolean instructionRequired) {
        this.resultLabelKey = resultLabelKey;
        this.cloud = cloud;
        this.instructionRequired = instructionRequired;
    }

    public boolean cloud() { return cloud; }

    /** Human-readable suffix used when naming a newly accepted result node. */
    public ApiMessage resultLabel() { return ApiMessage.of(resultLabelKey); }

    public boolean instructionRequired() { return instructionRequired; }
}
