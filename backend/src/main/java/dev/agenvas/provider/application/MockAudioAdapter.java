package dev.agenvas.provider.application;

import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A deterministic audible tone, explicitly labelled; never pretends to synthesize the prompt. */
@Component
public final class MockAudioAdapter implements MediaAdapter {
    private static final int SAMPLE_RATE = 24000;
    private static final int DURATION_SECONDS = 3;
    private static final int HEADER_BYTES = 44;
    private static final double FREQUENCY_HZ = 440;
    private final AssetService assets;
    private final ObjectMapper mapper;
    public MockAudioAdapter(AssetService assets, ObjectMapper mapper) { this.assets = assets; this.mapper = mapper; }
    @Override public String adapterId() { return "MOCK_AUDIO"; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.AUDIO_GENERATION; }
    @Override public Submission submit(AttemptContext context) {
        var task = context.lease();
        var asset = assets.archiveTaskAudio(context.ownerId(), task.projectId(), task.id(),
                () -> new ByteArrayInputStream(tone()));
        return new Submission.CompletedArtifact(AudioResult.content(mapper, task, asset.id().toString(), true));
    }
    @Override public Submission reconcile(AttemptContext context) { return submit(context); }
    static byte[] tone() {
        int samples = SAMPLE_RATE * DURATION_SECONDS;
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + samples * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x46464952).putInt(buffer.capacity() - 8).putInt(0x45564157).putInt(0x20746d66)
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE)
                .putInt(SAMPLE_RATE * Short.BYTES).putShort((short) Short.BYTES).putShort((short) 16)
                .putInt(0x61746164).putInt(samples * Short.BYTES);
        for (int index = 0; index < samples; index++) {
            double envelope = Math.min(1, Math.min(index, samples - index - 1) / (SAMPLE_RATE * 0.04));
            buffer.putShort((short) (Math.sin(2 * Math.PI * FREQUENCY_HZ * index / SAMPLE_RATE) * 4000 * envelope));
        }
        return buffer.array();
    }
}
