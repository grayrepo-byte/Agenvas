package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.NativeMediaFailure;
import dev.agenvas.provider.domain.NativeMediaProfiles;
import dev.agenvas.provider.domain.NativeMediaProtocol;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class GoogleVideoPreparedMediaTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeMediaReferences references = mock(NativeMediaReferences.class);
    private final GoogleVideoPreparedMedia media = new GoogleVideoPreparedMedia(references);
    private final UUID connection = UUID.randomUUID();
    private final MediaCapabilityBinding binding = new MediaCapabilityBinding(connection, 7, UUID.randomUUID(), 3, GoogleVideoModels.VEO, "a".repeat(64));
    private final AttemptContext context = new AttemptContext(mock(Task.class), binding, UUID.randomUUID(), "synthetic-key", "operations/synthetic");
    @Test void forwardsExactOriginalBytesAndNeverNeedsMediaRelay() {
        var selected = selected(GoogleVideoModels.OMNI, GoogleVideoModels.OMNI_MODEL, "GENERATE");
        byte[] exact = {1, 2, 3};
        var asset = image();
        when(references.load(eq(context), eq(selected), any(), eq(false))).thenReturn(Map.of("image_5",
                new NativeMediaReferences.Input(asset, mapper.createObjectNode(), exact, null)));
        var result = media.load(context, selected, mapper.createObjectNode(), false);
        assertThat(result.get("image_5").bytes()).isSameAs(exact);
        assertThat(result.get("image_5").mimeType()).isEqualTo("image/png");
        assertThat(result.get("image_5").fileName()).isNull();
        verify(references).load(eq(context), eq(selected), any(), eq(false));
    }
    @Test void extensionUsesOnlyServerProvenanceMatchingFrozenConnectionAndVersion() {
        var selected = selected(GoogleVideoModels.VEO, GoogleVideoModels.VEO_MODELS.getFirst(), "EXTEND");
        ObjectNode source = source();
        when(references.load(eq(context), eq(selected), any(), eq(true))).thenReturn(Map.of("base_video",
                new NativeMediaReferences.Input(video(), source, new byte[]{1, 2, 3}, null)));
        var result = media.load(context, selected, mapper.createObjectNode(), true).get("base_video");
        assertThat(result.fileName()).isEqualTo("files/synthetic-video");
        assertThat(result.bytes()).isNull();
        for (String field : java.util.List.of("connectionId", "model", "adapterId", "fileName")) {
            ObjectNode invalid = source(); ((ObjectNode) invalid.at("/parameters/nativeProviderResult")).put(field, "untrusted");
            assertThatThrownBy(() -> GoogleVideoPreparedMedia.extensionFile(context, invalid)).isInstanceOf(NativeMediaFailure.class);
        }
        ((ObjectNode) source.at("/parameters/nativeProviderResult")).put("connectionVersion", 8);
        assertThatThrownBy(() -> GoogleVideoPreparedMedia.extensionFile(context, source)).isInstanceOf(NativeMediaFailure.class);
    }
    @Test void anUploadedVideoOrMetadataWithSignedUrlIsNotEligibleForVeoExtension() {
        assertThatThrownBy(() -> GoogleVideoPreparedMedia.extensionFile(context, mapper.createObjectNode().put("sourceType", "UPLOAD")))
                .isInstanceOf(NativeMediaFailure.class);
        var source = source();
        ((ObjectNode) source.at("/parameters/nativeProviderResult")).put("uri", "https://example.invalid/result?key=synthetic");
        assertThatThrownBy(() -> GoogleVideoPreparedMedia.extensionFile(context, source)).isInstanceOf(NativeMediaFailure.class);
    }
    private NativeMediaProfiles.Selection selected(String adapter, String model, String operation) {
        return new NativeMediaProfiles.Selection(new GoogleVideoModels(), new NativeMediaProtocol.Profile(adapter, model, operation, Task.Kind.VIDEO_GENERATION));
    }
    private ObjectNode source() {
        var content = mapper.createObjectNode();
        content.putObject("parameters").putObject("nativeProviderResult").put("adapterId", GoogleVideoModels.VEO)
                .put("model", GoogleVideoModels.VEO_MODELS.getFirst()).put("connectionId", connection.toString()).put("connectionVersion", 7)
                .put("fileName", "files/synthetic-video");
        return content;
    }
    private Asset image() { return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.IMAGE, "synthetic.png", "image/png", 3,
            "a".repeat(64), 400, 600, null, null, null, null, Instant.now()); }
    private Asset video() { return new Asset(UUID.randomUUID(), UUID.randomUUID(), Asset.MediaKind.VIDEO, "synthetic.mp4", "video/mp4", 3,
            "a".repeat(64), 1280, 720, 8000, null, null, null, Instant.now()); }
}
