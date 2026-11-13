package dev.agenvas.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agenvas.shared.i18n.ApiMessages;
import dev.agenvas.shared.i18n.I18nConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** Request format errors must not invite automatic retries as transient server failures. */
class RequestMediaTypeTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new JsonRoute())
            .setControllerAdvice(new ApiExceptionHandler(new ApiMessages(
                    new I18nConfiguration().messageSource(), new ObjectMapper())))
            .build();

    @ParameterizedTest
    @CsvSource({"en, The request content type", "zh, 请求的内容类型", "ru, Тип содержимого запроса", "ja, リクエストのコンテンツ形式"})
    void unsupportedRequestContentTypeIsAClientErrorWithoutRetry(String language, String detailPrefix)
            throws Exception {
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("synthetic input")
                .header(HttpHeaders.ACCEPT_LANGUAGE, language))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(detailPrefix)))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("synthetic input"))))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @ParameterizedTest
    @CsvSource({"en, The requested response format", "zh, 无法提供请求的响应格式", "ru, Запрошенный формат ответа",
            "ja, 指定されたレスポンス形式"})
    void unavailableResponseFormatIsAClientErrorWithoutRetry(String language, String detailPrefix) throws Exception {
        mvc.perform(post("/json").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"synthetic\"}")
                .accept(MediaType.APPLICATION_XML).header(HttpHeaders.ACCEPT_LANGUAGE, language))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith(detailPrefix)))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void invalidAcceptDoesNotRemoveTheUnsupportedContentTypeProblemBody() throws Exception {
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("synthetic input")
                .header(HttpHeaders.ACCEPT, "invalid").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @RestController
    static class JsonRoute {
        @PostMapping(path = "/json", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        Input json(@RequestBody Input input) {
            return input;
        }
    }

    record Input(String name) {
    }
}
