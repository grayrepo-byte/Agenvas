package dev.agenvas.llm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.artifact.domain.ArtifactVersion;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.canvas.application.CanvasService;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.llm.application.AgentTurnWorker;
import dev.agenvas.llm.application.ChatGateway;
import dev.agenvas.llm.application.InitialModelContextService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.skill.application.SkillRunService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic gateway and screenplay: proves persistence and tool flow, not a real model's directing quality. */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = {AgenvasApplication.class, DirectorStoryboardWorkflowPostgresIT.FakeConfig.class})
class DirectorStoryboardWorkflowPostgresIT {
    private static final String SOURCE_TITLE = "合成验收剧本 · 钥匙";
    private static final String SOURCE_TEXT = """
            # EP001 钥匙（合成验收材料）
            ## EP001-SC001 内 · 玄关 · 夜
            小林右手握着钥匙，站在门内。阿青站在门外伸出左手。
            阿青：把钥匙还给我。
            ## EP001-SC002 外 · 门口 · 夜
            小林把钥匙交到阿青左手。阿青收回左手，看着钥匙。
            [SFX] 钥匙轻响。
            """;
    private static final String BREAKDOWN = "EP001 剧本拆解";
    private static final String VISUAL = "EP001 视觉设定";
    private static final String STORYBOARD = "EP001 分镜";
    private static final String KEYFRAMES = "EP001 关键帧提示词";
    private static final String MOTION = "EP001 视频提示词";
    private static final String REVIEW = "EP001 分镜审校";
    private static final String PROGRESS = "EP001 分镜制作进度";

