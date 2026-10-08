package dev.agenvas.mediatemplate.application;

import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt;
import dev.agenvas.mediatemplate.domain.ThirdPartyPrompt.Format;
import java.util.List;

/** Each upstream format converts into one of the two native media formats before persistence. */
public interface ThirdPartyPromptAdapter {
    Format format();
    List<ThirdPartyPrompt> parse(ThirdPartyPromptSource source, String body);
}
