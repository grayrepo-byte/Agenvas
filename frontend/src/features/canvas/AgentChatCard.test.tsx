import { QueryClientProvider } from "@tanstack/react-query";
import { ReactFlowProvider } from "@xyflow/react";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, AgentRun, CreateRunRequest, RunPreflight } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentChatCard, type AgentChatCardData } from "./AgentChatCard";
import { useCanvasStore } from "./canvasStore";

const PROJECT_ID = "project-1";
const AGENT_ID = "agent-1";
const AGENT_VERSION = 3;
const RUNS_URL = `/api/v1/projects/${PROJECT_ID}/runs`;
const NOW = "2026-09-26T10:00:00Z";
const TASK_TEXT = "规划三个镜头，生成之前先给我检查。";
const AGENT: Agent = {
  id: AGENT_ID, projectId: PROJECT_ID, name: "创作助手", instruction: "依据绑定素材提出创作计划。",
  profileKey: "creator", profileVersion: 1, outputGroupId: "output-group-1",
  version: AGENT_VERSION, createdAt: NOW, updatedAt: NOW, bindings: [],
};
const PREFLIGHT: RunPreflight = {
  agentId: AGENT_ID, agentVersion: AGENT_VERSION, agentName: AGENT.name,
  agentInstruction: AGENT.instruction, bindings: [], modelAvailable: true,
  providerAdapter: "Mock", modelId: "mock-storyboard-v1", toolCalling: true,
  policySnapshot: { schemaVersion: 2, systemPromptVersion: 2,
    modelConfigSource: "mock", modelConfigVersion: 7, maxModelTurns: 12,
    maxToolExecutions: 40, maxImages: 8, maxVideos: 6, maxShots: 6 },
};

function run(overrides: Partial<AgentRun> = {}): AgentRun {
  return { id: "run-current", projectId: PROJECT_ID, agentInstanceId: AGENT_ID,
    status: "QUEUED", instruction: TASK_TEXT, contextSnapshot: {
      agentId: AGENT_ID, agentVersion: AGENT_VERSION, profileKey: "creator",
      profileVersion: 1, outputGroupId: AGENT.outputGroupId, bindings: [],
    }, policySnapshot: PREFLIGHT.policySnapshot, profileVersion: 1, nextStepIndex: 0,
    version: 1, createdAt: NOW, updatedAt: NOW, ...overrides };
}

function mountCard(activeRun: AgentRun | null = null, overrides: Partial<AgentChatCardData> = {}, onOuterClick?: () => void) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const data: AgentChatCardData = {
    item: { id: "agent-item-1", subjectType: "AGENT", subjectId: AGENT_ID,
      x: 0, y: 0, width: 460, height: 600, zIndex: 0, locked: true, version: 1,
      artifact: null, agent: AGENT },
    projectId: PROJECT_ID, activeRun, redoCandidates: [], outputCount: 0,
    onShowOutputs: vi.fn(), onResizeEnd: vi.fn(), onRemove: vi.fn(),
    onToggleLocked: vi.fn(), onUpdateAgent: vi.fn(), updatingAgent: false, ...overrides,
  };
  render(<QueryClientProvider client={client}><ReactFlowProvider>
    <div onClick={onOuterClick}><AgentChatCard data={data} selected /></div>
  </ReactFlowProvider></QueryClientProvider>);
  return client;
}

