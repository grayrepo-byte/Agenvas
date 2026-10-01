package dev.agenvas.provider;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.MediaDraft;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.provider.application.MediaCapabilityService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.provider.application.RunningHubImportService;
import dev.agenvas.provider.domain.RunningHubDefinition;
import dev.agenvas.provider.infrastructure.RunningHubClient;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Paid opt-in test. Private files contain credentials; receipts prevent accidental repeated submission. */
@EnabledIfEnvironmentVariable(named = "AGENVAS_RUNNINGHUB_REAL_CALLS", matches = "true")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.identity.bootstrap-secret=runninghub-real-isolated-test", "agenvas.llm.scheduler-enabled=false",
        "agenvas.provider.comfyui.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class RunningHubRealProviderIT {
    private static final String ORIGIN = "https://www.runninghub.ai";
    private static final String WORKFLOW_ID = "2037454919065673729";
    private static final String APP_ID = "2039199752025280513";
    private static final int VIDEO_SECONDS = 4;
    private static final Duration DEADLINE = Duration.ofMinutes(20);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static Path privateRoot() { return Path.of(System.getenv("AGENVAS_RUNNINGHUB_REAL_DIRECTORY")); }
    private static String readPrivate(String name) {
        try { return Files.readString(privateRoot().resolve(name)).strip(); }
        catch (Exception unavailable) { throw new IllegalStateException("Private RunningHub verification file is unavailable"); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.credentials.master-key-base64", () -> readPrivate("master-key"));
        registry.add("agenvas.storage.root", () -> privateRoot().resolve("archive").toString());
    }
    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired ArtifactService artifacts;
    @Autowired MediaDraftService drafts;
    @Autowired CanvasService canvas;
    @Autowired AssetService assets;
    @Autowired MediaCapabilityService catalog;
    @Autowired MediaExecutionWorker worker;
    @Autowired RunningHubImportService imports;
    @Autowired RunningHubClient client;
    @Autowired DirectMediaTaskService direct;
    @Autowired TaskService tasks;
    @Autowired ObjectMapper mapper;
    private UUID owner;
    private final List<Attempt> attempts = new ArrayList<>();
    private record Attempt(String name, UUID projectId, UUID taskId) {}

    @Test @Timeout(1_800)
    void realWorkflowAndAppSubmitQueryDecodeArchiveAndSelect() throws Exception {
        Path receipt = privateRoot().resolve("real-evidence.json");
        assertThat(Files.exists(receipt)).as("An existing receipt must be reconciled; this test must not submit it again").isFalse();
        Files.createDirectories(privateRoot().resolve("results"));
        writeReceipts(); // Guard is installed before any upload or paid request.
        owner = identities.setup("runninghub-real-isolated-test", "real-rh-admin", "isolated-rh-password-123").userId();
        String key = readPrivate("api-key");
        var connection = catalog.createConnection(UUID.randomUUID().toString(), "RunningHub real verification", "RUNNINGHUB", ORIGIN, key);
        var workflowPreview = imports.preview(connection.id(), RunningHubDefinition.TargetType.WORKFLOW, WORKFLOW_ID, Task.Kind.VIDEO_GENERATION, null);
        assertThat(workflowPreview.definition().fields()).anyMatch(field -> field.nodeId().equals("1") && field.fieldName().equals("duration"));
        // Selected public inputs captured from the application page; Bearer-only discovery can fail upstream.
        try (var input = RunningHubRealProviderIT.class.getResourceAsStream("/runninghub/seedance-app-inputs.json")) {
            assertThat(input).isNotNull();
            var appPreview = imports.preview(connection.id(), RunningHubDefinition.TargetType.AI_APP, APP_ID, Task.Kind.VIDEO_GENERATION, mapper.readTree(input));
            assertThat(appPreview.definition().fields()).anyMatch(field -> field.fieldName().equals("resolution") && field.type() == RunningHubDefinition.FieldType.SELECT);
        }
        byte[] reference = referenceImage();
        Path upload = privateRoot().resolve("reference.png");
        Files.write(upload, reference);
        // Exercise the non-generative URL upload as well as FILE_NAME uploads performed by the adapter.
        String uploadedUrl = client.upload(ORIGIN, key, upload, "image/png", RunningHubDefinition.ResourceFormat.URL);
        RunningHubClient.validateDownload(ORIGIN, uploadedUrl);
        System.out.println("REAL_RUNNINGHUB URL_UPLOAD_OK");
        submit(connection.id(), "workflow", RunningHubDefinition.TargetType.WORKFLOW, WORKFLOW_ID, "1", "2", reference);
        submit(connection.id(), "app", RunningHubDefinition.TargetType.AI_APP, APP_ID, "15", "12", reference);
        Instant deadline = Instant.now().plus(DEADLINE);
        String previous = "";
        while (Instant.now().isBefore(deadline)) {
            worker.pollOnce("rh-real-poll");
            worker.pollOnce("rh-real-poll");
            writeReceipts();
            String current = attempts.stream().map(attempt -> attempt.name() + ":" + current(attempt).status()).reduce("", (a, b) -> a + " " + b);
            if (!current.equals(previous)) { System.out.println("REAL_RUNNINGHUB" + current); previous = current; }
            if (attempts.stream().allMatch(attempt -> terminal(current(attempt).status()))) break;
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        for (var attempt : attempts) {
            var task = current(attempt);
            assertThat(task.status()).as(attempt.name() + " provider task " + task.providerRequestId() + " error " + task.errorCode()).isEqualTo(Task.Status.SUCCEEDED);
            assertThat(task.output().path("selected").asBoolean()).isTrue();
            var version = artifacts.requireMediaVersionForTask(owner, attempt.projectId(), UUID.fromString(task.output().path("artifactVersionId").asText()), Artifact.Kind.VIDEO);
            var file = assets.get(owner, attempt.projectId(), UUID.fromString(version.content().path("assetId").asText()));
            assertThat(file.asset().durationMs()).isPositive();
            assertThat(file.asset().width()).isPositive();
            assertThat(file.asset().height()).isPositive();
            Files.copy(file.path(), privateRoot().resolve("results").resolve(attempt.name() + ".mp4"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(privateRoot().resolve("results").resolve(attempt.name() + "-asset.json"), mapper.writeValueAsString(file.asset()));
            assertThat(mapper.writeValueAsString(task).contains(key)).as("Public task must not expose the credential").isFalse();
        }
        writeReceipts();
        assertThat(worker.submitOnce("rh-real-no-resubmit")).isZero();
        assertThat(worker.pollOnce("rh-real-no-repoll")).isZero();
    }

    private void submit(UUID connectionId, String name, RunningHubDefinition.TargetType type, String id,
            String generationNode, String imageNode, byte[] reference) throws Exception {
        var project = projects.create(owner, "Real RunningHub " + name, Project.AspectRatio.LANDSCAPE_16_9);
        var referenceAsset = assets.archiveImage(owner, project.id(), new ByteArrayInputStream(reference));
        var referenceArtifact = artifacts.create(owner, project.id(), Artifact.Kind.IMAGE, "Synthetic reference",
                mapper.createObjectNode().put("sourceType", "UPLOAD").put("assetId", referenceAsset.id().toString()));
        var artifact = artifacts.create(owner, project.id(), Artifact.Kind.VIDEO, "Real result", null).artifact();
        UUID card = UUID.randomUUID();
        canvas.apply(owner, project.id(), List.of(new CanvasService.PlaceArtifact(card, artifact.id(), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("280"), new BigDecimal("240"), 0, null, false)));
        ObjectNode settings = mapper.createObjectNode();
        ObjectNode definition = settings.putObject("runningHub");
        definition.put("schemaVersion", 1).put("protocolVersion", "V2").put("targetType", type.name()).put("targetId", id);
        var fields = definition.putArray("fields");
        fields.addObject().put("key", "prompt").put("label", "提示词").put("type", "STRING").put("required", true)
                .put("nodeId", generationNode).put("fieldName", "prompt").put("source", "PROMPT");
        fields.addObject().put("key", "seconds").put("label", "时长").put("type", "INTEGER").put("required", true)
                .put("nodeId", generationNode).put("fieldName", "duration").put("source", "DURATION_SECONDS").put("encoding", "STRING")
                .put("minimum", VIDEO_SECONDS).put("maximum", VIDEO_SECONDS);
        fields.addObject().put("key", "reference").put("label", "参考图片").put("type", "IMAGE").put("required", true)
                .put("nodeId", imageNode).put("fieldName", "image").put("resourceFormat", "FILE_NAME");
        var fixed = definition.putArray("fixedBindings");
        fixed.addObject().put("nodeId", generationNode).put("fieldName", "resolution").put("value", "480p");
        fixed.addObject().put("nodeId", generationNode).put("fieldName", "ratio").put("value", "16:9");
        if (type == RunningHubDefinition.TargetType.WORKFLOW) {
            fixed.addObject().put("nodeId", generationNode).put("fieldName", "generateAudio").put("value", false);
            fixed.addObject().put("nodeId", generationNode).put("fieldName", "real_person_mode").put("value", false);
        }
        definition.putArray("outputs").addObject().put("kind", "VIDEO").put("primary", true).put("maxCount", 1);
        var capability = catalog.publishCapability(connectionId, "Real " + name + " 4s 480p", "RUNNINGHUB_VIDEO", settings);
        var parameters = mapper.createObjectNode();
        parameters.putObject("dynamicValues").put("reference", referenceArtifact.resourceDefaultVersion().id().toString());
        var draft = drafts.save(owner, project.id(), card, 0,
                "A red geometric sphere slowly rotates above a blue pedestal in a clean studio. Static camera, subtle motion, no people, no text.",
                parameters, VIDEO_SECONDS, capability.id(), null,
                List.of(new MediaDraftService.SaveMediaInput(referenceArtifact.resourceDefaultVersion().id(), MediaDraft.InputRole.REFERENCE, "#7C3AED")), List.of());
        var task = direct.run(owner, project.id(), artifact.id(), card, draft.version(), UUID.randomUUID().toString());
        attempts.add(new Attempt(name, project.id(), task.id()));
        writeReceipts();
        assertThat(worker.submitOnce("rh-real-submit")).isEqualTo(1);
        writeReceipts();
        var accepted = current(attempts.getLast());
        System.out.println("REAL_RUNNINGHUB " + name + " " + accepted.status() + " taskId=" + accepted.providerRequestId() + " error=" + accepted.errorCode());
        assertThat(accepted.status()).isEqualTo(Task.Status.WAITING_PROVIDER);
    }
    private Task current(Attempt attempt) { return tasks.get(owner, attempt.projectId(), attempt.taskId()); }
    private static boolean terminal(Task.Status status) {
        return switch (status) { case SUCCEEDED, FAILED, CANCELED, BLOCKED, UNKNOWN -> true; default -> false; };
    }
    private void writeReceipts() throws Exception {
        var evidence = mapper.createObjectNode().put("origin", ORIGIN).put("updatedAt", Instant.now().toString())
                .put("durationSeconds", VIDEO_SECONDS).put("resolution", "480p");
        var array = evidence.putArray("attempts");
        for (var attempt : attempts) {
            var task = current(attempt);
            var entry = array.addObject().put("name", attempt.name()).put("projectId", attempt.projectId().toString())
                    .put("taskId", task.id().toString()).put("providerRequestId", task.providerRequestId()).put("status", task.status().name())
                    .put("errorCode", task.errorCode());
            if (task.output() != null) entry.set("output", task.output());
        }
        Path temp = privateRoot().resolve("real-evidence.json.tmp");
        Files.writeString(temp, mapper.writeValueAsString(evidence));
        Files.move(temp, privateRoot().resolve("real-evidence.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    private static byte[] referenceImage() throws Exception {
        var image = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(230, 234, 242)); graphics.fillRect(0, 0, 1280, 720);
            graphics.setColor(new Color(40, 100, 200)); graphics.fillRect(450, 470, 380, 100);
            graphics.setColor(new Color(210, 45, 45)); graphics.fillOval(520, 230, 240, 240);
        } finally { graphics.dispose(); }
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
