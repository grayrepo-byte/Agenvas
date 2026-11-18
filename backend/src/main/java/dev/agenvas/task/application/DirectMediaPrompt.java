package dev.agenvas.task.application;

import dev.agenvas.artifact.domain.MediaDraft.PromptMention;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

final class DirectMediaPrompt {
    private static final char MENTION_MARKER = '\uFFFC';

    private DirectMediaPrompt() {
    }

    /**
     * Pairs markers with mentions in the saved order without changing the draft.
     * The caller owns aliases and protocol errors, including invalid slots.
     */
    static String render(String prompt, List<PromptMention> mentions, Function<PromptMention, String> alias,
            Supplier<? extends RuntimeException> invalid) {
        StringBuilder rendered = new StringBuilder(prompt.length());
        int start = 0;
        int mentionIndex = 0;
        for (int marker = prompt.indexOf(MENTION_MARKER); marker >= 0; marker = prompt.indexOf(MENTION_MARKER, start)) {
            if (mentionIndex >= mentions.size())
                throw invalid.get();
            rendered.append(prompt, start, marker);
            rendered.append(alias.apply(mentions.get(mentionIndex++)));
            start = marker + 1;
        }
        if (mentionIndex != mentions.size())
            throw invalid.get();
        return rendered.append(prompt, start, prompt.length()).toString();
    }
}
