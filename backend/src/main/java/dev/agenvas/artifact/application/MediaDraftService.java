package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Persists generation input separately from immutable media results. */
@Service
public class MediaDraftService {
    private static final int MAX_PROMPT_LENGTH = 20_000;
    private static final int MIN_VIDEO_SECONDS = 1;
    private static final int MAX_VIDEO_SECONDS = 30;

    private final ProjectService projects;
    private final ArtifactService artifactService;
    private final ArtifactRepository artifacts;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;

    public MediaDraftService(ProjectService projects, ArtifactService artifactService,
            ArtifactRepository artifacts, ProjectEventService events, ObjectMapper mapper,
            Clock clock) {
        this.projects = projects;
        this.artifactService = artifactService;
        this.artifacts = artifacts;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MediaDraft get(UUID ownerId, UUID projectId, UUID artifactId) {
        requireMedia(ownerId, projectId, artifactId);
        return artifacts.findMediaDraft(projectId, artifactId)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND,
                        "RESOURCE_NOT_FOUND", "草稿不存在", "该媒体产物没有工作草稿。", false));
    }

    @Transactional
    public MediaDraft save(UUID ownerId, UUID projectId, UUID artifactId,
            long expectedVersion, String prompt, UUID inputImageVersionId,
            Integer durationSeconds, UUID capabilityId) {
        projects.requireActiveProject(ownerId, projectId);
        Artifact.Kind kind = requireMedia(ownerId, projectId, artifactId);
        if (expectedVersion < 0 || prompt == null || prompt.length() > MAX_PROMPT_LENGTH) {
            throw invalid("草稿版本或提示词无效。");
        }
        if (kind == Artifact.Kind.IMAGE &&
                (inputImageVersionId != null || durationSeconds != null)) {
            throw invalid("文生图草稿不能指定视频输入图或时长。");
        }
        if (kind == Artifact.Kind.VIDEO && durationSeconds != null &&
                (durationSeconds < MIN_VIDEO_SECONDS || durationSeconds > MAX_VIDEO_SECONDS)) {
            throw invalid("视频时长必须为 1–30 秒的整数。");
        }
        if (inputImageVersionId != null) {
            ArtifactRepository.VersionTarget input = artifacts
                    .findVersionTarget(projectId, inputImageVersionId)
                    .orElseThrow(() -> invalid("输入图片版本不存在于本项目。"));
            if (input.kind() != Artifact.Kind.IMAGE) {
                throw invalid("视频输入必须是图片的精确版本。");
            }
        }
        return events.recordChange(ownerId, projectId, () -> {
            MediaDraft before = artifacts.findMediaDraft(projectId, artifactId)
                    .orElseThrow(() -> new IllegalStateException("Media draft missing"));
            MediaDraft update = new MediaDraft(projectId, artifactId, prompt,
                    inputImageVersionId, durationSeconds, capabilityId,
                    before.displayMode(),
                    expectedVersion + 1, before.createdAt(), clock.instant());
            if (!artifacts.updateMediaDraft(update, expectedVersion)) {
                throw new ApiProblemException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "草稿版本冲突", "草稿已被其他操作修改；请保留本地输入并重新核对。", true);
            }
            ObjectNode payload = mapper.createObjectNode();
            payload.put("artifactId", artifactId.toString());
            payload.put("draftVersion", update.version());
            return ProjectEventService.Change.changed(update,
                    new ProjectEventService.EventDraft("media.draft.changed", 1,
                            artifactId, update.version(), payload));
        }).value();
    }

    /** The enclosing project event transaction records why the visible card face changed. */
    public void setDisplayModeWithinChange(UUID projectId, UUID artifactId,
            MediaDraft.DisplayMode mode) {
        artifacts.setMediaDraftDisplayMode(projectId, artifactId, mode, clock.instant());
    }

    private Artifact.Kind requireMedia(UUID ownerId, UUID projectId, UUID artifactId) {
        Artifact.Kind kind = artifactService.get(ownerId, projectId, artifactId).artifact().kind();
        if (kind != Artifact.Kind.IMAGE && kind != Artifact.Kind.VIDEO) {
            throw invalid("只有图片和视频产物有媒体草稿。");
        }
        return kind;
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "媒体草稿无效", detail, false);
    }
}
