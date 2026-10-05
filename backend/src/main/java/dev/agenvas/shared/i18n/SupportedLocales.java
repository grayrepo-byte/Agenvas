package dev.agenvas.shared.i18n;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

/** Request-scoped language negotiation, including errors raised before DispatcherServlet. */
public final class SupportedLocales {
    public static final Locale DEFAULT = Locale.CHINESE;
    public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, Locale.CHINESE,
            Locale.forLanguageTag("ru"), Locale.JAPANESE);
    private static final String REQUEST_ATTRIBUTE = SupportedLocales.class.getName();

    private SupportedLocales() {}

    public static Locale resolve(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_ATTRIBUTE);
        if (cached instanceof Locale locale) return locale;
        Locale locale = negotiate(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        request.setAttribute(REQUEST_ATTRIBUTE, locale);
        return locale;
    }

    /** Supported regional variants use their base language. q=0 never selects a language. */
    public static Locale negotiate(String header) {
        if (header == null || header.isBlank()) return DEFAULT;
        try {
            for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
                if (range.getWeight() == 0) continue;
                String language = range.getRange().split("-", 2)[0];
                for (Locale supported : SUPPORTED) {
                    if (supported.getLanguage().equals(language)) return supported;
                }
            }
        } catch (IllegalArgumentException invalidHeader) {
            // An invalid preference must not turn an otherwise valid request into a failure.
        }
        return DEFAULT;
    }
}
