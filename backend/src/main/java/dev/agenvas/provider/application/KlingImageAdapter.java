package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.KlingModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Compiled image generation, multi-image, expansion and subject-completion profiles. */
@Component
public class KlingImageAdapter extends KlingProtocolAdapter {
    public KlingImageAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, KlingClient client,
            KlingMediaDownloadPolicy downloads, ObjectMapper mapper, KlingPreparedMedia media) {
        super(Task.Kind.IMAGE_GENERATION, catalog, cipher, client, downloads, mapper, media);
    }
    @Override public String adapterId() { return KlingModels.IMAGE_ADAPTER_ID; }
}
