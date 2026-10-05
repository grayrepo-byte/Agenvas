package dev.agenvas.artifact.api;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.identity.application.AdminPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 产物创建、读取、追加不可变版本和选择历史版本的 REST 边界。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/artifacts")
public class ArtifactController {

    /** 校验项目权限、正文 Schema、版本引用并提交产物操作。 */
    private final ArtifactService artifacts;

    /** 注入产物应用服务；控制器不直接访问产物仓储。
     * @param artifacts 执行项目授权、版本读取和手工产物创建
     */
    public ArtifactController(ArtifactService artifacts) {
        this.artifacts = artifacts;
    }

    /** Include resources whose CanvasItem was removed so they can be placed again. */
    @GetMapping
    public ArtifactListResponse list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId) {
        return new ArtifactListResponse(artifacts.listProject(principal.userId(), projectId)
                .stream().map(ArtifactResponse::from).toList());
    }

    /** 使用 Idempotency-Key 创建首个用户版本；同键重放通过响应头标记。 */
    @PostMapping
    public ResponseEntity<ArtifactResponse> create(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateArtifactRequest request) {
        ArtifactService.CreateResult result = artifacts.createIdempotent(
                principal.userId(), projectId, request.kind(), request.title(), request.content(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(ArtifactResponse.from(result.view()));
    }

    /** 读取项目范围内的稳定产物身份和资源默认版本。 */
    @GetMapping("/{artifactId}")
    public ArtifactResponse get(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {
        return ArtifactResponse.from(
                artifacts.get(principal.userId(), projectId, artifactId));
    }

    /** 按 expectedVersion 追加完整正文并切换当前版本，不修改既有版本内容。 */
    @PostMapping("/{artifactId}/revisions")
    public ResponseEntity<ArtifactResponse> revise(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody ReviseArtifactRequest request) {
        ArtifactService.ArtifactView view = artifacts.revise(
                principal.userId(),
                projectId,
                artifactId,
                request.expectedVersion(),
                request.title(),
                request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(ArtifactResponse.from(view));
    }

    /** 列出产物的不可变版本历史，按服务端顺序返回。 */
    @GetMapping("/{artifactId}/versions")
    public ArtifactVersionListResponse listVersions(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId) {
        return new ArtifactVersionListResponse(artifacts
                .listVersions(principal.userId(), projectId, artifactId)
                .stream()
                .map(ArtifactVersionResponse::from)
                .toList());
    }

    /** 按产物预期版本明确切换资源库默认版本，不产生新正文。 */
    @PostMapping("/{artifactId}/set-default-version")
    public ArtifactResponse setDefaultVersion(
            @AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId,
            @PathVariable UUID artifactId,
            @Valid @RequestBody SelectArtifactVersionRequest request) {
        return ArtifactResponse.from(artifacts.setResourceDefaultVersion(
                principal.userId(),
                projectId,
                artifactId,
                request.versionId(),
                request.expectedVersion()));
    }

    /**
     * 创建产物的完整请求正文。
     *
     * @param kind 正文所属产物类型
     * @param title 展示标题，长度不超过 160 字符
     * @param content 符合该类型 Schema 的首个完整版本正文
     */
    public record CreateArtifactRequest(
            @NotNull Artifact.Kind kind,
            @NotBlank @Size(max = 160) String title,
            JsonNode content) {}

    /**
     * 整体替换产物正文的请求；旧版本仍保留。
     *
     * @param expectedVersion 客户端读取到的产物乐观锁版本
     * @param title 新标题；null 时保留原标题
     * @param content 新版本完整正文，不能只提交差异字段
     */
    public record ReviseArtifactRequest(
            @PositiveOrZero long expectedVersion,
            @Size(min = 1, max = 160) String title,
            @NotNull JsonNode content) {}

    /**
     * 选择已有历史版本的并发控制请求。
     *
     * @param versionId 要设为当前版本的历史版本 ID
     * @param expectedVersion 客户端读取到的产物版本
     */
    public record SelectArtifactVersionRequest(
            @NotNull UUID versionId, @PositiveOrZero long expectedVersion) {}

    /**
     * 稳定产物身份和当前选中版本的 API 投影。
     *
     * @param id 产物 ID
     * @param projectId 所属项目 ID
     * @param kind 产物类型
     * @param title 当前展示标题
     * @param resourceDefaultVersionId 资源库默认版本 ID
     * @param version 产物并发控制版本
     * @param createdAt 创建时间
     * @param updatedAt 最近更新时间
     * @param resourceDefaultVersion 资源库默认版本的完整投影
     */
    public record ArtifactResponse(
            UUID id,
            UUID projectId,
            Artifact.Kind kind,
            String title,
            UUID resourceDefaultVersionId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            ArtifactVersionResponse resourceDefaultVersion) {

        /** 将领域产物与当前版本组合为 API 响应。 */
        public static ArtifactResponse from(ArtifactService.ArtifactView view) {
            Artifact artifact = view.artifact();
            return new ArtifactResponse(
                    artifact.id(),
                    artifact.projectId(),
                    artifact.kind(),
                    artifact.title(),
                    artifact.resourceDefaultVersionId(),
                    artifact.version(),
                    artifact.createdAt(),
                    artifact.updatedAt(),
                    view.resourceDefaultVersion() == null ? null
                            : ArtifactVersionResponse.from(view.resourceDefaultVersion()));
        }
    }

    /**
     * 不可变正文版本及其精确类型化输入引用。
     *
     * @param id 版本 ID
     * @param versionNo 产物内递增的版本序号
     * @param schemaVersion 正文 Schema 版本
     * @param content 完整不可变正文
     * @param inputReferences 正文读取的精确历史版本
     * @param createdByKind 版本创建来源
     * @param runId 产生该版本的 Run；用户版本可为空
     * @param createdAt 创建时间
     */
    public record ArtifactVersionResponse(
            UUID id,
            int versionNo,
            int schemaVersion,
            UUID baseVersionId,
            JsonNode frozenInput,
            JsonNode content,
            List<InputReferenceResponse> inputReferences,
            ArtifactVersion.CreatedByKind createdByKind,
            UUID runId,
            Instant createdAt) {

        /** 将内部版本转换为包含语义引用、但不含存储路径的 API 投影。 */
        public static ArtifactVersionResponse from(ArtifactVersion version) {
            return new ArtifactVersionResponse(
                    version.id(),
                    version.versionNo(),
                    version.schemaVersion(),
                    version.baseVersionId(),
                    version.frozenInput(),
                    version.content(),
                    version.inputReferences().stream()
                            .map(InputReferenceResponse::from)
                            .toList(),
                    version.createdByKind(),
                    version.runId(),
                    version.createdAt());
        }
    }

    /**
     * 对外显示的语义输入引用。
     *
     * @param versionId 被读取的精确版本 ID
     * @param role 该引用在正文中的角色
     * @param order 同角色输入顺序
     * @param kind 预期的产物类型
     */
    public record InputReferenceResponse(
            UUID versionId, String role, int order, Artifact.Kind kind) {

        /** 将领域引用映射为 API 类型名。 */
        static InputReferenceResponse from(ArtifactVersion.InputReference reference) {
            return new InputReferenceResponse(
                    reference.versionId(),
                    reference.role(),
                    reference.order(),
                    reference.expectedKind());
        }
    }

    /**
     * 不可变版本历史响应。
     *
     * @param items 按版本顺序返回的正文版本
     */
    public record ArtifactVersionListResponse(List<ArtifactVersionResponse> items) {}

    public record ArtifactListResponse(List<ArtifactResponse> items) {}
}
