package dev.agenvas.provider.domain;

import dev.agenvas.shared.error.ProviderFailureCodes;

/** Public, bounded provider diagnostics. Remote prose, bodies, URLs and credentials are never retained. */
public record ProviderFailure(Stage stage, Category category, Integer httpStatus, Long upstreamCode,
        String nodeId, String fieldName) {
    public enum Stage { DISCOVERY, UPLOAD, SUBMIT, POLL, DOWNLOAD }
    public enum Category { AUTHENTICATION, BALANCE, PARAMETER, RATE_LIMIT, UPLOAD, GENERATION,
        DOWNLOAD_EXPIRED, UNAVAILABLE, PROTOCOL }

    public ProviderFailure {
        if (stage == null || category == null
                || httpStatus != null && (httpStatus < 100 || httpStatus > 599)
                || upstreamCode != null && (upstreamCode < 0 || upstreamCode > 999_999_999L)
                || nodeId != null && !nodeId.matches("[0-9]{1,32}")
                || fieldName != null && (!fieldName.matches("[A-Za-z_][A-Za-z0-9_.]{0,79}")
                    || fieldName.matches("(?i).*(apikey|api_key|authorization|token|secret|password).*")))
            throw new IllegalArgumentException("Invalid public provider diagnostic");
    }

    public String code() {
        return switch (category) {
            case AUTHENTICATION -> ProviderFailureCodes.RUNNINGHUB_AUTHENTICATION_FAILED;
            case BALANCE -> ProviderFailureCodes.RUNNINGHUB_BALANCE_INSUFFICIENT;
            case PARAMETER -> ProviderFailureCodes.RUNNINGHUB_PARAMETER_INVALID;
            case RATE_LIMIT -> ProviderFailureCodes.RUNNINGHUB_RATE_LIMITED;
            case UPLOAD -> ProviderFailureCodes.RUNNINGHUB_UPLOAD_FAILED;
            case GENERATION -> ProviderFailureCodes.RUNNINGHUB_GENERATION_FAILED;
            case DOWNLOAD_EXPIRED -> ProviderFailureCodes.RUNNINGHUB_RESULT_EXPIRED;
            case UNAVAILABLE -> ProviderFailureCodes.RUNNINGHUB_UNAVAILABLE;
            case PROTOCOL -> ProviderFailureCodes.RUNNINGHUB_PROTOCOL_INVALID;
        };
    }
}
