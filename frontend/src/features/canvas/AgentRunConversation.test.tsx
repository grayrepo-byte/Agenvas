import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
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

  it("keeps failed, canceled, and unknown task outcomes distinct and labels ingest tasks", async () => {
    mockConversation([
      task({ id: "failed-planning", kind: "AGENT_TURN", stepKey: "planning-failed", status: "FAILED", errorCode: "MODEL_TURN_LIMIT_REACHED" }),
      task({ id: "failed-image", kind: "IMAGE_GENERATION", stepKey: "shot-failed", status: "FAILED", errorCode: "PROVIDER_TIMEOUT" }),
      task({ id: "canceled-video", kind: "VIDEO_GENERATION", stepKey: "shot-canceled", status: "CANCELED", cancelRequested: true }),
      task({ id: "unknown-image", kind: "IMAGE_GENERATION", stepKey: "shot-unknown", status: "UNKNOWN" }),
      task({ id: "ingest-task", kind: "ASSET_INGEST", stepKey: "ingest", status: "READY" }),
    ]);
    mountConversation("CANCELED");

    expect(await screen.findByText("生成图片 · shot-failed")).toBeInTheDocument();
    expect(taskSummary("AI 回复 · planning-failed").getByText("失败")).toBeInTheDocument();
    expect(screen.getByText(/MODEL_TURN_LIMIT_REACHED/)).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-failed").getByText("失败")).toBeInTheDocument();
    expect(taskSummary("生成视频 · shot-canceled").getByText("已取消")).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").getByText("未知")).toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").queryByText("失败")).not.toBeInTheDocument();
    expect(taskSummary("生成图片 · shot-unknown").queryByText("已完成")).not.toBeInTheDocument();
    expect(taskSummary("归档素材 · ingest").getByText("等待中")).toBeInTheDocument();
    expect(screen.getByText("结果未知")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(screen.getByText("仅停止本系统后续编排；外部任务可能继续执行并产生费用。")).toBeInTheDocument();
  });
});
