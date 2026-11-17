package dev.agenvas.skill.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import dev.agenvas.shared.i18n.SupportedLocales;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

class BuiltinSkillSearchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BuiltinSkillCatalogue catalogue = new BuiltinSkillCatalogue(mapper, new SkillFormat());
    private final ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), mapper);
    private final BuiltinSkillSearch search = new BuiltinSkillSearch(catalogue, messages);

    @ParameterizedTest
    @MethodSource("locales")
    void matchesTranslatedMetadataAndGuardsTheOriginalTitleAndDescription(Locale locale) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", locale.toLanguageTag());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var entry = catalogue.entries().stream().filter(value -> value.bundle().name().equals("short-drama-assets"))
                    .findFirst().orElseThrow();
            String title = messages.text(ApiMessage.of("skills.builtin.visualAssets.title"), locale);
            var titleMatch = search.matching(title.toUpperCase(locale));
            assertThat(titleMatch).hasSize(1);
            assertThat(titleMatch.getFirst().key()).isEqualTo(entry.key());
            assertThat(titleMatch.getFirst().title()).isEqualTo(entry.title());
            assertThat(titleMatch.getFirst().description()).isNull();
            String description = messages.text(ApiMessage.of("skills.builtin.visualAssets.description"), locale);
            var descriptionMatch = search.matching(description);
            assertThat(descriptionMatch).hasSize(1);
            assertThat(descriptionMatch.getFirst().key()).isEqualTo(entry.key());
            assertThat(descriptionMatch.getFirst().title()).isEqualTo(entry.title());
            assertThat(descriptionMatch.getFirst().description()).isEqualTo(entry.bundle().description());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @ParameterizedTest
    @MethodSource("locales")
    void everyRegisteredBuiltinHasSearchableDisplayMetadata(Locale locale) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", locale.toLanguageTag());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            for (var entry : catalogue.entries()) {
                // Stable bundle names occur in each localized display title, independently of its natural wording.
                assertThat(search.matching(entry.bundle().name())).as("%s / %s", locale, entry.key())
                        .anyMatch(match -> match.key().equals(entry.key()) && match.title().equals(entry.title()));
            }
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void backgroundSearchUsesEnglishWithoutAnUnrelatedThreadOrJvmLocale() {
        var previousAttributes = RequestContextHolder.getRequestAttributes();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        Locale previousDefault = Locale.getDefault();
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        Locale.setDefault(Locale.forLanguageTag("ru"));
        try {
            assertThat(search.matching("visual asset design")).hasSize(1);
            assertThat(search.matching("ビジュアル素材設計")).isEmpty();
            assertThat(search.matching("Визуальные материалы")).isEmpty();
            for (String blank : List.of("", "  "))
                assertThat(search.matching(blank)).isEmpty();
            assertThat(search.matching(null)).isEmpty();
        } finally {
            Locale.setDefault(previousDefault);
            LocaleContextHolder.setLocaleContext(previousLocale);
            RequestContextHolder.setRequestAttributes(previousAttributes);
        }
    }

    private static Stream<Locale> locales() {
        return SupportedLocales.SUPPORTED.stream();
    }
}
