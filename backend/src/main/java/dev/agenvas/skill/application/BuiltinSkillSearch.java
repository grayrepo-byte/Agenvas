package dev.agenvas.skill.application;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.SupportedLocales;
import dev.agenvas.skill.infrastructure.SkillRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Search aliases for packaged display metadata; stored Skill content is never translated. */
@Component
public final class BuiltinSkillSearch {
    private static final Map<String, String> MESSAGE_PREFIXES = Map.ofEntries(
            Map.entry("short-drama-assets", "skills.builtin.visualAssets"),
            Map.entry("short-drama-develop", "skills.builtin.storyDevelopment"),
            Map.entry("short-drama-image-prompts", "skills.builtin.imagePrompts"),
            Map.entry("short-drama-novel-analyze", "skills.builtin.sourceAnalysis"),
            Map.entry("short-drama-review", "skills.builtin.creativeReview"),
            Map.entry("short-drama-storyboard", "skills.builtin.storyboards"),
            Map.entry("short-drama-video-prompts", "skills.builtin.videoPrompts"),
            Map.entry("short-drama-write", "skills.builtin.episodeScripts"),
            Map.entry("h3-prompt-writing", "skills.builtin.h3Prompts"),
            Map.entry("minimalist-product-ad-generator", "skills.builtin.productAds"),
            Map.entry("3d-animation-short-generator", "skills.builtin.animatedShorts"),
            Map.entry("brand-promo-video-generator", "skills.builtin.brandVideos"),
            Map.entry("music-video-subtitle-generator", "skills.builtin.musicVideos"),
            Map.entry("co-op-game-intro-generator", "skills.builtin.gameIntros"),
            Map.entry("paper-collage-explainer-generator", "skills.builtin.collageExplainers"),
            Map.entry("papercraft-stop-motion-explainer", "skills.builtin.papercraftExplainers"),
            Map.entry("handdrawn-live-video-generator", "skills.builtin.handdrawnVideo"));
    private final BuiltinSkillCatalogue catalogue;
    private final ApiMessages messages;

    public BuiltinSkillSearch(BuiltinSkillCatalogue catalogue, ApiMessages messages) {
        this.catalogue = catalogue;
        this.messages = messages;
    }

    public List<SkillRepository.BuiltinSearchMatch> matching(String query) {
        if (query == null || query.isBlank())
            return List.of();
        Locale locale = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? SupportedLocales.resolve(attributes.getRequest())
                : Locale.ENGLISH;
        String search = query.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<SkillRepository.BuiltinSearchMatch>();
        for (var entry : catalogue.entries()) {
            String prefix = MESSAGE_PREFIXES.get(entry.bundle().name());
            if (prefix == null)
                continue;
            boolean title = messages.text(ApiMessage.of(prefix + ".title"), locale).toLowerCase(Locale.ROOT)
                    .contains(search);
            boolean description = messages.text(ApiMessage.of(prefix + ".description"), locale).toLowerCase(Locale.ROOT)
                    .contains(search);
            if (title || description) {
                // The UI localizes a packaged title independently, but descriptions require their original text too.
                matches.add(new SkillRepository.BuiltinSearchMatch(entry.key(), entry.title(),
                        title ? null : entry.bundle().description()));
            }
        }
        return List.copyOf(matches);
    }
}
