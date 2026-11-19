package dev.agenvas.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasCharacterNodeModels.CreateNode;
import dev.agenvas.canvas.application.CanvasCharacterNodeModels.Selection;
import dev.agenvas.canvas.application.CanvasCharacterNodeService;
import dev.agenvas.canvas.application.CanvasEditModels.Edit;
import dev.agenvas.canvas.application.CanvasEditModels.GroupChange;
import dev.agenvas.canvas.application.CanvasEditModels.GroupChangeType;
import dev.agenvas.canvas.application.CanvasEditModels.Operation;
import dev.agenvas.canvas.application.CanvasEditService;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.character.application.CharacterModels.CharacterContent;
import dev.agenvas.character.application.CharacterModels.CharacterLook;
import dev.agenvas.character.application.CharacterModels.SaveCharacter;
import dev.agenvas.character.application.CharacterService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.testing.ImageAssetFixture;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** A technical media scope reuses owned storage without becoming a user canvas. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class, properties = {
        "agenvas.llm.scheduler-enabled=false", "agenvas.provider.media.scheduler-enabled=false"})
class ProjectImageStudioScopePostgresIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    IdentityService identities;
    @Autowired
    ProjectService projects;
    @Autowired
    CanvasService canvas;
    @Autowired
    ArtifactService artifacts;
    @Autowired
    AssetService assets;
    @Autowired
    CanvasEditService edits;
    @Autowired
    CanvasCharacterNodeService characterNodes;
    @Autowired
    CharacterService characters;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    WebApplicationContext context;

    @Test
    void concurrentEntryCreatesOneOwnedScopeWithoutCanvasOrProjectListPollution() throws Exception {
        AdminPrincipal owner = owner();
        var gate = new CountDownLatch(1);
        List<Project> scopes;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> executor.submit(() -> {
                        gate.await();
                        return projects.ensureImageStudioScope(owner.userId());
                    })).toList();
            gate.countDown();
            scopes = new java.util.ArrayList<>();
            for (var call : calls)
                scopes.add(call.get(30, TimeUnit.SECONDS));
        }
        assertThat(scopes).extracting(Project::id).containsOnly(scopes.getFirst().id());
        UUID scope = scopes.getFirst().id();
        assertThat(projects.ensureImageStudioScope(owner.userId()).id()).isEqualTo(scope);
        assertThat(jdbc.sql("select count(*) from project where owner_id=:owner and purpose='IMAGE_STUDIO'")
                .param("owner", owner.userId()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:scope")
                .param("scope", scope).query(Integer.class).single()).isZero();
        Project visible = projects.create(owner.userId(), "Visible canvas", Project.AspectRatio.PORTRAIT_9_16);
        assertThat(projects.list(owner.userId(), true, null, 100).items())
                .extracting(Project::id).contains(visible.id()).doesNotContain(scope);
        assertThat(projects.get(owner.userId(), scope).id()).isEqualTo(scope);
        assertThatThrownBy(() -> projects.get(UUID.randomUUID(), scope))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    @Test
    void technicalScopeCannotBeOpenedManagedOrMutatedAsACanvas() throws Exception {
        AdminPrincipal owner = owner();
        Project scope = projects.ensureImageStudioScope(owner.userId());
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        var mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String base = "/api/v1/projects/" + scope.id();
        mvc.perform(get(base).with(auth)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/snapshot").with(auth)).andExpect(status().isNotFound());
        mvc.perform(get(base + "/canvas/items").with(auth)).andExpect(status().isNotFound());
        mvc.perform(patch(base).with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"name\":\"Renamed\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(base + "/archive").with(auth).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
                .andExpect(status().isNotFound());
        UUID artifact = artifacts.create(owner.userId(), scope.id(), Artifact.Kind.IMAGE, "Studio identity", null)
                .artifact().id();
        assertThatThrownBy(() -> canvas.apply(owner.userId(), scope.id(), List.of(new CanvasService.PlaceArtifact(
                UUID.randomUUID(), artifact, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(300), BigDecimal.valueOf(300), 0, null, false))))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> projects.requireAvailableRunSlot(owner.userId(), scope.id()))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.sql("select count(*) from canvas_item where project_id=:scope")
                .param("scope", scope.id()).query(Integer.class).single()).isZero();
        assertThat(projects.get(owner.userId(), scope.id()).version()).isZero();
    }

    @Test
    void httpEditorCannotWriteHiddenStudioGroupsOrEditReceipts() throws Exception {
        AdminPrincipal owner = owner();
        UUID project = studioContext(owner);
        var before = canvasCounts(project);
        Edit request = groupEdit();
        try {
            mvc().perform(post("/api/v1/projects/" + project + "/canvas/edits")
                    .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of())))
                    .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
            assertThat(canvasCounts(project)).isEqualTo(before);
        } finally {
            removeProbeGroup(project, request);
        }
    }

    @Test
    void applicationEditorCannotWriteHiddenStudioGroupsOrEditReceipts() {
        AdminPrincipal owner = owner();
        UUID project = projects.ensureImageStudioScope(owner.userId()).id();
        var before = canvasCounts(project);
        Edit request = groupEdit();
        try {
            assertNotFound(() -> edits.edit(owner.userId(), project, request));
            assertThat(canvasCounts(project)).isEqualTo(before);
        } finally {
            removeProbeGroup(project, request);
        }
    }

    @Test
    void httpCharacterNodeCannotCreateHiddenStudioCanvasItems() throws Exception {
        AdminPrincipal owner = owner();
        UUID project = studioContext(owner);
        CreateNode request = characterNode(owner, project);
        var before = canvasCounts(project);
        UUID item = UUID.randomUUID();
        try {
            mvc().perform(put("/api/v1/projects/" + project + "/canvas/character-nodes/" + item)
                    .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of())))
                    .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
            assertThat(canvasCounts(project)).isEqualTo(before);
        } finally {
            removeProbeNode(project, item);
        }
    }

    @Test
    void applicationCharacterNodeCannotCreateHiddenStudioCanvasItems() {
        AdminPrincipal owner = owner();
        UUID project = projects.ensureImageStudioScope(owner.userId()).id();
        CreateNode request = characterNode(owner, project);
        var before = canvasCounts(project);
        UUID item = UUID.randomUUID();
        try {
            assertNotFound(() -> characterNodes.create(owner.userId(), project, item, request));
            assertThat(canvasCounts(project)).isEqualTo(before);
        } finally {
            removeProbeNode(project, item);
        }
    }

    @Test
    void privateStudioImageReadsRemainAuthorizedWhileCanvasRoutesAreHidden() throws Exception {
        AdminPrincipal owner = owner();
        UUID project = studioContext(owner);
        UUID asset = ImageAssetFixture.archive(assets, owner.userId(), project);
        String path = "/api/v1/projects/" + project + "/assets/" + asset;
        var auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
        MockMvc mvc = mvc();
        mvc.perform(get(path).with(auth)).andExpect(status().isOk());
        var downloaded = mvc.perform(get(path + "/content").with(auth))
                .andExpect(status().isOk()).andReturn();
        assertThat(ImageIO.read(new ByteArrayInputStream(downloaded.getResponse().getContentAsByteArray()))
                .getWidth()).isEqualTo(2);
        mvc.perform(head(path + "/content").with(auth)).andExpect(status().isOk());
        mvc.perform(get(path + "/thumbnail").with(auth)).andExpect(status().isOk());
        mvc.perform(get(path + "/content").with(authentication(new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(UUID.randomUUID(), "foreign-studio-owner"), null, List.of()))))
                .andExpect(status().isNotFound());
        mvc.perform(get(path + "/content")).andExpect(status().isUnauthorized());
    }

    private UUID studioContext(AdminPrincipal owner) throws Exception {
        var result = mvc().perform(get("/api/v1/image-studio/context")
                .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()))))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("projectId").asText());
    }

    private MockMvc mvc() {
        return webAppContextSetup(context).apply(springSecurity()).build();
    }

    private Edit groupEdit() {
        return new Edit(UUID.randomUUID(), Operation.GROUP, List.of(),
                List.of(new GroupChange(GroupChangeType.UPSERT, UUID.randomUUID(), 0, "Hidden group", false)),
                List.of(), List.of());
    }

    private CreateNode characterNode(AdminPrincipal owner, UUID project) {
        UUID character = UUID.randomUUID();
        UUID look = UUID.randomUUID();
        characters.save(owner.userId(), project, character, new SaveCharacter(UUID.randomUUID(), 0,
                new CharacterContent("Synthetic character", "", List.of(new CharacterLook(
                        look, "Synthetic look", "", "", "", List.of())), null)));
        return new CreateNode(new Selection(character, 1, look, List.of(), true, false), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(300), BigDecimal.valueOf(300), 0, null, false);
    }

    private List<Integer> canvasCounts(UUID project) {
        return List.of("canvas_item", "canvas_group", "canvas_edit_receipt", "canvas_edit_command",
                "canvas_character_node", "canvas_character_connection", "canvas_character_command").stream()
                .map(table -> jdbc.sql("select count(*) from " + table + " where project_id=:project")
                        .param("project", project).query(Integer.class).single())
                .toList();
    }

    /** A pre-fix RED run must not make other scope tests fail from its deliberately exposed writes. */
    private void removeProbeGroup(UUID project, Edit request) {
        jdbc.sql("delete from canvas_edit_command where project_id=:project and command_id=:command")
                .param("project", project).param("command", request.commandId()).update();
        jdbc.sql("delete from canvas_edit_receipt where project_id=:project and id=:command")
                .param("project", project).param("command", request.commandId()).update();
        jdbc.sql("delete from canvas_group where project_id=:project and id=:group")
                .param("project", project).param("group", request.groupChanges().getFirst().groupId()).update();
    }

    private void removeProbeNode(UUID project, UUID item) {
        jdbc.sql("delete from canvas_item where project_id=:project and id=:item")
                .param("project", project).param("item", item).update();
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                error -> assertThat(error.code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }

    private AdminPrincipal owner() {
        return jdbc.sql("select id from app_user where login_name='image-studio-scope-admin'")
                .query(UUID.class).optional().map(id -> new AdminPrincipal(id, "image-studio-scope-admin"))
                .orElseGet(() -> identities.setup("image-studio-scope-admin", "synthetic-scope-password-123"));
    }
}
