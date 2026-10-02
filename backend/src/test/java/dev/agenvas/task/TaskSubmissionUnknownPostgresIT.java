package dev.agenvas.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.agenvas.agent.application.AgentInstanceService;
import dev.agenvas.agent.domain.AgentInstance;
import dev.agenvas.artifact.application.ArtifactService;
import dev.agenvas.artifact.domain.Artifact;
import dev.agenvas.bootstrap.AgenvasApplication;
import dev.agenvas.identity.application.AdminPrincipal;
import dev.agenvas.identity.application.IdentityService;
import dev.agenvas.project.application.ProjectService;
import dev.agenvas.project.domain.Project;
import dev.agenvas.run.application.AgentRunService;
import dev.agenvas.run.domain.AgentRun;
import dev.agenvas.shared.error.ProviderFailureCodes;
import dev.agenvas.task.application.TaskService;
import dev.agenvas.task.domain.Task;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real-PostgreSQL evidence for the immediate classification of an uncertain synchronous submit.
 *
 * <p>本地一旦拿到「无法确认外部是否完成」的确定结论就有两种落地，必须各就各位：租约仍在时
 * 当场按原因码转 UNKNOWN，并让等待中的 Run 转 BLOCKED（人工重试的前置条件）；租约已失效时
 * 放弃写回而不抛异常，交回租约扫描兜底。抛异常会让调度器只记一条错误日志，任务照样空等。
 *
 * <p>本用例用带媒体卡片的 Run 内生成任务驱动：Run 阻断只对这类任务成立（{@code runId} 非空且
 * 类别为图片/视频生成），而项目里的直接任务没有 Run，不需要承载人工重试入口。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = AgenvasApplication.class,
        properties = "agenvas.identity.bootstrap-secret=submission-unknown-secret")
class TaskSubmissionUnknownPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private dev.agenvas.task.application.TaskRepository taskRepository;

    @Autowired private IdentityService identities;
    @Autowired private ProjectService projects;
    @Autowired private ArtifactService artifacts;
    @Autowired private AgentInstanceService agents;
    @Autowired private AgentRunService runs;
    @Autowired private TaskService tasks;
    @Autowired private JdbcClient jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void immediateUnknownKeepsItsReasonAndYieldsToRecoveryWhenTheLeaseIsGone() {
        AdminPrincipal owner = identities.setup(
                "submission-unknown-secret", "unknown-admin", "unknown-password-123");
        Project project = projects.create(owner.userId(), "Uncertain submission project",
                Project.AspectRatio.LANDSCAPE_16_9);

        // 直连媒体任务固定一张已存在的空图片卡片；提交与判定路径与用户直连生成一致。
        var card = artifacts.create(owner.userId(), project.id(), Artifact.Kind.IMAGE,
                "Frame card", null);
        var agent = agents.create(owner.userId(), project.id(), "Creator", "Create", List.of());
        AgentRun run = runs.create(owner.userId(), project.id(), agent.id(),
                "生成一张图片", "uncertain-submission-run").run();
        runs.transition(owner.userId(), project.id(), run.id(), run.version(),
                AgentRun.Status.RUNNING);
        ObjectNode input = mapper.createObjectNode();
        input.put("prompt", "Cinematic coffee pour");
        Task media = dev.agenvas.task.application.TaskMediaFixture.create(tasks, taskRepository, artifacts, owner.userId(), project.id(), run.id(), "frame-1", Task.Kind.IMAGE_GENERATION, input, card.artifact().id());
        // Run 自带的 AGENT_TURN 之外，这次提交的媒体任务就是 Run 里唯一的图片生成任务。
        assertThat(tasks.listByRun(owner.userId(), project.id(), run.id()))
                .filteredOn(task -> task.kind() == Task.Kind.IMAGE_GENERATION)
                .extracting(Task::id).containsExactly(media.id());
        AgentRun running = runs.get(owner.userId(), project.id(), run.id());
        runs.transition(owner.userId(), project.id(), run.id(), running.version(),
                AgentRun.Status.WAITING_TASKS);

        Task lease = claimMediaTask("worker-immediate");
        tasks.beginSubmission(lease, "worker-immediate");
        assertThat(tasks.markSubmissionUnknown(lease, "worker-immediate",
                ProviderFailureCodes.CALL_TIMEOUT)).isTrue();

        Task resolved = tasks.get(owner.userId(), project.id(), lease.id());
        assertThat(resolved.status()).isEqualTo(Task.Status.UNKNOWN);
        assertThat(resolved.errorCode()).isEqualTo(ProviderFailureCodes.CALL_TIMEOUT);
        // ck_task_lease_pair 要求两者同时为空；UNKNOWN 不是终态，completed_at 必须保持为空。
        assertThat(resolved.leaseOwner()).isNull();
        assertThat(resolved.leaseUntil()).isNull();
        assertThat(resolved.completedAt()).isNull();
        assertThat(jdbc.sql("select status from provider_attempt where task_id = :id")
                .param("id", lease.id()).query(String.class).single()).isEqualTo("UNKNOWN");
        // 人工重试要求 Run 处于 BLOCKED/WAITING_TASKS，所以这一步必须发生。
        assertThat(runs.get(owner.userId(), project.id(), run.id()).status())
                .isEqualTo(AgentRun.Status.BLOCKED);
        // 当场判定后兜底扫描无事可做。
        assertThat(tasks.recoverExpiredSubmissions(16)).isZero();
        // 任务已不在 SUBMITTING，同一租约不可再次改写。
        assertThat(tasks.markSubmissionUnknown(lease, "worker-immediate",
                ProviderFailureCodes.CALL_TIMEOUT)).isFalse();
        assertThat(tasks.get(owner.userId(), project.id(), lease.id()).errorCode())
                .isEqualTo(ProviderFailureCodes.CALL_TIMEOUT);
        // UNKNOWN 不是可认领状态：当场判定后不会再多放行一次外部调用。
        assertThat(tasks.claimDue("third-worker", 16)).isEmpty();

        // 第二阶段验证租约失效时的回落。Run 一旦不在活动状态，其任务就不再可认领，
        // 且项目活动槽位不因 BLOCKED 释放，因此换一个全新项目只验证 CAS 回落本身。
        Project second = projects.create(owner.userId(), "Expired lease project",
                Project.AspectRatio.LANDSCAPE_16_9);
        AgentInstance secondAgent = agents.create(owner.userId(), second.id(), "Creator",
                "Create", List.of());
        AgentRun secondRun = runs.create(owner.userId(), second.id(), secondAgent.id(),
                "Make an image", "expired-lease-run").run();
        runs.transition(owner.userId(), second.id(), secondRun.id(), secondRun.version(),
                AgentRun.Status.RUNNING);
        Task expired = create(owner.userId(), second.id(), secondRun.id(), "expired");
        Task expiredLease = tasks.claimDue("worker-expired", 16).getFirst();
        tasks.beginSubmission(expiredLease, "worker-expired");
        jdbc.sql("update task set lease_until = now() - interval '1 second' where id = :id")
                .param("id", expired.id()).update();
        // 租约已失效时放弃写回而不是抛异常，否则调度器只会记一条错误日志。
        assertThat(tasks.markSubmissionUnknown(expiredLease, "worker-expired",
                ProviderFailureCodes.CALL_TIMEOUT)).isFalse();
        assertThat(tasks.get(owner.userId(), second.id(), expired.id()).status())
                .isEqualTo(Task.Status.SUBMITTING);
        assertThat(tasks.recoverExpiredSubmissions(16)).isEqualTo(1);
        // 退化为兜底的笼统码是这条路径的已知代价：租约过期后已无法证明具体原因。
        assertThat(tasks.get(owner.userId(), second.id(), expired.id()).errorCode())
                .isEqualTo(ProviderFailureCodes.SUBMISSION_UNKNOWN);
    }

    /** 认领本次要提交的图片生成任务；同一批里不应混入别的可认领任务。 */
    private Task claimMediaTask(String workerId) {
        return tasks.claimDue(workerId, 16).stream()
                .filter(task -> task.kind() == Task.Kind.IMAGE_GENERATION)
                .findFirst().orElseThrow();
    }

    private Task create(UUID ownerId, UUID projectId, UUID runId, String stepKey) {
        return tasks.create(ownerId, projectId, runId, stepKey,
                Task.Kind.IMAGE_GENERATION,
                mapper.readTree("{\"step\":\"" + stepKey + "\"}"), 1);
    }
}
