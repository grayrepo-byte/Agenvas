package dev.agenvas.task.domain;

/** Accepted media explicitly chooses whether results may be projected onto a canvas. */
public enum MediaExecutionSurface {
    CANVAS, IMAGE_STUDIO, VIDEO_STUDIO;

    public static final String PROPERTY = "executionSurface";

    public static boolean isImageStudio(Task task) {
        return IMAGE_STUDIO.name().equals(task.input().path(PROPERTY).asText());
    }

    public static boolean isVideoStudio(Task task) {
        return VIDEO_STUDIO.name().equals(task.input().path(PROPERTY).asText());
    }

    public static boolean isStudio(Task task) {
        return isImageStudio(task) || isVideoStudio(task);
    }
}
