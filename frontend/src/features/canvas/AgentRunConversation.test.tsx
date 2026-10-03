import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { AgentRun, RunAction, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentRunConversation } from "./AgentRunConversation";
import { runAssistantStreamKey, type AssistantTurnStream } from "./agentRunStream";

const PROJECT_ID = "project-1";
const RUN_ID = "run-1";
const RUN_URL = `/api/v1/projects/${PROJECT_ID}/runs/${RUN_ID}`;
const NOW = "2026-09-26T00:00:00Z";
const USER_INSTRUCTION = "请描述这次创作，并在开始前给我确认。";

function task(overrides: Partial<Task>): Task {
  return {
    id: "task-1", projectId: PROJECT_ID, runId: RUN_ID, stepKey: "agent-turn-1",
    kind: "AGENT_TURN", status: "SUCCEEDED", cancelRequested: false,
    input: {}, output: {}, attemptNo: 1, nextActionAt: NOW, version: 1,
    createdAt: NOW, updatedAt: NOW, completedAt: NOW, ...overrides,
  };
}

function action(overrides: Partial<RunAction>): RunAction {
  return { id: "action-1", stepIndex: 0, toolName: "create_text", status: "SUCCEEDED",
    summary: "已创建一份文字说明", completedAt: NOW, ...overrides };
}

