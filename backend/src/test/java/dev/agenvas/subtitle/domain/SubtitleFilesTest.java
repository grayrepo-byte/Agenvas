package dev.agenvas.subtitle.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubtitleFilesTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void importsBomCrLfAndMultilineSrtAndExportsTheCorrectedSourceTimes() {
        var track = SubtitleFiles.decode("\ufeff1\r\n00:00:01,200 --> 00:00:02,300\r\n你好\r\nSecond line\r\n",
                SubtitleFiles.Format.SRT, SOURCE, 3000, "zh");
        assertThat(track.sourceVersionId()).isEqualTo(SOURCE);
        assertThat(track.cues()).containsExactly(new SubtitleCue("你好\nSecond line", 1200, 2300));
        var corrected = track.withCues(List.of(new SubtitleCue("校正后", 1250, 2400)));
        assertThat(SubtitleFiles.encode(corrected, SubtitleFiles.Format.SRT))
                .isEqualTo("1\n00:00:01,250 --> 00:00:02,400\n校正后\n\n");
        assertThat(track.cues().getFirst().text()).isEqualTo("你好\nSecond line");
    }

    @Test void importsWebVttIdentifiersSettingsAndNotesWithoutTreatingPayloadAsMarkup() {
        var track = SubtitleFiles.decode("WEBVTT\n\nNOTE synthetic fixture\nignored\n\ncue-id\n00:01.200 --> 00:02.300 align:start\n&lt;b&gt;中文 &amp; text&lt;/b&gt;\n\n",
                SubtitleFiles.Format.VTT, SOURCE, 3000, "auto");
        assertThat(track.cues()).containsExactly(new SubtitleCue("<b>中文 & text</b>", 1200, 2300));
        String exported = SubtitleFiles.encode(track, SubtitleFiles.Format.VTT);
        assertThat(exported).isEqualTo("WEBVTT\n\n00:00:01.200 --> 00:00:02.300\n&lt;b&gt;中文 &amp; text&lt;/b&gt;\n\n");
        assertThat(SubtitleFiles.decode(exported, SubtitleFiles.Format.VTT, SOURCE, 3000, "auto")).isEqualTo(track);
    }

    @Test void preservesOverlappingCuesAndSortsExportsWithoutMutatingTheCorrectedTrack() {
        var later = new SubtitleCue("后", 1500, 2500);
        var earlier = new SubtitleCue("先", 1000, 2000);
        var track = new SubtitleTrack(SOURCE, 3000, "zh", List.of(later, earlier));
        var decoded = SubtitleFiles.decode(SubtitleFiles.encode(track, SubtitleFiles.Format.SRT),
                SubtitleFiles.Format.SRT, SOURCE, 3000, "zh");
        assertThat(decoded.cues()).containsExactly(earlier, later);
        assertThat(track.cues()).containsExactly(later, earlier);
        assertThatThrownBy(() -> track.cues().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void representsSilenceAsAnEmptyDraftInBothFormats() {
        for (var format : SubtitleFiles.Format.values()) {
            var track = new SubtitleTrack(SOURCE, 3000, "auto", List.of());
            assertThat(SubtitleFiles.decode(SubtitleFiles.encode(track, format), format, SOURCE, 3000, "auto"))
                    .isEqualTo(track);
        }
    }

    @Test void rejectsMalformedTimingOutOfRangeCuesAndUnboundedInput() {
        for (String input : List.of("1\n00:60:01,000 --> 00:00:02,000\ntext",
                "1\n00:00:02,000 --> 00:00:01,000\ntext", "1\n00:00:00,000 --> 00:00:03,001\ntext",
                "1\n99999999999999999:00:00,000 --> 99999999999999999:00:01,000\ntext",
                "1\n00:00:00,000 --> 00:00:01,000\n", "x".repeat(SubtitleFiles.MAX_FILE_BYTES + 1))) {
            assertThatThrownBy(() -> SubtitleFiles.decode(input, SubtitleFiles.Format.SRT, SOURCE, 3000, "en"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> SubtitleFiles.decode("WEBVTT\n00:00.000 --> 00:01.000\ntext",
                SubtitleFiles.Format.VTT, SOURCE, 3000, "en")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubtitleCue("line\n\nnext", 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubtitleCue("null\u0000byte", 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SubtitleTrack(SOURCE, 3000, "en", java.util.Collections.nCopies(5001,
                new SubtitleCue("text", 0, 100)))).isInstanceOf(IllegalArgumentException.class);
    }
}
