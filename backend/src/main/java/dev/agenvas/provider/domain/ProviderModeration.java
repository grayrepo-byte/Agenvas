package dev.agenvas.provider.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Public moderation classifications; upstream prose and unrecognized values are never retained. */
public record ProviderModeration(String code, String stage, List<String> categories) {
    private static final Set<String> CODES = Set.of("moderation_blocked", "content_policy_violation");
    private static final Set<String> STAGES = Set.of("input", "output", "unknown");
    private static final Set<String> CATEGORIES = Set.of("harassment", "self-harm", "sexual", "violence", "other");
    private static final String UNKNOWN_STAGE = "unknown";
    private static final String OTHER_CATEGORY = "other";
    private static final int MAX_CATEGORIES = 5;

    public ProviderModeration {
        if (!supportsCode(code))
            throw new IllegalArgumentException("Invalid public moderation code");
        stage = stage != null && STAGES.contains(stage) ? stage : UNKNOWN_STAGE;
        List<String> safeCategories = new ArrayList<>();
        if (categories != null) {
            for (String category : categories) {
                String safe = category != null && CATEGORIES.contains(category) ? category : OTHER_CATEGORY;
                if (!safeCategories.contains(safe))
                    safeCategories.add(safe);
                if (safeCategories.size() == MAX_CATEGORIES)
                    break;
            }
        }
        categories = List.copyOf(safeCategories);
    }

    /** Only exact protocol codes authorize attaching moderation diagnostics to a rejection. */
    public static boolean supportsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
