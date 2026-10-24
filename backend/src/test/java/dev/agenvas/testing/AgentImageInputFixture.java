package dev.agenvas.testing;

import static dev.agenvas.testing.AgentFixture.configureDirector;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.canvas.application.CanvasService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Places an exact image on the canvas before creating a Run; no assistant node or input edge exists. */
public final class AgentImageInputFixture {
    private static final BigDecimal SOURCE_X = BigDecimal.ZERO;
    private static final BigDecimal CARD_Y = BigDecimal.ZERO;
    private static final BigDecimal CARD_WIDTH = new BigDecimal("320");
    private static final BigDecimal CARD_HEIGHT = new BigDecimal("240");

    private AgentImageInputFixture() {}

    public static AgentInstance withCanvasImage(
            AgentInstanceService agents,
            CanvasService canvas,
            UUID ownerId,
            UUID projectId,
            UUID imageArtifactId,
            UUID imageVersionId,
            String agentName,
            String instruction) {
        AgentInstance agent = configureDirector(agents, ownerId, projectId, agentName, instruction);
        UUID sourceItemId = UUID.randomUUID();
        canvas.apply(ownerId, projectId, List.of(
                new CanvasService.PlaceArtifact(sourceItemId, imageArtifactId,
                        SOURCE_X, CARD_Y, CARD_WIDTH, CARD_HEIGHT, 0, null, false)));
        var item = canvas.list(ownerId, projectId).stream().map(CanvasService.CanvasEntry::item)
                .filter(candidate -> candidate.id().equals(sourceItemId)).findFirst().orElseThrow();
        if (!imageVersionId.equals(item.selectedVersionId()))
            canvas.selectMediaVersion(ownerId, projectId, sourceItemId, imageVersionId, item.version());
        return agent;
    }
}
