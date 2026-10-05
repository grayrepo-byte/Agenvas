package dev.agenvas.task.application;

import dev.agenvas.event.application.ProjectEventService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Administrator-confirmed abandonment of expired work. Keeps results and usage records;
 * makes no assertion that an external request stopped or its cost was refunded.
 */
@Service
public class TaskHistoryCleanupService {
    public static final String CLEANED_ERROR_CODE = "EXECUTION_HISTORY_CLEANED";
    private final TaskRepository tasks;
    private final ProjectEventService events;
    private final ObjectMapper mapper;

    public TaskHistoryCleanupService(TaskRepository tasks, ProjectEventService events, ObjectMapper mapper) {
        this.tasks = tasks; this.events = events; this.mapper = mapper;
    }

    /** Called only for complete units with project/task locks held by the cleanup coordinator. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stopFor(List<UUID> taskIds, Instant now) {
        for (var task : tasks.stopForHistoryCleanup(taskIds, now)) {
            UUID owner = tasks.ownerId(task.id()).orElseThrow();
            var payload = mapper.createObjectNode().put("taskId", task.id().toString())
                    .put("status", task.status().name()).put("cancelRequested", true)
                    .put("possibleExternalCost", true);
            events.append(owner, task.projectId(), new ProjectEventService.EventDraft(
                    "task.status.changed", 1, task.id(), task.version(), payload));
        }
    }
}
