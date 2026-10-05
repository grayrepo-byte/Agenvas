package dev.agenvas.audit.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("agenvas.audit.stream-writer")
public record CallLogWriterProperties(@DefaultValue("1") @Min(1) @Max(4) int threads,
        @DefaultValue("16") @Min(1) @Max(128) int queueCapacity,
        @DefaultValue("10") @Min(1) @Max(60) int shutdownSeconds) {}
