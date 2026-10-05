package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LocalVideoProcessorTest {
    private final ArtifactService artifacts = mock(ArtifactService.class);
    private final AssetService assets = mock(AssetService.class);
    private final MediaToolRunner tools = mock(MediaToolRunner.class);
    private final TaskService tasks = mock(TaskService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalVideoProcessor processor = new LocalVideoProcessor(artifacts, assets, tools,
            mock(LocalImageProcessorAdapter.class), tasks, mapper);
    @TempDir Path temp;

    private AttemptContext context() {
        var owner = java.util.UUID.randomUUID();
        var project = java.util.UUID.randomUUID();
        var sourceVersion = java.util.UUID.randomUUID();
        var assetId = java.util.UUID.randomUUID();
        Task task = mock(Task.class);
        when(task.id()).thenReturn(java.util.UUID.randomUUID());
        when(task.projectId()).thenReturn(project);
        var input = mapper.createObjectNode();
        input.putObject("videoOperation").put("sourceVersionId", sourceVersion.toString());
        when(task.input()).thenReturn(input);
        when(tasks.get(owner, project, task.id())).thenReturn(task);
        var version = mock(ArtifactVersion.class);
        when(version.content()).thenReturn(mapper.createObjectNode().put("assetId", assetId.toString()));
        when(artifacts.requireMediaVersionForTask(owner, project, sourceVersion, Artifact.Kind.VIDEO)).thenReturn(version);
        when(assets.get(owner, project, assetId)).thenReturn(new AssetService.AssetFile(mock(Asset.class), temp.resolve("source.mp4")));
        return new AttemptContext(task, null, owner, "synthetic-request", null);
    }

    @Test void silentArchiveIdentityIsStableAndDifferentFromAudioWithinTheSameTask() throws Exception {
        var context = context();
        var output = mock(Asset.class);
        var outputId = java.util.UUID.randomUUID();
        when(output.id()).thenReturn(outputId);
        var archiveIds = new java.util.ArrayList<java.util.UUID>();
        var ownerId = context.ownerId();
        var projectId = context.lease().projectId();
        when(assets.archiveTaskVideo(eq(ownerId), eq(projectId), any(), any()))
                .thenAnswer(call -> { archiveIds.add(call.getArgument(2)); return output; });
        var scratchDirectories = new java.util.ArrayList<Path>();
        doAnswer(call -> { scratchDirectories.add(call.getArgument(1)); return null; })
                .when(tools).ffmpegExport(any(), any(), any(), any(), any());
        var first = processor.silentVideoResult(context);
        var replay = processor.silentVideoResult(context);
        assertThat(archiveIds).hasSize(2);
        assertThat(archiveIds.get(0)).isEqualTo(archiveIds.get(1)).isNotEqualTo(context.lease().id());
        assertThat(first).isEqualTo(replay);
        assertThat(first.path("sourceTaskId").asText()).isEqualTo(context.lease().id().toString());
        assertThat(scratchDirectories).allSatisfy(directory -> assertThat(Files.exists(directory)).isFalse());
    }

    @Test void canceledProcessingCannotArchiveASilentResult() {
        var context = context();
        doThrow(new CancellationException()).when(tools).ffmpegExport(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> processor.silentVideoResult(context)).isInstanceOf(CancellationException.class);
        verify(assets, never()).archiveTaskVideo(any(), any(), any(), any());
    }

    @Test void failedRemuxCannotArchiveASilentResult() {
        var context = context();
        doThrow(new MediaToolRunner.MediaToolException("Synthetic tool failure", true, null))
                .when(tools).ffmpegExport(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> processor.silentVideoResult(context)).isInstanceOf(MediaToolRunner.MediaToolException.class);
        verify(assets, never()).archiveTaskVideo(any(), any(), any(), any());
    }
}
