package dev.agenvas.mediatemplate.infrastructure;

import dev.agenvas.mediatemplate.application.ThirdPartyPromptAdapter;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptSource;
import dev.agenvas.mediatemplate.application.ThirdPartyPromptValidation;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Native feeds are either an array or an {items: [...]} envelope. */
@Component
public class NativeJsonPromptAdapter implements ThirdPartyPromptAdapter {
    private final ObjectMapper mapper;
    public NativeJsonPromptAdapter(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public Format format() { return Format.NATIVE_JSON; }
    @Override public List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body) {
        var root = mapper.readTree(body);
        var items = root.isArray() ? root : root.path("items");
        if (!items.isArray()) throw ThirdPartyPromptValidation.invalid();
        List<ThirdPartyPrompt> result = new ArrayList<>();
        for (var item : items) result.add(source.targetKind() == TargetKind.IMAGE
                ? mapper.treeToValue(item, ThirdPartyPrompt.Image.class)
                : mapper.treeToValue(item, ThirdPartyPrompt.Video.class));
        return result;
    }
}
