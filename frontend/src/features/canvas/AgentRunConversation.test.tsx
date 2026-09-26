import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { AgentRun, RunAction, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentRunConversation } from "./AgentRunConversation";

const PROJECT_ID = "project-1";
const RUN_ID = "run-1";
const RUN_URL = `/api/v1/projects/${PROJECT_ID}/runs/${RUN_ID}`;
const NOW = "2026-09-26T00:00:00Z";
const PLAN_HASH = "a".repeat(64);
const USER_INSTRUCTION = "请规划三个镜头，并等待我确认图片计划。";

function task(overrides: Partial<Task>): Task {
  return {
    id: "task-1", projectId: PROJECT_ID, runId: RUN_ID, stepKey: "agent-turn-1",
    kind: "AGENT_TURN", status: "SUCCEEDED", cancelRequested: false,
    input: {}, output: {}, attemptNo: 1, nextActionAt: NOW, version: 1,
    createdAt: NOW, updatedAt: NOW, completedAt: NOW, ...overrides,
  };
}

function action(overrides: Partial<RunAction>): RunAction {
  return { id: "action-1", stepIndex: 0, toolName: "create_artifact", status: "SUCCEEDED",
    summary: "已创建第一个镜头的文字说明", completedAt: NOW, ...overrides };
}

function mockConversation(tasks: Task[], actions: RunAction[] = []) {
  server.use(
    http.get(`${RUN_URL}/tasks`, () => HttpResponse.json(tasks)),
    http.get(`${RUN_URL}/actions`, () => HttpResponse.json(actions)),
    http.get(`${RUN_URL}/plans`, () => HttpResponse.json([])),
  );
}

function mountConversation(status: AgentRun["status"] = "SUCCEEDED", active = false) {
  const client = createQueryClient();
  // Deterministic failures belong to each test; do not add query retries to test timing.
  client.setDefaultOptions({ queries: { retry: false } });
  render(<QueryClientProvider client={client}>
    <AgentRunConversation projectId={PROJECT_ID} active={active}
      run={{ id: RUN_ID, status, instruction: USER_INSTRUCTION, createdAt: NOW }} />
  </QueryClientProvider>);
}

function taskSummary(label: string) {
  const summary = screen.getByText(label).closest("summary");
  if (!summary) throw new Error(`Missing task summary: ${label}`);
  return within(summary);
}

