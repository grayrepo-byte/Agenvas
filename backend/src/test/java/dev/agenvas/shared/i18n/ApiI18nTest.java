package dev.agenvas.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.artifact.domain.VideoGenerationParameters;
import dev.agenvas.identity.api.AuthenticationController;
import dev.agenvas.shared.error.ApiExceptionHandler;
import dev.agenvas.shared.error.ApiProblemException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ApiI18nTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), mapper);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new ApiExceptionHandler(messages))
            .setValidator(validator())
            .setLocaleResolver(new I18nConfiguration().localeResolver())
            .addFilters(new LocaleResponseFilter()).build();

    @Test
    void negotiatesWeightedRegionsAndRejectsInvalidOrExcludedLanguages() {
        assertThat(SupportedLocales.negotiate("fr-FR,ru-RU;q=0.8,en;q=0.6")).isEqualTo(Locale.forLanguageTag("ru"));
        assertThat(SupportedLocales.negotiate("en-US")).isEqualTo(Locale.ENGLISH);
        assertThat(SupportedLocales.negotiate("ja-JP")).isEqualTo(Locale.JAPANESE);
        assertThat(SupportedLocales.negotiate("zh-Hans-CN")).isEqualTo(Locale.CHINESE);
        assertThat(SupportedLocales.negotiate("en;q=0,ja;q=0.5")).isEqualTo(Locale.JAPANESE);
        for (String value : new String[] {"", "fr", "en;q=invalid", "en;q=0"}) {
            assertThat(SupportedLocales.negotiate(value)).isEqualTo(SupportedLocales.DEFAULT);
        }
    }

    @Test
    void businessErrorsUseFourLanguagesWithUnchangedCodesAndStatuses() throws Exception {
        String[] details = {"Please sign in to continue.", "请登录后继续。", "Войдите, чтобы продолжить.", "ログインして続行してください。"};
        for (int index = 0; index < SupportedLocales.SUPPORTED.size(); index++) {
            Locale locale = SupportedLocales.SUPPORTED.get(index);
            mvc.perform(get("/failure").header(HttpHeaders.ACCEPT_LANGUAGE, locale.toLanguageTag()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CONTENT_LANGUAGE, locale.toLanguageTag()))
                    .andExpect(header().string(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE))
                    .andExpect(jsonPath("$.detail").value(details[index]))
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                    .andExpect(jsonPath("$.retryable").value(false))
                    .andExpect(jsonPath("$.traceId").isString());
        }
    }

    @Test
    void realDomainValidationPreservesDynamicDetailInEveryLanguage() throws Exception {
        String[] details = {
            "Video generation parameters contain an unknown field: extra.",
            "视频生成参数包含未知字段：extra。",
            "Параметры генерации видео содержат неизвестное поле: extra.",
            "動画生成パラメータに不明なフィールドがあります：extra。"
        };
        for (int index = 0; index < SupportedLocales.SUPPORTED.size(); index++) {
            mvc.perform(post("/video").header(HttpHeaders.ACCEPT_LANGUAGE, SupportedLocales.SUPPORTED.get(index).toLanguageTag())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"extra\":\"secret value never echoed\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(details[index]))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void placeholdersAreSubstitutedOnceWithoutFormattingIdsOrInterpretingValues() {
        ApiMessage message = ApiMessage.of("api.direct-media-task-service.the-selected-image-capability-accepts-at-most-reference-images", 1234567);
        assertThat(messages.text(message, Locale.ENGLISH)).isEqualTo("The selected image capability accepts at most 1234567 reference images.");
        ApiMessage special = ApiMessage.of("api.video-generation-parameters.video-generation-parameters-contain-an-unknown-field", "$1\\{0}'");
        assertThat(messages.text(special, Locale.ENGLISH)).endsWith("$1\\{0}'.");
        assertThatThrownBy(() -> ApiMessage.of(message.key())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiMessage.of("unregistered.message")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidBodiesAndUnexpectedErrorsAreLocalizedWithoutEchoingInputs() throws Exception {
        mvc.perform(post("/validate").header(HttpHeaders.ACCEPT_LANGUAGE, "ru")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("не должно быть пустым"));
        mvc.perform(post("/validate").header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content("broken-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        var result = mvc.perform(get("/unexpected").header(HttpHeaders.ACCEPT_LANGUAGE, "ja"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("private implementation detail", "api.api-exception-handler");
    }

    @Test
    void actualSetupValidationUsesCustomCatalogMessagesInAllFourLanguages() throws Exception {
        String[] explanations = {
            "Use only letters, digits, dots, underscores or hyphens.",
            "只能包含字母、数字、点、下划线和连字符。",
            "Используйте только буквы, цифры, точки, подчёркивания или дефисы.",
            "英字、数字、ドット、アンダースコア、ハイフンのみ使用できます。"
        };
        for (int index = 0; index < SupportedLocales.SUPPORTED.size(); index++) {
            var response = mvc.perform(post("/setup-validation")
                            .header(HttpHeaders.ACCEPT_LANGUAGE, SupportedLocales.SUPPORTED.get(index).toLanguageTag())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginName\":\"bad name\",\"password\":\"private-fixture-password\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("loginName"))
                    .andExpect(jsonPath("$.fieldErrors[0].message").value(explanations[index])).andReturn();
            assertThat(response.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .doesNotContain("private-fixture-password", "bad name");
        }
    }

    @Test
    void storedDescriptorsAndHistoricalStaticErrorsResolveForEachReader() {
        ApiMessage message = ApiMessage.of("api.video-generation-parameters.video-generation-parameters-contain-an-unknown-field", "extra");
        String descriptor = mapper.writeValueAsString(message);
        for (Locale locale : SupportedLocales.SUPPORTED) {
            assertThat(messages.persisted(descriptor, request(locale.toLanguageTag())))
                    .isEqualTo(messages.text(message, locale));
        }
        assertThat(messages.persisted("资产已变化，请刷新后重试。", request("en")))
                .doesNotContain("资产已变化").doesNotContain("api.library-service");
        assertThat(messages.persisted("Unknown dynamic failure with private data", request("en")))
                .doesNotContain("private data");
        assertThat(messages.persisted("{\"key\":\"unknown\",\"arguments\":[]}", request("ja")))
                .doesNotContain("unknown");
        assertThat(messages.persisted(descriptor, request("fr"))).isEqualTo(message.source());
        assertThat(messages.persisted(null, request("en"))).isNull();
    }

    private org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator() {
        var validator = new I18nConfiguration().validator(new I18nConfiguration().messageSource());
        validator.afterPropertiesSet();
        return validator;
    }

    private MockHttpServletRequest request(String language) {
        var request = new MockHttpServletRequest("GET", "/failure");
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, language);
        return request;
    }

    @RestController
    static class FailureController {
        @GetMapping("/failure")
        void failure() {
            throw new ApiProblemException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    ApiMessage.of("api.security-configuration.sign-in-required"),
                    ApiMessage.of("api.security-configuration.please-sign-in-to-continue"), false);
        }
        @GetMapping("/unexpected")
        void unexpected() { throw new IllegalStateException("private implementation detail"); }
        @PostMapping("/validate")
        void validate(@Valid @RequestBody Input input) {}
        @PostMapping("/video")
        void video(@RequestBody JsonNode parameters) { VideoGenerationParameters.parse(parameters); }
        @PostMapping("/setup-validation")
        void setup(@Valid @RequestBody AuthenticationController.SetupRequest request) {}
    }
    record Input(@NotBlank String name) {}
}
