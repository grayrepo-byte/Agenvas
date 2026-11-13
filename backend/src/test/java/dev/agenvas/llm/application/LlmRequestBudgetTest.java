package dev.agenvas.llm.application;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.settings.application.LlmModelPolicy;
import dev.agenvas.settings.application.ModelCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.SystemMessage;
import tools.jackson.databind.ObjectMapper;

class LlmRequestBudgetTest {
    @Test
    void countsEachManifestEntryAndHydratedAttachmentBeforeDispatch() {
        var manifest = org.springframework.ai.chat.messages.UserMessage.builder().text("Inspect images")
                .metadata(java.util.Map.of(AgentImageInputService.METADATA_KEY, List.of("first", "second"))).build();
        var media = new org.springframework.ai.content.Media(org.springframework.util.MimeTypeUtils.IMAGE_PNG,
                new org.springframework.core.io.ByteArrayResource(new byte[]{1}));
        var hydrated = org.springframework.ai.chat.messages.UserMessage.builder().text("Inspect images")
                .media(List.of(media, media)).build();
        assertThat(LlmTokenEstimator.estimate(List.of(manifest), List.of()))
                .isEqualTo(LlmTokenEstimator.estimate(List.of(hydrated), List.of())).isGreaterThan(8192);
        var direct = org.springframework.ai.chat.messages.UserMessage.builder().text("Inspect image")
                .metadata(java.util.Map.of(ImagePromptInputService.METADATA_KEY, List.of("source-version"))).build();
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), LlmModelPolicy.manual(
                ModelCatalog.Provider.COMPATIBLE, 4096, null, 512, LlmModelPolicy.OutputParameter.MAX_TOKENS));
        assertThatThrownBy(() -> budget.requireInput(List.of(direct), List.of()))
                .hasMessage("AGENT_CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void untouchedDefaultIsAnApplicationCeilingNotAMillionTokenCapacityClaim() {
        assertThat(DirectorSettings.defaults().contextWindowTokens()).isEqualTo(32768);
        assertThat(DirectorSettings.defaults().responseReserveTokens()).isEqualTo(4096);
        assertThat(DirectorSettings.defaults().summaryTokens()).isEqualTo(1024);
    }
    @ParameterizedTest
    @CsvSource({"32768,32768", "2000000,1000000"})
    void missingCapacityUsesGenericMillionTokenDefaultWithinTheApplicationCeiling(int applicationWindow,
            int expectedWindow) {
        var settings = new DirectorSettings(applicationWindow, 50, 8192, 1024, 4, 1440, 288, 300, 4, 1);
        var budget = LlmRequestBudget.resolve(settings, null);
        assertThat(budget.contextWindowTokens()).isEqualTo(expectedWindow);
        assertThat(budget.responseReserveTokens()).isEqualTo(4096);
        assertThat(budget.inputBudget()).isEqualTo(expectedWindow - 4096);
        assertThat(budget.outputParameter()).isEqualTo(LlmModelPolicy.OutputParameter.MAX_TOKENS);
    }
    @Test
    void fourKCapacityStaysFourKAndOutputIsActuallyReserved() {
        var policy = LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, 4096, null, 1024,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
        assertThat(budget.contextWindowTokens()).isEqualTo(4096);
        assertThat(budget.responseReserveTokens()).isEqualTo(1024);
        assertThat(budget.inputBudget()).isEqualTo(3072);
        assertThat(budget.compactionTriggerTokens()).isEqualTo(2048);
        assertThat(budget.summaryTokens()).isLessThanOrEqualTo(768);
    }
    @Test
    void independentInputLimitAndGlobalCeilingBothApply() {
        var policy = LlmModelPolicy.manual(ModelCatalog.Provider.GEMINI, 64000, 12000, 2048,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
        assertThat(budget.inputBudget()).isEqualTo(12000);
        assertThat(budget.contextWindowTokens()).isLessThanOrEqualTo(64000);
    }
    @Test
    void inputOnlyMetadataDoesNotTurnIntoAClaimedCombinedCapacity() {
        var policy = LlmModelPolicy.manual(ModelCatalog.Provider.GEMINI, null, 8192, 4096,
                LlmModelPolicy.OutputParameter.MAX_TOKENS);
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
        assertThat(policy.contextWindowTokens()).isNull();
        assertThat(budget.contextWindowTokens()).isEqualTo(DirectorSettings.defaults().contextWindowTokens());
        assertThat(budget.inputBudget()).isEqualTo(8192);
        assertThat(budget.responseReserveTokens()).isEqualTo(4096);
    }
    @Test
    void frozenBudgetSurvivesSerializationAndOversizeFixedInputFailsBeforeDispatch() {
        var policy = LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE, 4096, null, 1024,
                LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS);
        var budget = LlmRequestBudget.resolve(DirectorSettings.defaults(), policy);
        var mapper = new ObjectMapper();
        assertThat(mapper.treeToValue(mapper.valueToTree(budget), LlmRequestBudget.class)).isEqualTo(budget);
        assertThatThrownBy(
                () -> budget.requireInput(List.of(new SystemMessage("fixed instruction ".repeat(10000))), List.of()))
                .hasMessage("AGENT_CONTEXT_BUDGET_EXCEEDED");
    }
    @Test
    void contextPlannerPersistsBudgetAndRejectsFixedInputsAgainstIt() {
        var settings = new DirectorSettingsService(null) {
            @Override
            public DirectorSettings get() {
                return DirectorSettings.defaults();
            }
        };
        var mapper = new ObjectMapper();
        var codec = new LlmProtocolCodec(mapper);
        var planner = new AgentContextService(settings, mapper, codec);
        var budget = LlmRequestBudget.resolve(settings.get(), LlmModelPolicy.manual(ModelCatalog.Provider.COMPATIBLE,
                4096, null, 1024, LlmModelPolicy.OutputParameter.MAX_COMPLETION_TOKENS));
        var request = planner.prepare(List.of(new SystemMessage("small fixed instruction")), List.of(), budget);
        assertThat(codec.requestBudget(request)).isEqualTo(budget);
        assertThatThrownBy(() -> planner.prepare(List.of(new SystemMessage("fixed instruction ".repeat(10000))),
                List.of(), budget))
                .hasMessage("AGENT_CONTEXT_BUDGET_EXCEEDED");
    }
}
