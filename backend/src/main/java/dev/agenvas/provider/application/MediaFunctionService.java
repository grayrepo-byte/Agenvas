package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaFunctionRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.provider.domain.MediaFunction;
import dev.agenvas.task.domain.ImageOperation;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Routes tools only to published, compatible adapters; never accepts arbitrary provider requests. */
@Service
public class MediaFunctionService {
    public static final UUID LOCAL_DEPTH_CAPABILITY = UUID.fromString("00000000-0000-4000-8000-000000000203");
    public static final UUID LOCAL_AUDIO_CAPABILITY = UUID.fromString("00000000-0000-4000-8000-000000000204");
    private final JooqMediaFunctionRepository repository;
    private final MediaCapabilityService catalog;
    private final Clock clock;

    public MediaFunctionService(JooqMediaFunctionRepository repository, MediaCapabilityService catalog, Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.clock = clock;
    }

    public List<JooqMediaFunctionRepository.Setting> list() { return repository.list(); }

    @Transactional
    public void update(MediaFunction operation, long expectedVersion, UUID capabilityId) {
        if (capabilityId != null) {
            catalog.lockCapabilityForConfiguration(capabilityId);
            compatibleBinding(operation, capabilityId);
        }
        if (!repository.update(operation, expectedVersion, capabilityId, clock.instant())) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_FUNCTION_CONFLICT", ApiMessage.of("api.media-function.conflict"));
        }
    }

    public MediaCapabilityBinding resolve(MediaFunction operation, long expectedVersion) {
        var setting = repository.get(operation);
        if (setting.version() != expectedVersion) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_FUNCTION_CONFLICT", ApiMessage.of("api.media-function.conflict"));
        }
        if (setting.capabilityId() == null) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_FUNCTION_UNCONFIGURED", ApiMessage.of("api.media-function.unconfigured"));
        }
        return compatibleBinding(operation, setting.capabilityId());
    }

    private MediaCapabilityBinding compatibleBinding(MediaFunction operation, UUID capabilityId) {
        var binding = catalog.resolve(capabilityId, operation.taskKind(), 0);
        var image = operation.imageOperation();
        boolean local = image != null && !image.cloud()
                && MediaAdapterRegistry.LOCAL_IMAGE_PROCESSOR.equals(binding.adapterId());
        if (image != null) {
            if (local) return binding;
            var policy = catalog.inputPolicy(binding);
            boolean transparent = image == ImageOperation.REMOVE_BACKGROUND || image == ImageOperation.LAYER_SPLIT;
            boolean nativeEdit = image.cloud() && policy.maxReferenceImages() > 0
                    && (!transparent || policy.supportsTransparentBackground())
                    && (MediaAdapterRegistry.OPENAI_GPT_IMAGE_2.equals(binding.adapterId())
                    || MediaAdapterRegistry.GOOGLE_NANO_BANANA_2.equals(binding.adapterId())
                    || MediaAdapterRegistry.COMFY_IMAGE_V1.equals(binding.adapterId()) && policy.maxReferenceImages() == 1);
            boolean workflow = !transparent && (image.cloud() || image == ImageOperation.DEPTH_MAP || image == ImageOperation.UPSCALE)
                    && compatibleDefinition(catalog.runningHubDefinition(binding), RunningHubDefinition.FieldType.IMAGE);
            if (!local && !nativeEdit && !workflow) {
                throw problem(HttpStatus.BAD_REQUEST, "MEDIA_FUNCTION_INCOMPATIBLE", ApiMessage.of("api.media-function.incompatible"));
            }
            return binding;
        }
        local = (operation == MediaFunction.VIDEO_DEPTH_MAP
                && MediaAdapterRegistry.LOCAL_VIDEO_PROCESSOR.equals(binding.adapterId()))
                || (operation == MediaFunction.VIDEO_EXTRACT_AUDIO
                && MediaAdapterRegistry.LOCAL_VIDEO_AUDIO_EXTRACTOR.equals(binding.adapterId()));
        if (!local && !compatibleDefinition(catalog.runningHubDefinition(binding), RunningHubDefinition.FieldType.VIDEO)) {
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_FUNCTION_INCOMPATIBLE", ApiMessage.of("api.media-function.incompatible"));
        }
        return binding;
    }

    /** A workflow consumes one fixed source; additional or conditional media would change the tool's meaning. */
    public static boolean compatibleDefinition(RunningHubDefinition definition, RunningHubDefinition.FieldType sourceType) {
        if (definition == null) return false;
        var media = definition.fields().stream().filter(RunningHubDefinition.Field::media).toList();
        return media.size() == 1 && media.getFirst().type() == sourceType
                && media.getFirst().enabledWhen() == null;
    }

    private static ApiProblemException problem(HttpStatus status, String code, ApiMessage detail) {
        return new ApiProblemException(status, code, ApiMessage.of("api.media-function.title"), detail, false);
    }
}
