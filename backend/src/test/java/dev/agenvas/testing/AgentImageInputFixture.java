package dev.agenvas.testing;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.domain.CanvasConnection;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Establishes an Agent image binding through the production canvas-connection boundary. */
public final class AgentImageInputFixture {
    private static final BigDecimal SOURCE_X = BigDecimal.ZERO;
    private static final BigDecimal TARGET_X = new BigDecimal("400");
    private static final BigDecimal CARD_Y = BigDecimal.ZERO;
    private static final BigDecimal CARD_WIDTH = new BigDecimal("320");
    private static final BigDecimal CARD_HEIGHT = new BigDecimal("240");

    private AgentImageInputFixture() {}

    public static AgentInstance connect(
            AgentInstanceService agents,
            CanvasService canvas,
            CanvasConnectionService connections,
            UUID ownerId,
            UUID projectId,
            UUID imageArtifactId,
            UUID imageVersionId,
            String agentName,
            String instruction) {
        AgentInstance agent = agents.create(ownerId, projectId, agentName, instruction, List.of());
        UUID sourceItemId = UUID.randomUUID();
        UUID targetItemId = UUID.randomUUID();
        canvas.apply(ownerId, projectId, List.of(
                new CanvasService.PlaceArtifact(sourceItemId, imageArtifactId,
                        SOURCE_X, CARD_Y, CARD_WIDTH, CARD_HEIGHT, 0, null, false),
                new CanvasService.PlaceAgent(targetItemId, agent.id(),
                        TARGET_X, CARD_Y, CARD_WIDTH, CARD_HEIGHT, 1, null, false)));
        return Objects.requireNonNull(connections.connect(ownerId, projectId,
                sourceItemId, targetItemId, imageVersionId,
                CanvasConnection.RelationType.AGENT_IMAGE_INPUT, null, agent.version()).agent());
    }
}