    @org.junit.jupiter.api.io.TempDir static java.nio.file.Path storage;
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("agenvas.storage.root", () -> storage.toString());
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired IdentityService identities;
    @Autowired ProjectService projects;
    @Autowired AgentInstanceService agents;
    @Autowired CanvasService canvas;
    @Autowired AgentRunService runs;
    @Autowired ArtifactService artifacts;
    @Autowired InitialModelContextService initial;
    @Autowired AgentTurnWorker worker;
    @Autowired FakeGateway gateway;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void defaultDirectorWithNoSkillsReadsTheScriptSavesTheWorkflowAndRevisesImmutableCardsWithoutGenerating() {
        var owner = identities.setup("storyboard-workflow-admin", "storyboard-workflow-password-123");
        var project = projects.create(owner.userId(), "Synthetic storyboard workflow", Project.AspectRatio.PORTRAIT_9_16);
        var source = artifacts.create(owner.userId(), project.id(), Artifact.Kind.TEXT, SOURCE_TITLE,
                mapper.createObjectNode().put("format", "MARKDOWN").put("text", SOURCE_TEXT));
        UUID sourceItem = UUID.randomUUID();
        canvas.apply(owner.userId(), project.id(), List.of(new CanvasService.PlaceArtifact(sourceItem,
                source.artifact().id(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("320"),
                new BigDecimal("420"), 0, null, false)));
        // Use the installation's actual editable default, without substituting test instructions.
        var director = agents.ensureDirector(owner.userId(), project.id());
        assertThat(director.instruction()).contains("剧本拆解", "SHOT-", "连续性");
        var none = new SkillRunService.Selection(SkillRunService.SelectionMode.NONE, List.of());
        var run = runs.create(owner.userId(), project.id(), director.id(),
                "对当前合成剧本完成拆解、视觉设定、分镜、关键帧和视频提示词、审校与进度卡；只保存文字，修订镜头轴线表述。",
                "synthetic-storyboard-workflow", director.version(), List.of(sourceItem),
                null, null, null, null, null, none).run();
        assertThat(run.contextSnapshot().path("creativeSkills")).isEmpty();
        var messages = initial.assemble(owner.userId(), project.id(), run.id());
        var systems = messages.stream().filter(SystemMessage.class::isInstance).map(Message::getText).toList();
        assertThat(systems).hasSize(2);
        assertThat(systems.getFirst()).contains("read_canvas").doesNotContain("剧本拆解", "SHOT-", "连续性");
        assertThat(systems.getLast()).isEqualTo(director.instruction()).contains("剧本拆解", "SHOT-", "连续性");
        assertThat(messages).noneSatisfy(message -> assertThat(message.getText()).contains(SOURCE_TEXT));

        for (int turn = 0; turn < 13; turn++) assertThat(worker.runOnce("storyboard-workflow-worker"))
                .as("synthetic workflow turn %s", turn + 1).isEqualTo(1);

        assertThat(runs.get(owner.userId(), project.id(), run.id()).status()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(gateway.calls.get()).isEqualTo(13);
        assertThat(gateway.saved.keySet()).containsExactly(BREAKDOWN, VISUAL, STORYBOARD, KEYFRAMES, MOTION, REVIEW, PROGRESS);
        var entries = canvas.list(owner.userId(), project.id());
        assertThat(entries).hasSize(8);
        for (var saved : gateway.saved.values()) {
            var view = artifacts.get(owner.userId(), project.id(), saved.artifactId());
            assertThat(view.artifact().kind()).isEqualTo(Artifact.Kind.TEXT);
            assertThat(view.resourceDefaultVersion().id()).isEqualTo(saved.versionId());
            assertThat(view.resourceDefaultVersion().createdByKind()).isEqualTo(ArtifactVersion.CreatedByKind.AGENT);
            assertThat(view.resourceDefaultVersion().runId()).isEqualTo(run.id());
            assertThat(entries).anySatisfy(entry -> {
                assertThat(entry.artifact().artifact().id()).isEqualTo(saved.artifactId());
                assertThat(entry.artifact().resourceDefaultVersion().id()).isEqualTo(saved.versionId());
            });
        }
        var storyboard = gateway.saved.get(STORYBOARD);
        var versions = artifacts.listVersions(owner.userId(), project.id(), storyboard.artifactId());
        assertThat(versions).hasSize(2).anySatisfy(version -> {
            assertThat(version.id()).isEqualTo(gateway.originalStoryboardVersion);
            assertThat(version.content().path("text").asText()).isEqualTo(gateway.originalStoryboardText);
        });
        assertThat(storyboard.text()).contains("SHOT-001", "SHOT-002", "门框内沿—门外台阶", "镜号保持不变");
        assertThat(storyboard.versionId()).isNotEqualTo(gateway.originalStoryboardVersion);
        var progress = gateway.saved.get(PROGRESS);
        assertThat(progress.text()).contains(storyboard.versionId().toString(), "未生成媒体", "未验证真实模型质量");
        assertThat(artifacts.listVersions(owner.userId(), project.id(), progress.artifactId())).hasSize(2);
        assertThat(artifacts.get(owner.userId(), project.id(), source.artifact().id()).resourceDefaultVersion().content().path("text").asText())
                .isEqualTo(SOURCE_TEXT);
        assertThat(artifacts.listVersions(owner.userId(), project.id(), source.artifact().id())).hasSize(1);
        assertThat(jdbc.sql("select count(*) from task where project_id=:project and kind<>'AGENT_TURN'")
                .param("project", project.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from agent_media_approval where project_id=:project")
                .param("project", project.id()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("select count(*) from tool_execution where run_id=:run and tool_name in ('read_skill','propose_media_generation')")
                .param("run", run.id()).query(Integer.class).single()).isZero();
        // PostgreSQL JSONB may deserialize an originally constructed LongNode as an IntNode.
        // Normalize both representations while still comparing the entire frozen snapshot.
        assertThat(mapper.readTree(runs.get(owner.userId(), project.id(), run.id()).contextSnapshot().toString()))
                .isEqualTo(mapper.readTree(run.contextSnapshot().toString()));
    }

    @TestConfiguration
    static class FakeConfig {
        @Bean @Primary FakeGateway fakeGateway(ObjectMapper mapper) { return new FakeGateway(mapper); }
    }

    private record SavedCard(UUID artifactId, UUID versionId, long expectedVersion, String text) {}

    /** Every dependency ID comes from the preceding committed receipt, never a fabricated identifier. */
    static class FakeGateway implements ChatGateway {
        private final ObjectMapper mapper;
        private final AtomicInteger calls = new AtomicInteger();
        private final Map<String, SavedCard> saved = new LinkedHashMap<>();
        private UUID sourceId;
        private UUID sourceVersion;
        private UUID originalStoryboardVersion;
        private String originalStoryboardText;
        private String pendingTitle;
        private String pendingText;

        FakeGateway(ObjectMapper mapper) { this.mapper = mapper; }
        @Override public String configSource() { return "test-synthetic-storyboard"; }
        @Override public int configVersion() { return 1; }
        @Override public Capabilities capabilities() { return new Capabilities(true, false, false); }

        @Override
        public Exchange call(List<Message> messages, List<ToolCallback> tools, Map<String, Object> toolContext) {
            assertThat(tools).extracting(tool -> tool.getToolDefinition().name())
                    .contains("read_canvas", "read_artifacts", "create_text", "revise_artifact");
            int turn = calls.incrementAndGet();
            if (turn >= 4 && turn <= 10 || turn == 12 || turn == 13) captureSaved(messages);
            return switch (turn) {
                case 1 -> tool(turn, "read_canvas", mapper.createObjectNode().put("limit", 40));
                case 2 -> {
                    JsonNode nodes = reply(messages).path("data");
                    assertThat(nodes).hasSize(1);
                    JsonNode node = nodes.get(0);
                    assertThat(node.path("title").asText()).isEqualTo(SOURCE_TITLE);
                    sourceId = UUID.fromString(node.path("artifactId").asText());
                    sourceVersion = UUID.fromString(node.path("selectedVersionId").asText());
                    yield read(turn, sourceVersion);
                }
                case 3 -> {
                    JsonNode source = reply(messages).path("data").get(0);
                    assertThat(source.path("contentTruncated").asBoolean()).isFalse();
                    assertThat(source.path("content").path("text").asText()).isEqualTo(SOURCE_TEXT);
                    assertThat(source.path("textPage").path("offset").asInt()).isZero();
                    assertThat(source.path("textPage").path("nextOffset").isNull()).isTrue();
                    yield create(turn, BREAKDOWN, "# 合成剧本拆解\n来源：" + sourceRef()
                            + "\nEP001-SC001，行2–4，玄关/夜，小林持钥匙，阿青索还；对白：把钥匙还给我。"
                            + "\nEP001-SC002，行5–7，门口/夜，右手→左手交接，归属小林→阿青，SFX：钥匙轻响。"
                            + "\n覆盖：SC001–SC002；未提供人物外貌与真实参考，保持未决。");
                }
                case 4 -> create(turn, VISUAL, "# 合成视觉设定\n来源：" + sourceRef() + "\n拆解：" + ref(BREAKDOWN)
                        + "\nAST-01 小林：门内，初始右手持钥匙；AST-02 阿青：门外，左手接钥匙。"
                        + "\nAST-03 玄关/门口：门框与门外台阶作为地理锚点；AST-04 钥匙：唯一交接道具。"
                        + "\n外貌未指定，真实 IMAGE/AUDIO 未提供；本卡文字条目不代表媒体。");
                case 5 -> create(turn, STORYBOARD, storyboard());
                case 6 -> {
                    originalStoryboardVersion = saved.get(STORYBOARD).versionId();
                    originalStoryboardText = saved.get(STORYBOARD).text();
                    yield create(turn, KEYFRAMES, "# 合成关键帧提示词\n分镜：" + ref(STORYBOARD) + "\n视觉设定：" + ref(VISUAL)
                            + "\nSHOT-001 起始边界：门框一侧的双人中景，小林在门内右手握钥匙，阿青在门外伸左手。"
                            + "\nSHOT-002 起始边界：钥匙仍在小林右手、阿青左手尚未接触的交接前瞬间。"
                            + "\n两条均为静态提示词；真实图片未提供，不能作为 mediaInputs。");
                }
                case 7 -> create(turn, MOTION, "# 合成视频提示词\n分镜：" + ref(STORYBOARD) + "\n关键帧：" + ref(KEYFRAMES)
                        + "\nSHOT-001：阿青伸手说“把钥匙还给我。”，小林看向她；同轴固定机位保留二人距离。"
                        + "\nSHOT-002：小林右手前伸→钥匙落入阿青左手→小林松手→阿青收手，钥匙轻响。"
                        + "\n时长是文本估计，未运行媒体能力；真实图片与目标能力待补，不声称视频可直接提交。");
                case 8 -> create(turn, REVIEW, "# 合成分镜审校\n来源：" + sourceRef() + "\n分镜：" + ref(STORYBOARD)
                        + "\n逐项内容核对：两场动作/逐字对白/SFX已覆盖；右手到左手交接仅发生一次，前后归属连续。"
                        + "\n需修订：轴线锚点措辞明确为门框内沿—门外台阶；镜头职责保持。"
                        + "\n未验证：真实图像、表演、语速和成片质量，本报告只是合成工具流程验收。");
                case 9 -> create(turn, PROGRESS, progress(false));
                case 10 -> read(turn, saved.get(STORYBOARD).versionId());
                case 11 -> {
                    JsonNode current = reply(messages).path("data").get(0);
                    assertThat(current.path("versionId").asText()).isEqualTo(saved.get(STORYBOARD).versionId().toString());
                    yield revise(turn, STORYBOARD, current.path("expectedVersion").asLong(),
                            originalStoryboardText.replace("轴线：门框—台阶", "轴线：门框内沿—门外台阶")
                                    + "\n修订：只明确同一轴线锚点措辞，SHOT-001/SHOT-002 镜号保持不变，职责与起止边界未改。"
                                    + "\n拆并镜时停用前身并创建新镜号；重排不重编号。");
                }
                case 12 -> revise(turn, PROGRESS, saved.get(PROGRESS).expectedVersion(), progress(true));
                case 13 -> response(new AssistantMessage("合成验收的拆解、视觉设定、分镜、提示词、审校和进度已保存；未生成媒体，未验证真实模型质量。"));
                default -> throw new IllegalStateException("Unexpected synthetic storyboard turn " + turn);
            };
        }

        private String storyboard() {
            return "# 合成分镜\n来源：" + sourceRef() + "\n拆解：" + ref(BREAKDOWN) + "\n视觉设定：" + ref(VISUAL)
                    + "\n## SHOT-001\n来源：EP001-SC001 行2–4；职责：索要钥匙；中景/平视/固定，轴线：门框—台阶。"
                    + "\n人物屏幕左/右：小林/阿青；起始小林右手握钥匙，结束阿青左手伸出、小林仍持有。"
                    + "\n对白原文：把钥匙还给我。声音：对白与伸手并行；文本估计3秒，非实测。"
                    + "\n## SHOT-002\n来源：EP001-SC002 行5–7；职责：交接完成；手部近景，沿同轴工作侧。"
                    + "\n动作：小林右手交到阿青左手；起始同前镜结束，结束阿青左手持钥匙收回。"
                    + "\nSFX：钥匙轻响；文本估计5秒含交接后反应，非实测。转场：连续切。"
                    + "\n连续性：钥匙归属只变化一次，左右手与屏幕方向明确；总时长估计8秒。"
                    + "\n覆盖：SC001–SC002全部已提供内容；真实 IMAGE/AUDIO 未提供，引用计划不是媒体。";
        }

        private String progress(boolean revised) {
            var text = new StringBuilder("# 合成分镜制作进度\n实际覆盖：EP001-SC001–SC002\n来源：").append(sourceRef());
            for (var entry : saved.entrySet()) if (!entry.getKey().equals(PROGRESS))
                text.append('\n').append(entry.getKey()).append("：").append(ref(entry.getKey()));
            text.append("\n轴线措辞修订：").append(revised ? "已保存新分镜版本；提示词的镜头职责与边界未变，旧引用保留来源谱系。" : "待处理。");
            return text.append("\n素材缺项：人物/场景/道具参考图与目标媒体能力；只完成文字设计。")
                    .append("\n未生成媒体；未验证真实模型质量、真实表演与成片连续性。下一步按用户要求准备素材。").toString();
        }

        private String sourceRef() { return sourceId + " / " + sourceVersion + " / 行1–7"; }
        private String ref(String title) {
            SavedCard card = saved.get(title);
            return card.artifactId() + " / " + card.versionId();
        }

        private Exchange read(int turn, UUID version) {
            ObjectNode args = mapper.createObjectNode().put("textOffset", 0).put("textLimit", 4000);
            args.putArray("versionIds").add(version.toString());
            return tool(turn, "read_artifacts", args);
        }

        private Exchange create(int turn, String title, String text) {
            pendingTitle = title;
            pendingText = text;
            return tool(turn, "create_text", mapper.createObjectNode().put("title", title).put("format", "MARKDOWN").put("text", text));
        }

        private Exchange revise(int turn, String title, long expected, String text) {
            pendingTitle = title;
            pendingText = text;
            ObjectNode args = mapper.createObjectNode().put("artifactId", saved.get(title).artifactId().toString()).put("expectedVersion", expected);
            args.putObject("content").put("format", "MARKDOWN").put("text", text);
            return tool(turn, "revise_artifact", args);
        }

        private void captureSaved(List<Message> messages) {
            JsonNode receipt = reply(messages);
            assertThat(receipt.path("status").asText()).isEqualTo("SUCCEEDED");
            UUID artifact = receipt.path("createdIds").isEmpty() ? saved.get(pendingTitle).artifactId()
                    : UUID.fromString(receipt.path("createdIds").get(0).asText());
            UUID version = UUID.fromString(receipt.path("affectedVersions").path(artifact.toString()).asText());
            long expected = receipt.path("artifactVersions").path(artifact.toString()).asLong();
            saved.put(pendingTitle, new SavedCard(artifact, version, expected, pendingText));
        }

        private JsonNode reply(List<Message> messages) {
            var reply = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .reduce((earlier, later) -> later).orElseThrow();
            return mapper.readTree(reply.getResponses().getLast().responseData());
        }

        private Exchange tool(int turn, String name, ObjectNode args) {
            return response(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                    "storyboard-call-" + turn, "function", name, args.toString()))).build());
        }

        private Exchange response(AssistantMessage message) {
            return new Exchange(1, new ChatResponse(List.of(new Generation(message))));
        }
    }
}
