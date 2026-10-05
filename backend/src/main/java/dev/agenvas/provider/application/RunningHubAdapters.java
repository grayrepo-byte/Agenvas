package dev.agenvas.provider.application;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.provider.domain.MediaAdapter;
import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class RunningHubAdapters {
    @Bean MediaAdapter runningHubImage(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, ArtifactService artifacts, AssetService assets, RunningHubClient client, ObjectMapper mapper, Clock clock) {
        return new RunningHubAdapter(MediaAdapterRegistry.RUNNINGHUB_IMAGE, Task.Kind.IMAGE_GENERATION, catalog, cipher, artifacts, assets, client, mapper, clock);
    }
    @Bean MediaAdapter runningHubVideo(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, ArtifactService artifacts, AssetService assets, RunningHubClient client, ObjectMapper mapper, Clock clock) {
        return new RunningHubAdapter(MediaAdapterRegistry.RUNNINGHUB_VIDEO, Task.Kind.VIDEO_GENERATION, catalog, cipher, artifacts, assets, client, mapper, clock);
    }
    @Bean MediaAdapter runningHubAudio(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, ArtifactService artifacts, AssetService assets, RunningHubClient client, ObjectMapper mapper, Clock clock) {
        return new RunningHubAdapter(MediaAdapterRegistry.RUNNINGHUB_AUDIO, Task.Kind.AUDIO_GENERATION, catalog, cipher, artifacts, assets, client, mapper, clock);
    }
}
