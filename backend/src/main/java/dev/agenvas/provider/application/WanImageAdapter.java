package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.WanModels;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.WanClient;
import dev.agenvas.provider.infrastructure.WanMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** A single asynchronous image request archives all returned standalone or sequential images. */
@Component
public final class WanImageAdapter extends WanProtocolAdapter {
    public WanImageAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, WanClient client,
            WanMediaDownloadPolicy downloads, ObjectMapper mapper, NativeMediaReferences references) {
        super(Task.Kind.IMAGE_GENERATION, catalog, cipher, client, downloads, mapper, references);
    }
    @Override public String adapterId() { return WanModels.IMAGE_ADAPTER_ID; }
}
