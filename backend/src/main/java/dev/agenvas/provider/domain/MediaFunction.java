package dev.agenvas.provider.domain;

import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.task.domain.Task;

/** Qualified tool identities keep image and video routes independent even when operation names match. */
public enum MediaFunction {
    IMAGE_SMART_EDIT(ImageOperation.SMART_EDIT),
    IMAGE_RELIGHT(ImageOperation.RELIGHT),
    IMAGE_OUTPAINT(ImageOperation.OUTPAINT),
    IMAGE_THREE_VIEW(ImageOperation.THREE_VIEW),
    IMAGE_LAYER_SPLIT(ImageOperation.LAYER_SPLIT),
    IMAGE_EXPRESSION_EDIT(ImageOperation.EXPRESSION_EDIT),
    IMAGE_REMOVE_BACKGROUND(ImageOperation.REMOVE_BACKGROUND),
    IMAGE_OBJECT_REMOVE(ImageOperation.OBJECT_REMOVE),
    IMAGE_VIEW_ANGLE(ImageOperation.VIEW_ANGLE),
    IMAGE_DEPTH_MAP(ImageOperation.DEPTH_MAP),
    IMAGE_UPSCALE(ImageOperation.UPSCALE),
    IMAGE_CROP(ImageOperation.CROP),
    IMAGE_ROTATE(ImageOperation.ROTATE),
    IMAGE_FLIP_HORIZONTAL(ImageOperation.FLIP_HORIZONTAL),
    IMAGE_FLIP_VERTICAL(ImageOperation.FLIP_VERTICAL),
    VIDEO_DEPTH_MAP(VideoOperation.DEPTH_MAP),
    VIDEO_EXTRACT_AUDIO(VideoOperation.EXTRACT_AUDIO),
    VIDEO_UPSCALE(VideoOperation.UPSCALE);

    private final ImageOperation imageOperation;
    private final VideoOperation videoOperation;

    MediaFunction(ImageOperation operation) { this.imageOperation = operation; this.videoOperation = null; }
    MediaFunction(VideoOperation operation) { this.imageOperation = null; this.videoOperation = operation; }
    public ImageOperation imageOperation() { return imageOperation; }
    public VideoOperation videoOperation() { return videoOperation; }
    public Task.Kind taskKind() { return imageOperation == null ? videoOperation.taskKind() : Task.Kind.IMAGE_GENERATION; }
    public static MediaFunction forImage(ImageOperation operation) { return valueOf("IMAGE_" + operation.name()); }
    public static MediaFunction forVideo(VideoOperation operation) { return valueOf("VIDEO_" + operation.name()); }
}
