package dev.agenvas.artifact.application;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only account and project catalogs; browsing never saves LibraryEntries or changes selections. */
@Service
public class ResourceCatalogService {
    public static final int MAX_QUERY_LENGTH = 160;
    public static final int MAX_CURSOR_LENGTH = 256;
    public static final int DEFAULT_PAGE_SIZE = 40;
    public static final int MAX_PAGE_SIZE = 100;
    private static final String CURSOR_SEPARATOR = "|";
    private static final int CURSOR_PARTS = 2;
    private final ArtifactRepository artifacts;
    private final ProjectService projects;

    public ResourceCatalogService(ArtifactRepository artifacts, ProjectService projects) {
        this.artifacts = artifacts;
        this.projects = projects;
    }

    @Transactional(readOnly = true)
    public ResourcePage list(UUID ownerId, Artifact.Kind kind, String query, String encodedCursor, Integer requestedLimit) {
        if (kind == Artifact.Kind.TEXT) throw invalidRequest();
        return listResults(ownerId, null, kind, query, encodedCursor, requestedLimit);
    }

    /** Project results include nonblank text revisions; empty node identities never become resources. */
    @Transactional(readOnly = true)
    public ResourcePage listProject(UUID ownerId, UUID projectId, Artifact.Kind kind, String query,
            String encodedCursor, Integer requestedLimit) {
        projects.get(ownerId, projectId);
        return listResults(ownerId, projectId, kind, query, encodedCursor, requestedLimit);
    }

    private ResourcePage listResults(UUID ownerId, UUID projectId, Artifact.Kind kind, String query,
            String encodedCursor, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE || query.length() > MAX_QUERY_LENGTH) throw invalidRequest();
        Cursor cursor = decode(encodedCursor);
        var rows = artifacts.listResources(ownerId, projectId, kind, query.trim(), cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id(), limit + 1);
        boolean hasMore = rows.size() > limit;
        var items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = hasMore ? encode(items.getLast()) : null;
        return new ResourcePage(items, next);
    }

    private Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        if (encoded.length() > MAX_CURSOR_LENGTH) throw invalidRequest();
        try {
            String value = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != CURSOR_PARTS) throw invalidRequest();
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeException invalidCursor) {
            throw invalidRequest();
        }
    }

    private String encode(ArtifactRepository.ResourceResult item) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (item.createdAt() + CURSOR_SEPARATOR + item.versionId()).getBytes(StandardCharsets.UTF_8));
    }

    private ApiProblemException invalidRequest() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "RESOURCE_QUERY_INVALID",
                ApiMessage.of("api.resource-catalog.invalid-query"),
                ApiMessage.of("api.resource-catalog.check-query"), false);
    }

    private record Cursor(Instant createdAt, UUID id) {}
    public record ResourcePage(List<ArtifactRepository.ResourceResult> items, String nextCursor) {}
}
