package dev.agenvas.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** Checks actual files without ResourceBundle parent fallback masking incomplete translations. */
class MessageCatalogTest {
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\d+)}");

    @Test
    void everyLanguageHasExactlyTheSameKeysAndPlaceholderCounts() throws Exception {
        Properties source = load("zh");
        assertThat(load("")).isEqualTo(source);
        for (String language : List.of("en", "ru", "ja")) {
            Properties translated = load(language);
            assertThat(translated.keySet()).containsExactlyInAnyOrderElementsOf(source.keySet());
            for (String key : source.stringPropertyNames()) {
                String value = translated.getProperty(key);
                assertThat(value).as(language + ": " + key).isNotBlank();
                assertThat(parameters(value)).as(language + ": " + key)
                        .isEqualTo(parameters(source.getProperty(key)));
                if (!language.equals("ja")) assertThat(value).as(language + ": " + key).doesNotMatch("(?s).*\\p{IsHan}.*");
            }
        }
    }

    @Test
    void everyProductionMessageReferenceIsRegisteredAndHasTheRequiredArguments() throws Exception {
        Properties source = load("zh");
        List<String> referenced = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"));
                var manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = manager.getJavaFileObjectsFromPaths(paths.filter(path -> path.toString().endsWith(".java")).toList());
            var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(null, manager, null, List.of("-proc:none"), null, units);
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
                        if (invocation.getMethodSelect().toString().equals("ApiMessage.of")) {
                            assertThat(invocation.getArguments().getFirst()).as(unit.getSourceFile().getName()).isInstanceOf(LiteralTree.class);
                            String key = (String) ((LiteralTree) invocation.getArguments().getFirst()).getValue();
                            assertThat(source).as(unit.getSourceFile().getName() + ": " + key).containsKey(key);
                            int count = parameters(source.getProperty(key)).keySet().stream().mapToInt(Integer::parseInt).max().orElse(-1) + 1;
                            assertThat(invocation.getArguments()).as(unit.getSourceFile().getName() + ": " + key).hasSize(count + 1);
                            referenced.add(key);
                        }
                        return super.visitMethodInvocation(invocation, unused);
                    }
                }.scan(unit, null);
            }
        }
        // Bean Validation interpolates this explicit annotation through the same MessageSource.
        assertThat(Files.readString(Path.of("src/main/java/dev/agenvas/identity/api/AuthenticationController.java")))
                .contains("{validation.username-pattern}");
        referenced.add("validation.username-pattern");
        assertThat(source.stringPropertyNames().stream()
                .filter(key -> !referenced.contains(key)).sorted().toList())
                .as("Message catalog keys without production references").isEmpty();
    }

    private Properties load(String language) throws Exception {
        var properties = new Properties();
        Path path = Path.of("src/main/resources/i18n/messages" + (language.isEmpty() ? "" : "_" + language) + ".properties");
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        return properties;
    }

    private Map<String, Integer> parameters(String template) {
        var counts = new TreeMap<String, Integer>();
        PARAMETER.matcher(template).results().forEach(match -> counts.merge(match.group(1), 1, Integer::sum));
        return counts;
    }
}
