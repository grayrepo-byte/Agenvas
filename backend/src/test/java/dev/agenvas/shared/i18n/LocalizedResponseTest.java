package dev.agenvas.shared.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.library.api.LibraryController;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryCommand;
import dev.agenvas.provider.api.RunningHubImportController;
import dev.agenvas.provider.application.RunningHubImportService;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.task.domain.Task;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

/** Fake services isolate the actual controller presentation boundary from storage and paid providers. */
class LocalizedResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiMessages messages = new ApiMessages(new I18nConfiguration().messageSource(), mapper);
    private final AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "i18n-test");

    @Test
    void libraryPollingLocalizesPersistedFailureWithoutChangingCommandOrUserContent() {
        var service = mock(LibraryService.class);
        var controller = new LibraryController(service, messages);
        UUID id = UUID.randomUUID();
        var detail = ApiMessage.of("api.video-generation-parameters.video-generation-parameters-contain-an-unknown-field", "extra");
        var result = mapper.readTree("{\"text\":\"用户创作内容\"}");
        var command = new LibraryCommand(id, principal.userId(), "command", "hash", LibraryCommand.Kind.IMPORT,
                mapper.createObjectNode(), LibraryCommand.Status.FAILED, 1, null, result, "VALIDATION_ERROR",
                mapper.writeValueAsString(detail), Instant.EPOCH, Instant.EPOCH);
        when(service.command(principal.userId(), id)).thenReturn(command);
        for (var locale : SupportedLocales.SUPPORTED) {
            var response = controller.command(principal, id, request(locale.toLanguageTag()));
            assertThat(response.id()).isEqualTo(id);
            assertThat(response.status()).isEqualTo(LibraryCommand.Status.FAILED);
            assertThat(response.errorCode()).isEqualTo("VALIDATION_ERROR");
            assertThat(response.errorDetail()).isEqualTo(messages.text(detail, locale)).contains("extra");
            assertThat(response.result()).isSameAs(result);
            assertThat(mapper.valueToTree(response).path("errorDetail").isTextual()).isTrue();
        }
        assertThat(command.errorDetail()).contains(detail.key());
    }

    @Test
    void runningHubPreviewLocalizesWarningsButPreservesImportedDefinitionAndUserLabels() {
        var service = mock(RunningHubImportService.class);
        var controller = new RunningHubImportController(service, messages);
        UUID connectionId = UUID.randomUUID();
        var definition = new RunningHubDefinition(RunningHubDefinition.SCHEMA_VERSION, RunningHubDefinition.PROTOCOL_VERSION,
                RunningHubDefinition.TargetType.WORKFLOW, "123", List.of(), List.of(),
                List.of(new RunningHubDefinition.Output(null, RunningHubDefinition.OutputKind.IMAGE, true, 1)),
                "default", false, false, null, "source-hash", List.of(new RunningHubDefinition.NodeOption("20", "保存图片")), null);
        var warning = ApiMessage.of("api.running-hub-import-service.node-field-uses-an-unsupported-mapping-format-and-was-skipped", "6", "用户字段");
        when(service.preview(eq(connectionId), eq(definition.targetType()), eq("123"), eq(Task.Kind.IMAGE_GENERATION), any()))
                .thenReturn(new RunningHubImportService.Preview(definition, List.of(warning), List.of(), "原始工作流名称"));
        var input = new RunningHubImportController.ImportRequest(definition.targetType(), "123", Task.Kind.IMAGE_GENERATION, mapper.createObjectNode());
        for (var locale : SupportedLocales.SUPPORTED) {
            var response = controller.preview(principal, connectionId, input, request(locale.toLanguageTag()));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().definition()).isSameAs(definition);
            assertThat(response.getBody().targetName()).isEqualTo("原始工作流名称");
            assertThat(response.getBody().warnings()).containsExactly(messages.text(warning, locale));
            assertThat(response.getBody().warnings().getFirst()).contains("6", "用户字段");
            assertThat(mapper.valueToTree(response.getBody()).path("warnings").get(0).isTextual()).isTrue();
            var nodes = mapper.valueToTree(response.getBody()).path("definition").path("nodeOptions");
            assertThat(nodes.get(0).path("nodeId").asText()).isEqualTo("20");
            assertThat(nodes.get(0).path("label").asText()).isEqualTo("保存图片");
        }
    }

    private MockHttpServletRequest request(String locale) {
        var request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", locale);
        return request;
    }
}
