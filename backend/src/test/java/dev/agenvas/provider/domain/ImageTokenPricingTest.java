package dev.agenvas.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ImageTokenPricingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final tools.jackson.databind.JsonNode rates = mapper.readTree("""
            {"currency":"USD","textInputPerMillion":"5","imageInputPerMillion":"8","imageOutputPerMillion":"30"}
            """);

    @Test
    void missingCacheRatesPartialCacheAndOversizedValuesStayUnknown() {
        for (String fields : java.util.List.of(
                "\"cachedInputTokens\":1,\"cachedTextInputTokens\":1,\"cachedImageInputTokens\":0",
                "\"cachedTextInputTokens\":0", "\"cachedTextInputTokens\":-1,\"cachedImageInputTokens\":0",
                "\"cachedInputTokens\":101", "\"totalTokens\":999", "\"imageOutputTokens\":1")) {
            var usage = mapper.readTree(
                    "{\"valid\":true,\"inputTokens\":100,\"textInputTokens\":100,\"imageInputTokens\":0,\"outputTokens\":2,"
                            + fields + "}");
            assertThat(ImageTokenPricing.estimate(rates, usage)).as(fields).isNull();
        }
        var oversized = mapper.readTree("""
                {"valid":true,"inputTokens":2147483648,"textInputTokens":2147483648,"imageInputTokens":0,"outputTokens":0}
                """);
        assertThat(ImageTokenPricing.estimate(rates, oversized)).isNull();
    }

    @Test
    void zeroCacheIsExplicitAndAllAmountsAreRoundedOnlyAfterSumming() {
        var usage = mapper.readTree("""
                {"valid":true,"inputTokens":2,"textInputTokens":1,"imageInputTokens":1,"outputTokens":1,"cachedInputTokens":0}
                """);
        var tiny = mapper.readTree("""
                {"textInputPerMillion":"0.2","imageInputPerMillion":"0.2","imageOutputPerMillion":"0.2"}
                """);
        assertThat(ImageTokenPricing.estimate(tiny, usage)).isEqualByComparingTo("0.000001");
    }

    @Test
    void amountsThatCannotFitExistingLedgerRemainUnknown() {
        var usage = mapper.readTree("""
                {"valid":true,"inputTokens":2147483647,"textInputTokens":2147483647,"imageInputTokens":0,"outputTokens":0}
                """);
        assertThat(ImageTokenPricing.estimate(rates, usage)).isEqualByComparingTo("10737.418235");
        ((tools.jackson.databind.node.ObjectNode) rates).put("textInputPerMillion", "9999999999.999999");
        assertThat(ImageTokenPricing.estimate(rates, usage)).isNull();
    }
}
