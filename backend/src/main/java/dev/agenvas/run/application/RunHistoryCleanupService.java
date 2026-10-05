package dev.agenvas.run.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.project.application.ProjectService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stops expired runs before their execution ledgers are removed; no external cancellation. */
@Service
public class RunHistoryCleanupService {
    private final AgentRunRepository runs;
    private final ProjectService projects;
    private final ProjectEventService events;
    private final ObjectMapper mapper;

    public RunHistoryCleanupService(AgentRunRepository runs, ProjectService projects,
            ProjectEventService events, ObjectMapper mapper) {
        this.runs = runs; this.projects = projects; this.events = events; this.mapper = mapper;
    }

    /** Project locks must precede run/task locks, matching ordinary state-change transactions. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stopFor(List<UUID> runIds, Instant now) {
        for (var run : runs.stopForHistoryCleanup(runIds, now)) {
            projects.releaseRunSlotIfHeld(run.userId(), run.projectId(), run.id());
            var payload = mapper.createObjectNode().put("runId", run.id().toString())
                    .put("status", run.status().name()).put("nextStepIndex", run.nextStepIndex());
            events.append(run.userId(), run.projectId(), new ProjectEventService.EventDraft(
                    "agent.run.changed", 1, run.id(), run.version(), payload));
        }
    }
}
