package dev.agenvas.provider.domain;

import dev.agenvas.provider.domain.NativeMediaProfiles.Selection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Explicit assembly of compiled protocols. No classpath scanning, remote code or mutable global registration. */
public final class NativeMediaCatalog {
    private final Map<String, List<Selection>> byAdapter;
    private final List<Selection> profiles;

    public NativeMediaCatalog(List<NativeMediaProtocol> protocols) {
        var indexed = new LinkedHashMap<String, List<Selection>>();
        var ordered = new ArrayList<Selection>();
        for (var protocol : List.copyOf(protocols)) {
            var ids = java.util.Set.copyOf(protocol.adapterIds());
            if (ids.isEmpty())
                throw new IllegalStateException("Native protocol has no adapter IDs");
            for (String id : ids) {
                if (!id.matches("[A-Z][A-Z0-9_]{0,119}") || indexed.putIfAbsent(id, new ArrayList<>()) != null)
                    throw new IllegalStateException("Invalid or duplicate native adapter: " + id);
            }
            var identities = new java.util.HashSet<NativeMediaProtocol.Profile>();
            for (var profile : List.copyOf(protocol.profiles())) {
                Objects.requireNonNull(profile.kind(), "Native profile kind");
                if (!ids.contains(profile.adapterId()) || profile.model() == null || profile.model().isBlank()
                        || profile.operation() == null || profile.operation().isBlank() || !identities.add(profile))
                    throw new IllegalStateException("Invalid or duplicate native profile: " + profile.adapterId());
                if (protocol.declaration(profile).kind() != profile.kind())
                    throw new IllegalStateException(
                            "Native profile kind conflicts with its declaration: " + profile.adapterId());
                var entries = indexed.get(profile.adapterId());
                if (!entries.isEmpty() && entries.getFirst().profile().kind() != profile.kind())
                    throw new IllegalStateException(
                            "Native adapter has conflicting output kinds: " + profile.adapterId());
                if (entries.stream().anyMatch(previous -> previous.profile().model().equals(profile.model())
                        && previous.profile().operation().equals(profile.operation())))
                    throw new IllegalStateException("Ambiguous native profile: " + profile.adapterId());
                var selected = new Selection(protocol, profile);
                entries.add(selected);
                ordered.add(selected);
            }
            for (String id : ids)
                if (indexed.get(id).isEmpty())
                    throw new IllegalStateException("Native adapter has no profiles: " + id);
        }
        var frozen = new LinkedHashMap<String, List<Selection>>();
        indexed.forEach((id, selections) -> frozen.put(id, List.copyOf(selections)));
        byAdapter = Map.copyOf(frozen);
        profiles = List.copyOf(ordered);
    }

    public boolean supports(String adapterId) {
        return adapterId != null && byAdapter.containsKey(adapterId);
    }

    /** Protocol order and model order define the historical default; hash-map iteration never selects it. */
    public List<Selection> profiles() {
        return profiles;
    }

    public Selection resolve(String adapterId, JsonNode settings) {
        if (settings == null || !settings.isObject())
            throw NativeMediaProfiles.invalid();
        for (String key : List.of("model", "operation"))
            if (settings.hasNonNull(key) && !settings.path(key).isTextual())
                throw NativeMediaProfiles.invalid();
        String operation = settings.path("operation").asText("GENERATE");
        String model = settings.path("model").asText("").trim();
        var candidates = adapterId == null ? List.<Selection>of() : byAdapter.getOrDefault(adapterId, List.of());
        return candidates.stream().filter(p -> p.profile().operation().equals(operation)
                && (model.isEmpty() || p.profile().model().equals(model)))
                .findFirst().orElseThrow(NativeMediaProfiles::invalid);
    }
}
