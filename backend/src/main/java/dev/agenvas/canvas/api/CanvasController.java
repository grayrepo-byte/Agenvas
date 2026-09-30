package dev.agenvas.canvas.api;

import dev.agenvas.agent.api.AgentInstanceController.AgentResponse;
import dev.agenvas.artifact.api.ArtifactController.ArtifactResponse;
import dev.agenvas.artifact.api.ArtifactController.ArtifactVersionResponse;
import dev.agenvas.artifact.api.ArtifactController.ArtifactVersionListResponse;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 已认证的画布展示状态读取与原子命令 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canvas")
public class CanvasController {

    /** 验证项目权限、命令范围并提交卡片展示变化。 */
    private final CanvasService canvas;

    /** 注入画布布局用例服务。
     * @param canvas 校验项目访问并处理布局命令
     */
    public CanvasController(CanvasService canvas) {
        this.canvas = canvas;
    }

    /** 返回数据库中的卡片展示状态及当前业务对象投影。 */
    @GetMapping("/items")
    public CanvasResponse list(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return CanvasResponse.from(canvas.list(principal.userId(), projectId));
    }

    /** 将同批画布命令原子提交；任一命令失败时不保留部分变化。 */
    @PostMapping("/commands")
    public CanvasResponse apply(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @Valid @RequestBody CanvasCommandBatchRequest request) {
        List<CanvasService.CanvasCommand> commands =
                request.commands().stream().map(this::toCommand).toList();
        return CanvasResponse.from(canvas.apply(principal.userId(), projectId, commands));
    }

    /** Copies one saved media card branch without inheriting tasks or canvas topology. */
    @PostMapping("/items/{sourceItemId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public DuplicateCanvasItemResponse duplicate(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID sourceItemId,
            @Valid @RequestBody DuplicateCanvasItemRequest request) {
        CanvasService.DuplicateResult result = canvas.duplicate(principal.userId(), projectId,
                sourceItemId, request.targetItemId(), request.expectedSourceVersion(),
                request.expectedSourceDraftVersion(), request.x(), request.y(),
                request.width(), request.height(), request.zIndex());
        return new DuplicateCanvasItemResponse(CanvasItemResponse.from(result.item()),
                result.draft());
    }

    public record DuplicateCanvasItemRequest(@NotNull UUID targetItemId,
            @PositiveOrZero long expectedSourceVersion,
            @PositiveOrZero long expectedSourceDraftVersion,
            @NotNull BigDecimal x, @NotNull BigDecimal y,
            @NotNull BigDecimal width, @NotNull BigDecimal height, int zIndex) {}

    public record DuplicateCanvasItemResponse(CanvasItemResponse item,
            dev.agenvas.artifact.domain.MediaDraft draft) {}

    /** Node-scoped history prevents siblings and image edits from appearing as regeneration versions. */
    @GetMapping("/items/{itemId}/media-versions")
    public ArtifactVersionListResponse mediaVersions(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID itemId) {
        return new ArtifactVersionListResponse(
                canvas.listMediaVersions(principal.userId(), projectId, itemId).stream()
                        .map(ArtifactVersionResponse::from).toList());
    }

    @PostMapping("/items/{itemId}/select-media-version")
    public CanvasItemResponse selectMediaVersion(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID itemId,
            @Valid @RequestBody SelectMediaVersionRequest request) {
        return CanvasItemResponse.from(canvas.selectMediaVersion(principal.userId(), projectId,
                itemId, request.versionId(), request.expectedVersion()));
    }

    public record SelectMediaVersionRequest(@NotNull UUID versionId,
            @PositiveOrZero long expectedVersion) {}

    /** 按命令类型提取必填字段并转换为封闭的应用层命令。 */
    private CanvasService.CanvasCommand toCommand(CanvasCommandRequest request) {
        return switch (request.type()) {
            case PLACE_ARTIFACT -> new CanvasService.PlaceArtifact(
                    request.itemId(),
                    require(request.artifactId(), "artifactId"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId(),
                    request.locked() != null && request.locked());
            case PLACE_AGENT -> new CanvasService.PlaceAgent(
                    request.itemId(),
                    require(request.agentId(), "agentId"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId(),
                    request.locked() != null && request.locked());
            case UPDATE_TITLE -> new CanvasService.UpdateTitle(
                    request.itemId(),
                    require(request.expectedVersion(), "expectedVersion"),
                    require(request.title(), "title"));
            case UPDATE_LAYOUT -> new CanvasService.UpdateLayout(
                    request.itemId(),
                    require(request.expectedVersion(), "expectedVersion"),
                    require(request.x(), "x"),
                    require(request.y(), "y"),
                    require(request.width(), "width"),
                    require(request.height(), "height"),
                    require(request.zIndex(), "zIndex"),
                    request.groupId());
            case SET_LOCKED -> new CanvasService.SetLocked(
                    request.itemId(),
                    require(request.expectedVersion(), "expectedVersion"),
                    require(request.locked(), "locked"));
            case REMOVE -> new CanvasService.Remove(
                    request.itemId(), require(request.expectedVersion(), "expectedVersion"));
        };
    }

    /** 联合 DTO 按多种命令建模；选中命令缺少必需字段时返回 400。 */
    private <T> T require(T value, String field) {
        if (value == null) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "画布命令无效",
                    field + " 是当前命令的必填字段。",
                    false);
        }
        return value;
    }

