package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.domain.Submission;
import dev.agenvas.task.application.TaskWorker;
import dev.agenvas.task.domain.Task;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the deterministic demo renderer behind the version-pinned adapter boundary. */
@Component
public class MockImageAdapter implements MediaAdapter {
    private final MockImageWorker renderer;

    public MockImageAdapter(MockImageWorker renderer) { this.renderer = renderer; }

    @Override public String adapterId() { return "MOCK_IMAGE"; }

    @Override public boolean supports(PortInput input) {
        return input.kind() == Task.Kind.IMAGE_GENERATION;
    }

    @Override public Submission submit(AttemptContext context) {
        TaskWorker.Outcome result = renderer.executeBound(context.lease(),
                UUID.fromString(context.requestKey()));
        return switch (result) {
            case TaskWorker.GeneratedArtifact generated ->
                    new Submission.CompletedArtifact(generated.content());
            case TaskWorker.Failed failed -> new Submission.Rejected(failed.errorCode());
            default -> new Submission.Unknown("MOCK_PROTOCOL_INVALID");
        };
    }

    @Override public Submission reconcile(AttemptContext context) {
        return new Submission.Blocked("MOCK_HAS_NO_EXTERNAL_REQUEST");
    }
}
