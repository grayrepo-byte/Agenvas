package dev.agenvas.run.application;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.canvas.application.CanvasService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Current canvas output attribution follows the selected immutable version's Run provenance. */
@Service
public class AgentOutputService {
    private final AgentInstanceService agents;
    private final CanvasService canvas;
    private final AgentRunRepository runs;

    public AgentOutputService(AgentInstanceService agents, CanvasService canvas, AgentRunRepository runs) {
        this.agents = agents;
        this.canvas = canvas;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<UUID> listCanvasItemIds(UUID ownerId, UUID projectId, UUID agentId) {
        agents.get(ownerId, projectId, agentId);
        var candidates = new LinkedHashMap<UUID, UUID>();
        for (var entry : canvas.list(ownerId, projectId)) {
            ArtifactVersion version = entry.artifact().artifact().kind() == Artifact.Kind.TEXT
                    ? entry.artifact().resourceDefaultVersion() : entry.selectedVersion();
            // An empty approval draft has no archived output; human edits end current attribution.
            if (version != null && version.runId() != null
                    && version.createdByKind() != ArtifactVersion.CreatedByKind.USER) {
                candidates.put(entry.item().id(), version.runId());
            }
        }
        Set<UUID> matching = runs.filterAgentRunIds(ownerId, projectId, agentId, Set.copyOf(candidates.values()));
        return candidates.entrySet().stream().filter(entry -> matching.contains(entry.getValue()))
                .map(java.util.Map.Entry::getKey).toList();
    }
}
