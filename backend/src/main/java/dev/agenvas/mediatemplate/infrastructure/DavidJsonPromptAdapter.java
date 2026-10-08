package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptAdapter;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DavidJsonPromptAdapter implements ThirdPartyPromptAdapter {
    private final ObjectMapper mapper;
    public DavidJsonPromptAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public Format format() { return Format.DAVID_JSON; }
    @Override public List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body) {
        var root = mapper.readTree(body);
        if (!root.isArray()) throw ThirdPartyPromptValidation.invalid();
        List<ThirdPartyPrompt> prompts = new ArrayList<>();
        for (var item : root) {
            String id = item.path("id").asText();
            if (id.isBlank()) throw ThirdPartyPromptValidation.invalid();
            String image = item.path("image").asText("");
            String cover = image.isBlank() ? "" : java.net.URI.create(source.url()).resolve(image).toString();
            String category = item.path("category_cn").asText(item.path("category").asText(""));
            prompts.add(new Image(source.id() + ":" + id, source.id(),
                    item.path("title_cn").asText(item.path("title_en").asText()), item.path("prompt").asText(),
                    item.path("note").asText(""), cover, List.of(), category.isBlank() ? List.of() : List.of(category),
                    item.path("author").asText(""), MarkdownPromptAdapter.repositoryUrl(source.url()) + "#prompt-" + id,
                    "", item.path("needs_ref").asBoolean() ? ImageMode.edit : ImageMode.generate, source.model()));
        }
        return prompts;
    }
}
