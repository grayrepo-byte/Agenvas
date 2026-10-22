package dev.agenvas.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in acceptance test against an already running service with a real image Provider.
 * Configuration and all receipts stay outside the repository; this test never starts a
 * Spring context, creates credentials, changes Provider settings or resubmits generation.
 */
@EnabledIfSystemProperty(named = "agenvas.mcp.real-test", matches = "true")
class McpRealServiceIT {
    private static final Set<String> ALL_TOOLS = Set.of("list_projects", "read_project_summary", "read_canvas",
            "read_artifacts", "create_text", "revise_artifact", "manage_canvas_node", "create_media_node",
            "update_media_draft", "list_media_capabilities", "run_media", "read_task_status");
    private static final Set<String> READ_TOOLS = Set.of("list_projects", "read_project_summary", "read_canvas",
            "read_artifacts", "list_media_capabilities", "read_task_status");
    private static final Set<String> TERMINAL_FAILURES = Set.of("UNKNOWN", "BLOCKED", "FAILED", "CANCELED");
    private static final String INITIAL_TEXT = "Synthetic MCP integration text.";
    private static final String REVISED_TEXT = "Synthetic MCP integration revised text.";
    private static final String IMAGE_PROMPT = "A colorful paper boat on a plain white table, soft studio lighting, "
            + "simple still life, no text, no people.";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Set<String> invokedTools = new HashSet<>();
    private final ObjectNode receipt = mapper.createObjectNode();
    private final String commandPrefix = "mcp-real-" + UUID.randomUUID();
    private Configuration configuration;
    private Path receiptFile;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void officialClientCompletesAllToolsAndOneRealImageWithoutAnApproval() {
        try {
            configuration = readConfiguration();
            prepareEvidenceDirectory();
            receipt.put("startedAt", Instant.now().toString());
            receipt.put("projectId", configuration.projectId);
            receipt.put("capabilityId", configuration.capabilityId);
            receipt.put("generationCount", 1);
            receipt.put("status", "RUNNING");
            receipt.putArray("calls");
            saveReceipt();

            verifyReadOnlyAccess();
            try (var client = client(configuration.token)) {
                var initialized = client.initialize();
                check("2025-11-25".equals(initialized.protocolVersion()), "MCP protocol negotiation failed");
                check(initialized.capabilities().tools() != null, "MCP tools capability is missing");
                check(initialized.capabilities().resources() == null, "Unexpected MCP resource capability");
                receipt.put("protocolVersion", initialized.protocolVersion());
                validateCatalog(client);
                verifyProjectListed(client);
                JsonNode initialSummary = call(client, "read_project_summary", read());
                check(initialSummary.path("nodeCount").asLong(-1) == 0
                        && initialSummary.path("artifactCount").asLong(-1) == 0,
                        "Real MCP test requires a dedicated empty project");

                JsonNode created = call(client, "create_text", command("create-text", Map.of(
                        "title", "Synthetic MCP integration", "text", INITIAL_TEXT)));
                check("EXTERNAL_TOOL".equals(created.at("/artifact/createdByKind").asText()),
                        "Text provenance is incorrect");
                check(created.equals(call(client, "create_text", command("create-text", Map.of(
                        "title", "Synthetic MCP integration", "text", INITIAL_TEXT)))),
                        "Text command replay changed its result");
                JsonNode textNode = created.path("node");
                String originalVersion = requiredText(created.at("/artifact/versionId"));
                JsonNode revised = call(client, "revise_artifact", command("revise-text", Map.of(
                        "artifactId", requiredText(textNode.path("artifactId")),
                        "expectedVersion", created.at("/artifact/expectedVersion").asLong(), "text", REVISED_TEXT)));
                String revisedVersion = requiredText(revised.at("/artifact/versionId"));
                check(!originalVersion.equals(revisedVersion), "Text revision did not append an immutable version");
                check(revised.at("/artifact/versionNo").asLong() == created.at("/artifact/versionNo").asLong() + 1,
                        "Text version number did not advance");
                JsonNode moved = call(client, "manage_canvas_node", command("move-text", Map.of(
                        "canvasItemId", requiredText(textNode.path("canvasItemId")), "action", "MOVE",
                        "expectedVersion", textNode.path("expectedVersion").asLong(), "x", 400, "y", 200)));
                check(moved.at("/node/expectedVersion").asLong() == textNode.path("expectedVersion").asLong() + 1,
                        "Canvas CAS version did not advance");
                check(moved.at("/node/x").asDouble() == 400 && moved.at("/node/y").asDouble() == 200,
                        "Canvas move did not persist");
                JsonNode textVersions = call(client, "read_artifacts", Map.of("projectId", configuration.projectId,
                        "versionIds", List.of(originalVersion, revisedVersion)));
                check(textVersions.path("items").size() == 2, "Both immutable text versions must remain readable");
                check(INITIAL_TEXT.equals(textVersions.at("/items/0/content/text").asText())
                        && REVISED_TEXT.equals(textVersions.at("/items/1/content/text").asText()),
                        "Immutable text contents are incorrect");

                verifyImageCapability(client);
                JsonNode media = call(client, "create_media_node", command("create-image", Map.of(
                        "kind", "IMAGE", "title", "Synthetic MCP integration image")));
                String mediaItem = requiredText(media.at("/node/canvasItemId"));
                Map<String, Object> parameters = new HashMap<>();
                configuration.parameters.properties().forEach(entry ->
                        parameters.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class)));
                // Even when a configured capability defaults to a batch, this acceptance test delegates one image.
                parameters.put("generationCount", 1);
                JsonNode draft = call(client, "update_media_draft", command("image-draft", Map.of(
                        "canvasItemId", mediaItem, "expectedDraftVersion", media.at("/node/mediaDraft/version").asLong(),
                        "patch", Map.of("prompt", IMAGE_PROMPT, "capabilityId", configuration.capabilityId,
                                "parameters", parameters))));
                check(draft.at("/draft/parameters/generationCount").asInt() == 1,
                        "Draft must request exactly one image");
                JsonNode canvas = call(client, "read_canvas", Map.of("projectId", configuration.projectId,
                        "includeDrafts", true, "limit", 6));
                check(canvas.path("total").asLong() == 2 && canvas.path("items").size() == 2,
                        "Exactly two canvas nodes should exist before generation");

                Map<String, Object> generation = command("generate-image", Map.of("canvasItemId", mediaItem,
                        "expectedDraftVersion", draft.at("/draft/version").asLong()));
                JsonNode accepted = call(client, "run_media", generation);
                check(accepted.path("tasks").size() == 1, "Generation accepted more than one Task");
                check("EXTERNAL_MCP".equals(accepted.at("/tasks/0/source").asText()),
                        "Generation Task provenance is incorrect");
                check(accepted.at("/tasks/0/inputSummary/generationCount").asInt() == 1,
                        "Task must pin exactly one generated output");
                // The only retry uses the identical command and must return the already accepted Task.
                check(accepted.equals(call(client, "run_media", generation)), "Generation replay accepted a new Task");
                String taskId = requiredText(accepted.at("/tasks/0/taskId"));
                JsonNode completed = awaitSucceeded(client, taskId);
                String imageVersion = requiredText(completed.path("artifactVersionId"));
                check(requiredText(accepted.path("operationId")).equals(completed.path("operationId").asText()),
                        "Completed Task lost its MCP operation provenance");
                McpSchema.CallToolResult preview = invoke(client, "read_artifacts", Map.of(
                        "projectId", configuration.projectId, "versionIds", List.of(imageVersion)));
                JsonNode metadata = structured(preview);
                check(metadata.at("/items/0/kind").asText().equals("IMAGE"), "Result is not an image artifact");
                check(metadata.at("/items/0/media/byteSize").asLong() > 0, "Archived image is empty");
                saveDecodedImage(preview);
                JsonNode finalSummary = call(client, "read_project_summary", read());
                check(finalSummary.path("nodeCount").asLong() == 2 && finalSummary.path("artifactCount").asLong() == 2,
                        "Idempotent replay created duplicate content");
                check(invokedTools.equals(ALL_TOOLS), "Not all twelve MCP tools were exercised");
                receipt.set("invokedTools", mapper.valueToTree(invokedTools.stream().sorted().toList()));
                receipt.put("status", "SUCCEEDED");
                receipt.put("completedAt", Instant.now().toString());
                saveReceipt();
            }
        } catch (SafeFailure failure) {
            recordFailure(failure.getMessage());
            throw failure;
        } catch (Exception | AssertionError failure) {
            // SDK/HTTP exceptions can embed response bodies or URLs. Never pass them to test reporters.
            recordFailure("MCP_REAL_TEST_TRANSPORT_OR_EVIDENCE_ERROR");
            throw new SafeFailure("MCP_REAL_TEST_TRANSPORT_OR_EVIDENCE_ERROR");
        }
    }

    private void verifyReadOnlyAccess() {
        try (var reader = client(configuration.readOnlyToken)) {
            reader.initialize();
            Set<String> visible = new HashSet<>();
            reader.listTools().tools().forEach(tool -> visible.add(tool.name()));
            check(visible.equals(READ_TOOLS), "Read-only credential must expose exactly six read tools");
            var denied = reader.callTool(new McpSchema.CallToolRequest("create_text", command("read-only-denied",
                    Map.of("title", "Synthetic MCP integration", "text", INITIAL_TEXT))));
            JsonNode result = mapper.valueToTree(denied.structuredContent());
            check(Boolean.TRUE.equals(denied.isError()) && "MCP_SCOPE_DENIED".equals(result.path("code").asText()),
                    "Read-only write must fail with MCP_SCOPE_DENIED");
            receipt.put("readOnlyScopeEnforced", true);
            saveReceipt();
        }
    }

    private void validateCatalog(McpSyncClient client) {
        var tools = client.listTools().tools();
        Set<String> names = new HashSet<>();
        for (var tool : tools) {
            names.add(tool.name());
            check(tool.outputSchema() != null
                    && McpJsonDefaults.getSchemaValidator().validateSchema(tool.outputSchema()).valid(),
                    "A tool output schema is invalid");
            check(Boolean.FALSE.equals(tool.inputSchema().get("additionalProperties")),
                    "Tool input schemas must reject extra fields");
        }
        check(tools.size() == 13 && names.containsAll(ALL_TOOLS) && names.contains("compose_video"), "Full credential must expose thirteen tools");
    }

    private void verifyProjectListed(McpSyncClient client) {
        String cursor = null;
        boolean found = false;
        for (int page = 0; page < 100 && !found; page++) {
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("limit", 100);
            if (cursor != null) arguments.put("cursor", cursor);
            JsonNode projects = call(client, "list_projects", arguments);
            for (JsonNode item : projects.path("items")) {
                if (configuration.projectId.equals(item.path("projectId").asText())) found = true;
            }
            if (projects.path("nextCursor").isNull()) break;
            cursor = requiredText(projects.path("nextCursor"));
        }
        check(found, "Dedicated project is not accessible to the full credential");
    }

    private void verifyImageCapability(McpSyncClient client) {
        int offset = 0;
        boolean found = false;
        for (int page = 0; page < 100 && !found; page++) {
            JsonNode capabilities = call(client, "list_media_capabilities", Map.of("projectId", configuration.projectId,
                    "kind", "IMAGE", "offset", offset, "limit", 100));
            check(!containsField(capabilities, "adapterId"), "Capability catalog exposed its adapter identifier");
            for (JsonNode item : capabilities.path("items")) {
                if (configuration.capabilityId.equals(item.path("capabilityId").asText())) found = true;
            }
            if (capabilities.path("nextOffset").isNull()) break;
            offset = capabilities.path("nextOffset").asInt();
        }
        check(found, "Configured image capability is not available through MCP");
    }

    private JsonNode awaitSucceeded(McpSyncClient client, String taskId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode states = call(client, "read_task_status", Map.of("projectId", configuration.projectId,
                    "taskIds", List.of(taskId)));
            check(states.path("items").size() == 1, "Task status response has an unexpected size");
            JsonNode task = states.at("/items/0");
            String status = task.path("status").asText();
            if ("SUCCEEDED".equals(status)) return task;
            if (TERMINAL_FAILURES.contains(status)) {
                throw new SafeFailure("MCP_REAL_TASK_STATUS_" + safeCode(status)
                        + "_CODE_" + safeCode(task.path("errorCode").asText("NONE")));
            }
            check(Set.of("READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER").contains(status),
                    "Task returned an unexpected status");
            Thread.sleep(Duration.ofSeconds(5));
        }
        throw new SafeFailure("MCP_REAL_TASK_TIMEOUT_NO_RESUBMISSION");
    }

    private void saveDecodedImage(McpSchema.CallToolResult result) throws Exception {
        var images = new ArrayList<McpSchema.ImageContent>();
        result.content().forEach(content -> {
            if (content instanceof McpSchema.ImageContent image) images.add(image);
        });
        check(images.size() == 1, "MCP must return exactly one image preview");
        byte[] bytes = Base64.getDecoder().decode(images.getFirst().data());
        check(bytes.length > 0, "MCP image bytes are empty");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        check(decoded != null && decoded.getWidth() > 0 && decoded.getHeight() > 0,
                "MCP image bytes cannot be decoded by ImageIO");
        var png = new ByteArrayOutputStream();
        check(ImageIO.write(decoded, "png", png), "Decoded image could not be saved as PNG");
        Path imageFile = configuration.evidenceDirectory.resolve("mcp-real-preview.png");
        writePrivate(imageFile, png.toByteArray());
        receipt.put("previewWidth", decoded.getWidth());
        receipt.put("previewHeight", decoded.getHeight());
        receipt.put("previewByteSize", bytes.length);
        receipt.put("previewSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        receipt.put("previewFile", imageFile.getFileName().toString());
        saveReceipt();
    }

    private McpSyncClient client(String token) {
        URI uri = configuration.url;
        String base = uri.getScheme() + "://" + uri.getRawAuthority();
        var transport = HttpClientStreamableHttpTransport.builder(base).endpoint(uri.getRawPath())
                .openConnectionOnStartup(false).resumableStreams(false)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token)).build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(40)).initializationTimeout(Duration.ofSeconds(30))
                .clientInfo(new McpSchema.Implementation("agenvas-real-service-integration", "1.0"))
                .enableCallToolSchemaCaching(true).build();
    }

    private JsonNode call(McpSyncClient client, String name, Map<String, Object> arguments) {
        return structured(invoke(client, name, arguments));
    }

    private McpSchema.CallToolResult invoke(McpSyncClient client, String name, Map<String, Object> arguments) {
        invokedTools.add(name);
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(name, arguments));
        JsonNode data = structured(result);
        ObjectNode entry = receipt.withArray("calls").addObject();
        entry.put("tool", name).put("at", Instant.now().toString());
        entry.set("result", data.deepCopy());
        // Only structured data is recorded; no Authorization, arguments or image base64 enter the receipt.
        saveReceipt();
        if (Boolean.TRUE.equals(result.isError())) {
            throw new SafeFailure("MCP_REAL_TOOL_" + name + "_CODE_" + safeCode(data.path("code").asText("UNKNOWN")));
        }
        return result;
    }

    private JsonNode structured(McpSchema.CallToolResult result) {
        JsonNode data = mapper.valueToTree(result.structuredContent());
        check(data != null && data.isObject(), "MCP tool response has no structured object");
        return data;
    }

    private Configuration readConfiguration() throws IOException {
        String setting = System.getProperty("agenvas.mcp.real-config-file");
        check(setting != null && !setting.isBlank(), "Private real MCP configuration file is required");
        JsonNode json = mapper.readTree(Files.readString(Path.of(setting)));
        URI url = URI.create(requiredText(json.path("url")));
        check(Set.of("http", "https").contains(url.getScheme()) && url.getHost() != null
                && url.getRawUserInfo() == null && url.getRawQuery() == null && url.getRawFragment() == null
                && url.getPath().endsWith("/mcp"), "Private MCP endpoint configuration is invalid");
        String projectId = requiredText(json.path("projectId"));
        String capabilityId = requiredText(json.path("capabilityId"));
        UUID.fromString(projectId);
        UUID.fromString(capabilityId);
        check(json.path("parameters").isObject(), "Real Provider parameters must be a JSON object");
        Path evidence = Path.of(requiredText(json.path("evidenceDirectory")));
        check(evidence.isAbsolute(), "Evidence directory must be an absolute private path");
        return new Configuration(url, requiredText(json.path("token")), requiredText(json.path("readOnlyToken")),
                projectId, capabilityId, json.path("parameters").deepCopy(), evidence.normalize());
    }

    private void prepareEvidenceDirectory() throws IOException {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (repository.getParent() != null && !Files.exists(repository.resolve(".git"))) repository = repository.getParent();
        check(repository.getParent() == null || !configuration.evidenceDirectory.startsWith(repository),
                "Real test evidence must remain outside the repository");
        Files.createDirectories(configuration.evidenceDirectory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.setPosixFilePermissions(configuration.evidenceDirectory, PosixFilePermissions.fromString("rwx------"));
        Path candidate = configuration.evidenceDirectory.resolve("mcp-real-receipt.json");
        check(!Files.exists(candidate), "Use a new evidence directory for each real test attempt");
        receiptFile = candidate;
    }

    private void saveReceipt() {
        try {
            writePrivate(receiptFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        } catch (IOException failure) {
            throw new SafeFailure("MCP_REAL_EVIDENCE_WRITE_FAILED");
        }
    }

    private void recordFailure(String safeMessage) {
        if (receiptFile == null) return;
        receipt.put("status", "FAILED");
        receipt.put("failure", safeMessage);
        receipt.put("completedAt", Instant.now().toString());
        try { saveReceipt(); } catch (SafeFailure ignored) { /* Preserve the original safe failure. */ }
    }

    private void writePrivate(Path path, byte[] bytes) throws IOException {
        if (!Files.exists(path)) Files.createFile(path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        Files.write(path, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Map<String, Object> read() { return Map.of("projectId", configuration.projectId); }

    private Map<String, Object> command(String suffix, Map<String, Object> fields) {
        var result = new HashMap<String, Object>(fields);
        result.put("projectId", configuration.projectId);
        result.put("commandId", commandPrefix + "-" + suffix);
        return result;
    }

    private boolean containsField(JsonNode node, String field) {
        if (node.isObject()) {
            if (node.has(field)) return true;
            for (var entry : node.properties()) if (containsField(entry.getValue(), field)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsField(child, field)) return true;
        }
        return false;
    }

    private static String requiredText(JsonNode node) {
        check(node.isString() && !node.asText().isBlank(), "Required MCP configuration or result field is missing");
        return node.asText();
    }

    private static String safeCode(String value) {
        return value.matches("[A-Z][A-Z0-9_]{0,119}") ? value : "REDACTED";
    }

    private static void check(boolean condition, String safeMessage) {
        if (!condition) throw new SafeFailure(safeMessage);
    }

    private static final class SafeFailure extends AssertionError {
        SafeFailure(String safeMessage) { super(safeMessage); }
    }

    /** Deliberately has no generated toString: its fields include private Bearer credentials. */
    private static final class Configuration {
        final URI url;
        final String token;
        final String readOnlyToken;
        final String projectId;
        final String capabilityId;
        final JsonNode parameters;
        final Path evidenceDirectory;

        Configuration(URI url, String token, String readOnlyToken, String projectId, String capabilityId,
                JsonNode parameters, Path evidenceDirectory) {
            this.url = url;
            this.token = token;
            this.readOnlyToken = readOnlyToken;
            this.projectId = projectId;
            this.capabilityId = capabilityId;
            this.parameters = parameters;
            this.evidenceDirectory = evidenceDirectory;
        }
    }
}
