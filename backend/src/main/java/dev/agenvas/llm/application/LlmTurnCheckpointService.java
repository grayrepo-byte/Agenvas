package dev.agenvas.llm.application;

import dev.agenvas.event.application.ProjectEventService;
import dev.agenvas.run.application.AgentRunRepository;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.usage.application.UsageService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Commits complete model-round protocol checkpoints independently of network calls. */
@Service
public class LlmTurnCheckpointService {

    private final AgentRunRepository runs;
    private final LlmTurnRepository turns;
    private final ProjectEventService events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final UsageService usage;

    public LlmTurnCheckpointService(AgentRunRepository runs, LlmTurnRepository turns,
            ProjectEventService events, ObjectMapper mapper, Clock clock,
            UsageService usage) {
        this.runs = runs;
        this.turns = turns;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.usage = usage;
    }

    /** Reserves a Run step before dispatch; exact replays retain the original request. */
    @Transactional
    public LlmTurn reserve(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, String configSource, JsonNode request) {
        if (stepIndex < 0 || configVersion < 1 || configSource == null
                || configSource.isBlank() || request == null || !request.isObject()) {
            throw new IllegalArgumentException("Invalid model turn request");
        }
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun run = requireRunning(ownerId, projectId, runId);
            int pinnedVersion = run.policySnapshot().path("modelConfigVersion")
                    .asInt(-1);
            String pinnedSource = run.policySnapshot().path("modelConfigSource").asText("");
            if (pinnedVersion != configVersion || !pinnedSource.equals(configSource)) {
                throw conflict("Run model configuration changed; explicit recovery is required");
            }
            boolean inserted = turns.insertRequested(projectId, runId, stepIndex,
                    configVersion, request, clock.instant());
            LlmTurn turn = turns.find(projectId, runId, stepIndex).orElseThrow();
            if (turn.modelConfigVersion() != configVersion || !turn.request().equals(request)) {
                throw conflict("Model step already exists with a different request or config version");
            }
            if (inserted) {
                usage.reserveModelTurn(ownerId, turn);
                events.append(ownerId, projectId, event("llm.turn.requested", run, stepIndex));
            }
            return ProjectEventService.Change.unchanged(turn);
        }).value();
    }

    /** Saves every generation and tool-call ID before any caller may execute a tool. */
    @Transactional
    public LlmTurn saveResponse(UUID ownerId, UUID projectId, UUID runId, int stepIndex,
            int configVersion, JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new IllegalArgumentException("Invalid model response checkpoint");
        }
        return events.recordChange(ownerId, projectId, () -> {
            AgentRun run = runs.find(ownerId, projectId, runId)
                    .orElseThrow(() -> conflict("Run is not accessible"));
            LlmTurn existing = turns.find(projectId, runId, stepIndex)
                    .orElseThrow(() -> conflict("Model request checkpoint is missing"));
            if (existing.modelConfigVersion() != configVersion) {
                throw conflict("Model config version changed during this round");
            }
            if (existing.status() == LlmTurn.Status.RESPONDED) {
                if (!existing.response().equals(response)) {
                    throw conflict("Model response was already recorded differently");
                }
                return ProjectEventService.Change.unchanged(existing);
            }
            if (!turns.saveResponse(projectId, runId, stepIndex, response, clock.instant())) {
                throw conflict("Model response checkpoint was updated concurrently");
            }
            LlmTurn saved = turns.find(projectId, runId, stepIndex).orElseThrow();
            usage.settleModelTurn(ownerId, saved);
            events.append(ownerId, projectId, event("llm.turn.recorded", run, stepIndex));
            return ProjectEventService.Change.unchanged(saved);
        }).value();
    }

    private AgentRun requireRunning(UUID ownerId, UUID projectId, UUID runId) {
        AgentRun run = runs.find(ownerId, projectId, runId)
                .orElseThrow(() -> conflict("Run is not accessible"));
        if (run.status() != AgentRun.Status.RUNNING) {
            throw conflict("Run is not accepting a model round");
        }
        return run;
    }

    private ProjectEventService.EventDraft event(String type, AgentRun run, int stepIndex) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("runId", run.id().toString());
        payload.put("stepIndex", stepIndex);
        return new ProjectEventService.EventDraft(type, 1, run.id(),
                run.version(), payload);
    }

    private ApiProblemException conflict(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "LLM_TURN_CONFLICT",
                "模型回合冲突", detail, false);
    }
}
