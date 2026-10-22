package dev.agenvas.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CompositionSubtitlesTest {
    @TempDir Path scratch;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void writesMultilingualTextAsDataAndNeutralizesAssOverridesAndFilterText() throws Exception {
        var captions = mapper.createArrayNode();
        captions.addObject().put("text", "中文 русский {\\p1}\\N, [v]; movie=/etc/passwd\n第二行")
                .put("startMs", 100).put("endMs", 1900);
        CompositionSubtitles.write(captions, 2000, 720, 1280, "Noto Sans CJK SC", scratch);
        String ass = Files.readString(scratch.resolve(CompositionSubtitles.FILE_NAME));
        assertThat(ass).contains("PlayResX: 720", "PlayResY: 1280", "中文 русский ｛＼p1｝＼N, [v]; movie=/etc/passwd\\N第二行");
        assertThat(ass).doesNotContain("{\\p1}");
        assertThat(ass.lines().filter(line -> line.startsWith("Dialogue:"))).hasSize(1);
    }

    @Test void rejectsSubtitlesOutsideTheActualTimelineAndUnsafeServerFontNames() {
        var captions = mapper.createArrayNode();
        captions.addObject().put("text", "caption").put("startMs", 0).put("endMs", 2001);
        assertThatThrownBy(() -> CompositionSubtitles.write(captions, 2000, 1280, 720, "Noto Sans CJK SC", scratch))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VideoCompositionRenderProperties("font\nDialogue: 0,0:00:00.00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
