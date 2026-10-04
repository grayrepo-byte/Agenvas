package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.task.domain.Task;
import dev.agenvas.task.domain.VideoOperation;
import org.springframework.stereotype.Component;

@Component
public class LocalVideoAudioAdapter implements MediaAdapter {
    private final LocalVideoProcessor processor;
    public LocalVideoAudioAdapter(LocalVideoProcessor processor) { this.processor = processor; }
    public String adapterId() { return MediaAdapterRegistry.LOCAL_VIDEO_AUDIO_EXTRACTOR; }
    public boolean supports(PortInput input) { return input.kind() == Task.Kind.AUDIO_GENERATION; }
    public String preflightFailure(AttemptContext context) { return processor.preflight(context, VideoOperation.EXTRACT_AUDIO); }
    public Submission submit(AttemptContext context) { return processor.submit(context, VideoOperation.EXTRACT_AUDIO); }
    public Submission reconcile(AttemptContext context) { return new Submission.Blocked("LOCAL_VIDEO_HAS_NO_EXTERNAL_REQUEST"); }
}
