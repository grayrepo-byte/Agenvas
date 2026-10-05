package dev.agenvas.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.asset.domain.Asset;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.domain.SkillContent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL and public authoring/archival boundaries; no external model or Provider calls. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.skill.worker-enabled=false", "agenvas.library.worker-enabled=false"})
class SkillPostgresIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");
    private static final Path STORAGE = temporaryRoot();
    private static final String BODY = "---\nname: warm-illustration\ndescription: Prepare a warm illustration from an explicit subject.\n---\nUse references/style-guide.md and the chosen subject.\n";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        // PER_CLASS Spring injection can precede Testcontainers' beforeAll callback.
        if (!POSTGRES.isRunning()) POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE::toString);
    }
    @Autowired IdentityService identities;
    @Autowired SkillService skills;
    @Autowired dev.agenvas.skill.infrastructure.SkillRepository repository;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired LibraryService library;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    private AdminPrincipal owner;
    private MockMvc mvc;
    private RequestPostProcessor auth;
    @BeforeAll void setup() {
        owner = identities.setup("skill-catalogue-admin", "synthetic-password-123");
        auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test void missingVersionsAndRequiredInputFlagsCannotBecomeDefaultValues() throws Exception {
        var skill = skills.create(owner.userId(), "Required fields", "");
        var body = mapper.createObjectNode().put("skillMd", BODY);
        body.putArray("outputKinds").add("IMAGE");
        body.putArray("inputSlots"); body.putArray("resources"); body.putArray("assets");
        String path = "/api/v1/skills/" + skill.id();
        mvc.perform(put(path + "/draft").with(auth).with(csrf()).contentType("application/json")
                .content(body.toString())).andExpect(status().isBadRequest());
        body.put("expectedVersion", 0);
        ((tools.jackson.databind.node.ArrayNode) body.path("inputSlots")).addObject()
                .put("alias", "subject-image").put("kind", "IMAGE");
        mvc.perform(put(path + "/draft").with(auth).with(csrf()).contentType("application/json")
                .content(body.toString())).andExpect(status().isBadRequest());
        mvc.perform(post(path + "/versions").with(auth).with(csrf()).contentType("application/json")
                .content("{\"commandKey\":\"missing-version\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch(path).with(auth).with(csrf()).contentType("application/json")
                .content("{\"title\":\"Unsafe default\",\"description\":\"\",\"trashed\":false}"))
                .andExpect(status().isBadRequest());
        assertThat(skills.getDraft(owner.userId(), skill.id()).version()).isZero();
        assertThat(skills.versions(owner.userId(), skill.id())).isEmpty();
        assertThatThrownBy(() -> jdbc.sql("update skill_draft set content_json = '{}'::jsonb where skill_id = :id")
                .param("id", skill.id()).update()).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void expiredPublicationCannotRenewOrFinishEvenBeforeAnotherWorkerClaimsIt() {
        var skill = skills.create(owner.userId(), "Expired lease", "");
        skills.saveDraft(owner.userId(), skill.id(), 0, new SkillContent.DraftContent(1, BODY,
                List.of(dev.agenvas.artifact.domain.Artifact.Kind.IMAGE), List.of(), List.of(), List.of()));
        var accepted = skills.publish(owner.userId(), skill.id(), 1, "expired-before-takeover");
        var now = java.time.Instant.now();
        var claim = transactions.execute(ignored -> repository.claim(now, now.plusSeconds(30)).orElseThrow());
        assertThat(claim.id()).isEqualTo(accepted.id());
        jdbc.sql("update skill_publish_operation set lease_until = now() - interval '1 second' where id = :id")
                .param("id", accepted.id()).update();
        assertThat(repository.progress(claim, claim.progress(), now.plusSeconds(60), java.time.Instant.now())).isFalse();
        assertThat(repository.finish(claim, SkillContent.OperationStatus.FAILED, null, "LATE", "Late writer",
                java.time.Instant.now())).isFalse();
        assertThat(skills.processNext()).isTrue();
        assertThat(skills.getOperation(owner.userId(), accepted.id()).status()).isEqualTo(SkillContent.OperationStatus.SUCCEEDED);
        assertThat(skills.versions(owner.userId(), skill.id())).hasSize(1);
    }

    @Test void authoringRequiresAuthenticationCsrfAndCasAndPublishesAFixedVersion() throws Exception {
        mvc.perform(get("/api/v1/skills")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/skills").with(auth).contentType("application/json").content("{\"title\":\"Style\"}"))
                .andExpect(status().isForbidden());
        UUID skill = UUID.fromString(json(mvc.perform(post("/api/v1/skills").with(auth).with(csrf())
                .contentType("application/json").content("{\"title\":\"Style\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asText());
        String draftPath = "/api/v1/skills/" + skill + "/draft";
        var input = Map.of("expectedVersion", 0, "skillMd", BODY, "outputKinds", List.of("IMAGE"),
                "inputSlots", List.of(Map.of("alias", "subject-image", "kind", "IMAGE", "required", true)),
                "resources", List.of(Map.of("path", "references/style-guide.md", "content", "Use a warm color palette.")), "assets", List.of());
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isOk());
        mvc.perform(put(draftPath).with(auth).with(csrf()).contentType("application/json").content(mapper.writeValueAsString(input)))
                .andExpect(status().isConflict());
        var accepted = json(mvc.perform(post("/api/v1/skills/" + skill + "/versions").with(auth).with(csrf())
                .contentType("application/json").content("{\"expectedDraftVersion\":1,\"commandKey\":\"publish-style\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        UUID operation = UUID.fromString(accepted.path("id").asText());
        assertThat(skills.processNext()).isTrue();
        UUID version = skills.getOperation(owner.userId(), operation).resultVersionId();
        assertThat(version).isNotNull();
        assertThat(skills.publish(owner.userId(), skill, 1, "publish-style").id()).isEqualTo(operation);
        assertThatThrownBy(() -> skills.publish(owner.userId(), skill, 2, "publish-style")).isInstanceOf(ApiProblemException.class);
        var original = skills.getVersion(owner.userId(), skill, version);
        skills.saveDraft(owner.userId(), skill, 1, new SkillContent.DraftContent(1, BODY + "Changed draft\n",
                List.of(dev.agenvas.artifact.domain.Artifact.Kind.IMAGE), List.of(), List.of(), List.of()));
        assertThat(skills.getVersion(owner.userId(), skill, version)).isEqualTo(original);
        assertThat(original.resources().getFirst().contentHash()).hasSize(64);
        var outsider = authentication(new UsernamePasswordAuthenticationToken(new AdminPrincipal(UUID.randomUUID(), "synthetic-outsider"), null, List.of()));
        mvc.perform(get("/api/v1/skills/" + skill + "/versions/" + version).with(outsider)).andExpect(status().isNotFound());
        var catalogue = skills.get(owner.userId(), skill);
        mvc.perform(patch("/api/v1/skills/" + skill).with(auth).with(csrf()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("expectedVersion", catalogue.version(), "title", "Renamed style", "description", "", "trashed", true))))
                .andExpect(status().isOk());
        assertThat(skills.getBundle(owner.userId(), skill, version).bundle().skillMd()).isEqualTo(BODY);
        assertThatThrownBy(() -> skills.requireSelectableVersion(owner.userId(), skill, version)).isInstanceOf(ApiProblemException.class);
        assertThat(skills.list(owner.userId(), "Renamed", true, null).items()).extracting(SkillService.SkillResponse::id).contains(skill);
        assertThat(jdbc.sql("select count(*) from skill_version where skill_id = :skill").param("skill", skill).query(Long.class).single()).isEqualTo(1);
    }

    @Test void publishedImageAndCopiedSkillSurvivePermanentSourceDeletion() throws Exception {
        byte[] image = image();
        UUID entry = uploadedImage(image, "independent-source");
        var skill = skills.create(owner.userId(), "Independent style", "");
        var draft = skills.saveDraft(owner.userId(), skill.id(), 0, imageDraft(entry, 0L));
        assertThat(draft.assets().getFirst().contentHash()).hasSize(64);
        var accepted = skills.publish(owner.userId(), skill.id(), draft.version(), "publish-independent");
        skills.processNext();
        UUID version = skills.getOperation(owner.userId(), accepted.id()).resultVersionId();
        assertThat(version).isNotNull();
        while (skills.cleanupNext()) { /* Drain durable pin cleanup for successful fixtures. */ }
        var trashed = library.trash(owner.userId(), entry, 0, false);
        library.delete(owner.userId(), entry, trashed.version());
        library.cleanupNext();
        byte[] retained = mvc.perform(get("/api/v1/skills/" + skill.id() + "/versions/" + version + "/assets/style-reference/file").with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(retained).containsExactly(image);
        String safeVersion = mvc.perform(get("/api/v1/skills/" + skill.id() + "/versions/" + version).with(auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(safeVersion).doesNotContain("objectKey", "pinKey", "ownerId", STORAGE.toString());
        var copied = skills.copy(owner.userId(), skill.id(), version, "Style copy");
        var copiedDraft = skills.getDraft(owner.userId(), copied.id());
        assertThat(copiedDraft.assets().getFirst().libraryEntryId()).isNull();
        assertThat(copiedDraft.assets().getFirst().sourceVersionId()).isEqualTo(version);
        var copiedOperation = skills.publish(owner.userId(), copied.id(), 0, "publish-copy");
        skills.processNext();
        UUID copiedVersion = skills.getOperation(owner.userId(), copiedOperation.id()).resultVersionId();
        assertThat(copiedVersion).isNotNull();
        assertThat(skills.getVersion(owner.userId(), copied.id(), copiedVersion).bundleHash())
                .isEqualTo(skills.getVersion(owner.userId(), skill.id(), version).bundleHash());
        assertThat(Files.readAllBytes(skills.file(owner.userId(), copied.id(), copiedVersion, "style-reference", false).path())).containsExactly(image);
    }

    @Test void sourceTrashedBeforePinFailsWithoutPublishingHalfABundle() throws Exception {
        UUID entry = uploadedImage(image(), "trashed-source");
        var skill = skills.create(owner.userId(), "Trashed reference", "");
        var draft = skills.saveDraft(owner.userId(), skill.id(), 0, imageDraft(entry, 0L));
        var operation = skills.publish(owner.userId(), skill.id(), draft.version(), "publish-trashed-source");
        library.trash(owner.userId(), entry, 0, false);
        skills.processNext();
        var failed = skills.getOperation(owner.userId(), operation.id());
        assertThat(failed.status()).isEqualTo(SkillContent.OperationStatus.FAILED);
        assertThat(failed.resultVersionId()).isNull();
        assertThat(failed.errorCode()).isEqualTo("VERSION_CONFLICT");
        assertThat(skills.getDraft(owner.userId(), skill.id()).version()).isEqualTo(draft.version());
        assertThat(skills.versions(owner.userId(), skill.id())).isEmpty();
    }

    @Test void expiredClaimIsRecoveredAndConcurrentWorkersPublishExactlyOnce() throws Exception {
        var skill = skills.create(owner.userId(), "Recovered style", "");
        skills.saveDraft(owner.userId(), skill.id(), 0, new SkillContent.DraftContent(1, BODY,
                List.of(dev.agenvas.artifact.domain.Artifact.Kind.IMAGE), List.of(), List.of(), List.of()));
        var operation = skills.publish(owner.userId(), skill.id(), 1, "publish-recovered-style");
        jdbc.sql("update skill_publish_operation set status = 'ARCHIVING', epoch = 7, lease_until = now() - interval '1 minute' where id = :id")
                .param("id", operation.id()).update();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return skills.processNext(); });
            var second = executor.submit(() -> { start.await(); return skills.processNext(); });
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(skills.getOperation(owner.userId(), operation.id()).status()).isEqualTo(SkillContent.OperationStatus.SUCCEEDED);
        assertThat(skills.versions(owner.userId(), skill.id())).hasSize(1);
        assertThat(jdbc.sql("select epoch from skill_publish_operation where id = :id").param("id", operation.id()).query(Long.class).single()).isEqualTo(8);
    }

    @Test void committedPinResumesAfterTheSourceIsPermanentlyDeleted() throws Exception {
        UUID entry = uploadedImage(image(), "pinned-source");
        var skill = skills.create(owner.userId(), "Pinned reference", "");
        var draft = skills.saveDraft(owner.userId(), skill.id(), 0, imageDraft(entry, 0L));
        var operation = skills.publish(owner.userId(), skill.id(), draft.version(), "publish-pinned-source");
        UUID pinId = UUID.nameUUIDFromBytes(("agenvas:skill-pin:v1:" + operation.id() + ":style-reference").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var pinned = library.pinForSkill(owner.userId(), entry, 0, pinId);
        var progress = mapper.createObjectNode();
        progress.withObject("pins").set("style-reference", mapper.valueToTree(pinned));
        // Simulate a committed pin checkpoint followed by a worker restart before archival.
        jdbc.sql("update skill_publish_operation set progress_json = cast(:progress as jsonb), status = 'ARCHIVING', epoch = 3, lease_until = now() - interval '1 minute' where id = :id")
                .param("progress", progress.toString()).param("id", operation.id()).update();
        var trashed = library.trash(owner.userId(), entry, 0, false);
        library.delete(owner.userId(), entry, trashed.version());
        skills.processNext();
        var completed = skills.getOperation(owner.userId(), operation.id());
        assertThat(completed.status()).isEqualTo(SkillContent.OperationStatus.SUCCEEDED);
        assertThat(skills.getVersion(owner.userId(), skill.id(), completed.resultVersionId()).assets()).hasSize(1);
        while (skills.cleanupNext()) { /* Drain durable pin cleanup for successful fixtures. */ }
        assertThat(Files.exists(STORAGE.resolve("library").resolve(pinned.pinKey()))).isFalse();
    }

    private SkillContent.DraftContent imageDraft(UUID entry, long version) {
        return new SkillContent.DraftContent(1, BODY, List.of(dev.agenvas.artifact.domain.Artifact.Kind.IMAGE),
                List.of(new SkillContent.InputSlot("subject-image", dev.agenvas.artifact.domain.Artifact.Kind.IMAGE, true)),
                List.of(new SkillContent.Resource("references/style-guide.md", "Use warm colors and soft strokes.")),
                List.of(new SkillContent.DraftAsset("style-reference", entry, version, null, null, null,
                        SkillContent.Usage.PROVIDER_REFERENCE, true, "Style only")));
    }
    private UUID uploadedImage(byte[] bytes, String key) throws Exception {
        var command = library.upload(owner.userId(), "Synthetic style", LibraryEntry.Category.OTHER, Asset.MediaKind.IMAGE,
                key, new MockMultipartFile("file", "style.png", "image/png", bytes));
        library.processNext();
        return UUID.fromString(library.command(owner.userId(), command.id()).result().path("entryId").asText());
    }
    private byte[] image() throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            return output.toByteArray();
        }
    }
    private JsonNode json(String text) { return mapper.readTree(text); }
    private static Path temporaryRoot() {
        try { return Files.createTempDirectory("agenvas-skill-test-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
