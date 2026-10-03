import { QueryClientProvider } from "@tanstack/react-query";
import { ReactFlowProvider } from "@xyflow/react";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, AgentRun, AgentConversation, CreateRunRequest, RunPreflight } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentChatCard, type AgentChatCardData } from "./AgentChatCard";
import { useCanvasStore } from "./canvasStore";
import { projectCacheCallbacks } from "./projectCache";
import { runAssistantStreamKey, type AssistantTurnStream } from "./agentRunStream";

const PROJECT_ID = "project-1";
const AGENT_ID = "agent-1";
const AGENT_VERSION = 3;
const RUNS_URL = `/api/v1/projects/${PROJECT_ID}/runs`;
const CONVERSATION_ID = "conversation-1";
const CONVERSATIONS_URL = `/api/v1/projects/${PROJECT_ID}/agents/${AGENT_ID}/conversations`;
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
  conversationId: CONVERSATION_ID, conversationVersion: 0, conversationTurnCount: 0, inheritedBindingCount: 0, memoryTruncated: false,
  providerAdapter: "Mock", modelId: "mock-storyboard-v1", toolCalling: true,
  policySnapshot: { schemaVersion: 2, systemPromptVersion: 2,
    modelConfigSource: "mock", modelConfigVersion: 7, maxModelTurns: 12,
    maxToolExecutions: 40 },
};

function run(overrides: Partial<AgentRun> = {}): AgentRun {
  return { id: "run-current", projectId: PROJECT_ID, agentInstanceId: AGENT_ID,
    conversationId: CONVERSATION_ID, conversationTurn: 1, status: "QUEUED", instruction: TASK_TEXT, contextSnapshot: {
      agentId: AGENT_ID, agentVersion: AGENT_VERSION, profileKey: "creator",
      profileVersion: 1, outputGroupId: AGENT.outputGroupId, bindings: [],
    }, policySnapshot: PREFLIGHT.policySnapshot, profileVersion: 1, nextStepIndex: 0,
    version: 1, createdAt: NOW, updatedAt: NOW, ...overrides };
}

function conversation(overrides: Partial<AgentConversation> = {}): AgentConversation {
  return { id: CONVERSATION_ID, projectId: PROJECT_ID, agentInstanceId: AGENT_ID,
    title: "当前创作会话", version: 0, turnCount: 0, createdAt: NOW, updatedAt: NOW, ...overrides };
}

let storedConversations: AgentConversation[];
let storedRuns: AgentRun[];
let currentConversationId: string | null;

function persistRun(saved = run()) {
  storedRuns = [...storedRuns.filter((item) => item.id !== saved.id), saved];
  return HttpResponse.json(saved, { status: 202 });
}
function persistConversation(id = "conversation-new") {
  const created = conversation({ id, title: "新会话" });
  storedConversations = [created, ...storedConversations.filter((item) => item.id !== created.id)];
  currentConversationId = created.id;
  return HttpResponse.json(created, { status: 201 });
}

function mountCard(activeRun: AgentRun | null = null, overrides: Partial<AgentChatCardData> = {}, onOuterClick?: () => void) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  const data: AgentChatCardData = {
    item: { id: "agent-item-1", subjectType: "AGENT", subjectId: AGENT_ID,
      title: AGENT.name,
      x: 0, y: 0, width: 460, height: 600, zIndex: 0, locked: true, selectedVersionId: null, selectedVersion: null, version: 1,
      artifact: null, agent: AGENT },
    projectId: PROJECT_ID, activeRun, outputCount: 0,
    onShowOutputs: vi.fn(), onResizeEnd: vi.fn(), onRemove: vi.fn(),
    onToggleLocked: vi.fn(), onUpdateAgent: vi.fn(), updatingAgent: false, ...overrides,
  };
  render(<QueryClientProvider client={client}><ReactFlowProvider>
    <div onClick={onOuterClick}><AgentChatCard data={data} selected /></div>
  </ReactFlowProvider></QueryClientProvider>);
  return client;
}

async function readyComposer() {
  const input = screen.getByRole("textbox", { name: "本次任务" });
  await waitFor(() => expect(input).toBeEnabled());
  return input;
}

