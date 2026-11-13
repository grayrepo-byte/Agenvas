package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProviderFailureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistedSixFieldRunningHubDiagnosticsStillReadWithoutModerationAndKeepTheirFailureCode() {
        var failure = mapper.readValue("""
                {"stage":"SUBMIT","category":"PARAMETER","httpStatus":200,"upstreamCode":803,
                  "nodeId":"27","fieldName":"aspect_ratio"}
                """, ProviderFailure.class);

        assertThat(failure).isEqualTo(new ProviderFailure(ProviderFailure.Stage.SUBMIT,
                ProviderFailure.Category.PARAMETER, 200, 803L, "27", "aspect_ratio"));
        assertThat(failure.code()).isEqualTo("RUNNINGHUB_PARAMETER_INVALID");
        assertThat(failure.moderation()).isNull();
    }

    @Test
    void moderationDiagnosticsSurvivePersistenceWithTheStablePublicFailureCode() {
        var failure = mapper.readValue("""
                {"stage":"SUBMIT","category":"MODERATION","httpStatus":400,"upstreamCode":null,
                  "nodeId":null,"fieldName":null,"moderation":{"code":"moderation_blocked",
                  "stage":"output","categories":["sexual"]}}
                """, ProviderFailure.class);

        assertThat(failure.code()).isEqualTo("OPENAI_IMAGE_MODERATION_BLOCKED");
        assertThat(failure.moderation())
                .isEqualTo(new ProviderModeration("moderation_blocked", "output", List.of("sexual")));
        assertThat(mapper.readValue(mapper.writeValueAsString(failure), ProviderFailure.class)).isEqualTo(failure);
    }
}
