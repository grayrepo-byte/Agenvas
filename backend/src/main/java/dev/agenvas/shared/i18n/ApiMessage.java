package dev.agenvas.shared.i18n;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/** A public message retains its key and arguments until the requesting user's locale is known. */
public record ApiMessage(String key, List<String> arguments) {
    private static final ResourceBundle SOURCE = ResourceBundle.getBundle("i18n/messages", Locale.CHINESE,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\d+)}");

    public ApiMessage {
        Objects.requireNonNull(key, "Message key required");
        if (!SOURCE.containsKey(key)) throw new IllegalArgumentException("Unknown public message key: " + key);
        arguments = List.copyOf(arguments);
        int required = PARAMETER.matcher(SOURCE.getString(key)).results()
                .mapToInt(match -> Integer.parseInt(match.group(1)) + 1).max().orElse(0);
        if (arguments.size() != required) throw new IllegalArgumentException("Wrong argument count for " + key);
    }

    public static ApiMessage of(String key, Object... arguments) {
        return new ApiMessage(key, Arrays.stream(arguments).map(String::valueOf).toList());
    }

    /** Locale-independent source explanation for diagnostics and existing immutable Run records. */
    public String source() {
        return render(SOURCE.getString(key));
    }

    String render(String template) {
        // One substitution pass preserves literal braces, apostrophes and $/backslashes in values.
        return PARAMETER.matcher(template).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                arguments.get(Integer.parseInt(match.group(1)))));
    }

    static ApiMessage fromLegacySource(String source) {
        return SOURCE.keySet().stream().filter(key -> SOURCE.getString(key).equals(source)
                        && !PARAMETER.matcher(source).find())
                .findFirst().map(ApiMessage::of).orElse(null);
    }
}
