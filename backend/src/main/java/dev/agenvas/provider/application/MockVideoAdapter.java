package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the local FFmpeg demo renderer; it never calls an external model. */
@Component
public class MockVideoAdapter implements MediaAdapter {
    private final MockVideoRenderer renderer;

    public MockVideoAdapter(MockVideoRenderer renderer) { this.renderer = renderer; }

    @Override public String adapterId() { return "MOCK_VIDEO"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.VIDEO_GENERATION
                && input.durationSeconds() >= 1 && input.durationSeconds() <= 30;
    }

    @Override public Submission submit(AttemptContext context) {
        return renderer.executeBound(context.lease(),
                UUID.fromString(context.requestKey()));
    }

    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("MOCK_HAS_NO_EXTERNAL_REQUEST");
    }
}