function mockConversation(tasks: Task[], actions: RunAction[] = []) {
  server.use(
    http.get(`${RUN_URL}/tasks`, () => HttpResponse.json(tasks)),
    http.get(`${RUN_URL}/actions`, () => HttpResponse.json(actions)),
    http.get(`${RUN_URL}/media-approvals`, () => HttpResponse.json([])),
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
  return client;
}

function taskSummary(label: string, index = 0) {
  const summary = screen.getAllByText(label)[index]?.closest(".agent-chat-task")?.querySelector<HTMLElement>(".agent-chat-task__headline");
  if (!summary) throw new Error(`Missing task summary: ${label}`);
  return within(summary);
}

describe("AgentRunConversation", () => {
  it("formats historical assistant Markdown while keeping the user instruction literal", async () => {
    mockConversation([task({ output: { assistantText: "**结果**\n\n- 视频已完成\n\n版本 `synthetic-version`" } })]);
    const client = createQueryClient();
    render(<QueryClientProvider client={client}>
      <AgentRunConversation projectId={PROJECT_ID} active={false}
        run={{ id: RUN_ID, status: "SUCCEEDED", instruction: "**保留用户原文**", createdAt: NOW }} />
    </QueryClientProvider>);
    expect((await screen.findByText("结果")).tagName).toBe("STRONG");
    const reply = screen.getByRole("article", { name: "Agent" });
    expect(within(reply).getByRole("listitem")).toHaveTextContent("视频已完成");
    expect(within(reply).getByText("synthetic-version").tagName).toBe("CODE");
    expect(screen.getByRole("article", { name: "你" })).toHaveTextContent("**保留用户原文**");
  });

  it("does not turn normal model rounds into repetitive operation rows", async () => {
    mockConversation([
      task({ id: "first-model-round", output: { stepIndex: 0, assistantText: "我会先查看参考素材。" } }),
      task({ id: "next-model-round", status: "RUNNING", input: { stepIndex: 1 } }),
    ]);
    mountConversation("RUNNING", true);

    expect(await screen.findByText("我会先查看参考素材。")).toBeInTheDocument();
    expect(screen.queryAllByText("AI 回复")).toHaveLength(0);
    expect(screen.queryByText(/第 1 次尝试/)).not.toBeInTheDocument();
    expect(screen.queryByText(/项操作/)).not.toBeInTheDocument();
  });

  it("shows actual actions and media states without first-attempt filler", async () => {
    mockConversation([
      task({ id: "model-round", output: { stepIndex: 0 } }),
      task({ id: "media-task", kind: "IMAGE_GENERATION", status: "WAITING_PROVIDER", input: { stepIndex: 0 } }),
    ], [action({ summary: "已读取参考图片" })]);
    mountConversation("WAITING_TASKS", true);

    expect(await screen.findByText("已读取参考图片")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("2 项操作")).toBeInTheDocument());
    expect(taskSummary("生成图片").getByText("运行中")).toBeInTheDocument();
    expect(screen.queryAllByText("AI 回复")).toHaveLength(0);
    expect(screen.queryByText(/第 1 次尝试/)).not.toBeInTheDocument();
  });

  it("renders persisted public streaming text and replaces it with one committed final reply", async () => {
    const streaming = task({ status: "RUNNING", input: { stepIndex: 0 }, output: {
      assistantStream: { streamEpoch: 1, chunkIndex: 1, text: "**收到的公开片段**", status: "STREAMING" },
      reasoning: "PRIVATE_STREAM_REASONING",
    } });
    mockConversation([streaming]);
    const client = mountConversation("RUNNING", true);
    expect(await screen.findByText("收到的公开片段")).toBeInTheDocument();
    expect(screen.getByText("收到的公开片段").tagName).toBe("STRONG");
    expect(screen.getByRole("article", { name: "Agent" })).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("正在输出")).toBeInTheDocument();
    expect(screen.queryByText("PRIVATE_STREAM_REASONING")).not.toBeInTheDocument();

    act(() => {
      client.setQueryData<AssistantTurnStream[]>(runAssistantStreamKey(PROJECT_ID, RUN_ID), [{
        taskId: streaming.id, stepIndex: 0, streamEpoch: 1, chunkIndex: 2,
        text: "收到的公开片段以及后续内容", status: "COMPLETED",
      }]);
      client.setQueryData(["run-history-tasks", PROJECT_ID, RUN_ID], [
        { ...streaming, status: "SUCCEEDED", output: { stepIndex: 0, assistantText: "## 持久化最终回复" } },
      ]);
    });
    expect(await screen.findByText("持久化最终回复")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "持久化最终回复", level: 2 })).toBeInTheDocument();
    expect(screen.getAllByRole("article", { name: "Agent" })).toHaveLength(1);
    expect(screen.getByRole("article", { name: "Agent" })).toHaveAttribute("aria-busy", "false");
    expect(screen.queryByText(/收到的公开片段/)).not.toBeInTheDocument();
    expect(screen.queryByText("正在输出")).not.toBeInTheDocument();
  });

  it.each(["FAILED", "BLOCKED"] as const)("exposes %s failure feedback even for a historical run", async (status) => {
    mockConversation([task({ status: "FAILED", errorCode: "LLM_CONFIG_UNAVAILABLE",
      output: { rawResponse: "PRIVATE_PROVIDER_FAILURE" } })]);
    mountConversation(status, false);
    const error = await screen.findByRole("alert");
    await waitFor(() => expect(error).toHaveTextContent("固定的模型配置或工具调用能力不可用"));
    expect(error.closest(".agent-execution-trace")).toBeNull();
    expect(error).not.toHaveTextContent("PRIVATE_PROVIDER_FAILURE");
    expect(screen.queryByText("执行记录")).not.toBeInTheDocument();
  });

  it("keeps an interrupted public stream visible and identifies the interruption", async () => {
    mockConversation([task({ status: "FAILED", input: { stepIndex: 0 }, output: {
      assistantStream: { streamEpoch: 1, chunkIndex: 1, text: "**中断前收到的内容**", status: "INTERRUPTED" },
    } })]);
    mountConversation("FAILED");
    expect(await screen.findByText("中断前收到的内容")).toBeInTheDocument();
    expect(screen.getByText("中断前收到的内容").tagName).toBe("STRONG");
    expect(screen.getByText("输出已中断，已保留收到的内容。")).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "Agent" })).toHaveAttribute("aria-busy", "false");
  });

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
    expect(screen.queryByText("模型调用")).not.toBeInTheDocument();
    for (const hidden of ["PRIVATE_TASK_INPUT", "PRIVATE_RAW_RESPONSE", "PRIVATE_OUTPUT_METADATA",
      "PRIVATE_REASONING", "UNCOMMITTED_ASSISTANT_TEXT", "assistantText", "stepIndex", "agent-turn-1"]) {
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

  it("keeps retry attempts, model failures, and cancellation details visible", async () => {
    mockConversation([
      task({ id: "retry-round", attemptNo: 2, status: "RUNNING", input: { stepIndex: 0 } }),
      task({ id: "blocked-round", status: "BLOCKED", errorCode: "PROVIDER_CALL_TIMEOUT", input: { stepIndex: 1 } }),
      task({ id: "stopping-round", status: "RUNNING", cancelRequested: true, input: { stepIndex: 2 } }),
    ]);
    mountConversation("CANCEL_REQUESTED", true);

    expect(await screen.findByText("第 2 次尝试")).toBeInTheDocument();
    expect(screen.getByText("调用超时，结果未知")).toBeInTheDocument();
    expect(screen.getByText("已请求停止后续编排")).toBeInTheDocument();
    expect(taskSummary("模型调用", 1).getByText("失败")).toBeInTheDocument();
    expect(screen.queryByText(/第 1 次尝试/)).not.toBeInTheDocument();
  });

  it("shows committed action summaries without leaking tool arguments or results", async () => {
    const createdSummary = "已创建一份文字说明";
    const revisedSummary = "已按说明改写文字产物";
    mockConversation([], [
      action({ summary: createdSummary }),
      action({ id: "revise-action", stepIndex: 1, toolName: "revise_artifact",
        summary: revisedSummary }),
    ]);
    server.use(http.get(`${RUN_URL}/actions`, () => HttpResponse.json([
      { ...action({ summary: createdSummary }), arguments: { private: "PRIVATE_TOOL_ARGUMENT" },
        result: { raw: "PRIVATE_TOOL_RESULT" } },
      action({ id: "revise-action", stepIndex: 1, toolName: "revise_artifact",
        summary: revisedSummary }),
    ])));
    mountConversation();

    expect(await screen.findByText(createdSummary)).toBeInTheDocument();
    expect(taskSummary(createdSummary).getByText("已完成")).toBeInTheDocument();
    expect(taskSummary(revisedSummary).getByText("已完成")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "任务对话" })).not.toHaveTextContent("PRIVATE_TOOL_ARGUMENT");
    expect(screen.getByRole("region", { name: "任务对话" })).not.toHaveTextContent("PRIVATE_TOOL_RESULT");
  });

  it("keeps failed, canceled, unknown and queued task outcomes distinct", async () => {
    mockConversation([
      task({ id: "failed-planning", kind: "AGENT_TURN", stepKey: "planning-failed", status: "FAILED", errorCode: "MODEL_TURN_LIMIT_REACHED" }),
      task({ id: "failed-image", kind: "IMAGE_GENERATION", stepKey: "shot-failed", status: "FAILED", errorCode: "PROVIDER_TIMEOUT" }),
      task({ id: "canceled-video", kind: "VIDEO_GENERATION", stepKey: "shot-canceled", status: "CANCELED", cancelRequested: true }),
      task({ id: "unknown-image", kind: "IMAGE_GENERATION", stepKey: "shot-unknown", status: "UNKNOWN" }),
      task({ id: "queued-text", kind: "TEXT_GENERATION", stepKey: "text", status: "READY" }),
    ]);
    mountConversation("CANCELED");

    expect(await screen.findAllByText("生成图片")).toHaveLength(2);
    expect(taskSummary("模型调用").getByText("失败")).toBeInTheDocument();
    expect(screen.getByText(/MODEL_TURN_LIMIT_REACHED/)).toBeInTheDocument();
    expect(taskSummary("生成图片").getByText("失败")).toBeInTheDocument();
    expect(taskSummary("生成视频").getByText("已取消")).toBeInTheDocument();
    expect(taskSummary("生成图片", 1).getByText("未知")).toBeInTheDocument();
    expect(taskSummary("生成图片", 1).queryByText("失败")).not.toBeInTheDocument();
    expect(taskSummary("生成图片", 1).queryByText("已完成")).not.toBeInTheDocument();
    expect(taskSummary("生成文字").getByText("等待中")).toBeInTheDocument();
    const conversation = screen.getByRole("region", { name: "任务对话" });
    for (const internalKey of ["planning-failed", "shot-failed", "shot-canceled", "shot-unknown", " · ingest"]) {
      expect(conversation).not.toHaveTextContent(internalKey);
    }
    expect(screen.getByText("结果未知")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(screen.getByText("仅停止本系统后续编排；外部任务可能继续执行并产生费用。")).toBeInTheDocument();
  });

  it("labels media tools in the public trace and never offers direct retry for an approved unknown task", async () => {
    mockConversation([
      task({ kind: "AUDIO_GENERATION", stepKey: "approved-audio", status: "UNKNOWN", input: { agentApprovalId: "approval-1" } }),
    ], [action({ toolName: "list_media_capabilities", summary: "已读取可用能力" }),
      action({ id: "proposal", toolName: "propose_media_generation", summary: "已提交生成审批" })]);
    mountConversation("WAITING_TASKS", true);
    expect(await screen.findByText("已读取可用能力")).toBeInTheDocument();
    expect(taskSummary("已读取可用能力").getByText("查看媒体能力")).toBeInTheDocument();
    expect(taskSummary("已提交生成审批").getByText("提出媒体生成")).toBeInTheDocument();
    expect(taskSummary("音频生成").getByText("未知")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: /Reasoning|Search|思考/ })).not.toBeInTheDocument();
  });
});
