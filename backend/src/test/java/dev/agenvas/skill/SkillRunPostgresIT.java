package dev.agenvas.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.application.MediaDraftService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.asset.application.AssetService;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.canvas.application.CanvasConnectionService;
import dev.agenvas.export.application.ProjectExportManifestService;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.library.application.LibraryService;
import dev.agenvas.library.domain.LibraryEntry;
import dev.agenvas.llm.application.AgentMediaApprovalService;
import dev.agenvas.llm.application.AgentMediaOutcomeService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.llm.application.ReadToolService;
import dev.agenvas.llm.application.TrustedToolContext;
import dev.agenvas.llm.application.ToolExecutionService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.provider.application.MediaExecutionWorker;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ApiProblemException;
import dev.agenvas.skill.application.SkillService;
import dev.agenvas.skill.application.SkillRunService;
import dev.agenvas.skill.domain.SkillContent;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.application.DirectMediaTaskService;
import dev.agenvas.task.domain.Task;
import dev.agenvas.testing.ImageAssetFixture;
import dev.agenvas.testing.AgentImageInputFixture;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import tools.jackson.databind.node.ObjectNode;

/** Agent-only public flow with real PostgreSQL and a deterministic fake model. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, SkillRunPostgresIT.FakeConfig.class}, properties = { "agenvas.llm.scheduler-enabled=false",
        "agenvas.library.worker-enabled=false", "agenvas.skill.worker-enabled=false",
        "agenvas.provider.media.scheduler-enabled=false", "agenvas.tasks.scheduler-enabled=false",
        "agenvas.provider.mock.scheduler-enabled=false", "agenvas.provider.mock.video-scheduler-enabled=false"})
class SkillRunPostgresIT {
    private static final String WORKER = "skill-run-test-model";
    private static final Path STORAGE_ROOT = temporaryRoot();
    private static AdminPrincipal owner;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agenvas.storage.root", STORAGE_ROOT::toString);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired ArtifactService artifacts;
    @Autowired AssetService assets;
    @Autowired CanvasService canvas;
    @Autowired CanvasConnectionService connections;
    @Autowired LibraryService library;
    @Autowired SkillService skills;
    @Autowired SkillRunService skillRuns;
    @Autowired ProjectExportManifestService exports;
    @Autowired AgentRunService runs;
    @Autowired InitialModelContextService initialContext;
    @Autowired ReadToolService reader;
    @Autowired ToolExecutionService toolExecutions;
    @Autowired AgentTurnWorker worker;
    @Autowired AgentMediaApprovalService approvals;
    @Autowired AgentMediaOutcomeService outcomes;
    @Autowired MediaExecutionWorker mediaWorker;
    @Autowired DirectMediaTaskService directMedia;
    @Autowired MediaDraftService drafts;
    @Autowired TaskService tasks;
    @Autowired FakeGateway gateway;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;
    @Autowired WebApplicationContext context;
    private MockMvc mvc;
    private RequestPostProcessor auth;

    @BeforeEach void setup() {
        if (owner == null) owner = identities.setup("skill-run-admin", "skill-password-123");
        mvc = webAppContextSetup(context).apply(springSecurity()).build();
        auth = authentication(new UsernamePasswordAuthenticationToken(owner, null, List.of()));
    }

    @Test void multipleSelectionsFreezeTogetherAndOnlyActivatedSkillsEnterApprovalProvenance() throws Exception {
        var scenario = scenario(false);
        var first = publishedSkill(scenario, false, SkillContent.Usage.PROVIDER_REFERENCE, false);
        var second = publishedSkill(scenario, false, SkillContent.Usage.PROVIDER_REFERENCE, false);
        var refs = List.of(new SkillRunService.VersionRef(first.skillId(), first.versionId()),
                new SkillRunService.VersionRef(second.skillId(), second.versionId()));
        var saved = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), refs, "multi-default");
        assertThat(skillRuns.getBinding(owner.userId(), scenario.project().id(), scenario.agent().id()).skills()).containsExactlyElementsOf(refs);
        assertProblem("SKILL_SELECTION_INVALID", () -> skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), saved.agentVersion(),
                List.of(refs.getFirst(), refs.getFirst()), "duplicate-default"));
        for (var ref : refs) {
            skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(), ref.skillId(), ref.skillVersionId(), "multi-install-" + ref.skillId());
            assertThat(skillRuns.processNext()).isTrue();
        }
        assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), null)).hasSize(2);
        String bindingJson = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/projects/" + scenario.project().id() + "/agents/" + scenario.agent().id() + "/skill-binding").with(auth))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(bindingJson).path("skills")).hasSize(2);
        String previewJson = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/projects/" + scenario.project().id() + "/runs/preflight").with(auth).with(csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "agentId", scenario.agent().id(), "skillSelection", Map.of("mode", "DEFAULT", "skills", List.of())))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(previewJson).path("creativeSkills")).hasSize(2);
        AgentRun run = createRun(scenario, saved.agentVersion(), "multi-run", new SkillRunService.Selection(SkillRunService.SelectionMode.DEFAULT, List.of())).run();
        assertThat(run.contextSnapshot().path("creativeSkills")).hasSize(2);
        assertThat(initialContext.assemble(owner.userId(), scenario.project().id(), run.id()))
                .allSatisfy(message -> assertThat(message.getText()).doesNotContain(first.body(), first.resourceText()));
        String proposal = mapper.writeValueAsString(Map.of("outputs", List.of(Map.of("kind", "IMAGE", "title", "Combined catalogue result", "prompt", "Synthetic poster", "parameters", Map.of()))));
        gateway.prepare(run.id(), List.of(toolCall("load-first", "read_skill", "{\"skillVersionId\":\"" + first.versionId() + "\"}"),
                toolCall("read-first", "read_skill_resource", "{\"skillVersionId\":\"" + first.versionId() + "\",\"path\":\"references/style-guide.md\",\"limit\":10}"),
                toolCall("propose-combined", "propose_media_generation", proposal)));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertProblem("TOOL_ARGUMENT_INVALID", () -> reader.skillResource(runs.get(owner.userId(), scenario.project().id(), run.id()), UUID.randomUUID(),
                "{\"skillVersionId\":\"" + second.versionId() + "\",\"path\":\"references/style-guide.md\"}"));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        JsonNode source = approvals.list(owner.userId(), scenario.project().id(), run.id()).getFirst().outputs().getFirst().preview().path("creativeSkill");
        assertThat(source.path("skills")).hasSize(1);
        assertThat(source.path("skills").path(0).path("skillVersionId").asText()).isEqualTo(first.versionId().toString());
        assertThat(source.path("skills").path(0).path("resourceReads")).hasSize(1);
        runs.cancel(owner.userId(), scenario.project().id(), run.id());
    }

    @Test void rejectedAtomicReadBatchDoesNotActivateItsMainFile() throws Exception {
        var scenario = scenario(false);
        var skill = publishedSkill(scenario, false, SkillContent.Usage.PROVIDER_REFERENCE, false);
        skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(), skill.skillId(), skill.versionId(), "rollback-install");
        assertThat(skillRuns.processNext()).isTrue();
        AgentRun run = createRun(scenario, scenario.agent().version(), "rollback-read", new SkillRunService.Selection(
                SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of())))).run();
        String main = "{\"skillVersionId\":\"" + skill.versionId() + "\"}";
        String resource = "{\"skillVersionId\":\"" + skill.versionId() + "\",\"path\":\"references/style-guide.md\"}";
        var batch = AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall("rollback-main", "function", "read_skill", main),
                new AssistantMessage.ToolCall("rollback-missing", "function", "read_skill_resource", resource.replace("style-guide.md", "missing.md")))).build();
        gateway.prepare(run.id(), List.of(batch, toolCall("committed-main", "read_skill", main), new AssistantMessage("The selected method is loaded.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertProblem("TOOL_ARGUMENT_INVALID", () -> reader.skillResource(runs.get(owner.userId(), scenario.project().id(), run.id()), UUID.randomUUID(), resource));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(reader.skillResource(runs.get(owner.userId(), scenario.project().id(), run.id()), UUID.randomUUID(), resource)
                .path("data").path("content").asText()).isEqualTo(skill.resourceText());
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), scenario.project().id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test void bundledSkillsCanRunTogetherImmediatelyAndKeepProgressiveLoading() throws Exception {
        var scenario = scenario(false);
        var catalogue = skills.list(owner.userId(), "short-drama", false, null).items();
        assertThat(catalogue).hasSize(8);
        var selection = new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, catalogue.stream()
                .map(skill -> new SkillRunService.Choice(skill.id(), skill.currentVersionId(), List.of())).toList());
        assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), selection))
                .hasSize(8).allMatch(SkillRunService.Summary::installed);
        AgentRun run = createRun(scenario, scenario.agent().version(), "builtin-run", selection).run();
        var snapshots = run.contextSnapshot().path("creativeSkills");
        assertThat(snapshots).hasSize(8);
        var writing = catalogue.stream().filter(skill -> skill.title().endsWith("short-drama-write")).findFirst().orElseThrow();
        var version = skills.getVersion(owner.userId(), writing.id(), writing.currentVersionId());
        assertThat(initialContext.assemble(owner.userId(), scenario.project().id(), run.id()))
                .allSatisfy(message -> assertThat(message.getText()).doesNotContain(version.skillMd(), "Agenvas 创作卡片工作流"));
        var resource = version.resources().getFirst();
        gateway.prepare(run.id(), List.of(
                toolCall("load-builtin", "read_skill", mapper.writeValueAsString(Map.of("skillVersionId", version.id()))),
                toolCall("read-builtin-template", "read_skill_resource", mapper.writeValueAsString(Map.of("skillVersionId", version.id(), "path", resource.path()))),
                new AssistantMessage("Synthetic screenplay prepared without external calls.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(reader.skill(runs.get(owner.userId(), scenario.project().id(), run.id()), UUID.randomUUID(),
                mapper.writeValueAsString(Map.of("skillVersionId", version.id()))).path("data").path("content").asText()).isEqualTo(version.skillMd());
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(reader.skillResource(runs.get(owner.userId(), scenario.project().id(), run.id()), UUID.randomUUID(),
                mapper.writeValueAsString(Map.of("skillVersionId", version.id(), "path", resource.path(), "limit", 4000)))
                .path("data").path("content").asText()).isEqualTo(resource.content().substring(0, resource.content().offsetByCodePoints(0, Math.min(4000, resource.content().codePointCount(0, resource.content().length())))));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), scenario.project().id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test void retiredBuiltinBindingsCannotStartNewWorkButFrozenRunsAndExportsRemainReadable() throws Exception {
        var scenario = scenario(false);
        var retired = publishedSkill(scenario, false, SkillContent.Usage.PROVIDER_REFERENCE, false);
        var available = skills.list(owner.userId(), "short-drama-write", false, null).items().getFirst();
        var refs = List.of(new SkillRunService.VersionRef(retired.skillId(), retired.versionId()),
                new SkillRunService.VersionRef(available.id(), available.currentVersionId()));
        var saved = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), refs, "retired-default");
        skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(), retired.skillId(), retired.versionId(), "retired-original-install");
        assertThat(skillRuns.processNext()).isTrue();
        AgentRun frozen = createRun(scenario, saved.agentVersion(), "retired-frozen-run", null).run();
        jdbc.sql("update creative_skill set builtin_key='drama-skills/short-drama-edit' where id=:id")
                .param("id", retired.skillId()).update();
        assertThat(skillRuns.getBinding(owner.userId(), scenario.project().id(), scenario.agent().id()).skills()).containsExactly(refs.getLast());
        assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), null))
                .extracting(SkillRunService.Summary::skillId).containsExactly(available.id());
        var explicit = new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS,
                List.of(new SkillRunService.Choice(retired.skillId(), retired.versionId(), List.of())));
        assertProblem("SKILL_NOT_FOUND", () -> skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), explicit));
        assertProblem("SKILL_NOT_FOUND", () -> skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), saved.agentVersion(), refs, "retired-rebind"));
        assertProblem("SKILL_NOT_FOUND", () -> skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(), retired.skillId(), retired.versionId(), "retired-new-install"));
        assertThat(skills.getBindings(owner.userId(), scenario.project().id(), scenario.agent().id())).hasSize(2);
        assertThat(exports.build(owner.userId(), scenario.project().id()).creativeSkills()).hasSize(2);
        assertSameJson(runs.get(owner.userId(), scenario.project().id(), frozen.id()).contextSnapshot(), frozen.contextSnapshot());
        gateway.prepare(frozen.id(), List.of(
                toolCall("load-retired", "read_skill", mapper.writeValueAsString(Map.of("skillVersionId", retired.versionId()))),
                toolCall("read-retired", "read_skill_resource", mapper.writeValueAsString(Map.of("skillVersionId", retired.versionId(), "path", "references/style-guide.md"))),
                new AssistantMessage("Synthetic historical creative work completed.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(reader.skill(runs.get(owner.userId(), scenario.project().id(), frozen.id()), UUID.randomUUID(),
                mapper.writeValueAsString(Map.of("skillVersionId", retired.versionId()))).path("data").path("content").asText()).isEqualTo(retired.body());
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(reader.skillResource(runs.get(owner.userId(), scenario.project().id(), frozen.id()), UUID.randomUUID(),
                mapper.writeValueAsString(Map.of("skillVersionId", retired.versionId(), "path", "references/style-guide.md")))
                .path("data").path("content").asText()).isEqualTo(retired.resourceText());
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), scenario.project().id(), frozen.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test void nativeNovelWorkflowCreatesChapterAndCoverageCardsAndRevisesWithoutChangingPriorVersions() throws Exception {
        var project = projects.create(owner.userId(), "Native novel cards", Project.AspectRatio.LANDSCAPE_16_9);
        String original = "第一章 旧钥匙\n阿青找到钥匙，把它交给小林。\n第二章 门后的信\n小林打开门，发现一封未寄出的信。";
        var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, "原文 · 两章合成小说",
                mapper.valueToTree(Map.of("format", "PLAIN_TEXT", "text", original)));
        UUID sourceVersion = source.resourceDefaultVersion().id();
        var agent = agents.create(owner.userId(), project.id(), "原著分析", "使用卡片保存章节分析与覆盖范围", List.of(
                new AgentInstanceService.BindingInput(source.artifact().id(), sourceVersion)));
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(UUID.randomUUID(), agent.id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("420"), 0, null, false),
                new CanvasService.PlaceArtifact(UUID.randomUUID(), source.artifact().id(), new BigDecimal("400"), BigDecimal.ZERO,
                        new BigDecimal("320"), new BigDecimal("420"), 0, null, false)));
        var skill = skills.list(owner.userId(), "short-drama-novel-analyze", false, null).items().getFirst();
        var selection = new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS,
                List.of(new SkillRunService.Choice(skill.id(), skill.currentVersionId(), List.of())));
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(), "分析这两章，保存章节索引、逐章分析和覆盖检查卡", "native-novel-run",
                agent.version(), List.of(), null, null, null, null, null, selection).run();
        String indexText = "# 原著章节索引\n范围：仅提供两章\n源 artifactId：" + source.artifact().id() + "\n源 versionId：" + sourceVersion
                + "\n| sequence | 标题 | 行范围 |\n| 1 | 第一章 旧钥匙 | 1–2 |\n| 2 | 第二章 门后的信 | 3–4 |";
        gateway.prepare(run.id(), List.of(
                toolCall("native-load-main", "read_skill", mapper.writeValueAsString(Map.of("skillVersionId", skill.currentVersionId()))),
                toolCall("native-read-guide", "read_skill_resource", mapper.writeValueAsString(Map.of("skillVersionId", skill.currentVersionId(), "path", "references/agenvas-cards.md"))),
                toolCall("native-read-templates", "read_skill_resource", mapper.writeValueAsString(Map.of("skillVersionId", skill.currentVersionId(), "path", "assets/card-templates.md"))),
                toolCall("native-read-source", "read_artifacts", mapper.writeValueAsString(Map.of("versionIds", List.of(sourceVersion)))),
                toolCall("native-create-index", "create_text", mapper.writeValueAsString(Map.of("title", "原著章节索引", "format", "MARKDOWN", "text", indexText)))));
        for (int turn = 0; turn < 5; turn++) assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var index = createdTextCard(project.id(), "原著章节索引");
        UUID indexVersion = index.resourceDefaultVersion().id();
        String firstAnalysis = "# 原著分析 ch-1\nsequence：1\n索引 versionId：" + indexVersion + "\n原文 versionId：" + sourceVersion
                + "\n行范围：1–2\n## 概要\n钥匙的保管权发生变化。\n## 情节点\nP1 行动 | 阿青把钥匙交给小林 | 涉及：阿青、小林、钥匙 | 功能：小林获得开门条件 | 来源：第2行"
                + "\n## 载体记录\n可见行动：钥匙交接。\n## 出场称谓\n阿青、小林。\n## 未决项\n钥匙对应的门尚未明确。";
        gateway.prepare(run.id(), List.of(toolCall("native-create-first", "create_text", mapper.writeValueAsString(Map.of(
                "title", "原著分析 ch-1", "format", "MARKDOWN", "text", firstAnalysis)))));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var first = createdTextCard(project.id(), "原著分析 ch-1");
        String partial = "# 原著分析覆盖检查\n核对方式：Agent 内容核对\n索引 versionId：" + indexVersion
                + "\nsequence 1：" + first.resourceDefaultVersion().id() + "，完成\n缺章：2\n已核对：1/2\n下一步：分析 sequence 2";
        gateway.prepare(run.id(), List.of(toolCall("native-create-coverage", "create_text", mapper.writeValueAsString(Map.of(
                "title", "原著分析覆盖检查", "format", "MARKDOWN", "text", partial)))));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var coverage = createdTextCard(project.id(), "原著分析覆盖检查");
        UUID partialVersion = coverage.resourceDefaultVersion().id();
        String secondAnalysis = "# 原著分析 ch-2\nsequence：2\n索引 versionId：" + indexVersion + "\n原文 versionId：" + sourceVersion
                + "\n行范围：3–4\n## 概要\n门后的信带来新的未决信息。\n## 情节点\nP1 行动 | 小林打开门发现未寄的信 | 涉及：小林、门、信 | 功能：形成新的信息缺口 | 来源：第4行"
                + "\n## 载体记录\n可见行动：开门见信。\n## 出场称谓\n小林。\n## 未决项\n信件内容未明确。";
        gateway.prepare(run.id(), List.of(toolCall("native-create-second", "create_text", mapper.writeValueAsString(Map.of(
                "title", "原著分析 ch-2", "format", "MARKDOWN", "text", secondAnalysis)))));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var second = createdTextCard(project.id(), "原著分析 ch-2");
        String complete = "# 原著分析覆盖检查\n范围：提供的两章\n核对方式：Agent 内容核对\n索引 versionId：" + indexVersion
                + "\nsequence 1：" + first.resourceDefaultVersion().id() + "，完成\nsequence 2：" + second.resourceDefaultVersion().id()
                + "，完成\n已核对：2/2\n缺章：无\n重复：无\n来源变化：无\n未核对：无";
        gateway.prepare(run.id(), List.of(
                toolCall("native-revise-coverage", "revise_artifact", mapper.writeValueAsString(Map.of("artifactId", coverage.artifact().id(),
                        "expectedVersion", coverage.artifact().version(), "content", Map.of("format", "MARKDOWN", "text", complete)))),
                new AssistantMessage("已保存两章索引、分析和覆盖卡，范围仅限提供的两章。")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var updated = artifacts.get(owner.userId(), project.id(), coverage.artifact().id());
        assertThat(updated.resourceDefaultVersion().id()).isNotEqualTo(partialVersion);
        assertThat(updated.resourceDefaultVersion().content().path("text").asText()).isEqualTo(complete);
        assertThat(artifacts.requireVersion(owner.userId(), project.id(), coverage.artifact().id(), partialVersion).content().path("text").asText()).isEqualTo(partial);
        assertThat(artifacts.requireVersion(owner.userId(), project.id(), source.artifact().id(), sourceVersion).content().path("text").asText()).isEqualTo(original);
        assertThat(canvas.list(owner.userId(), project.id()).stream().filter(item -> item.item().subjectType() == dev.agenvas.canvas.domain.CanvasItem.SubjectType.ARTIFACT))
                .hasSize(5).extracting(item -> item.item().title()).contains("原著章节索引", "原著分析 ch-1", "原著分析 ch-2", "原著分析覆盖检查");
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    private ArtifactService.ArtifactView createdTextCard(UUID project, String title) {
        UUID id = jdbc.sql("select id from artifact where project_id=:project and title=:title").param("project", project).param("title", title).query(UUID.class).single();
        return artifacts.get(owner.userId(), project, id);
    }

    @Test void defaultBindingsAndPreflightAcceptOneHundredDistinctSkillsAndRejectTheNext() throws Exception {
        var scenario = scenario(false);
        var bundle = new SkillContent.Bundle(1, "synthetic-method", "Synthetic text method", "---\nname: synthetic-method\ndescription: Synthetic text method\n---\nCreate text.",
                List.of(Artifact.Kind.TEXT), List.of(), List.of(), List.of());
        var refs = new java.util.ArrayList<SkillRunService.VersionRef>();
        for (int index = 0; index < 101; index++) {
            UUID id = UUID.randomUUID(), version = UUID.randomUUID();
            jdbc.sql("insert into creative_skill(id,owner_id,title,current_version_id,created_at,updated_at) values (:id,:owner,:title,:version,now(),now())")
                    .param("id", id).param("owner", owner.userId()).param("title", "Synthetic method " + index).param("version", version).update();
            jdbc.sql("insert into skill_version(id,owner_id,skill_id,version_number,bundle_hash,bundle_json,created_at) values (:version,:owner,:id,1,repeat('0',64),cast(:bundle as jsonb),now())")
                    .param("version", version).param("owner", owner.userId()).param("id", id).param("bundle", mapper.writeValueAsString(bundle)).update();
            refs.add(new SkillRunService.VersionRef(id, version));
        }
        var saved = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), refs.subList(0, 100), "hundred-defaults");
        assertThat(saved.skills()).containsExactlyElementsOf(refs.subList(0, 100));
        assertThat(skillRuns.getBinding(owner.userId(), scenario.project().id(), scenario.agent().id()).skills()).hasSize(100);
        assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), null)).hasSize(100);
        assertProblem("SKILL_SELECTION_INVALID", () -> skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), saved.agentVersion(), refs, "too-many-defaults"));
        var choices = refs.stream().map(ref -> new SkillRunService.Choice(ref.skillId(), ref.skillVersionId(), List.of())).toList();
        assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(),
                new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, choices.subList(0, 100)))).hasSize(100);
        assertProblem("SKILL_SELECTION_INVALID", () -> skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(),
                new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, choices)));
    }

    @Test void selectionAndLocalInstallationRequireAnAgentAndDoNotStartGeneration() throws Exception {
        var scenario = scenario(true);
        var skill = publishedSkill(scenario, true);
        int callsBefore = gateway.calls.get();
        assertProblem("RESOURCE_NOT_FOUND", () -> skillRuns.install(owner.userId(), scenario.project().id(),
                scenario.subject().artifact().id(), skill.skillId(), skill.versionId(), "media-install-rejected"));
        assertProblem("RESOURCE_NOT_FOUND", () -> skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.subject().artifact().id(), 0, List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "media-binding-rejected"));
        var install = skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(),
                skill.skillId(), skill.versionId(), "agent-install");
        assertThat(install.status()).isEqualTo(SkillRunService.InstallStatus.ACCEPTED);
        assertThat(skillRuns.processNext()).isTrue();
        var ready = skillRuns.getInstallation(owner.userId(), scenario.project().id(), scenario.agent().id(), install.id());
        assertThat(ready.status()).isEqualTo(SkillRunService.InstallStatus.SUCCEEDED);
        assertThat(skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(),
                skill.skillId(), skill.versionId(), "agent-install-again").id()).isEqualTo(install.id());
        assertThat(canvas.list(owner.userId(), scenario.project().id())).hasSize(2);
        assertThat(artifacts.listProject(owner.userId(), scenario.project().id())).hasSize(2);

        var binding = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "agent-binding");
        assertThat(binding.agentVersion()).isEqualTo(scenario.agent().version() + 1);
        assertThat(skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "agent-binding")).isEqualTo(binding);
        assertThat(agents.get(owner.userId(), scenario.project().id(), scenario.agent().id()).bindings()).hasSize(1);
        assertThat(gateway.calls).hasValue(callsBefore);
        assertThat(runs.list(owner.userId(), scenario.project().id(), scenario.agent().id(), null, null).items()).isEmpty();
        assertThat(tasks.listActiveDirect(owner.userId(), scenario.project().id())).isEmpty();
        assertProblem("AGENT_VERSION_CONFLICT", () -> skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(), "stale-agent-binding"));
    }

    @Test void runChecksRequiredExactInputAndKeepsBodyResourcesAndReferencesAfterNewVersionAndUnbinding() throws Exception {
        var scenario = scenario(true);
        var skill = publishedSkill(scenario, true);
        int callsBefore = gateway.calls.get();
        var install = skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(),
                skill.skillId(), skill.versionId(), "freeze-install");
        assertThat(skillRuns.processNext()).isTrue();
        assertThat(skillRuns.getInstallation(owner.userId(), scenario.project().id(), scenario.agent().id(), install.id()).status())
                .isEqualTo(SkillRunService.InstallStatus.SUCCEEDED);
        var binding = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "freeze-binding");
        assertProblem("SKILL_INPUT_REQUIRED", () -> createRun(scenario, binding.agentVersion(), "missing-subject",
                new SkillRunService.Selection(SkillRunService.SelectionMode.DEFAULT, List.of())));
        assertProblem("SKILL_INPUT_NOT_BOUND", () -> createRun(scenario, binding.agentVersion(), "forged-subject",
                new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of(new SkillRunService.Input("subject-image", UUID.randomUUID())))))));
        assertThat(runs.list(owner.userId(), scenario.project().id(), scenario.agent().id(), null, null).items()).isEmpty();
        assertThat(gateway.calls).hasValue(callsBefore);

        var selection = new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of(new SkillRunService.Input("subject-image", scenario.subject().resourceDefaultVersion().id())))));
        AgentRun run = createRun(scenario, binding.agentVersion(), "fixed-skill-run", selection).run();
        JsonNode frozen = run.contextSnapshot().path("creativeSkills").path(0);
        assertThat(frozen.path("skillVersionId").asText()).isEqualTo(skill.versionId().toString());
        assertThat(frozen.path("skillMd").asText()).isEqualTo(skill.body());
        assertThat(frozen.path("resources").path(0).path("content").asText()).isEqualTo(skill.resourceText());
        assertThat(frozen.path("inputs").path(0).path("artifactVersionId").asText())
                .isEqualTo(scenario.subject().resourceDefaultVersion().id().toString());
        assertThat(frozen.path("assets").path(0).path("usage").asText()).isEqualTo("PROVIDER_REFERENCE");
        UUID installedArtifact = UUID.fromString(frozen.path("assets").path(0).path("artifactId").asText());
        UUID installedVersion = UUID.fromString(frozen.path("assets").path(0).path("artifactVersionId").asText());
        assertThat(artifacts.requireVersion(owner.userId(), scenario.project().id(), installedArtifact, installedVersion)
                .content().path("sourceType").asText()).isEqualTo("SKILL_IMPORT");
        assertThat(run.policySnapshot().path("systemPromptVersion").asInt()).isEqualTo(InitialModelContextService.CURRENT_SYSTEM_PROMPT_VERSION);
        assertThat(initialContext.assemble(owner.userId(), scenario.project().id(), run.id()))
                .allSatisfy(message -> assertThat(message.getText()).doesNotContain(skill.body(), skill.resourceText()));

        var copied = skills.copyVersionToDraft(owner.userId(), skill.skillId(), skill.versionId(), 1);
        var edited = skills.saveDraft(owner.userId(), skill.skillId(), copied.version(),
                new SkillContent.DraftContent(1, copied.skillMd().replace("Warm illustration", "Cold illustration"),
                        copied.outputKinds(), copied.inputSlots(),
                        List.of(new SkillContent.Resource("references/style-guide.md", "New cold palette")), copied.assets()));
        var newer = skills.publish(owner.userId(), skill.skillId(), edited.version(), "freeze-newer-version");
        assertThat(skills.processNext()).isTrue();
        assertThat(skills.getOperation(owner.userId(), newer.id()).resultVersionId()).isNotEqualTo(skill.versionId());
        skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), binding.agentVersion(), List.of(), "unbind-active-skill");
        AgentRun restored = runs.get(owner.userId(), scenario.project().id(), run.id());
        assertSameJson(restored.contextSnapshot().path("creativeSkills").path(0), frozen);
        assertProblem("TOOL_ARGUMENT_INVALID", () -> reader.skillResource(restored, UUID.randomUUID(),
                "{\"skillVersionId\":\"" + skill.versionId() + "\",\"path\":\"references/style-guide.md\"}"));
        assertThat(reader.skill(restored, UUID.randomUUID(), "{\"skillVersionId\":\"" + skill.versionId() + "\"}")
                .path("data").path("content").asText()).isEqualTo(skill.body());
        gateway.prepare(run.id(), List.of(toolCall("load-main", "read_skill", "{\"skillVersionId\":\"" + skill.versionId() + "\"}"), toolCall("read-style", "read_skill_resource",
                        "{\"skillVersionId\":\"" + skill.versionId() + "\",\"path\":\"references/style-guide.md\",\"offset\":21,\"limit\":2}"),
                new AssistantMessage("The fixed style guide is read.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(gateway.lastTools).anySatisfy(tool -> assertThat(tool.getToolDefinition().name()).isEqualTo("read_skill_resource"));
        assertThat(gateway.lastMessages.getLast()).isInstanceOf(ToolResponseMessage.class);
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        var reply = (ToolResponseMessage) gateway.lastMessages.getLast();
        assertThat(mapper.readTree(reply.getResponses().getFirst().responseData()).path("data").path("content").asText()).isEqualTo("; ");
        assertThat(runs.get(owner.userId(), scenario.project().id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
    }

    @Test void committedInstallationRemainsUsableDuringCleanupIncludingAResourceOnlySkill() throws Exception {
        for (boolean fixedAsset : List.of(true, false)) {
            var scenario = scenario(false);
            var skill = publishedSkill(scenario, false, SkillContent.Usage.PROVIDER_REFERENCE, fixedAsset);
            var accepted = skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(),
                    skill.skillId(), skill.versionId(), "cleanup-visible-" + fixedAsset);
            assertThat(skillRuns.processNext()).isTrue();
            assertThat(skillRuns.getInstallation(owner.userId(), scenario.project().id(), scenario.agent().id(), accepted.id()).status())
                    .isEqualTo(SkillRunService.InstallStatus.SUCCEEDED);
            // Synthetic recovery fixture: cleanup has a live lease after registration committed.
            jdbc.sql("update skill_install_operation set status='CLEANING', lease_until=now()+interval '1 minute' where id=:id")
                    .param("id", accepted.id()).update();
            String response = mvc.perform(get("/api/v1/projects/" + scenario.project().id() + "/agents/"
                            + scenario.agent().id() + "/skill-installations/" + accepted.id()).with(auth))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(response).path("status").asText()).isEqualTo("SUCCEEDED");
            var selection = new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of())));
            assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), selection).getFirst().installed()).isTrue();
            AgentRun run = createRun(scenario, scenario.agent().version(), "cleanup-freeze-" + fixedAsset, selection).run();
            JsonNode frozen = run.contextSnapshot().path("creativeSkills").path(0);
            assertThat(frozen.path("skillMd").asText()).isEqualTo(skill.body());
            assertThat(frozen.path("assets").size()).isEqualTo(fixedAsset ? 1 : 0);
            assertThat(exports.build(owner.userId(), scenario.project().id()).creativeSkills()).singleElement()
                    .satisfies(export -> {
                        assertThat(export.path("version").path("id").asText()).isEqualTo(skill.versionId().toString());
                        assertThat(export.path("mapping").path("assets").size()).isEqualTo(fixedAsset ? 1 : 0);
                    });
            runs.cancel(owner.userId(), scenario.project().id(), run.id());
            jdbc.sql("update skill_install_operation set lease_until=now()-interval '1 second' where id=:id")
                    .param("id", accepted.id()).update();
            // Drain the finite recovery queue through its public worker boundary.
            while (skillRuns.cleanupNext()) { }
            assertThat(skillRuns.getInstallation(owner.userId(), scenario.project().id(), scenario.agent().id(), accepted.id()).status())
                    .isEqualTo(SkillRunService.InstallStatus.SUCCEEDED);
            assertThat(skillRuns.preview(owner.userId(), scenario.project().id(), scenario.agent().id(), selection).getFirst().installed()).isTrue();
            if (fixedAsset) {
                UUID artifactId = UUID.fromString(frozen.path("assets").path(0).path("artifactId").asText());
                UUID versionId = UUID.fromString(frozen.path("assets").path(0).path("artifactVersionId").asText());
                var version = artifacts.requireVersion(owner.userId(), scenario.project().id(), artifactId, versionId);
                UUID assetId = UUID.fromString(version.content().path("assetId").asText());
                assertThat(Files.readAllBytes(assets.get(owner.userId(), scenario.project().id(), assetId).path())).isNotEmpty();
            }
        }
    }

    private AgentRunService.CreateResult createRun(Scenario scenario, long expectedAgentVersion,
            String key, SkillRunService.Selection selection) {
        return runs.create(owner.userId(), scenario.project().id(), scenario.agent().id(), "Create the warm subject image", key,
                expectedAgentVersion, List.of(), null, null, null, null, null, selection);
    }

    @Test void approvedBatchKeepsActualSkillReadsAndExactReferencesWhileDirectRegenerationHasNoSkillExecutionSource() throws Exception {
        var scenario = scenario(true);
        var skill = publishedSkill(scenario, true);
        skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(),
                skill.skillId(), skill.versionId(), "approved-install");
        assertThat(skillRuns.processNext()).isTrue();
        var binding = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "approved-binding");
        AgentRun run = createRun(scenario, binding.agentVersion(), "approved-skill-run",
                new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of(new SkillRunService.Input("subject-image", scenario.subject().resourceDefaultVersion().id())))))).run();
        String styleVersion = run.contextSnapshot().path("creativeSkills").path(0).path("assets").path(0).path("artifactVersionId").asText();
        String proposal = mapper.writeValueAsString(Map.of("outputs", List.of(Map.of(
                "kind", "IMAGE", "title", "Warm subject result", "prompt", "Warm synthetic subject", "parameters", Map.of(),
                "mediaInputs", List.of(Map.of("versionId", scenario.subject().resourceDefaultVersion().id(), "role", "REFERENCE"),
                        Map.of("versionId", styleVersion, "role", "REFERENCE"))))));
        gateway.prepare(run.id(), List.of(toolCall("load-main", "read_skill", "{\"skillVersionId\":\"" + skill.versionId() + "\"}"), toolCall("read-before-media", "read_skill_resource",
                        "{\"skillVersionId\":\"" + skill.versionId() + "\",\"path\":\"references/style-guide.md\",\"offset\":0,\"limit\":21}"),
                toolCall("propose-style-image", "propose_media_generation", proposal),
                new AssistantMessage("The approved style image is ready for user inspection.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), scenario.project().id(), run.id()).status()).isEqualTo(AgentRun.Status.WAITING_TASKS);
        var pending = approvals.list(owner.userId(), scenario.project().id(), run.id()).getFirst();
        assertThat(pending.taskIds()).isEmpty();
        assertThat(tasks.listByRun(owner.userId(), scenario.project().id(), run.id()))
                .allSatisfy(task -> assertThat(task.kind()).isEqualTo(Task.Kind.AGENT_TURN));
        var target = pending.outputs().getFirst();
        JsonNode source = target.preview().path("creativeSkill");
        assertThat(source.path("skills").path(0).path("skillMd").asText()).isEqualTo(skill.body());
        assertThat(source.path("skills").path(0).path("resources").path(0).path("content").asText()).isEqualTo(skill.resourceText());
        assertThat(source.path("skills").path(0).path("resourceReads").path(0).path("path").asText()).isEqualTo("references/style-guide.md");
        assertThat(source.path("skills").path(0).path("resourceReads").path(0).path("endOffset").asInt()).isEqualTo(21);
        assertThat(source.path("skills").path(0).path("assets").path(0).path("artifactVersionId").asText()).isEqualTo(styleVersion);
        var ordinaryPreflight = directMedia.preflight(owner.userId(), scenario.project().id(), target.artifactId(), target.canvasItemId(), target.draftVersion());
        var approvedPreflight = directMedia.preflightApproved(owner.userId(), scenario.project().id(), target.artifactId(), target.canvasItemId(), target.draftVersion(), source);
        assertThat(approvedPreflight.frozenInputHash()).isNotEqualTo(ordinaryPreflight.frozenInputHash());
        assertThat(ordinaryPreflight.safeSummary().has("creativeSkill")).isFalse();

        var accepted = approvals.decide(owner.userId(), scenario.project().id(), run.id(), pending.id(), pending.version(),
                AgentMediaApprovalService.Decision.APPROVE, "approve-fixed-style");
        assertThat(accepted.taskIds()).hasSize(1);
        Task media = tasks.get(owner.userId(), scenario.project().id(), accepted.taskIds().getFirst());
        assertSameJson(media.input().path("mediaInput").path("creativeSkill"), source);
        assertThat(media.input().path("mediaInput").path("prompt").asText()).isEqualTo("Warm synthetic subject");
        assertThat(mediaWorker.submitOnce("skill-run-test-media")).isEqualTo(1);
        Task completed = tasks.get(owner.userId(), scenario.project().id(), media.id());
        assertThat(completed.status()).isEqualTo(Task.Status.SUCCEEDED);
        UUID resultVersion = UUID.fromString(completed.output().path("artifactVersionId").asText());
        var result = artifacts.requireVersion(owner.userId(), scenario.project().id(), target.artifactId(), resultVersion);
        assertSameJson(result.frozenInput().path("creativeSkill"), source);
        outcomes.reconcile(owner.userId(), scenario.project().id(), run.id(), pending.id());
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(runs.get(owner.userId(), scenario.project().id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);

        var currentDraft = drafts.get(owner.userId(), scenario.project().id(), target.canvasItemId());
        Task direct = directMedia.run(owner.userId(), scenario.project().id(), target.artifactId(), target.canvasItemId(),
                currentDraft.version(), "manual-style-rerun");
        assertThat(direct.runId()).isNull();
        assertThat(direct.input().path("mediaInput").has("creativeSkill")).isFalse();
        assertSameJson(artifacts.requireVersion(owner.userId(), scenario.project().id(), target.artifactId(), resultVersion)
                .frozenInput().path("creativeSkill"), source);
        directMedia.cancelQueued(owner.userId(), scenario.project().id(), direct.id());
    }

    private AssistantMessage toolCall(String id, String name, String arguments) {
        return AssistantMessage.builder().content("").toolCalls(List.of(
                new AssistantMessage.ToolCall(id, "function", name, arguments))).build();
    }

    @Test void requiredProviderReferencesCannotBeDroppedAndGuideMediaCannotBecomeProviderInput() throws Exception {
        for (SkillContent.Usage usage : List.of(SkillContent.Usage.PROVIDER_REFERENCE, SkillContent.Usage.GUIDE)) {
            var scenario = scenario(true);
            var skill = publishedSkill(scenario, true, usage);
            skillRuns.install(owner.userId(), scenario.project().id(), scenario.agent().id(), skill.skillId(), skill.versionId(), "guard-install-" + usage);
            assertThat(skillRuns.processNext()).isTrue();
            var binding = skillRuns.saveBinding(owner.userId(), scenario.project().id(), scenario.agent().id(), scenario.agent().version(), List.of(new SkillRunService.VersionRef(skill.skillId(), skill.versionId())), "guard-binding-" + usage);
            AgentRun run = createRun(scenario, binding.agentVersion(), "guard-run-" + usage,
                    new SkillRunService.Selection(SkillRunService.SelectionMode.VERSIONS, List.of(new SkillRunService.Choice(skill.skillId(), skill.versionId(), List.of(new SkillRunService.Input("subject-image", scenario.subject().resourceDefaultVersion().id())))))).run();
            gateway.prepare(run.id(), List.of(toolCall("guard-read", "read_skill",
                    "{\"skillVersionId\":\"" + skill.versionId() + "\"}")));
            assertThat(worker.runOnce(WORKER)).isEqualTo(1);
            AgentRun active = runs.get(owner.userId(), scenario.project().id(), run.id());
            String styleVersion = active.contextSnapshot().path("creativeSkills").path(0).path("assets").path(0).path("artifactVersionId").asText();
            List<Map<String, Object>> mediaInputs = usage == SkillContent.Usage.GUIDE
                    ? List.of(Map.of("versionId", scenario.subject().resourceDefaultVersion().id(), "role", "REFERENCE"),
                            Map.of("versionId", styleVersion, "role", "REFERENCE"))
                    : List.of(Map.of("versionId", scenario.subject().resourceDefaultVersion().id(), "role", "REFERENCE"));
            String invalidProposal = mapper.writeValueAsString(Map.of("outputs", List.of(Map.of(
                    "kind", "IMAGE", "title", "Invalid references", "prompt", "Warm subject", "parameters", Map.of(), "mediaInputs", mediaInputs))));
            assertProblem("TOOL_ARGUMENT_INVALID", () -> approvals.propose(
                    new TrustedToolContext(owner.userId(), scenario.project().id(), run.id()), active,
                    UUID.randomUUID(), active.nextStepIndex(), "invalid-reference-" + usage, invalidProposal));
            assertThat(approvals.list(owner.userId(), scenario.project().id(), run.id())).isEmpty();
            assertThat(tasks.listByRun(owner.userId(), scenario.project().id(), run.id()))
                    .allSatisfy(task -> assertThat(task.kind()).isEqualTo(Task.Kind.AGENT_TURN));
            assertThat(mediaWorker.submitOnce("no-invalid-skill-generation")).isZero();
            runs.cancel(owner.userId(), scenario.project().id(), run.id());
        }
    }

    @Test void historicalPolicyOmitsNewToolAndRejectsItsSavedCallDuringRepair() {
        var scenario = scenario(false);
        AgentRun run = createRun(scenario, 0, "historical-policy-run",
                new SkillRunService.Selection(SkillRunService.SelectionMode.NONE, List.of())).run();
        // A synthetic pre-upgrade record is a fixture; all behavioral assertions use public interfaces.
        ObjectNode legacy = (ObjectNode) run.policySnapshot().deepCopy();
        legacy.put("schemaVersion", 2).put("systemPromptVersion", 3);
        legacy.remove("toolPolicyVersion"); legacy.remove("allowedTools");
        jdbc.sql("update agent_run set policy_snapshot_json=cast(:policy as jsonb) where id=:run")
                .param("policy", mapper.writeValueAsString(legacy)).param("run", run.id()).update();
        gateway.prepare(run.id(), List.of(toolCall("forged-historical-read", "read_skill_resource",
                        "{\"skillVersionId\":\"" + UUID.randomUUID() + "\",\"path\":\"references/style-guide.md\",\"offset\":0,\"limit\":21}"),
                new AssistantMessage("The historical policy continues without the unavailable tool.")));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(gateway.lastTools).noneSatisfy(tool -> assertThat(tool.getToolDefinition().name()).isEqualTo("read_skill_resource"));
        assertProblem("TOOL_ARGUMENT_INVALID", () -> toolExecutions.execute(
                new TrustedToolContext(owner.userId(), scenario.project().id(), run.id()), 0, "forged-historical-read"));
        assertThat(worker.runOnce(WORKER)).isEqualTo(1);
        assertThat(gateway.lastTools).noneSatisfy(tool -> assertThat(tool.getToolDefinition().name()).isEqualTo("read_skill_resource"));
        AgentRun completed = runs.get(owner.userId(), scenario.project().id(), run.id());
        assertThat(completed.status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(completed.policySnapshot().path("systemPromptVersion").asInt()).isEqualTo(3);
        assertThat(completed.policySnapshot().has("allowedTools")).isFalse();
        assertThat(completed.contextSnapshot().has("creativeSkill")).isFalse();
    }

    private void assertProblem(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiProblemException.class,
                problem -> assertThat(problem.code()).isEqualTo(code));
    }

    private void assertSameJson(JsonNode actual, JsonNode expected) {
        // JSONB does not retain Java numeric-node widths or object insertion order.
        assertThat(mapper.readTree(actual.toString())).isEqualTo(mapper.readTree(expected.toString()));
    }

    private record Scenario(Project project, AgentInstance agent, ArtifactService.ArtifactView subject) {}

    private record PublishedSkill(UUID skillId, UUID versionId, String body, String resourceText) {}

    private PublishedSkill publishedSkill(Scenario scenario, boolean requiredSubject) throws Exception {
        return publishedSkill(scenario, requiredSubject, SkillContent.Usage.PROVIDER_REFERENCE);
    }

    private PublishedSkill publishedSkill(Scenario scenario, boolean requiredSubject, SkillContent.Usage usage) throws Exception {
        return publishedSkill(scenario, requiredSubject, usage, true);
    }

    private PublishedSkill publishedSkill(Scenario scenario, boolean requiredSubject, SkillContent.Usage usage,
            boolean fixedAsset) throws Exception {
        List<SkillContent.DraftAsset> references = List.of();
        if (fixedAsset) {
            byte[] image = Files.readAllBytes(assets.get(owner.userId(), scenario.project().id(),
                UUID.fromString(scenario.subject().resourceDefaultVersion().content().path("assetId").asText())).path());
            var saved = library.upload(owner.userId(), "Synthetic style reference", LibraryEntry.Category.OTHER,
                    dev.agenvas.asset.domain.Asset.MediaKind.IMAGE, "run-reference-" + UUID.randomUUID(),
                    new MockMultipartFile("file", "style.png", "image/png", image));
            assertThat(library.processNext()).isTrue();
            UUID entryId = UUID.fromString(library.command(owner.userId(), saved.id()).result().path("entryId").asText());
            references = List.of(new SkillContent.DraftAsset("style-reference", entryId, 0L, null, null, null,
                    usage, true, "Warm palette reference"));
        }
        var skill = skills.create(owner.userId(), "Warm illustration", "Use a fixed style reference");
        String body = """
                ---
                name: warm-illustration
                description: Prepare a warm image from the user's subject and the fixed style.
                ---
                # Warm illustration
                Read references/style-guide.md. Preserve the subject and use style-reference.
                User approval is required before media generation.
                """;
        String resource = "Original warm palette; keep the subject identity.\n".repeat(20);
        var content = new SkillContent.DraftContent(1, body, List.of(Artifact.Kind.IMAGE),
                requiredSubject ? List.of(new SkillContent.InputSlot("subject-image", Artifact.Kind.IMAGE, true)) : List.of(),
                List.of(new SkillContent.Resource("references/style-guide.md", resource)), references);
        var draft = skills.saveDraft(owner.userId(), skill.id(), 0, content);
        var operation = skills.publish(owner.userId(), skill.id(), draft.version(), "run-publish-" + UUID.randomUUID());
        assertThat(skills.processNext()).isTrue();
        var complete = skills.getOperation(owner.userId(), operation.id());
        assertThat(complete.status()).isEqualTo(SkillContent.OperationStatus.SUCCEEDED);
        return new PublishedSkill(skill.id(), complete.resultVersionId(), body, resource);
    }

    private Scenario scenario(boolean bindSubject) {
        Project project = projects.create(owner.userId(), "Skill image project", Project.AspectRatio.LANDSCAPE_16_9);
        UUID assetId = ImageAssetFixture.archive(assets, owner.userId(), project.id());
        var subject = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE, "Synthetic subject",
                mapper.valueToTree(Map.of("sourceType", "UPLOAD", "assetId", assetId)));
        if (bindSubject) {
            AgentInstance bound = AgentImageInputFixture.connect(agents, canvas, connections, owner.userId(), project.id(),
                    subject.artifact().id(), subject.resourceDefaultVersion().id(), "Skill Creator", "Use the reviewed style");
            return new Scenario(project, bound, subject);
        }
        AgentInstance agent = agents.create(owner.userId(), project.id(), "Skill Creator", "Use the reviewed style", List.of());
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceAgent(UUID.randomUUID(), agent.id(),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"), new BigDecimal("420"), 0, null, false)));
        return new Scenario(project, agent, subject);
    }

    @TestConfiguration static class FakeConfig {
        @Bean @Primary FakeGateway skillTestGateway() { return new FakeGateway(); }
    }

    static class FakeGateway implements ChatGateway {
        final AtomicInteger calls = new AtomicInteger();
        final Map<UUID, List<AssistantMessage>> responses = new ConcurrentHashMap<>();
        final Map<UUID, AtomicInteger> steps = new ConcurrentHashMap<>();
        volatile List<Message> lastMessages = List.of();
        volatile List<ToolCallback> lastTools = List.of();
        void prepare(UUID runId, List<AssistantMessage> planned) {
            responses.put(runId, planned); steps.put(runId, new AtomicInteger());
        }
        @Override public String configSource() { return "synthetic-skill-model"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }
        @Override public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> toolContext) {
            calls.incrementAndGet();
            lastMessages = List.copyOf(messages); lastTools = List.copyOf(tools);
            UUID runId = UUID.fromString((String) toolContext.get("runId"));
            var planned = responses.get(runId);
            assertThat(planned).as("each fake-model exchange belongs to a configured test Run").isNotNull();
            int index = steps.get(runId).getAndIncrement();
            assertThat(index).isLessThan(planned.size());
            return new Exchange(1, new ChatResponse(List.of(new Generation(planned.get(index)))));
        }
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("agenvas-skill-run-test-", PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    @AfterAll static void cleanupThisTestsFiles() throws IOException {
        try (var paths = Files.walk(STORAGE_ROOT)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
