package dev.agenvas.provider.application;

import dev.agenvas.artifact.domain.ImageGenerationParameters;
import dev.agenvas.provider.domain.AttemptContext;
import dev.agenvas.provider.domain.KlingProtocol;
import dev.agenvas.provider.domain.PortInput;
import dev.agenvas.provider.infrastructure.JooqMediaCapabilityRepository;
import dev.agenvas.provider.infrastructure.KlingClient;
import dev.agenvas.provider.infrastructure.KlingMediaDownloadPolicy;
import dev.agenvas.settings.application.CredentialCipher;
import dev.agenvas.task.domain.Task;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Asynchronous Image 3 generation with at most one original reference image. */
@Component
public class KlingImage3Adapter extends KlingAdapterSupport {
    public KlingImage3Adapter(JooqMediaCapabilityRepository catalog, CredentialCipher cipher,
            KlingClient client, KlingMediaDownloadPolicy downloads, ObjectMapper mapper, KlingReferenceLoader references) {
        super(catalog, cipher, client, downloads, mapper, references);
    }
    @Override public String adapterId() { return KlingProtocol.IMAGE_ADAPTER_ID; }
    @Override protected boolean video() { return false; }
    @Override public boolean supports(PortInput input) { return input.kind() == Task.Kind.IMAGE_GENERATION; }

    @Override protected ObjectNode prepare(AttemptContext context, boolean submitting) {
        var mediaInput = context.lease().input().path("mediaInput");
        var parameters = ImageGenerationParameters.parse(mediaInput.path("parameters"));
        if (parameters.transparentBackground()) throw KlingProtocol.invalid();
        String ratio = mediaInput.path("providerParameters").path("aspectRatio").asText();
        return client.prepareImage(prompt(context), ratio, parameters.resolution(), references.load(context, false));
    }
}
