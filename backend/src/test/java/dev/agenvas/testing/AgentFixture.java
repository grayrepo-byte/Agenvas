package dev.agenvas.testing;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import java.util.UUID;

/** Test scenarios configure the one project director through its public CAS use case. */
public final class AgentFixture {
    private AgentFixture() {}

    public static AgentInstance configureDirector(AgentInstanceService agents, UUID ownerId,
            UUID projectId, String name, String instruction) {
        AgentInstance director = agents.ensureDirector(ownerId, projectId);
        return agents.update(ownerId, projectId, director.id(), director.version(), name, instruction);
    }
}
