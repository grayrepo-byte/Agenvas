package dev.agenvas.mediatemplate.application;

import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Installed compiled parsers. A missing format is a local installation issue, not an upstream HTTP failure. */
@Component
public final class ThirdPartyPromptRegistry {
    private final Map<Format, ThirdPartyPromptAdapter> adapters;
    public ThirdPartyPromptRegistry(List<ThirdPartyPromptAdapter> installed) {
        var indexed = new EnumMap<Format, ThirdPartyPromptAdapter>(Format.class);
        for (var adapter : List.copyOf(installed)) {
            var format = Objects.requireNonNull(adapter.format(), "Prompt adapter format");
            if (indexed.putIfAbsent(format, adapter) != null)
                throw new IllegalStateException("Duplicate prompt format: " + format);
        }
        adapters = Map.copyOf(indexed);
    }
    public ThirdPartyPromptAdapter require(Format format) {
        var adapter = format == null ? null : adapters.get(format);
        if (adapter == null) throw new UnsupportedFormat();
        return adapter;
    }
    public static final class UnsupportedFormat extends IllegalStateException {
        private UnsupportedFormat() { super("Prompt format is not installed"); }
    }
}