    /**
     * 画布命令批次；目标卡片在同批中不可重复。
     *
     * @param commands 有序的布局命令列表，至少一条且最多 100 条
     */
    public record CanvasCommandBatchRequest(
            @NotEmpty @Size(max = 100) List<@Valid CanvasCommandRequest> commands) {}

    /**
     * 联合式命令输入；具体必填字段由 type 决定，并在转换前再次检查。
     *
     * @param type 命令类别
     * @param itemId 目标或新建画布项 ID
     * @param artifactId PLACE_ARTIFACT 的产物 ID
     * @param agentId PLACE_AGENT 的 Agent ID
     * @param expectedVersion 更新、锁定或删除时的预期画布项版本
     * @param title UPDATE_TITLE 的新卡片展示标题
     * @param x 放置或更新后的横坐标
     * @param y 放置或更新后的纵坐标
     * @param width 放置或更新后的宽度
     * @param height 放置或更新后的高度
     * @param zIndex 放置或更新后的显示层级
     * @param groupId 放置或更新后的分组
     * @param locked 初始或目标锁定状态
     */
    public record CanvasCommandRequest(
            @NotNull CommandType type,
            @NotNull UUID itemId,
            UUID artifactId,
            UUID agentId,
            @PositiveOrZero Long expectedVersion,
            @Size(max = 160) String title,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            Integer zIndex,
            UUID groupId,
            Boolean locked) {}

    /** 首版允许的画布展示操作。 */
    public enum CommandType {
        /** 创建产物卡片。 */
        PLACE_ARTIFACT,
        /** 创建 Agent 卡片。 */
        PLACE_AGENT,
        /** 更新单张卡片的展示标题。 */
        UPDATE_TITLE,
        /** 更新位置、尺寸、层级或分组。 */
        UPDATE_LAYOUT,
        /** 改变布局锁状态。 */
        SET_LOCKED,
        /** 删除卡片展示项。 */
        REMOVE
    }

    /**
     * 完整权威画布响应。
     *
     * @param items 布局及当前卡片内容投影
     */
    public record CanvasResponse(List<CanvasItemResponse> items) {

        /** 将应用层画布条目逐项映射为 API 卡片投影。 */
        public static CanvasResponse from(List<CanvasService.CanvasEntry> entries) {
            return new CanvasResponse(entries.stream().map(CanvasItemResponse::from).toList());
        }
    }

    /**
     * 持久化卡片展示状态和当前渲染所需的内容投影；artifact 与 agent 仅一个非空。
     *
     * @param id 画布项 ID
     * @param subjectType 被展示对象类型
     * @param subjectId 被展示对象 ID
     * @param selectedVersionId 该媒体卡片独立展示的版本 ID
     * @param title 当前卡片独立的展示标题
     * @param x 卡片横坐标
     * @param y 卡片纵坐标
     * @param width 卡片宽度
     * @param height 卡片高度
     * @param zIndex 显示层级
     * @param groupId 所属画布分组
     * @param locked 布局是否锁定
     * @param version 卡片展示状态的乐观锁版本
     * @param artifact 产物卡片当前版本投影
     * @param selectedVersion 该媒体卡片实际展示的完整版本投影
     * @param agent Agent 卡片当前配置投影
     */
    public record CanvasItemResponse(
            UUID id,
            CanvasItem.SubjectType subjectType,
            UUID subjectId,
            UUID selectedVersionId,
            String title,
            BigDecimal x,
            BigDecimal y,
            BigDecimal width,
            BigDecimal height,
            int zIndex,
            UUID groupId,
            boolean locked,
            long version,
            ArtifactResponse artifact,
            ArtifactVersionResponse selectedVersion,
            AgentResponse agent) {

        /** 按 subjectType 只填充对应的产物或 Agent 投影。 */
        static CanvasItemResponse from(CanvasService.CanvasEntry entry) {
            CanvasItem item = entry.item();
            return new CanvasItemResponse(
                    item.id(),
                    item.subjectType(),
                    item.subjectId(),
                    item.selectedVersionId(),
                    item.title(),
                    item.x(),
                    item.y(),
                    item.width(),
                    item.height(),
                    item.zIndex(),
                    item.groupId(),
                    item.locked(),
                    item.version(),
                    entry.artifact() == null ? null : ArtifactResponse.from(entry.artifact()),
                    entry.selectedVersion() == null ? null
                            : ArtifactVersionResponse.from(entry.selectedVersion()),
                    entry.agent() == null ? null : AgentResponse.from(entry.agent()));
        }
    }
}
