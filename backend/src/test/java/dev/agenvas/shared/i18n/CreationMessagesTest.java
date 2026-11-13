package dev.agenvas.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.canvas.domain.MediaUploadPurpose;
import dev.agenvas.task.domain.ImageOperation;
import dev.agenvas.task.domain.ImageOperationSpec;
import dev.agenvas.task.domain.VideoOperation;
import dev.agenvas.testing.CreationMessagesTestSupport;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

class CreationMessagesTest {
    private final CreationMessages messages = CreationMessagesTestSupport.messages();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
        LocaleContextHolder.resetLocaleContext();
    }

    @ParameterizedTest
    @CsvSource({"en-US, Crop, Upload", "zh-CN, 裁剪, 上传", "ru-RU, Обрезка, Загрузка", "ja-JP, 切り抜き, アップロード"})
    void resolvesBuiltInLabelsFromTheActualRequest(String language, String crop, String upload) {
        request(language);
        LocaleContextHolder.setLocale(Locale.CHINESE);
        assertThat(messages.text(ImageOperation.CROP.resultLabel())).isEqualTo(crop);
        assertThat(messages.text(MediaUploadPurpose.UPLOAD.resultLabel())).isEqualTo(upload);
    }

    @Test
    void backgroundCreationUsesEnglishEvenWithAnotherThreadLocale() {
        LocaleContextHolder.setLocale(Locale.JAPANESE);
        assertThat(messages.text(ImageOperation.CROP.resultLabel())).isEqualTo("Crop");
        assertThat(messages.text(VideoOperation.EXTRACT_AUDIO.resultLabel())).isEqualTo("Audio extraction");
        assertThat(messages.text(ApiMessage.of("creation.audio.trim"))).isEqualTo("Audio trim");
    }

    @Test
    void englishLabelsCoverEveryOperationAndStructuredVariantWithoutChinese() {
        request("en-US,zh;q=0.2");
        for (var operation : ImageOperation.values())
            assertEnglish(messages.text(operation.resultLabel()));
        for (var operation : VideoOperation.values())
            assertEnglish(messages.text(operation.resultLabel()));
        for (var purpose : MediaUploadPurpose.values())
            assertEnglish(messages.text(purpose.resultLabel()));
        for (String type : new String[]{"CHARACTER", "FACE", "PROP", "SCENE_GRID"}) {
            var spec = ImageOperationSpec.parse(mapper, ImageOperation.THREE_VIEW,
                    mapper.createObjectNode().put("threeViewType", type).put("aspectRatio", "16:9"));
            assertEnglish(messages.text(spec.resultLabel()));
        }
        for (String layer : new String[]{"FOREGROUND", "BACKGROUND"}) {
            var spec = ImageOperationSpec.parse(mapper, ImageOperation.LAYER_SPLIT,
                    mapper.createObjectNode().put("layerTarget", layer));
            assertEnglish(messages.text(spec.resultLabel()));
        }
        assertEnglish(messages.text(VideoOperation.SILENT_VIDEO_LABEL));
    }

    private void assertEnglish(String value) {
        assertThat(value).isNotBlank().doesNotContainPattern("\\p{IsHan}");
    }

    private void request(String language) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", language);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
