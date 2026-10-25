package dev.agenvas.mcp.api;

import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.domain.McpHttpEndpoints;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.shared.i18n.ApiMessage;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated binary side channel discovered through prepare_media_upload; no browser cookies or local paths. */
@RestController
@ConditionalOnProperty(name = "agenvas.mcp.enabled", havingValue = "true", matchIfMissing = true)
public class McpUploadController {
    private final McpToolService tools;
    private final ObjectMapper mapper;

    public McpUploadController(McpToolService tools, ObjectMapper mapper) {
        this.tools = tools;
        this.mapper = mapper;
    }

    @PostMapping(path = McpHttpEndpoints.UPLOAD, consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public JsonNode upload(@AuthenticationPrincipal McpPrincipal principal,
            @RequestPart("metadata") Part metadata, @RequestPart("file") MultipartFile file) throws IOException {
        JsonNode arguments;
        if (metadata.getSize() > McpHttpEndpoints.MAX_UPLOAD_METADATA_BYTES) throw invalid();
        try (var input = metadata.getInputStream()) {
            byte[] bytes = input.readNBytes(McpHttpEndpoints.MAX_UPLOAD_METADATA_BYTES + 1);
            if (bytes.length > McpHttpEndpoints.MAX_UPLOAD_METADATA_BYTES) throw invalid();
            try { arguments = mapper.readTree(bytes); }
            catch (tools.jackson.core.JacksonException rejected) { throw invalid(); }
        }
        return tools.uploadMedia(principal, arguments, file.getSize(), () -> {
            try { return file.getInputStream(); }
            catch (IOException failure) { throw new UncheckedIOException("Cannot open uploaded media", failure); }
        }).data();
    }

    private ApiProblemException invalid() {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "TOOL_ARGUMENT_INVALID",
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"),
                ApiMessage.of("api.tool-execution-service.tool-parameter-is-invalid"), false);
    }
}
