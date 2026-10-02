package dev.agenvas.mediatemplate.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import dev.agenvas.mediatemplate.domain.MediaTemplate.TargetKind;
import dev.agenvas.shared.error.ApiProblemException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaTemplateValidationTest {
    @Test void keepsPromptWhitespaceAndImageOrderWhileNormalizingName() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var draft = MediaTemplateService.validateDraft("  风格模板  ", TargetKind.IMAGE, "  柔和光影\n", List.of(second, first));
        assertThat(draft.name()).isEqualTo("风格模板");
        assertThat(draft.prompt()).isEqualTo("  柔和光影\n");
        assertThat(draft.imageIds()).containsExactly(second, first);
    }
    @Test void rejectsBlankPromptDuplicateImagesAndUnsupportedKind() {
        UUID image = UUID.randomUUID();
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("模板", TargetKind.VIDEO, " ", List.of()))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("模板", TargetKind.IMAGE, "提示", List.of(image, image)))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("模板", null, "提示", List.of()))
                .isInstanceOf(ApiProblemException.class);
    }
    @Test void importedImageTitlePreservesUnicodeAtTheLengthBoundary() {
        String prefix = "x".repeat(MediaTemplateService.MAX_NAME_LENGTH - " · 1".length() - 1);
        String name = prefix + "😀abc";
        assertThat(name.length()).isEqualTo(MediaTemplateService.MAX_NAME_LENGTH);
        assertThat(MediaTemplateService.importedImageTitle(name, 1)).isEqualTo(prefix + " · 1");
        assertThat(MediaTemplateService.importedImageTitle("水彩😀", 2)).isEqualTo("水彩😀 · 2");
    }

    @Test void enforcesNamePromptAndImageLimits() {
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("x".repeat(MediaTemplateService.MAX_NAME_LENGTH + 1), TargetKind.IMAGE, "提示", List.of()))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("模板", TargetKind.VIDEO, "x".repeat(MediaTemplateService.MAX_PROMPT_LENGTH + 1), List.of()))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> MediaTemplateService.validateDraft("模板", TargetKind.VIDEO, "提示",
                java.util.stream.IntStream.rangeClosed(0, MediaTemplateService.MAX_IMAGES).mapToObj(ignored -> UUID.randomUUID()).toList()))
                .isInstanceOf(ApiProblemException.class);
    }
}