beforeEach(() => {
  useCanvasStore.getState().setSelectedIds([]);
  server.use(
    http.get(RUNS_URL, () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get(`${RUNS_URL}/:runId/tasks`, () => HttpResponse.json([])),
    http.get(`${RUNS_URL}/:runId/actions`, () => HttpResponse.json([])),
    http.get(`${RUNS_URL}/:runId/plans`, () => HttpResponse.json([])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
  );
});
afterEach(() => useCanvasStore.getState().setSelectedIds([]));

describe("AgentChatCard", () => {
  it("checks a new message before creating a run and sends only after the reviewed confirmation", async () => {
    let checks = 0;
    const requests: CreateRunRequest[] = [];
    server.use(
      http.get(`${RUNS_URL}/preflight`, ({ request }) => {
        expect(new URL(request.url).searchParams.get("agentId")).toBe(AGENT_ID);
        checks++;
        return HttpResponse.json(PREFLIGHT);
      }),
      http.post(RUNS_URL, async ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        requests.push(await request.json() as CreateRunRequest);
        return HttpResponse.json(run(), { status: 202 });
      }),
    );
    useCanvasStore.getState().setSelectedIds(["selected-image-1"]);
    mountCard();
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "本次任务" }), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));

    const confirmation = await screen.findByRole("button", { name: "确认开始规划" });
    expect(confirmation).toBeEnabled();
    expect(checks).toBe(1);
    expect(requests).toHaveLength(0);
    expect(screen.getByRole("article", { name: "待发送" })).toHaveTextContent(TASK_TEXT);
    await user.click(confirmation);

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toEqual({ agentId: AGENT_ID, instruction: TASK_TEXT,
      expectedAgentVersion: AGENT_VERSION, expectedModelConfigSource: "mock",
      expectedModelConfigVersion: 7, expectedSystemPromptVersion: 2,
      selectedItemIds: ["selected-image-1"] });
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(""));
    expect(screen.queryByRole("article", { name: "待发送" })).not.toBeInTheDocument();
  });

  it("preserves ordinary and composing Enter input while Ctrl+Enter opens the preflight review", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, () => { creations++; return HttpResponse.json(run(), { status: 202 }); }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = screen.getByRole("textbox", { name: "本次任务" });
    await user.type(input, TASK_TEXT);
    await user.keyboard("{Enter}");
    expect(input).toHaveValue(`${TASK_TEXT}\n`);
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true, isComposing: true });
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(checks).toBe(0);
    expect(creations).toBe(0);

    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true, isComposing: false });
    expect(await screen.findByRole("button", { name: "确认开始规划" })).toBeEnabled();
    expect(checks).toBe(1);
    expect(creations).toBe(0);
  });

  it("blocks sending and keyboard submission while a different Agent owns the active run", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, () => { creations++; return HttpResponse.json(run(), { status: 202 }); }),
    );
    mountCard(run({ id: "other-run", agentInstanceId: "other-agent", status: "RUNNING" }));
    const user = userEvent.setup();
    const input = screen.getByRole("textbox", { name: "本次任务" });
    await user.type(input, TASK_TEXT);

    expect(screen.getByText("其他 Agent 运行中")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true });
    fireEvent.submit(screen.getByRole("form", { name: "发送新任务" }));
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(checks).toBe(0);
    expect(creations).toBe(0);
  });

  it("returns to the latest current conversation after opening a run from an older history page", async () => {
    const current = run({ id: "run-latest", status: "SUCCEEDED", instruction: "最新任务内容" });
    const historical = run({ id: "run-old", status: "SUCCEEDED", instruction: "历史任务内容",
      createdAt: "2026-09-25T10:00:00Z" });
    server.use(http.get(RUNS_URL, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor");
      return HttpResponse.json(cursor ? { items: [historical], nextCursor: null }
        : { items: [current], nextCursor: "older-page" });
    }));
    mountCard();
    const user = userEvent.setup();
    expect(await screen.findByRole("article", { name: "你" })).toHaveTextContent(current.instruction);

    await user.click(screen.getByRole("button", { name: "查看记录" }));
    await user.click(screen.getByRole("button", { name: "下一页" }));
    await user.click(await screen.findByRole("button", { name: /历史任务内容/ }));
    expect(screen.getByRole("article", { name: "你" })).toHaveTextContent(historical.instruction);
    await user.click(screen.getByRole("button", { name: "返回当前任务" }));

    await waitFor(() => expect(screen.getByRole("article", { name: "你" })).toHaveTextContent(current.instruction));
    expect(screen.queryByText("正在查看任务记录")).not.toBeInTheDocument();
  });

  it("cannot confirm cached preflight data after checking a revised message fails", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => {
        checks++;
        return checks === 1 ? HttpResponse.json(PREFLIGHT) : HttpResponse.json({
          title: "核对失败", detail: "运行前配置当前不可用。", code: "PREFLIGHT_UNAVAILABLE",
        }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
      }),
      http.post(RUNS_URL, () => { creations++; return HttpResponse.json(run(), { status: 202 }); }),
    );
    const client = mountCard();
    const user = userEvent.setup();
    const input = screen.getByRole("textbox", { name: "本次任务" });
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("button", { name: "确认开始规划" })).toBeEnabled();

    await user.clear(input);
    await user.type(input, "改为规划夜景镜头。");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("运行前配置当前不可用。");
    expect(client.getQueryData(["run-preflight", PROJECT_ID, AGENT_ID, AGENT_VERSION])).toEqual(PREFLIGHT);
    const review = screen.getByRole("region", { name: "运行前确认" });
    expect(within(review).queryByRole("button", { name: "确认开始规划" })).not.toBeInTheDocument();
    expect(within(review).queryByText(/mock-storyboard-v1/)).not.toBeInTheDocument();
    expect(input).toHaveValue("改为规划夜景镜头。");
    expect(checks).toBe(2);
    expect(creations).toBe(0);
  });

  it("preserves a new composer draft when the preceding run finishes submitting", async () => {
    let creations = 0;
    let releaseResponse: (() => void) | undefined;
    const responseGate = new Promise<void>((resolve) => { releaseResponse = resolve; });
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => HttpResponse.json(PREFLIGHT)),
      http.post(RUNS_URL, async () => {
        creations++;
        await responseGate;
        return HttpResponse.json(run(), { status: 202 });
      }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = screen.getByRole("textbox", { name: "本次任务" });
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认开始规划" }));
    await waitFor(() => expect(creations).toBe(1));

    const nextDraft = "下一次任务改为拍摄雨中的街道。";
    await user.clear(input);
    await user.type(input, nextDraft);
    if (!releaseResponse) throw new Error("Run response gate is missing");
    releaseResponse();

    expect(await screen.findByRole("article", { name: "你" })).toHaveTextContent(TASK_TEXT);
    expect(input).toHaveValue(nextDraft);
    expect(creations).toBe(1);
  });

  it("shows Agent outputs without bubbling the click into canvas selection", async () => {
    const onShowOutputs = vi.fn();
    const onOuterClick = vi.fn();
    mountCard(null, { outputCount: 2, onShowOutputs }, onOuterClick);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: /查看产物/ }));

    expect(onShowOutputs).toHaveBeenCalledExactlyOnceWith(AGENT);
    expect(onOuterClick).not.toHaveBeenCalled();
  });
});
