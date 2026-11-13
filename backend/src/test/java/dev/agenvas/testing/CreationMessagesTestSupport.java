package dev.agenvas.testing;

import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.CreationMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import tools.jackson.databind.ObjectMapper;

/** Uses the packaged catalog, including production fallback and interpolation behavior. */
public final class CreationMessagesTestSupport {
    private CreationMessagesTestSupport() {
    }

    public static CreationMessages messages() {
        return new CreationMessages(new ApiMessages(new I18nConfiguration().messageSource(), new ObjectMapper()));
    }
}
