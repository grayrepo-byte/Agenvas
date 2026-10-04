package dev.agenvas.artifact.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.shared.error.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResourceCatalogServiceTest {
    private final ArtifactRepository repository = mock(ArtifactRepository.class);
    private final ResourceCatalogService service = new ResourceCatalogService(repository);
    private final UUID owner = UUID.randomUUID();

    @Test
    void pagesFromTheLastReturnedResultWithoutLosingTimestampPrecision() {
        Instant createdAt = Instant.parse("2026-10-04T00:00:00.123456Z");
        var first = result(createdAt);
        var extra = result(createdAt);
        when(repository.listResources(eq(owner), eq(Artifact.Kind.IMAGE), eq("Example"), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(first, extra));
        var page = service.list(owner, Artifact.Kind.IMAGE, " Example ", null, 1);
        assertThat(page.items()).containsExactly(first);
        when(repository.listResources(eq(owner), any(), any(), eq(createdAt), eq(first.versionId()), anyInt()))
                .thenReturn(List.of(extra));
        var lastPage = service.list(owner, Artifact.Kind.IMAGE, "Example", page.nextCursor(), 1);
        assertThat(lastPage.items()).containsExactly(extra);
        assertThat(lastPage.nextCursor()).isNull();
        verify(repository).listResources(owner, Artifact.Kind.IMAGE, "Example", createdAt, first.versionId(), 2);
    }

    @Test
    void rejectsCorruptCursorsAndOutOfRangeLimitsWithAStableProblemCode() {
        String invalidTimestamp = Base64.getUrlEncoder().encodeToString(
                ("invalid|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("!", invalidTimestamp, "a".repeat(ResourceCatalogService.MAX_CURSOR_LENGTH + 1))) {
            assertThatThrownBy(() -> service.list(owner, null, "", cursor, null))
                    .isInstanceOfSatisfying(ApiProblemException.class, error -> {
                        assertThat(error.code()).isEqualTo("RESOURCE_QUERY_INVALID");
                        assertThat(error.status().value()).isEqualTo(400);
                    });
        }
        for (int limit : List.of(0, ResourceCatalogService.MAX_PAGE_SIZE + 1)) {
            assertThatThrownBy(() -> service.list(owner, null, "", null, limit)).isInstanceOf(ApiProblemException.class);
        }
    }

    @Test
    void rejectsTextFiltersAndAcceptsAudio() {
        assertThatThrownBy(() -> service.list(owner, Artifact.Kind.TEXT, "", null, null))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_QUERY_INVALID"));
        when(repository.listResources(eq(owner), eq(Artifact.Kind.AUDIO), eq(""), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());
        assertThat(service.list(owner, Artifact.Kind.AUDIO, "", null, null).items()).isEmpty();
        verify(repository).listResources(owner, Artifact.Kind.AUDIO, "", null, null, ResourceCatalogService.DEFAULT_PAGE_SIZE + 1);
    }

    private ArtifactRepository.ResourceResult result(Instant createdAt) {
        return new ArtifactRepository.ResourceResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Synthetic project", "Example", Artifact.Kind.IMAGE, 1,
                new ObjectMapper().createObjectNode().put("assetId", UUID.randomUUID().toString()), createdAt);
    }
}
