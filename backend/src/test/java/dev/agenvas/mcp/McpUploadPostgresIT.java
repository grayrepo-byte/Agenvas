package dev.agenvas.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.mcp.application.McpTokenService;
import dev.agenvas.mcp.application.McpToolPolicy;
import dev.agenvas.mcp.application.McpToolService;
import dev.agenvas.mcp.domain.McpPrincipal;
import dev.agenvas.mcp.domain.McpScope;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real native-client multipart HTTP, actual media decoders and PostgreSQL publication/CAS boundaries. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class McpUploadPostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    @TempDir
    static Path assets;
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("agenvas.storage.root", () -> assets.toString());
    }
    @LocalServerPort
    int port;
    @Autowired
    McpTokenService tokens;
    @Autowired
    McpToolService tools;
    @Autowired
    McpToolPolicy policy;
    @Autowired
    ProjectService projects;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ObjectMapper mapper;
    Map<String, Object> uploadResponseSchema;
    UUID user;
    Project project;
    McpPrincipal principal;
    McpTokenService.Created credential;

    @BeforeEach
    void fixture() {
        jdbc.sql("update app_user set status='DISABLED' where status='ACTIVE'").update();
        user = UUID.randomUUID();
        var now = OffsetDateTime.now();
        jdbc.sql("insert into app_user(id,login_name,password_hash,status,created_at,password_changed_at,version) "
                + "values (:id,:name,'synthetic-unused-hash','ACTIVE',:now,:now,0)")
                .param("id", user).param("name", "upload-" + user).param("now", now).update();
        project = projects.create(user, "Upload project", Project.AspectRatio.PORTRAIT_9_16);
        credential = tokens.create(user, new McpTokenService.Create("Upload fixture",
                Set.of(McpScope.READ, McpScope.CONTENT_WRITE), 30));
        principal = tokens.authenticate(credential.token());
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("contracts/openapi.yaml")))
            directory = directory.getParent();
        assertThat(directory).isNotNull();
        var yaml = new org.springframework.beans.factory.config.YamlMapFactoryBean();
        yaml.setResources(
                new org.springframework.core.io.FileSystemResource(directory.resolve("contracts/openapi.yaml")));
        JsonNode document = mapper.valueToTree(yaml.getObject());
        assertThat(document.at("/components/schemas/McpMediaUploadMetadata"))
                .isEqualTo(policy.definition("prepare_media_upload").path("inputSchema"));
        uploadResponseSchema = mapper.convertValue(document.at("/components/schemas/McpMediaUploadResponse"),
                new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
    }

    @Test
    void uploadsAllMediaKindsAndUsesTheExactImageVersionInADraft(@TempDir Path fixtures) throws Exception {
        String imageVersion = null;
        for (String kind : List.of("IMAGE", "AUDIO", "VIDEO")) {
            byte[] bytes = syntheticMedia(kind, fixtures);
            var metadata = metadata("upload-" + kind, kind, bytes);
            if (kind.equals("IMAGE"))
                metadata.put("placement", Map.of("x", 10, "y", 20, "width", 360, "height", 240));
            JsonNode plan = call("prepare_media_upload", metadata);
            assertThat(plan.path("path").asText()).isEqualTo("/mcp/uploads");
            assertThat(plan.path("metadata")).isEqualTo(mapper.valueToTree(metadata));
            assertThat(plan.toString()).doesNotContain(credential.token(), "objectKey", "file://");
            var response = upload(credential.token(), null, metadata, bytes, true);
            assertThat(response.statusCode()).as("%s: %s", kind, response.body()).isEqualTo(201);
            JsonNode result = mapper.readTree(response.body());
            var validation = io.modelcontextprotocol.json.McpJsonDefaults.getSchemaValidator().validate(
                    uploadResponseSchema,
                    mapper.convertValue(result, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
                    }));
            assertThat(validation.valid()).as(validation.toString()).isTrue();
            assertThat(result.at("/artifact/createdByKind").asText()).isEqualTo("EXTERNAL_TOOL");
            assertThat(result.at("/media/kind").asText()).isEqualTo(kind);
            assertThat(result.at("/media/byteSize").asLong()).isEqualTo(bytes.length);
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(mapper.readTree(upload(credential.token(), null, metadata, bytes, false).body()))
                    .isEqualTo(result);
            String version = result.at("/artifact/versionId").asText();
            JsonNode read = call("read_artifacts",
                    Map.of("projectId", project.id().toString(), "versionIds", List.of(version)));
            assertThat(read.at("/items/0/media/kind").asText()).isEqualTo(kind);
            if (kind.equals("IMAGE")) {
                assertThat(bytes.length).isGreaterThan(300 * 1024);
                imageVersion = version;
                assertThat(result.at("/node/versionId").asText()).isEqualTo(version);
            } else
                assertThat(result.has("node")).isFalse();
        }
        JsonNode target = call("create_media_node",
                command("draft-target", Map.of("kind", "IMAGE", "title", "Consumer")));
        JsonNode draft = call("update_media_draft", command("use-upload", Map.of(
                "canvasItemId", target.at("/node/canvasItemId").asText(), "expectedDraftVersion", 0,
                "patch", Map.of("mediaInputs",
                        List.of(Map.of("versionId", imageVersion, "role", "REFERENCE", "color", "#F15CAF"))))));
        assertThat(draft.at("/draft/mediaInputs/0/versionId").asText()).isEqualTo(imageVersion);
        assertThat(call("read_project_summary", Map.of("projectId", project.id().toString())).path("artifactCount")
                .asInt()).isEqualTo(4);
    }

    @Test
    void concurrentReplaysShareOneVersionAndChangedMetadataConflicts(@TempDir Path fixtures) throws Exception {
        byte[] bytes = syntheticMedia("IMAGE", fixtures);
        var metadata = metadata("concurrent", "IMAGE", bytes);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return upload(credential.token(), null, metadata, bytes, false);
            });
            var second = executor.submit(() -> {
                start.await();
                return upload(credential.token(), null, metadata, bytes, true);
            });
            start.countDown();
            var a = first.get(40, TimeUnit.SECONDS);
            var b = second.get(40, TimeUnit.SECONDS);
            assertThat(a.statusCode()).as(a.body()).isEqualTo(201);
            assertThat(b.statusCode()).as(b.body()).isEqualTo(201);
            assertThat(mapper.readTree(b.body())).isEqualTo(mapper.readTree(a.body()));
        }
        metadata.put("title", "Changed upload under the same command");
        var conflict = upload(credential.token(), null, metadata, bytes, false);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(conflict.body()).path("code").asText()).isEqualTo("MCP_COMMAND_CONFLICT");
        assertThat(call("read_project_summary", Map.of("projectId", project.id().toString())).path("artifactCount")
                .asInt()).isEqualTo(1);
    }

    @Test
    void rejectsMissingReadOnlyRevokedAuthorityAndUntrustedBytes(@TempDir Path fixtures) throws Exception {
        byte[] bytes = syntheticMedia("IMAGE", fixtures);
        var metadata = metadata("rejected", "IMAGE", bytes);
        var reader = tokens.create(user, new McpTokenService.Create("Reader", Set.of(McpScope.READ), 30));
        assertThat(upload(null, null, metadata, bytes, false).statusCode()).isEqualTo(401);
        assertThat(upload(reader.token(), null, metadata, bytes, false).statusCode()).isEqualTo(403);
        assertThat(upload(credential.token(), "https://foreign.example", metadata, bytes, false).statusCode())
                .isEqualTo(403);
        metadata.put("projectId", UUID.randomUUID().toString());
        assertThat(upload(credential.token(), null, metadata, bytes, false).statusCode()).isEqualTo(404);
        metadata.put("projectId", project.id().toString());
        metadata.put("byteSize", bytes.length + 1);
        assertThat(upload(credential.token(), null, metadata, bytes, false).statusCode()).isEqualTo(422);
        metadata.put("byteSize", bytes.length);
        metadata.put("title", "x".repeat(17 * 1024));
        assertThat(upload(credential.token(), null, metadata, bytes, true).statusCode()).isEqualTo(400);
        metadata.put("title", "Synthetic IMAGE");
        metadata.put("sha256", "0".repeat(64));
        var mismatch = upload(credential.token(), null, metadata, bytes, false);
        assertThat(mismatch.statusCode()).isEqualTo(422);
        assertThat(mapper.readTree(mismatch.body()).path("code").asText()).isEqualTo("MCP_UPLOAD_CONTENT_MISMATCH");
        byte[] invalid = "synthetic private invalid media".getBytes(StandardCharsets.UTF_8);
        var rejected = upload(credential.token(), null, metadata("invalid", "IMAGE", invalid), invalid, false);
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(rejected.body()).doesNotContain("synthetic private invalid media", credential.token());
        tokens.revoke(user, credential.credential().id(), credential.credential().version());
        assertThat(upload(credential.token(), null, metadata, bytes, false).statusCode()).isEqualTo(401);
    }

    @Test
    void independentOwnersCannotUploadOrReferenceEachOthersMedia(@TempDir Path fixtures) throws Exception {
        byte[] bytes = syntheticMedia("IMAGE", fixtures);
        JsonNode first = mapper.readTree(
                upload(credential.token(), null, metadata("first-owner", "IMAGE", bytes), bytes, false).body());
        UUID firstProject = project.id();
        String firstVersion = first.at("/artifact/versionId").asText();
        fixture(); // A separate active account, token and project; previous owner's data remains private.
        var foreign = metadata("foreign-project", "IMAGE", bytes);
        foreign.put("projectId", firstProject.toString());
        assertThat(upload(credential.token(), null, foreign, bytes, false).statusCode()).isEqualTo(404);
        assertThat(
                upload(credential.token(), null, metadata("second-owner", "IMAGE", bytes), bytes, false).statusCode())
                .isEqualTo(201);
        try (var client = HttpClient.newHttpClient()) {
            String body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                    "params", Map.of("name", "read_artifacts", "arguments", Map.of("projectId", project.id().toString(),
                            "versionIds", List.of(firstVersion)))));
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                    .header("Authorization", "Bearer " + credential.token()).header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(response.body()).at("/result/structuredContent/code").asText())
                    .isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    private JsonNode call(String name, Map<String, Object> args) {
        var result = tools.execute(principal, name, mapper.valueToTree(args));
        policy.validateOutput(name, result.data());
        return result.data();
    }
    private Map<String, Object> command(String command, Map<String, Object> fields) {
        var result = new HashMap<String, Object>(fields);
        result.put("projectId", project.id().toString());
        result.put("commandId", command);
        return result;
    }
    private Map<String, Object> metadata(String id, String kind, byte[] bytes) throws Exception {
        return command(id, Map.of("kind", kind, "title", "Synthetic " + kind, "byteSize", bytes.length,
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
    }
    private HttpResponse<String> upload(String token, String origin, Map<String, Object> metadata, byte[] file,
            boolean chunked) throws Exception {
        String boundary = "synthetic-upload-" + UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        output.write(("--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"metadata\"; filename=\"metadata.json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + mapper.writeValueAsString(metadata) + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"untrusted.exe\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(file);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] body = output.toByteArray();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/uploads"))
                .timeout(Duration.ofSeconds(40))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(chunked
                        ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null)
            request.header("Authorization", "Bearer " + token);
        if (origin != null)
            request.header("Origin", origin);
        try (var http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private byte[] syntheticMedia(String kind, Path directory) throws Exception {
        if (kind.equals("IMAGE")) {
            var image = new java.awt.image.BufferedImage(700, 500, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var random = new java.util.Random(42);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++)
                    image.setRGB(x, y, random.nextInt());
            var output = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
        Path target = directory.resolve("synthetic." + (kind.equals("VIDEO") ? "mp4" : "wav"));
        var command = kind.equals("VIDEO")
                ? List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        "color=c=blue:s=64x96:r=24:d=0.5", "-an", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                        target.toString())
                : List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                        "sine=frequency=440:duration=0.5", "-c:a", "pcm_s16le", target.toString());
        var process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve("ffmpeg.log").toFile()).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return Files.readAllBytes(target);
    }
}
