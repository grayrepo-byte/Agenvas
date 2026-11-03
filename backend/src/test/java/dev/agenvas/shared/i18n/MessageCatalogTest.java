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
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/** Validates exact catalog resources and runtime descriptors without constraining Java source syntax. */
class MessageCatalogTest {
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\d+)}");

    @ParameterizedTest(name = "{0} catalog matches the Chinese source")
    @MethodSource("supportedLocales")
    void everyLanguageHasExactlyTheSameKeysAndPlaceholderCounts(Locale locale) throws Exception {
        Properties source = load("zh");
        String language = locale.getLanguage();
        Properties translated = load(language);
        var missing = new TreeSet<>(source.stringPropertyNames());
        missing.removeAll(translated.stringPropertyNames());
        var extra = new TreeSet<>(translated.stringPropertyNames());
        extra.removeAll(source.stringPropertyNames());
        // Report only broken keys/values, rather than dumping every translation into CI logs.
        SoftAssertions.assertSoftly(check -> {
            check.assertThat(missing).as(language + ": missing translation keys").isEmpty();
            check.assertThat(extra).as(language + ": keys missing from the Chinese source").isEmpty();
            for (String key : new TreeSet<>(source.stringPropertyNames())) {
                String value = translated.getProperty(key);
                check.assertThat(value).as(language + ": " + key).isNotBlank();
                if (value == null) continue;
                check.assertThat(parameters(value)).as(language + ": " + key + " placeholders")
                        .isEqualTo(parameters(source.getProperty(key)));
                if (!language.equals("zh") && !language.equals("ja"))
                    check.assertThat(value).as(language + ": " + key).doesNotMatch("(?s).*\\p{IsHan}.*");
            }
        });
    }

    private static Stream<Locale> supportedLocales() {
        return SupportedLocales.SUPPORTED.stream();
    }

    @Test
    void everyRegisteredMessageValidatesItsArgumentsAndRendersInEveryLanguage() throws Exception {
        Properties source = load("zh");
        ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), new ObjectMapper());
        for (String key : new TreeSet<>(source.stringPropertyNames())) {
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
                if (locale.equals(Locale.CHINESE))
                    assertThat(rendered).as("Chinese source: " + key).isEqualTo(message.source());
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
        // The base bundle is the sole Chinese catalog; other languages must exist as exact resources.
        String path = "/i18n/messages" + (language.isEmpty() || language.equals("zh") ? "" : "_" + language) + ".properties";
        var properties = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                assertThat(containsKey(key)).as(path + ": duplicate key " + key).isFalse();
                return super.put(key, value);
            }
        };
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
