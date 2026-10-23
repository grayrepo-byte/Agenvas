package dev.agenvas.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Validates exact catalog resources and runtime descriptors without constraining Java source syntax. */
class MessageCatalogTest {
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\d+)}");

    @Test
    void everyLanguageHasExactlyTheSameKeysAndPlaceholderCounts() throws Exception {
        Properties source = load("zh");
        assertThat(load("")).isEqualTo(source);
        for (Locale locale : SupportedLocales.SUPPORTED) {
            String language = locale.getLanguage();
            Properties translated = load(language);
            assertThat(translated.keySet()).containsExactlyInAnyOrderElementsOf(source.keySet());
            for (String key : source.stringPropertyNames()) {
                String value = translated.getProperty(key);
                assertThat(value).as(language + ": " + key).isNotBlank();
                assertThat(parameters(value)).as(language + ": " + key)
                        .isEqualTo(parameters(source.getProperty(key)));
                if (!language.equals("zh") && !language.equals("ja"))
                    assertThat(value).as(language + ": " + key).doesNotMatch("(?s).*\\p{IsHan}.*");
            }
        }
    }

    @Test
    void everyRegisteredMessageValidatesItsArgumentsAndRendersInEveryLanguage() throws Exception {
        Properties source = load("zh");
        ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), new ObjectMapper());
        for (String key : source.stringPropertyNames()) {
            int count = parameters(source.getProperty(key)).keySet().stream()
                    .mapToInt(Integer::parseInt).max().orElse(-1) + 1;
            Object[] arguments = IntStream.range(0, count).mapToObj(index -> "argument-" + index).toArray();
            // Keys may pass through variables or shared factories. ApiMessage enforces the
            // catalog and argument contract at runtime, regardless of that refactoring.
            ApiMessage message = ApiMessage.of(key, arguments);
            for (Locale locale : SupportedLocales.SUPPORTED) {
                String rendered = messages.text(message, locale);
                assertThat(rendered).as(locale + ": " + key).isNotBlank();
                assertThat(PARAMETER.matcher(rendered).find()).as(locale + ": " + key).isFalse();
            }
            assertThatThrownBy(() -> ApiMessage.of(key, Arrays.copyOf(arguments, count + 1)))
                    .as(key + ": extra argument").isInstanceOf(IllegalArgumentException.class);
            if (count > 0)
                assertThatThrownBy(() -> ApiMessage.of(key, Arrays.copyOf(arguments, count - 1)))
                        .as(key + ": missing argument").isInstanceOf(IllegalArgumentException.class);
        }
        // Unreferenced entries can still be needed by persisted error descriptors and
        // historical source messages; their translations remain covered above.
        assertThatThrownBy(() -> ApiMessage.of("unregistered.catalog-test-message"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Properties load(String language) throws Exception {
        var properties = new Properties();
        String path = "/i18n/messages" + (language.isEmpty() ? "" : "_" + language) + ".properties";
        try (var input = MessageCatalogTest.class.getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private Map<String, Integer> parameters(String template) {
        var counts = new TreeMap<String, Integer>();
        PARAMETER.matcher(template).results().forEach(match -> counts.merge(match.group(1), 1, Integer::sum));
        return counts;
    }
}
