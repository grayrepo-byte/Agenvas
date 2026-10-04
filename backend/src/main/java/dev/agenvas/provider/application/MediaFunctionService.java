package dev.agenvas.provider.application;

import dev.agenvas.provider.domain.MediaAdapterRegistry;
import dev.agenvas.provider.domain.MediaCapabilityBinding;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.JooqMediaFunctionRepository;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import dev.agenvas.task.domain.VideoOperation;
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
    public void update(VideoOperation operation, long expectedVersion, UUID capabilityId) {
        if (capabilityId != null) compatibleBinding(operation, capabilityId);
        if (!repository.update(operation, expectedVersion, capabilityId, clock.instant())) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_FUNCTION_CONFLICT", "api.media-function.conflict");
        }
    }

    public MediaCapabilityBinding resolve(VideoOperation operation, long expectedVersion) {
        var setting = repository.get(operation);
        if (setting.version() != expectedVersion) {
            throw problem(HttpStatus.CONFLICT, "MEDIA_FUNCTION_CONFLICT", "api.media-function.conflict");
        }
        if (setting.capabilityId() == null) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_FUNCTION_UNCONFIGURED", "api.media-function.unconfigured");
        }
        return compatibleBinding(operation, setting.capabilityId());
    }

    private MediaCapabilityBinding compatibleBinding(VideoOperation operation, UUID capabilityId) {
        var binding = catalog.resolve(capabilityId, operation.taskKind(), 0);
        boolean local = (operation == VideoOperation.DEPTH_MAP
                && MediaAdapterRegistry.LOCAL_VIDEO_PROCESSOR.equals(binding.adapterId()))
                || (operation == VideoOperation.EXTRACT_AUDIO
                && MediaAdapterRegistry.LOCAL_VIDEO_AUDIO_EXTRACTOR.equals(binding.adapterId()));
        if (!local && !compatibleDefinition(catalog.runningHubDefinition(binding))) {
            throw problem(HttpStatus.BAD_REQUEST, "MEDIA_FUNCTION_INCOMPATIBLE", "api.media-function.incompatible");
        }
        return binding;
    }

    /** A transform consumes exactly one fixed video; other required media would change this tool's meaning. */
    public static boolean compatibleDefinition(RunningHubDefinition definition) {
        if (definition == null) return false;
        var media = definition.fields().stream().filter(RunningHubDefinition.Field::media).toList();
        return media.size() == 1 && media.getFirst().type() == RunningHubDefinition.FieldType.VIDEO
                && media.getFirst().enabledWhen() == null;
    }

    private static ApiProblemException problem(HttpStatus status, String code, String message) {
        return new ApiProblemException(status, code, ApiMessage.of("api.media-function.title"), ApiMessage.of(message), false);
    }
}
