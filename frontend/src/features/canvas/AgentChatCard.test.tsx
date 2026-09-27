import { QueryClientProvider } from "@tanstack/react-query";
import { ReactFlowProvider } from "@xyflow/react";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent, AgentRun, AgentConversation, CreateRunRequest, RunPreflight } from "../../shared/api/client";
import { server } from "../../test/server";
import { AgentChatCard, type AgentChatCardData } from "./AgentChatCard";
import { useCanvasStore } from "./canvasStore";

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
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
  );
});
afterEach(() => {
  useCanvasStore.getState().setSelectedIds([]);
  vi.restoreAllMocks();
});

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
        return persistRun();
      }),
    );
    useCanvasStore.getState().setSelectedIds(["selected-image-1"]);
    mountCard();
    expect(screen.getByRole("textbox", { name: "本次任务" })).toBeDisabled();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));

    const confirmation = await screen.findByRole("button", { name: "确认开始" });
    expect(confirmation).toBeEnabled();
    expect(checks).toBe(1);
    expect(requests).toHaveLength(0);
    expect(screen.getByRole("article", { name: "待发送" })).toHaveTextContent(TASK_TEXT);
    await user.click(confirmation);

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toEqual({ agentId: AGENT_ID, conversationId: CONVERSATION_ID, expectedConversationVersion: 0, instruction: TASK_TEXT,
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
    expect(await screen.findByRole("button", { name: "确认开始" })).toBeEnabled();
    expect(checks).toBe(1);
    expect(creations).toBe(0);
  });

  it("blocks sending and keyboard submission while a different Agent owns the active run", async () => {
    let checks = 0;
    let creations = 0;
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => { checks++; return HttpResponse.json(PREFLIGHT); }),
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
    await user.click(screen.getByRole("button", { name: "加载更早的消息" }));
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    expect(screen.getAllByRole("article", { name: "你" })[0]).toHaveTextContent(historical.instruction);
    expect(screen.getAllByRole("article", { name: "你" })[1]).toHaveTextContent(current.instruction);
    expect(body.scrollTop).toBe(140);
    expect(screen.queryByText(/独立任务/)).not.toBeInTheDocument();
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
      http.post(RUNS_URL, () => { creations++; return persistRun(); }),
    );
    const client = mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("button", { name: "确认开始" })).toBeEnabled();

    await user.clear(input);
    await user.type(input, "改为规划夜景镜头。");
    await user.click(screen.getByRole("button", { name: "发送" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("运行前配置当前不可用。");
    expect(client.getQueryData(["run-preflight", PROJECT_ID, AGENT_ID, AGENT_VERSION, CONVERSATION_ID])).toEqual(PREFLIGHT);
    const review = screen.getByRole("region", { name: "运行前确认" });
    expect(within(review).queryByRole("button", { name: "确认开始" })).not.toBeInTheDocument();
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
        return persistRun();
      }),
    );
    mountCard();
    const user = userEvent.setup();
    const input = await readyComposer();
    await user.type(input, TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认开始" }));
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
      http.get(`${RUNS_URL}/preflight`, ({ request }) => {
        operations.push("preflight");
        expect(new URL(request.url).searchParams.get("conversationId")).toBe("conversation-new");
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
    await screen.findByRole("button", { name: "确认开始" });
    expect(operations).toEqual(["create-conversation", "preflight"]);
    expect(screen.getByRole("textbox", { name: "本次任务" })).toHaveValue(TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "确认开始" }));
    await waitFor(() => expect(operations).toEqual(["create-conversation", "preflight", "create-run"]));
  });

  it("continues the same conversation and restores both persisted messages after remount", async () => {
    const first = run({ id: "first-run", status: "SUCCEEDED", instruction: "先规划晨光镜头。" });
    storedRuns = [first];
    storedConversations = [conversation({ version: 1, turnCount: 1 })];
    server.use(
      http.get(`${RUNS_URL}/preflight`, () => HttpResponse.json({ ...PREFLIGHT,
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
    expect(await screen.findByText(/本会话已有 1 轮消息，将继承 2 个精确素材绑定/)).toBeInTheDocument();
    expect(screen.getByText("保留早期背景和最近交流，部分历史未纳入本轮；完整记录仍可查看。")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "确认开始" }));
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    cleanup();
    mountCard();
    await waitFor(() => expect(screen.getAllByRole("article", { name: "你" })).toHaveLength(2));
    const messages = screen.getAllByRole("article", { name: "你" });
    expect(messages[0]).toHaveTextContent(first.instruction);
    expect(messages[1]).toHaveTextContent("把刚才的场景改成黄昏。");
    expect(screen.getByText("当前创作会话")).toBeInTheDocument();
  });

  it("does not restore an obsolete confirmation when the first conversation is created after the draft changes", async () => {
    storedConversations = []; currentConversationId = null;
    let creating = false;
    let checked = false;
    let releaseCreation: (() => void) | undefined;
    const creationGate = new Promise<void>((resolve) => { releaseCreation = resolve; });
    server.use(
      http.post(CONVERSATIONS_URL, async () => {
        creating = true; await creationGate; return persistConversation();
      }),
      http.get(`${RUNS_URL}/preflight`, () => {
        checked = true; return HttpResponse.json({ ...PREFLIGHT, conversationId: "conversation-new" });
      }),
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
    expect(await screen.findByRole("button", { name: "确认开始" })).toBeEnabled();
    expect(screen.getByRole("article", { name: "待发送" })).toHaveTextContent("等待期间改成新的想法");
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
      http.get(`${RUNS_URL}/preflight`, () => HttpResponse.json(PREFLIGHT)),
      http.post(RUNS_URL, async () => { started = true; await responseGate; return persistRun(); }),
    );
    mountCard();
    const user = userEvent.setup();
    await user.type(await readyComposer(), TASK_TEXT);
    await user.click(screen.getByRole("button", { name: "发送" }));
    await user.click(await screen.findByRole("button", { name: "确认开始" }));
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
