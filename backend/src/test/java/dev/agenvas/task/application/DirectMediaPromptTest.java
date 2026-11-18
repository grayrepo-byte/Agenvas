package dev.agenvas.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agenvas.artifact.domain.MediaDraft;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectMediaPromptTest {
    private static final MediaDraft.PromptMention AUDIO = new MediaDraft.PromptMention(UUID.randomUUID(),
            MediaDraft.InputRole.AUDIO_REFERENCE);
    private static final MediaDraft.PromptMention VIDEO = new MediaDraft.PromptMention(UUID.randomUUID(),
            MediaDraft.InputRole.VIDEO_REFERENCE);

    @Test
    void rendersMixedAndRepeatedMentionsInOrderWithoutTouchingLiteralText() {
        assertThat(DirectMediaPrompt.render("🎬 e\u0301 @audio_1 \uFFFC\uFFFC then \uFFFC",
                List.of(VIDEO, AUDIO, VIDEO), mention -> mention.equals(VIDEO) ? "视频 2" : "@audio_1",
                IllegalArgumentException::new)).isEqualTo("🎬 e\u0301 @audio_1 视频 2@audio_1 then 视频 2");
    }

    @Test
    void leavesUnmarkedContentAndEmptyAliasesAsSupplied() {
        assertThat(DirectMediaPrompt.render(" @audio_1 🎬 ", List.of(), mention -> {
            throw new AssertionError("Unexpected alias resolution");
        }, IllegalArgumentException::new)).isEqualTo(" @audio_1 🎬 ");
        assertThat(DirectMediaPrompt.render("before\uFFFCafter", List.of(AUDIO), mention -> "",
                IllegalArgumentException::new)).isEqualTo("beforeafter");
    }

    @Test
    void delegatesBothCountMismatchesToTheCallersErrorFactory() {
        var mismatch = new IllegalArgumentException("Protocol mismatch");
        assertThatThrownBy(() -> DirectMediaPrompt.render("\uFFFC\uFFFC", List.of(AUDIO), mention -> "@audio_1",
                () -> mismatch)).isSameAs(mismatch);
        assertThatThrownBy(() -> DirectMediaPrompt.render("literal only", List.of(VIDEO), mention -> "@video_1",
                () -> mismatch)).isSameAs(mismatch);
    }

    @Test
    void keepsAnEarlierAliasFailureAheadOfALaterCountMismatch() {
        var aliasFailure = new IllegalStateException("Invalid protocol slot");
        assertThatThrownBy(() -> DirectMediaPrompt.render("\uFFFC\uFFFC", List.of(VIDEO), mention -> {
            throw aliasFailure;
        }, IllegalArgumentException::new)).isSameAs(aliasFailure);
    }
}
