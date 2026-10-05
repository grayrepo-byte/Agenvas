package dev.agenvas.audit.infrastructure;

import dev.agenvas.audit.application.CallLogWriterProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CallLogWriterConfiguration {
    @Bean("llmStreamLogExecutor")
    public ThreadPoolTaskExecutor llmStreamLogExecutor(CallLogWriterProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("llm-stream-log-");
        executor.setCorePoolSize(properties.threads());
        executor.setMaxPoolSize(properties.threads());
        executor.setQueueCapacity(properties.queueCapacity());
        // Keep the default rejecting policy: a full queue must never execute JDBC on the reader.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownSeconds());
        return executor;
    }
}
