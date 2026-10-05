package dev.agenvas.settings.application;

import java.util.regex.Pattern;

/** Defensive masking for console diagnostics; callers must still avoid logging sensitive content. */
final class SystemLogRedactor {
    private static final String MASK = "[REDACTED]";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern HEADER = Pattern.compile(
            "(?i)\\b(authorization|proxy-authorization|cookie|set-cookie)\\s*[\\\\\"']*\\s*[:=]\\s*[^\\r\\n]*");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(\\b(?:[a-z0-9]+[_-])*(?:api[_-]?key|password|passwd|(?:client[_-]?)?secret|"
                    + "(?:access[_-]?|refresh[_-]?)?token|master[_-]?key(?:[_-]?base64)?|previous[_-]?keys)"
                    + "[\\\\\"']*\\s*[:=]\\s*)[^\\r\\n]*");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s\\\"'\\\\,;}]+");
    private static final Pattern PROVIDER_KEY = Pattern.compile("\\b(?:sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+)");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)(https?://[^\\s\\\"'<>?]+)\\?[^\\s\\\"'<>]*");
    private static final Pattern URL_CREDENTIAL = Pattern.compile("(?i)(https?://)[^/\\s@]+@");

    private SystemLogRedactor() {}

    static String redact(String line) {
        String safe = ANSI.matcher(line).replaceAll("");
        safe = HEADER.matcher(safe).replaceAll("$1: " + MASK);
        // Mask the remaining line: arbitrary secrets can contain spaces, quotes or JSON escapes.
        safe = CREDENTIAL.matcher(safe).replaceAll("$1" + MASK);
        safe = BEARER.matcher(safe).replaceAll("Bearer " + MASK);
        safe = PROVIDER_KEY.matcher(safe).replaceAll(MASK);
        safe = URL_CREDENTIAL.matcher(safe).replaceAll("$1" + MASK + "@");
        return URL_QUERY.matcher(safe).replaceAll("$1?" + MASK);
    }
}
