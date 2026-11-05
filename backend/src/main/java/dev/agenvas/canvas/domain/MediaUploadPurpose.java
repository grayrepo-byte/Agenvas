package dev.agenvas.canvas.domain;

import dev.agenvas.shared.i18n.ApiMessage;

/** User-edited bytes are archived directly, without a model or generation task. */
public enum MediaUploadPurpose {
    UPLOAD("creation.upload.file"),
    BRUSH_MARKUP("creation.upload.brush-markup"),
    DIRECTOR_3D("creation.upload.director-3d");

    private final String resultLabelKey;

    MediaUploadPurpose(String resultLabelKey) { this.resultLabelKey = resultLabelKey; }

    public ApiMessage resultLabel() { return ApiMessage.of(resultLabelKey); }
}
