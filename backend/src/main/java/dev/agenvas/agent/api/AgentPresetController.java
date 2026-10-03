package dev.agenvas.agent.api;

import dev.agenvas.settings.application.PromptService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Creation menus expose only Agent names/keys, not administrator prompt content or function prompts. */
@RestController
public class AgentPresetController {
    private final PromptService prompts;
    public AgentPresetController(PromptService prompts) { this.prompts = prompts; }
    @GetMapping("/api/v1/agent-presets") public ResponseEntity<Presets> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Presets(prompts.agentPresets()));
    }
    public record Presets(List<PromptService.AgentPreset> items) {}
}
