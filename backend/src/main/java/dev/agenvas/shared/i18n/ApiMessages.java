package dev.agenvas.shared.i18n;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Localizes presentation at the HTTP boundary, never persisted content or machine-readable codes. */
@Component
public class ApiMessages {
    private final MessageSource messages;
    private final ObjectMapper mapper;

    public ApiMessages(MessageSource messages, ObjectMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    public String text(ApiMessage message, HttpServletRequest request) {
        return text(message, SupportedLocales.resolve(request));
    }

    public String text(ApiMessage message, Locale locale) {
        if (message == null) return null;
        return message.render(messages.getMessage(message.key(), null, locale));
    }

    public String invalidField(HttpServletRequest request) {
        return text(ApiMessage.of("validation.invalid"), request);
    }

    /** Library transfers store a descriptor in their existing error-detail column, with no schema change. */
    public String persisted(String value, HttpServletRequest request) {
        if (value == null) return null;
        ApiMessage message = ApiMessage.fromLegacySource(value);
        if (message == null && value.startsWith("{")) {
            try {
                message = mapper.readValue(value, ApiMessage.class);
            } catch (RuntimeException invalidDescriptor) {
                // Historical/unrecognized data is never returned as a raw implementation failure.
                message = null;
            }
        }
        return text(message != null ? message : ApiMessage.of("problem.fallback"), request);
    }
}