describe("AgentRunConversation", () => {
  it("renders only committed public assistant text and never raw task input or output metadata", async () => {
    mockConversation([
      task({ id: "later-turn", output: { stepIndex: 2, assistantText: "镜头草稿已保存。",
        rawResponse: "PRIVATE_RAW_RESPONSE", metadata: { secret: "PRIVATE_OUTPUT_METADATA" } } }),
      task({ id: "first-turn", input: { privatePrompt: "PRIVATE_TASK_INPUT" },
        output: { stepIndex: 0, assistantText: "我会先整理镜头说明。", reasoning: "PRIVATE_REASONING" } }),
      task({ id: "uncommitted-turn", status: "RUNNING",
        output: { stepIndex: 3, assistantText: "UNCOMMITTED_ASSISTANT_TEXT" } }),
    ]);
    mountConversation();

    expect(await screen.findByText("镜头草稿已保存。")).toBeInTheDocument();
    const conversation = screen.getByRole("region", { name: "任务对话" });
    expect(within(conversation).getByRole("article", { name: "你" })).toHaveTextContent(USER_INSTRUCTION);
    const replies = within(conversation).getAllByRole("article", { name: "Agent" });
    expect(replies).toHaveLength(2);
    expect(replies[0]).toHaveTextContent("我会先整理镜头说明。");
    expect(replies[1]).toHaveTextContent("镜头草稿已保存。");
    for (const hidden of ["PRIVATE_TASK_INPUT", "PRIVATE_RAW_RESPONSE", "PRIVATE_OUTPUT_METADATA",
      "PRIVATE_REASONING", "UNCOMMITTED_ASSISTANT_TEXT", "assistantText", "stepIndex"]) {
      expect(conversation).not.toHaveTextContent(hidden);
    }
  });

  it("does not invent an assistant message when committed turns contain no public reply", async () => {
    mockConversation([
      task({ id: "empty-turn", output: { stepIndex: 0, assistantText: "" } }),
      task({ id: "whitespace-turn", output: { stepIndex: 1, assistantText: " \n " } }),
      task({ id: "metadata-only-turn", output: { stepIndex: 2, tokenCount: 18 } }),
    ]);
    mountConversation();

    await waitFor(() => expect(screen.queryByRole("status", { name: "正在读取任务消息…" })).not.toBeInTheDocument());
    expect(screen.getByRole("article", { name: "你" })).toHaveTextContent(USER_INSTRUCTION);
    expect(screen.queryByRole("article", { name: "Agent" })).not.toBeInTheDocument();
    expect(screen.getByText("任务完成")).toBeInTheDocument();
  });

  it("shows committed action summaries while an approval proposal remains pending", async () => {
    const createdSummary = "已创建三份镜头说明";
    const proposalSummary = "已提出三张关键帧的图片计划";
    mockConversation([], [
      action({ summary: createdSummary }),
      action({ id: "approval-action", stepIndex: 1, toolName: "propose_media_plan",
        status: "WAITING_APPROVAL", summary: proposalSummary }),
    ]);
    server.use(http.get(`${RUN_URL}/actions`, () => HttpResponse.json([
      { ...action({ summary: createdSummary }), arguments: { private: "PRIVATE_TOOL_ARGUMENT" },
        result: { raw: "PRIVATE_TOOL_RESULT" } },
      action({ id: "approval-action", stepIndex: 1, toolName: "propose_media_plan",
        status: "WAITING_APPROVAL", summary: proposalSummary }),
    ])));
    mountConversation();

    expect(await screen.findByText(proposalSummary)).toBeInTheDocument();
    expect(taskSummary(createdSummary).getByText("已完成")).toBeInTheDocument();
    expect(taskSummary(proposalSummary).getByText("等待中")).toBeInTheDocument();
    expect(taskSummary(proposalSummary).queryByText("已完成")).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "任务对话" })).not.toHaveTextContent("PRIVATE_TOOL_ARGUMENT");
    expect(screen.getByRole("region", { name: "任务对话" })).not.toHaveTextContent("PRIVATE_TOOL_RESULT");
  });

  it("keeps failed, canceled, and unknown task outcomes distinct and labels export and ingest tasks", async () => {
    mockConversation([
      task({ id: "failed-planning", kind: "AGENT_TURN", stepKey: "planning-failed", status: "FAILED", errorCode: "MODEL_TURN_LIMIT_REACHED" }),
      task({ id: "failed-image", kind: "IMAGE_GENERATION", stepKey: "shot-failed", status: "FAILED", errorCode: "PROVIDER_TIMEOUT" }),
      task({ id: "canceled-video", kind: "VIDEO_GENERATION", stepKey: "shot-canceled", status: "CANCELED", cancelRequested: true }),
      task({ id: "unknown-image", kind: "IMAGE_GENERATION", stepKey: "shot-unknown", status: "UNKNOWN", planId: "plan-1" }),
      task({ id: "export-task", kind: "MEDIA_EXPORT", stepKey: "export", status: "SUCCEEDED" }),
      task({ id: "ingest-task", kind: "ASSET_INGEST", stepKey: "ingest", status: "READY" }),
    ]);
    mountConversation("CANCELED");

    expect(await screen.findByText("生成图片 · shot-failed")).toBeInTheDocument();
    expect(taskSummary("AI 规划 · planning-failed").getByText("失败")).toBeInTheDocument();
    expect(screen.getByText(/MODEL_TURN_LIMIT_REACHED/)).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-failed").getByText("失败")).toBeInTheDocument();
    expect(taskSummary("生成视频 · shot-canceled").getByText("已取消")).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").getByText("待核对")).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").queryByText("失败")).not.toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").queryByText("已完成")).not.toBeInTheDocument();
    expect(taskSummary("导出视频 · export").getByText("已完成")).toBeInTheDocument();
    expect(taskSummary("归档素材 · ingest").getByText("等待中")).toBeInTheDocument();
    expect(screen.getByText("请求结果待核实")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看提交账本并处理重试" })).toBeInTheDocument();
    expect(screen.getByText("仅停止本系统后续编排；外部任务可能继续执行并产生费用。")).toBeInTheDocument();
  });

  it("embeds the actual approval panel in the active conversation and requires explicit approval", async () => {
    mockConversation([task({ output: { stepIndex: 0, assistantText: "图片计划已准备好，请核对后确认。" } })],
      [action({ toolName: "propose_media_plan", status: "WAITING_APPROVAL", summary: "图片计划等待审批" })]);
    const plan = {
      id: "plan-1", projectId: PROJECT_ID, runId: RUN_ID, revision: 1,
      stage: "IMAGE", status: "PENDING", objective: "制作晨光街道的关键帧",
      steps: [{ stepKey: "shot-1", kind: "IMAGE_GENERATION", shotVersionId: "shot-version-1",
        imageVersionId: null, input: { prompt: "晨光中的街道" } }],
      estimate: { imageCount: 1, videoCount: 0, costSource: "MOCK_UNPRICED" },
      workflowVersion: "mock-image-v1", providerConfigVersion: 1, planHash: PLAN_HASH,
    };
    let approvals = 0;
    server.use(
      http.get(`${RUN_URL}/plans`, () => HttpResponse.json([plan])),
      http.get(`/api/v1/projects/${PROJECT_ID}/plans/${plan.id}/steps/shot-1/candidates`, () => HttpResponse.json([])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post(`/api/v1/projects/${PROJECT_ID}/plans/${plan.id}/approve`, async ({ request }) => {
        approvals++;
        expect(await request.json()).toEqual({ planHash: PLAN_HASH, confirmedStepKeys: ["shot-1"] });
        return HttpResponse.json({ approvalId: "approval-1", plan: { ...plan, status: "APPROVED" }, tasks: [], replayed: false });
      }),
    );
    const user = userEvent.setup();
    mountConversation("WAITING_APPROVAL", true);

    const conversation = screen.getByRole("region", { name: "任务对话" });
    const approval = await within(conversation).findByRole("region", { name: "关键帧图片计划待确认" });
    expect(approval).toHaveTextContent("制作晨光街道的关键帧");
    expect(approval).toHaveTextContent("Mock 只产生演示素材");
    expect(approvals).toBe(0);
    expect(within(approval).getByRole("button", { name: "确认执行此计划" })).toBeDisabled();
    await user.click(within(approval).getByRole("checkbox", { name: /确认镜头 shot-1/ }));
    expect(approvals).toBe(0);
    await user.click(within(approval).getByRole("button", { name: "确认执行此计划" }));
    await waitFor(() => expect(approvals).toBe(1));
  });
});
