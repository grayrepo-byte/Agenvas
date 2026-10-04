package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.MediaPayload;
import dev.agenvas.provider.domain.ProviderResultManifest;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RunningHubAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunningHubAdapter adapter = new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION,
            null, null, null, null, null, mapper, Clock.systemUTC());
    private final RunningHubDefinition definition = new RunningHubDefinition(1, "V2", RunningHubDefinition.TargetType.AI_APP,
            "2039199752025280513", List.of(), List.of(), List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.VIDEO, true, 1)),
            "default", false, false, null, null, null);

    @Test void realProviderNumericStringsAreRecordedWithoutLosingNullOrInferringCurrency() {
        var response = mapper.readTree("""
            {"status":"SUCCESS","results":[{"nodeId":"13","outputType":"mp4","url":"https://custom-cdn.example.com/video.mp4"}],
             "usage":{"consumeMoney":null,"consumeCoins":"3","taskCostTime":"143","thirdPartyConsumeMoney":"0.4","billingSeconds":"4"}}
            """);
        var manifest = adapter.manifest(response, definition, "https://www.runninghub.ai");
        assertThat(manifest.usage().path("consumeMoney").isNull()).isTrue();
        assertThat(manifest.usage().path("consumeCoins").decimalValue()).isEqualByComparingTo(new BigDecimal("3"));
        assertThat(manifest.usage().path("taskCostTime").decimalValue()).isEqualByComparingTo(new BigDecimal("143"));
        assertThat(manifest.usage().path("thirdPartyConsumeMoney").decimalValue()).isEqualByComparingTo(new BigDecimal("0.4"));
        assertThat(manifest.usage().has("currency")).isFalse();
        assertThat(manifest.usage().has("billingSeconds")).isFalse();
    }

    @Test void invalidUsageDoesNotCreateMisleadingAmounts() {
        var response = mapper.readTree("""
            {"results":[{"nodeId":"13","outputType":"mp4","url":"https://cdn.example.com/video.mp4"}],
             "usage":{"consumeMoney":"NaN","consumeCoins":"-1","taskCostTime":"1e999999","thirdPartyConsumeMoney":{"amount":1}}}
            """);
        assertThat(adapter.manifest(response, definition, "https://www.runninghub.ai").usage().isEmpty()).isTrue();
    }

    @Test void zipOnlyOutputUsesItsNodeMappingAndPinsExtractedBytes() throws Exception {
        RunningHubClient client = mock(RunningHubClient.class);
        byte[] zip = zip("output/视频.mp4");
        when(client.download("https://www.runninghub.ai", "https://cdn.example.com/result.zip"))
                .thenAnswer(ignored -> new MediaPayload(new ByteArrayInputStream(zip), "application/zip"));
        var adapter = adapter(client, null);
        var manifest = adapter.manifest(zipResponse(), definition, "https://www.runninghub.ai");
        assertThat(manifest.results()).hasSize(1);
        var result = manifest.results().getFirst();
        assertThat(result.nodeId()).isEqualTo("89");
        assertThat(result.kind()).isEqualTo(RunningHubDefinition.OutputKind.VIDEO);
        assertThat(result.primary()).isTrue();
        assertThat(result.archiveEntry().name()).isEqualTo("output/视频.mp4");
        assertThat(result.archiveEntry().sha256()).matches("[0-9a-f]{64}");
        assertThat(mapper.readValue(mapper.writeValueAsString(manifest), ProviderResultManifest.class)).isEqualTo(manifest);
        var wrongNode = new RunningHubDefinition(1, "V2", definition.targetType(), definition.targetId(), List.of(), List.of(),
                List.of(new RunningHubDefinition.Output("90", RunningHubDefinition.OutputKind.VIDEO, true, 1)), "default", false, false, null, null, null);
        assertThatThrownBy(() -> adapter.manifest(zipResponse(), wrongNode, "https://www.runninghub.ai")).isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void zipMembersShareOneDownloadAndChangedBytesCannotReplaceThePinnedResult() throws Exception {
        RunningHubClient client = mock(RunningHubClient.class);
        byte[] zip = zip("a.mp4", "b.mp4");
        when(client.download("https://www.runninghub.ai", "https://cdn.example.com/result.zip"))
                .thenAnswer(ignored -> new MediaPayload(new ByteArrayInputStream(zip), "application/zip"));
        var catalog = mock(JooqMediaCapabilityRepository.class);
        var binding = new MediaCapabilityBinding(UUID.randomUUID(), 1, UUID.randomUUID(), 1, "RUNNINGHUB_VIDEO", "hash");
        var connection = new JooqMediaCapabilityRepository.ConnectionVersion(binding.connectionId(), 1, "https://www.runninghub.ai", "origin-hash", null, null, null, null);
        when(catalog.snapshotAt(binding.capabilityId(), 1, binding.connectionId(), 1)).thenReturn(Optional.of(
                new JooqMediaCapabilityRepository.Snapshot(null, null, connection, "RUNNINGHUB_VIDEO", "hash", null)));
        var adapter = adapter(client, catalog);
        var two = new RunningHubDefinition(1, "V2", definition.targetType(), definition.targetId(), List.of(), List.of(),
                List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.VIDEO, true, 2)), "default", false, false, null, null, null);
        var manifest = adapter.manifest(zipResponse(), two, "https://www.runninghub.ai");
        try (var downloads = adapter.openResultDownloads(new AttemptContext(null, binding, UUID.randomUUID(), null, "synthetic-task"))) {
            for (var result : manifest.results()) try (var payload = downloads.download(result)) { assertThat(payload.stream().readAllBytes()).containsExactly((byte) 1); }
            var first = manifest.results().getFirst();
            var changed = new ProviderResultManifest.Result(0, first.nodeId(), first.kind(), true, first.url(),
                    new ProviderResultManifest.ArchiveEntry(first.archiveEntry().name(), "0".repeat(64)));
            assertThatThrownBy(() -> downloads.download(changed)).isInstanceOf(RunningHubClient.ProtocolFailure.class);
        }
        verify(client, times(2)).download("https://www.runninghub.ai", "https://cdn.example.com/result.zip");
        assertThatThrownBy(() -> adapter.manifest(zipResponse(), definition, "https://www.runninghub.ai"))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    @Test void invalidZipUrlsAndUnknownOutputTypesAreRejected() {
        var response = mapper.readTree("""
            {"results":[{"nodeId":"157","outputType":"zip","url":"http://127.0.0.1/never-download.zip"},
                        {"nodeId":"155","outputType":"mp4","url":"https://cdn.example.com/video.mp4"}]}
            """);
        assertThatThrownBy(() -> adapter.manifest(response, definition, "https://www.runninghub.ai"))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> adapter.manifest(mapper.readTree("{\"results\":[{\"outputType\":\"zip\"}]}"), definition, "https://www.runninghub.ai"))
                .isInstanceOf(dev.agenvas.provider.infrastructure.RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> adapter.manifest(mapper.readTree("{\"results\":[{\"outputType\":\"html\"}]}"), definition, "https://www.runninghub.ai"))
                .isInstanceOf(dev.agenvas.provider.infrastructure.RunningHubClient.ProtocolFailure.class);
    }

    @Test void oldDirectMediaManifestsRemainReadableWithoutArchiveMetadata() {
        var old = mapper.readValue("""
            {"schemaVersion":1,"results":[{"ordinal":0,"nodeId":"1","kind":"VIDEO","primary":true,"url":"https://cdn.example.com/video.mp4"}],"usage":null}
            """, ProviderResultManifest.class);
        assertThat(old.results().getFirst().archiveEntry()).isNull();
    }

    @Test void zipWithoutPrimaryMediaAndUnmappedMediaCannotCreateAResultManifest() throws Exception {
        var client = mock(RunningHubClient.class);
        byte[] metadata = zip("README.txt");
        byte[] unmapped = zip("audio.wav", "video.mp4");
        when(client.download("https://www.runninghub.ai", "https://cdn.example.com/result.zip"))
                .thenReturn(new MediaPayload(new ByteArrayInputStream(metadata), "application/zip"),
                        new MediaPayload(new ByteArrayInputStream(unmapped), "application/zip"));
        var adapter = adapter(client, null);
        assertThatThrownBy(() -> adapter.manifest(zipResponse(), definition, "https://www.runninghub.ai"))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
        assertThatThrownBy(() -> adapter.manifest(zipResponse(), definition, "https://www.runninghub.ai"))
                .isInstanceOf(RunningHubClient.ProtocolFailure.class);
    }

    private RunningHubAdapter adapter(RunningHubClient client, JooqMediaCapabilityRepository catalog) {
        return new RunningHubAdapter("RUNNINGHUB_VIDEO", Task.Kind.VIDEO_GENERATION, catalog, null, null, null, client, mapper, Clock.systemUTC());
    }
    private tools.jackson.databind.JsonNode zipResponse() {
        return mapper.readTree("""
            {"results":[{"nodeId":"89","outputType":"zip","url":"https://cdn.example.com/result.zip"}]}
            """);
    }
    private static byte[] zip(String... names) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (String name : names) { zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[] { 1 }); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
}
