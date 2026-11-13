package dev.agenvas.canvas.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.canvas.domain.CanvasItem;
import dev.agenvas.canvas.domain.MediaUploadPurpose;
import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.testing.CreationMessagesTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

class CanvasMediaDerivationDraftTest {
    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID artifactId = UUID.randomUUID();
    private final UUID parent = UUID.randomUUID();
    private final CanvasItemRepository items = mock(CanvasItemRepository.class);
    private final MediaDraftService drafts = mock(MediaDraftService.class);
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final CanvasConnectionService connections = mock(CanvasConnectionService.class);
    private final ProjectEventService events = mock(ProjectEventService.class);
    private final dev.agenvas.character.application.CharacterService characters = mock(
            dev.agenvas.character.application.CharacterService.class);
    private final CanvasService canvas = new CanvasService(projects, artifacts,
            drafts, items, connections,
            events, new ObjectMapper(), Clock.systemUTC(),
            new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("characters",
                    characters))
                    .getBeanProvider(dev.agenvas.character.application.CharacterService.class),
            dev.agenvas.testing.CreationMessagesTestSupport.messages());

    @BeforeEach
    void source() {
        Instant now = Instant.now();
        when(projects.getRetainedProject(owner, project)).thenReturn(new Project(project, owner,
                "Synthetic project", Project.AspectRatio.SQUARE_1_1, Project.Status.ACTIVE, 0, 0, now, now, null));
        CanvasItem item = sourceItem("Source");
        when(items.findForUpdate(owner, project, source)).thenReturn(Optional.of(item));
        when(items.list(owner, project)).thenReturn(List.of(item));
        when(items.create(any())).thenReturn(true);
        ArtifactService.ArtifactView view = mock(ArtifactService.ArtifactView.class);
        Artifact artifact = mock(Artifact.class);
        when(artifact.kind()).thenReturn(Artifact.Kind.IMAGE);
        when(view.artifact()).thenReturn(artifact);
        when(artifacts.get(owner, project, artifactId)).thenReturn(view);
        MediaDraft draft = mock(MediaDraft.class);
        when(draft.version()).thenReturn(5L);
        when(drafts.get(owner, project, source)).thenReturn(draft);
    }

    @Test
    void markupRequiresAnExactSourceAndIndependentTargetBeforeAnyWrite() {
        assertThatThrownBy(() -> canvas.uploadVersion(owner, project, source, target, 0,
                new ObjectMapper().createObjectNode(), MediaUploadPurpose.BRUSH_MARKUP, null))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> canvas.uploadVersion(owner, project, source, source, 0,
                new ObjectMapper().createObjectNode(), MediaUploadPurpose.BRUSH_MARKUP, parent))
                .isInstanceOf(ApiProblemException.class);
        verify(items, never()).create(any());
    }

    @Test
    void directorRequiresASceneAndOtherUploadsRejectDirectorMetadataBeforeAnyWrite() {
        var scene = new ObjectMapper().createObjectNode();
        assertThatThrownBy(() -> canvas.uploadVersion(owner, project, source, target, 0,
                scene, MediaUploadPurpose.DIRECTOR_3D, parent, null))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> canvas.uploadVersion(owner, project, source, target, 0,
                scene, MediaUploadPurpose.UPLOAD, null, scene))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> canvas.uploadVersion(owner, project, source, target, 0,
                scene, MediaUploadPurpose.BRUSH_MARKUP, parent, scene))
                .isInstanceOf(ApiProblemException.class);
        verify(items, never()).create(any());
    }

    @Test
    void editingCreatesAFreshDraftAndKeepsOnlyTheLineageReference() {
        CanvasItem result = canvas.forkMediaDerivationWithinChange(owner, project, source,
                target, 5, 0, "深度图");
        assertThat(result.title()).isEqualTo("Source · 深度图");
        verify(drafts).initializeWithinChange(project, target, false);
        verify(drafts, never()).duplicateWithinChange(any(), any(), any(), any(), anyLong());
        verify(connections).createMediaDerivationWithinChange(owner, project, source, target, parent);
        verify(characters, never()).copyBindingsWithinChange(any(), any(), any(), any());
        verify(characters, never()).copyFrozenBindingsWithinChange(any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"en, Source · Crop", "zh, Source · 裁剪", "ru, Source · Обрезка", "ja, Source · 切り抜き"})
    void derivesFreshTitlesWithTheLocalizedBuiltInSuffix(String language, String expected) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", language);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            String suffix = CreationMessagesTestSupport.messages().text(ImageOperation.CROP.resultLabel());
            var result = canvas.forkMediaDerivationWithinChange(owner, project, source, target, 5, 0, suffix);
            assertThat(result.title()).isEqualTo(expected);
            if (language.equals("en"))
                assertThat(result.title()).doesNotContainPattern("\\p{IsHan}");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void preservesTheUserTitleWhenCreatingAnEnglishOperationResult() {
        CanvasItem item = sourceItem("我的原图 😀");
        when(items.findForUpdate(owner, project, source)).thenReturn(Optional.of(item));
        when(items.list(owner, project)).thenReturn(List.of(item));
        String suffix = CreationMessagesTestSupport.messages().text(ImageOperation.CROP.resultLabel());
        var result = canvas.forkMediaDerivationWithinChange(owner, project, source, target, 5, 0, suffix);
        assertThat(result.title()).isEqualTo("我的原图 😀 · Crop");
        assertThat(item.title()).isEqualTo("我的原图 😀");
    }

    @ParameterizedTest
    @CsvSource({"en, Upload", "zh, 上传", "ru, Загрузка", "ja, アップロード"})
    void uploadCreatesItsOwnLocalizedDefaultTitle(String language, String suffix) {
        var inserted = new AtomicReference<CanvasItem>();
        when(items.create(any())).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return true;
        });
        when(items.find(owner, project, target)).thenAnswer(call -> Optional.ofNullable(inserted.get()));
        when(items.selectVersion(eq(owner), eq(project), eq(target), anyLong(), any(), any())).thenReturn(true);
        var version = mock(ArtifactVersion.class);
        when(version.id()).thenReturn(UUID.randomUUID());
        when(version.createdAt()).thenReturn(Instant.now());
        when(artifacts.appendUserMediaVersionWithinChange(eq(owner), eq(project), eq(artifactId), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull())).thenReturn(version);
        when(events.recordChange(eq(owner), eq(project), any())).thenAnswer(call -> {
            Supplier<?> mutation = call.getArgument(2);
            var change = (ProjectEventService.Change<?>) mutation.get();
            return new ProjectEventService.RecordedChange<>(change.value(), null);
        });
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", language);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var entry = canvas.uploadVersion(owner, project, source, target, 0,
                    new ObjectMapper().createObjectNode(), MediaUploadPurpose.UPLOAD, null);
            assertThat(entry.item().title()).isEqualTo("Source · " + suffix);
            assertThat(inserted.get().title()).isEqualTo(entry.item().title());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void additionalProviderResultsInheritCharactersAfterTheirFrozenDraftIsInitialized() {
        var frozen = new ObjectMapper().createObjectNode().put("prompt", "Old applied character description");
        CanvasItem result = canvas.placeTaskMediaOutputWithinChange(owner, project, source, artifactId, 1, frozen);
        var sequence = org.mockito.Mockito.inOrder(drafts, characters);
        sequence.verify(drafts).initializeFrozenWithinChange(project, result.id(), Artifact.Kind.IMAGE, frozen);
        sequence.verify(characters).copyFrozenBindingsWithinChange(owner, project, result.id(), frozen);
    }

    @Test
    void additionalBatchOutputsStillCopyTheDraftThatTheirGenerationUses() {
        CanvasItem result = canvas.forkMediaOutputWithinChange(owner, project, source, target, 5, 1);
        assertThat(result.title()).isEqualTo("Source");
        verify(drafts).duplicateBatchOutputWithinChange(owner, project, source, target, 5);
        verify(drafts, never()).initializeWithinChange(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void staleSourceDraftDoesNotCreateAPartialDerivation() {
        assertThatThrownBy(() -> canvas.forkMediaDerivationWithinChange(owner, project,
                source, target, 4, 0, "裁剪")).isInstanceOf(ApiProblemException.class);
        verify(items, never()).create(any());
        verify(drafts, never()).initializeWithinChange(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void keepsTheOperationSuffixWhenTruncatingLongTitlesWithoutSplittingAnEmoji() {
        CanvasItem item = sourceItem("图".repeat(153) + "😀" + "尾".repeat(5));
        when(items.findForUpdate(owner, project, source)).thenReturn(Optional.of(item));
        when(items.list(owner, project)).thenReturn(List.of(item));
        CanvasItem result = canvas.forkMediaDerivationWithinChange(owner, project, source,
                target, 5, 0, "深度图");
        assertThat(result.title()).isEqualTo("图".repeat(153) + " · 深度图");
    }

    private CanvasItem sourceItem(String title) {
        Instant now = Instant.now();
        return new CanvasItem(source, project, CanvasItem.SubjectType.ARTIFACT,
                artifactId, parent, title, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("280"), new BigDecimal("240"), 0, null, false, 0, now, now);
    }
}
