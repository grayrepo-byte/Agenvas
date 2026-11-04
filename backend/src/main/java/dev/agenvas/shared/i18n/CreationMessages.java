package dev.agenvas.shared.i18n;

import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Resolves built-in text once when creating content; background work has no user locale. */
@Component
public final class CreationMessages {
    private final ApiMessages messages;

    public CreationMessages(ApiMessages messages) {
        this.messages = messages;
    }

    public String text(ApiMessage message) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return messages.text(message, attributes.getRequest());
        }
        // Do not let the server's JVM locale or an unrelated LocaleContext choose saved names.
        return messages.text(message, Locale.ENGLISH);
    }
}
