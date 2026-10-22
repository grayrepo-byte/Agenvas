package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import org.springframework.stereotype.Component;

/** Local deterministic editing; no video generation or mouth replacement provider is involved. */
@Component
public class LocalVideoCompositionAdapter implements MediaAdapter {
    private final LocalVideoCompositionProcessor processor;
    public LocalVideoCompositionAdapter(LocalVideoCompositionProcessor processor) { this.processor = processor; }
    @Override public String adapterId() { return MediaAdapterRegistry.LOCAL_VIDEO_COMPOSITOR; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.VIDEO_GENERATION; }
    @Override public String preflightFailure(AttemptContext context) { return processor.preflight(context); }
    @Override public Submission submit(AttemptContext context) { return processor.submit(context); }
    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("LOCAL_VIDEO_HAS_NO_EXTERNAL_REQUEST");
    }
}
