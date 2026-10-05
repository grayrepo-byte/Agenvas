package dev.agenvas.run.api;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.run.application.AgentConversationService;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentConversation;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 持久会话切换与分页读取；创建新会话不取消已有运行。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agents/{agentId}/conversations")
public class AgentConversationController {
    private final AgentConversationService conversations;
    private final AgentRunService runs;

    public AgentConversationController(AgentConversationService conversations, AgentRunService runs) {
        this.conversations = conversations;
        this.runs = runs;
    }

    @GetMapping
    public AgentConversationService.ConversationPage list(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID agentId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return conversations.list(principal.userId(), projectId, agentId, cursor, limit);
    }

    @PostMapping
    public ResponseEntity<AgentConversation> create(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID agentId,
            @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.status(201).body(conversations.create(principal.userId(), projectId, agentId, key));
    }

    @PostMapping("/{conversationId}/select")
    public AgentConversation select(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID agentId, @PathVariable UUID conversationId) {
        return conversations.select(principal.userId(), projectId, agentId, conversationId);
    }

    @GetMapping("/{conversationId}/runs")
    public AgentRunController.RunListResponse runs(@AuthenticationPrincipal AdminPrincipal principal,
            @PathVariable UUID projectId, @PathVariable UUID agentId, @PathVariable UUID conversationId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var page = runs.listConversation(principal.userId(), projectId, agentId, conversationId, cursor, limit);
        return new AgentRunController.RunListResponse(page.items().stream()
                .map(AgentRunController.RunSummary::from).toList(), page.nextCursor());
    }
}
