package dev.agenvas.provider.application;

import dev.agenvas.asset.infrastructure.MediaToolRunner;
import dev.agenvas.provider.domain.GoogleVideoModels;
import dev.agenvas.provider.infrastructure.GoogleVideoClient;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.settings.application.CredentialCipher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GoogleOmniVideoAdapter extends GoogleVideoAdapter {
    public GoogleOmniVideoAdapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher, GoogleVideoClient client,
            GoogleVideoPreparedMedia references, MediaToolRunner tools, ObjectMapper mapper) {
        super(catalog, cipher, client, references, tools, mapper);
    }
    @Override public String adapterId() { return GoogleVideoModels.OMNI; }
}