it("retains unsaved Agent configuration across chat and settings tabs", async () => {
  const onUpdateAgent = vi.fn();
  mountCard(null, { onUpdateAgent });
  const user = userEvent.setup();
  await readyComposer();
  await user.click(screen.getByRole("button", { name: "Agent 设置" }));
  await user.clear(screen.getByRole("textbox", { name: "名称" }));
  await user.type(screen.getByRole("textbox", { name: "名称" }), "尚未保存的新名称");
  await user.click(screen.getByRole("button", { name: "聊天" }));
  await user.click(screen.getByRole("button", { name: "Agent 设置" }));
  expect(screen.getByRole("textbox", { name: "名称" })).toHaveValue("尚未保存的新名称");
  await user.click(screen.getByRole("button", { name: "保存配置" }));
  expect(onUpdateAgent).toHaveBeenCalledWith(AGENT, "尚未保存的新名称", AGENT.instruction);
});

beforeEach(() => {
  useCanvasStore.getState().setSelectedIds([]);
  storedConversations = [conversation()]; storedRuns = []; currentConversationId = CONVERSATION_ID;
  server.use(
    http.get(CONVERSATIONS_URL, () => HttpResponse.json({ items: storedConversations, nextCursor: null, currentConversationId })),
    http.post(CONVERSATIONS_URL, () => persistConversation()),
    http.post(`${CONVERSATIONS_URL}/:conversationId/select`, ({ params }) => {
      const target = storedConversations.find((item) => item.id === params.conversationId);
      if (!target) return HttpResponse.json({ title: "Missing conversation" }, { status: 404 });
      currentConversationId = target.id; return HttpResponse.json(target);
    }),
    http.get(`${CONVERSATIONS_URL}/:conversationId/runs`, ({ params }) => HttpResponse.json({
      items: storedRuns.filter((item) => item.conversationId === params.conversationId).sort((a, b) => b.conversationTurn - a.conversationTurn), nextCursor: null,
    })),
    http.get(RUNS_URL, () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get(`${RUNS_URL}/:runId/tasks`, () => HttpResponse.json([])),
    http.get(`${RUNS_URL}/:runId/actions`, () => HttpResponse.json([])),
    http.get(`${RUNS_URL}/:runId/media-approvals`, () => HttpResponse.json([])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
  );
});
afterEach(() => {
  useCanvasStore.getState().setSelectedIds([]);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("AgentChatCard", () => {
  it("follows growing streamed content only while the user remains at the bottom, with the composer outside scrolling content", async () => {
    const observations: { callback: ResizeObserverCallback; observer: ResizeObserver; targets: Set<Element> }[] = [];
    class ControlledResizeObserver implements ResizeObserver {
      private readonly targets = new Set<Element>();
      constructor(callback: ResizeObserverCallback) { observations.push({ callback, observer: this, targets: this.targets }); }
      observe(target: Element) { this.targets.add(target); }
      unobserve(target: Element) { this.targets.delete(target); }
      disconnect() { this.targets.clear(); }
    }
    vi.stubGlobal("ResizeObserver", ControlledResizeObserver);
    let contentHeight = 1200;
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockImplementation(() => contentHeight);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(400);
    const current = run({ status: "RUNNING" });
    const client = mountCard(current);
    await readyComposer();
    const card = screen.getByRole("article", { name: "创作助手 聊天卡片" });
    const body = card.querySelector<HTMLElement>(".agent-chat-body");
    const transcript = card.querySelector<HTMLElement>(".agent-chat-transcript");
    if (!body || !transcript) throw new Error("Chat scrolling elements are required");
    await waitFor(() => expect(body.scrollTop).toBe(contentHeight));
    const observation = observations.find((entry) => entry.targets.has(transcript));
    if (!observation) throw new Error("Transcript resize observer is required");
    expect(observation.targets).toEqual(new Set([transcript, body]));
    const composer = screen.getByRole("form", { name: "发送新任务" });
    expect(body).not.toContainElement(composer);
    expect(body.nextElementSibling).toHaveClass("agent-chat-feedback");
    expect(body.nextElementSibling?.nextElementSibling).toBe(composer);

    const updateReply = (text: string, height: number) => {
      act(() => {
        contentHeight = height;
        client.setQueryData<AssistantTurnStream[]>(runAssistantStreamKey(PROJECT_ID, current.id), [{
          taskId: "stream-task", stepIndex: 0, streamEpoch: 1, chunkIndex: 1, text, status: "STREAMING",
        }]);
        observation.callback([], observation.observer);
      });
    };
    body.scrollTop = contentHeight - body.clientHeight;
    fireEvent.scroll(body);
    updateReply("正在收到的第一段回复", 1600);
    expect(await screen.findByText("正在收到的第一段回复")).toBeInTheDocument();
    expect(body.scrollTop).toBe(1600);

    body.scrollTop = 300;
    fireEvent.scroll(body);
    updateReply("正在收到的第一段回复以及更多内容", 1800);
    expect(await screen.findByText("正在收到的第一段回复以及更多内容")).toBeInTheDocument();
    expect(body.scrollTop).toBe(300);

    body.scrollTop = contentHeight - body.clientHeight;
    fireEvent.scroll(body);
    updateReply("继续看到最新收到的回复", 2200);
    expect(await screen.findByText("继续看到最新收到的回复")).toBeInTheDocument();
    expect(body.scrollTop).toBe(2200);
    cleanup();
    expect(observation.targets.size).toBe(0);
  });

  it("preflights and starts a run with one send, without an extra confirmation", async () => {
    let checks = 0;
    const requests: CreateRunRequest[] = [];
    server.use(
      http.post(`${RUNS_URL}/preflight`, async ({ request }) => {
        expect(await request.json()).toMatchObject({agentId:AGENT_ID,skillSelection:{mode:"NONE",inputs:[]}});
        checks++;
        return HttpResponse.json(PREFLIGHT);
      }),
      http.post(RUNS_URL, async ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        requests.push(await request.json() as CreateRunRequest);
        return persistRun();
      }),
    );
    useCanvasStore.getState().setSelectedIds(["selected-image-1"]);
    mountCard();
    expect(screen.getByRole("textbox", { name: "本次任务" })).toBeDisabled();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(checks).toBe(1);
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "确认开始" })).not.toBeInTheDocument();
    expect(requests[0]).toEqual({ agentId: AGENT_ID, conversationId: CONVERSATION_ID, expectedConversationVersion: 0, instruction: TASK_TEXT,
      expectedAgentVersion: AGENT_VERSION, expectedModelConfigSource: "mock",
      expectedModelConfigVersion: 7, expectedSystemPromptVersion: 2,
      selectedItemIds: ["selected-image-1"], skillSelection: {mode:"NONE",inputs:[]} });
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(""));
    expect(screen.queryByRole("article", { name: "待发送" })).not.toBeInTheDocument();
  });

  it.each([
    { label: "missing model", preview: { modelAvailable: false }, message: "未配置 ChatModel，无法启动运行" },
    { label: "unsupported tools", preview: { toolCalling: false }, message: "当前模型未确认支持工具调用，无法运行。" },
    { label: "missing policy version", preview: { policySnapshot: { ...PREFLIGHT.policySnapshot, systemPromptVersion: null } }, message: "运行配置已变化，请重新发送。" },
  ])("preserves the draft and blocks creation for $label", async ({ preview, message }) => {
    let creations = 0;
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => HttpResponse.json({ ...PREFLIGHT, ...preview })),
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(creations).toBe(0);
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(TASK_TEXT);
  });

  it("blocks duplicate submissions and abandons a draft changed during preflight", async () => {
    let checks = 0;
    let creations = 0;
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(
      http.post(`${RUNS_URL}/preflight`, async () => { checks++; await gate; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    const form = screen.getByRole("form", { name: "发送新任务" });
    fireEvent.submit(form);
    fireEvent.submit(form);
    await waitFor(() => expect(checks).toBe(1));
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    await user.clear(input);
    await user.type(input, "改为新的任务");
    if (!release) throw new Error("Missing preflight gate");
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
    expect(creations).toBe(0);
    expect(input).toHaveValue("改为新的任务");
  });

  it("replays the exact create request after a lost response without preflighting an occupied slot", async () => {
    let checks = 0;
    const requests: unknown[] = [];
    const keys: (string | null)[] = [];
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, async ({ request }) => {
        requests.push(await request.json());
        keys.push(request.headers.get("Idempotency-Key"));
        const saved = persistRun();
        return requests.length === 1 ? new HttpResponse(null, { status: 503 }) : saved;
      }),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await screen.findByRole("alert");
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(checks).toBe(1);
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(requests[1]).toEqual(requests[0]);
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(""));
    expect(storedRuns).toHaveLength(1);
  });

  it("exposes startup errors outside the scrolling transcript and retains the draft", async () => {
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => HttpResponse.json(PREFLIGHT)),
      http.post(RUNS_URL, () => HttpResponse.json({ code: "INTERNAL_ERROR", detail: "服务端暂时无法完成请求。", retryable: true },
        { status: 500, headers: { "Content-Type": "application/problem+json" } })),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    const card = screen.getByRole("article", { name: "创作助手 聊天卡片" });
    const body = card.querySelector<HTMLElement>(".agent-chat-body");
    if (!body) throw new Error("Missing scrolling transcript");
    body.scrollTop = 100;
    fireEvent.scroll(body);
    await user.click(screen.getByRole("button", { name: "发送" }));
    const error = await screen.findByRole("alert");
    expect(error).toHaveTextContent("服务端暂时无法完成请求。");
    expect(body).not.toContainElement(error);
    expect(card).toContainElement(error);
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(TASK_TEXT);
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
  });

  it("pins a runtime failure above the composer when the transcript is scrolled away", async () => {
    const failed = run({ status: "FAILED" });
    storedRuns = [failed];
    server.use(http.get(`${RUNS_URL}/${failed.id}/tasks`, () => HttpResponse.json([{
      id: "failed-turn", kind: "AGENT_TURN", status: "FAILED", errorCode: "AGENT_TURN_FAILED",
      input: {}, output: {}, attemptNo: 1,
    }])));
    mountCard();
    await readyComposer();
    const card = screen.getByRole("article", { name: "创作助手 聊天卡片" });
    const body = card.querySelector<HTMLElement>(".agent-chat-body");
    if (!body) throw new Error("Missing scrolling transcript");
    body.scrollTop = 100;
    fireEvent.scroll(body);
    const error = await screen.findByRole("alert");
    await waitFor(() => expect(error).toHaveTextContent("模型回合未能完成"));
    expect(body).not.toContainElement(error);
    expect(card).toContainElement(error);
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(screen.getByText("任务失败", { selector: ".agent-chat-heading > span" })).toBeInTheDocument();
  });

  it("shows asynchronous failure from a project status event without reloading the card", async () => {
    const current = run({ status: "RUNNING" });
    storedRuns = [current];
    let failed = false;
    server.use(http.get(`${RUNS_URL}/${current.id}/tasks`, () => HttpResponse.json([{
      id: "changing-turn", kind: "AGENT_TURN", status: failed ? "FAILED" : "RUNNING",
      errorCode: failed ? "AGENT_TURN_FAILED" : null, input: {}, output: {}, attemptNo: 1,
    }])));
    const client = mountCard();
    await screen.findByRole("article", { name: "你" });
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    failed = true;
    storedRuns = [{ ...current, status: "BLOCKED", version: current.version + 1 }];
    act(() => projectCacheCallbacks(client, PROJECT_ID).onChange({
      projectId: PROJECT_ID, seq: 1, eventId: "event-failed-run", type: "agent.run.changed", schemaVersion: 1,
      aggregateId: current.id, aggregateVersion: current.version + 1,
      payload: { runId: current.id, status: "BLOCKED" }, occurredAt: NOW,
    }));
    const error = await screen.findByRole("alert");
    await waitFor(() => expect(error).toHaveTextContent("模型回合未能完成"));
    expect(error.closest(".agent-chat-body")).toBeNull();
    expect(screen.getByText("需要处理", { selector: ".agent-chat-heading > span" })).toBeInTheDocument();
  });

  it("preserves ordinary and composing Enter input while Ctrl+Enter submits directly", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    await user.keyboard("{Enter}");
    expect(input).toHaveValue(`${TASK_TEXT}\n`);
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true, isComposing: true });
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(checks).toBe(0);
    expect(creations).toBe(0);

    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true, isComposing: false });
    await waitFor(() => expect(creations).toBe(1));
    expect(checks).toBe(1);
  });

  it("blocks sending and keyboard submission while a different Agent owns the active run", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    mountCard(run({ id: "other-run", agentInstanceId: "other-agent", status: "RUNNING" }));
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);

    expect(screen.getByText("其他 Agent 运行中")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", ctrlKey: true });
    fireEvent.submit(screen.getByRole("form", { name: "发送新任务" }));
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(checks).toBe(0);
    expect(creations).toBe(0);
  });

  it("restores a persisted conversation and prepends older turns in chronological order", async () => {
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(1200);
    const current = run({ id: "run-latest", conversationTurn: 2, status: "SUCCEEDED", instruction: "最新任务内容" });
    const historical = run({ id: "run-old", conversationTurn: 1, status: "SUCCEEDED", instruction: "历史任务内容",
      createdAt: "2026-09-25T10:00:00Z" });
    server.use(http.get(`${CONVERSATIONS_URL}/${CONVERSATION_ID}/runs`, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor");
      return HttpResponse.json(cursor ? { items: [historical], nextCursor: null }
        : { items: [current], nextCursor: "older-page" });
    }));
    mountCard();
    const user = userEvent.setup();
    expect(await screen.findByRole("article", { name: "你" })).toHaveTextContent(current.instruction);
    const body = screen.getByRole("article", { name: "创作助手 聊天卡片" }).querySelector<HTMLElement>(".agent-chat-body");
    if (!body) throw new Error("Chat body is missing");
    await waitFor(() => expect(body.scrollTop).toBe(1200));
    body.scrollTop = 140;
    fireEvent.scroll(body);
    await user.click(screen.getByRole("button", { name: "加载更早的消息" }));
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    expect(screen.getAllByRole("article", { name: "你" })[0]).toHaveTextContent(historical.instruction);
    expect(screen.getAllByRole("article", { name: "你" })[1]).toHaveTextContent(current.instruction);
    expect(body.scrollTop).toBe(140);
    expect(screen.queryByText(/独立任务/)).not.toBeInTheDocument();
  });

  it("retains the draft and never creates a run when a fresh preflight fails", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => {
        checks++;
        return checks === 1 ? HttpResponse.json(PREFLIGHT) : HttpResponse.json({
          title: "核对失败", detail: "运行前配置当前不可用。", code: "PREFLIGHT_UNAVAILABLE",
        }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
      }),
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(creations).toBe(1));
    await waitFor(() => expect(input).toHaveValue(""));

    await user.clear(input);
    await user.type(input, "改为规划夜景镜头。");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("运行前配置当前不可用。");
    expect(screen.queryByRole("region", { name: "运行前确认" })).not.toBeInTheDocument();
    expect(input).toHaveValue("改为规划夜景镜头。");
    expect(checks).toBe(2);
    expect(creations).toBe(1);
  });

  it("preserves a new composer draft when the preceding run finishes submitting", async () => {
    let creations = 0;
    let releaseResponse: (() => void) | undefined;
    const responseGate = new Promise<void>((resolve) => { releaseResponse = resolve; });
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => HttpResponse.json(PREFLIGHT)),
      http.post(RUNS_URL, async () => {
        creations++;
        await responseGate;
        return persistRun();
      }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
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

  it("creates the first conversation before checking a first message and pins its identity", async () => {
    storedConversations = []; currentConversationId = null;
    const operations: string[] = [];
    server.use(
      http.post(CONVERSATIONS_URL, ({ request }) => {
        operations.push("create-conversation");
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        return persistConversation();
      }),
      http.post(`${RUNS_URL}/preflight`, async ({ request }) => {
        operations.push("preflight");
        expect(await request.json()).toMatchObject({conversationId:"conversation-new"});
        return HttpResponse.json({ ...PREFLIGHT, conversationId: "conversation-new" });
      }),
      http.post(RUNS_URL, async ({ request }) => {
        operations.push("create-run");
        expect(await request.json()).toMatchObject({ conversationId: "conversation-new", expectedConversationVersion: 0 });
        return persistRun(run({ conversationId: "conversation-new" }));
      }),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(operations).toEqual(["create-conversation", "preflight", "create-run"]));
  });

  it("continues the same conversation and restores both persisted messages after remount", async () => {
    const first = run({ id: "first-run", status: "SUCCEEDED", instruction: "先规划晨光镜头。" });
    storedRuns = [first];
    storedConversations = [conversation({ version: 1, turnCount: 1 })];
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => HttpResponse.json({ ...PREFLIGHT,
        conversationVersion: 1, conversationTurnCount: 1, inheritedBindingCount: 2, memoryTruncated: true })),
      http.post(RUNS_URL, async ({ request }) => {
        const body = await request.json() as CreateRunRequest;
        expect(body).toMatchObject({ conversationId: CONVERSATION_ID, expectedConversationVersion: 1 });
        storedConversations = [conversation({ version: 2, turnCount: 2 })];
        return persistRun(run({ id: "second-run", conversationTurn: 2, status: "SUCCEEDED", instruction: body.instruction }));
      }),
    );
    mountCard();
    const user = userEvent.setup();
    expect(await screen.findByRole("article", { name: "你" })).toHaveTextContent(first.instruction);
    await user.type(await readyComposer(), "把刚才的场景改成黄昏。");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    cleanup();
    mountCard();
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    const messages = screen.getAllByRole("article", { name: "你" });
    expect(messages[0]).toHaveTextContent(first.instruction);
    expect(messages[1]).toHaveTextContent("把刚才的场景改成黄昏。");
    expect(screen.getByText("当前创作会话")).toBeInTheDocument();
  });

  it("does not submit an obsolete message when the first conversation is created after the draft changes", async () => {
    storedConversations = []; currentConversationId = null;
    let creating = false;
    let checked = false;
    const requests: CreateRunRequest[] = [];
    let releaseCreation: (() => void) | undefined;
    const creationGate = new Promise<void>((resolve) => { releaseCreation = resolve; });
    server.use(
      http.post(CONVERSATIONS_URL, async () => {
        creating = true; await creationGate; return persistConversation();
      }),
      http.post(`${RUNS_URL}/preflight`, () => {
        checked = true; return HttpResponse.json({ ...PREFLIGHT, conversationId: "conversation-new" });
      }),
      http.post(RUNS_URL, async ({ request }) => { const body = await request.json() as CreateRunRequest; requests.push(body); return persistRun(run({ conversationId: "conversation-new", instruction: body.instruction })); }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, "原来的想法");
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(creating).toBe(true));
    await user.clear(input);
    await user.type(input, "等待期间改成新的想法");
    if (!releaseCreation) throw new Error("Missing conversation creation gate");
    releaseCreation();
    await waitFor(() => expect(checked).toBe(true));
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
    expect(input).toHaveValue("等待期间改成新的想法");
    expect(screen.queryByRole("article", { name: "待发送" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "确认开始" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]?.instruction).toBe("等待期间改成新的想法");
  });

  it("keeps the old conversation and draft after creation failure, then preserves them when a new conversation opens", async () => {
    const createKeys: (string | null)[] = [];
    storedRuns = [run({ status: "SUCCEEDED" })];
    server.use(http.post(CONVERSATIONS_URL, ({ request }) => {
      createKeys.push(request.headers.get("Idempotency-Key"));
      if (createKeys.length === 1) return HttpResponse.json({ title: "暂时失败", detail: "新会话未创建。" },
        { status: 503, headers: { "Content-Type": "application/problem+json" } });
      return persistConversation();
    }));
    mountCard();
    const user = userEvent.setup();
    await screen.findByRole("article", { name: "你" });
    await user.type(await readyComposer(), "留在原会话的草稿");
    await user.click(screen.getByRole("button", { name: "新建会话" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("新会话未创建。");
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("留在原会话的草稿");
    expect(screen.getByRole("article", { name: "你" })).toHaveTextContent(TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "新建会话" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(""));
    expect(screen.queryByRole("article", { name: "你" })).not.toBeInTheDocument();
    expect(createKeys[0]).toBeTruthy();
    expect(createKeys[1]).toBe(createKeys[0]);
    await user.type(await readyComposer(), "新会话草稿");
    await user.click(screen.getByRole("button", { name: "会话列表" }));
    await user.click(await screen.findByRole("button", { name: /当前创作会话/ }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("留在原会话的草稿"));
    expect(await screen.findByRole("article", { name: "你" })).toHaveTextContent(TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "会话列表" }));
    await user.click(within(screen.getByRole("region", { name: "会话列表" })).getByRole("button", { name: /新会话/ }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("新会话草稿"));
    cleanup();
    mountCard();
    expect(await screen.findByText("从一个想法开始")).toBeInTheDocument();
    expect(screen.queryByRole("article", { name: "你" })).not.toBeInTheDocument();
    expect(currentConversationId).toBe("conversation-new");
  });

  it("keeps a switched conversation and its draft when a previous conversation's run returns late", async () => {
    let started = false;
    let releaseResponse: (() => void) | undefined;
    const responseGate = new Promise<void>((resolve) => { releaseResponse = resolve; });
    server.use(
      http.post(`${RUNS_URL}/preflight`, () => HttpResponse.json(PREFLIGHT)),
      http.post(RUNS_URL, async () => { started = true; await responseGate; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await waitFor(() => expect(started).toBe(true));
    await user.click(screen.getByRole("button", { name: "新建会话" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(""));
    await user.type(await readyComposer(), "另一会话的全新想法");
    if (!releaseResponse) throw new Error("Missing delayed response gate");
    releaseResponse();
    await waitFor(() => expect(screen.getByRole("button", { name: "发送" })).toBeEnabled());
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue("另一会话的全新想法");
    expect(screen.queryByRole("article", { name: "你" })).not.toBeInTheDocument();
    expect(currentConversationId).toBe("conversation-new");
  });

  it("returns to the active run's conversation so its progress and stop control stay reachable", async () => {
    const active = run({ status: "RUNNING" });
    const selected = conversation({ id: "conversation-other", title: "另一会话" });
    storedConversations = [selected, conversation()]; currentConversationId = selected.id;
    storedRuns = [active];
    mountCard(active);
    const user = userEvent.setup();
    expect(await screen.findByRole("button", { name: "返回运行会话" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "发送" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "返回运行会话" }));
    expect(await screen.findByRole("region", { name: "任务对话" })).toHaveTextContent(TASK_TEXT);
    expect(screen.getByRole("button", { name: "停止" })).toBeEnabled();
    expect(screen.queryByRole("button", { name: "返回运行会话" })).not.toBeInTheDocument();
  });
});

describe("Agent Skill submission",()=>{
  const skillId="skill-example",skillVersionId="skill-version-example";
  const version={id:skillVersionId,skillId,versionNumber:1,name:"warm-art",description:"Synthetic style guide",bundleHash:"synthetic-bundle",skillMd:"# Creative method",outputKinds:["IMAGE"],inputSlots:[],resources:[],assets:[],createdAt:NOW};
  it("does not inject a saved default Skill without an explicit choice",async()=>{
    const requests:CreateRunRequest[]=[];const preflights:unknown[]=[];
    server.use(http.get(`/api/v1/projects/${PROJECT_ID}/agents/${AGENT_ID}/skill-binding`,()=>HttpResponse.json({agentVersion:AGENT_VERSION,skillId,skillVersionId})),
      http.get(`/api/v1/skills/${skillId}/versions/${skillVersionId}`,()=>HttpResponse.json(version)),
      http.post(`${RUNS_URL}/preflight`,async({request})=>{preflights.push(await request.json());return HttpResponse.json(PREFLIGHT);}),
      http.post(RUNS_URL,async({request})=>{requests.push(await request.json() as CreateRunRequest);return persistRun();}));
    mountCard();const user=userEvent.setup();await readyComposer();
    const trigger=screen.getByRole("button",{name:"选择 Skill"});
    expect(trigger.closest(".agent-chat-composer-actions")).not.toBeNull();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.type(screen.getByRole("textbox",{name:"本次任务"}),"创建一段文字");await user.click(screen.getByRole("button",{name:"发送"}));
    await waitFor(()=>expect(requests).toHaveLength(1));expect(preflights).toEqual([expect.objectContaining({skillSelection:{mode:"NONE",inputs:[]}})]);
    await waitFor(()=>expect(requests).toHaveLength(1));expect(requests[0]?.skillSelection).toEqual({mode:"NONE",inputs:[]});
  });
  it("selects through the modal without submitting and sends the explicit selection",async()=>{
    const preflights:Array<{skillSelection:unknown}>=[];const requests:CreateRunRequest[]=[];
    server.use(
      http.get("/api/v1/skills",()=>HttpResponse.json({items:[{id:skillId,title:"温暖手绘",description:"Synthetic guide",currentVersionId:skillVersionId,trashed:false}],nextCursor:null,total:1})),
      http.get(`/api/v1/skills/${skillId}/versions`,()=>HttpResponse.json([version])),
      http.get(`/api/v1/skills/${skillId}/versions/${skillVersionId}`,()=>HttpResponse.json(version)),
      http.post(`${RUNS_URL}/preflight`,async({request})=>{const input=await request.json() as {skillSelection:{mode:string}};preflights.push(input);
        return HttpResponse.json({...PREFLIGHT,creativeSkill:input.skillSelection.mode==="VERSION"
          ? {skillId,skillVersionId,title:"温暖手绘",versionNumber:1,bundleHash:version.bundleHash,inputSlots:[],resources:[],assets:[],installed:true}:null});}),
      http.post(RUNS_URL,async({request})=>{requests.push(await request.json() as CreateRunRequest);return persistRun();}));
    mountCard();const user=userEvent.setup();await user.type(await readyComposer(),"创建一段文字");
    await user.click(screen.getByRole("button",{name:"选择 Skill"}));const dialog=await screen.findByRole("dialog");
    await user.click(await within(dialog).findByRole("button",{name:"温暖手绘"}));
    await waitFor(()=>expect(within(dialog).getByRole("button",{name:"使用 Skill"})).toBeEnabled());
    await user.click(within(dialog).getByRole("button",{name:"使用 Skill"}));
    expect(preflights).toHaveLength(0);expect(requests).toHaveLength(0);
    await user.click(screen.getByRole("button",{name:"发送"}));
    await waitFor(()=>expect(requests).toHaveLength(1));
    await waitFor(()=>expect(screen.getByRole("textbox",{name:"本次任务"})).toHaveValue(""));
    expect(preflights[0]?.skillSelection).toEqual({mode:"VERSION",skillId,skillVersionId,inputs:[]});
    expect(requests[0]?.skillSelection).toEqual({mode:"VERSION",skillId,skillVersionId,inputs:[]});
    await user.click(screen.getByRole("button",{name:"选择 Skill"}));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button",{name:"本次不使用 Skill"}));
    expect(screen.queryByRole("button",{name:"确认开始"})).not.toBeInTheDocument();
    await user.type(screen.getByRole("textbox",{name:"本次任务"}),"新的文字任务");
    await user.click(screen.getByRole("button",{name:"发送"}));
    await waitFor(()=>expect(requests).toHaveLength(2));expect(requests[1]?.skillSelection).toEqual({mode:"NONE",inputs:[]});
  });
  it("saves a default binding through its independent endpoint without editing Agent instructions",async()=>{
    let current={agentVersion:AGENT_VERSION,skillId:null as string|null,skillVersionId:null as string|null};const bindings:unknown[]=[];const onUpdateAgent=vi.fn();
    server.use(http.get(`/api/v1/projects/${PROJECT_ID}/agents/${AGENT_ID}/skill-binding`,()=>HttpResponse.json(current)),
      http.get("/api/v1/skills",()=>HttpResponse.json({items:[{id:skillId,title:"温暖手绘",description:"",currentVersionId:skillVersionId,trashed:false,version:1,createdAt:NOW,updatedAt:NOW}],nextCursor:null,total:1})),
      http.get(`/api/v1/skills/${skillId}/versions`,()=>HttpResponse.json([{id:skillVersionId,skillId,versionNumber:1,name:"warm-art",description:"Synthetic guide",bundleHash:"synthetic",createdAt:NOW}])),
      http.get(`/api/v1/skills/${skillId}/versions/${skillVersionId}`,()=>HttpResponse.json(version)),
      http.put(`/api/v1/projects/${PROJECT_ID}/agents/${AGENT_ID}/skill-binding`,async({request})=>{bindings.push(await request.json());current={agentVersion:AGENT_VERSION+1,skillId,skillVersionId};return HttpResponse.json(current);}));
    mountCard(null,{onUpdateAgent});const user=userEvent.setup();await readyComposer();await user.click(screen.getByRole("button",{name:"Agent 设置"}));
    await user.click(screen.getByRole("combobox",{name:"选择 Skill"}));await user.click(await screen.findByRole("option",{name:"温暖手绘"}));
    const versions=screen.getByRole("combobox",{name:"发布版本"});await waitFor(()=>expect(versions).toBeEnabled());await user.click(versions);await user.click(await screen.findByRole("option",{name:"版本 1"}));
    await user.click(screen.getByRole("button",{name:"保存默认绑定"}));await waitFor(()=>expect(bindings).toHaveLength(1));expect(bindings[0]).toEqual({expectedAgentVersion:AGENT_VERSION,skillId,skillVersionId});expect(onUpdateAgent).not.toHaveBeenCalled();
  });
});
