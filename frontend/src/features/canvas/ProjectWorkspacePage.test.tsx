import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter,Route,Routes } from "react-router";
import { beforeEach,describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Agent,CanvasCommand,CanvasItem,ProjectSnapshot } from "../../shared/api/client";
import { changeControl } from "../../test/controls";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";
import { useCanvasStore } from "./canvasStore";

async function openLocalUpload(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByText("Reference project");
  const pane = screen.getByLabelText("项目画布").querySelector(".react-flow__pane")!;
  fireEvent.contextMenu(pane, { clientX: 235, clientY: 165 });
  const input = screen.getByLabelText("上传") as HTMLInputElement;
  const picker = vi.spyOn(input, "click");
  await user.click(screen.getByRole("menuitem", { name: "上传" }));
  expect(picker).toHaveBeenCalledOnce();
  picker.mockRestore();
  expect(document.querySelector(".workspace-drawer")).toBeNull();
  return input;
}

const imageVersionId = "11111111-1111-4111-8111-111111111111";

/** One persisted image artifact with an archived UPLOAD version. */
function imageCard(): CanvasItem {
  const now = "2026-09-24T00:00:00Z";
  return {
    id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id",
    title: "Hero",
    x: 20, y: 20, width: 320, height: 240, zIndex: 0, groupId: null,
    locked: false, selectedVersionId: imageVersionId, version: 0, agent: null,
    artifact: {
      id: "image-id", projectId: "project-1", kind: "IMAGE", title: "Hero",
      resourceDefaultVersionId: imageVersionId, version: 3, createdAt: now, updatedAt: now,
      resourceDefaultVersion: {
        id: imageVersionId, versionNo: 1, schemaVersion: 1,
        content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
        createdByKind: "USER", runId: null, createdAt: now,
      },
    },
    selectedVersion: {
      id: imageVersionId, versionNo: 1, schemaVersion: 1,
      content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
      createdByKind: "USER", runId: null, createdAt: now,
    },
  };
}

function textCard(): CanvasItem {
  const item = imageCard();
  return { ...item, id: "text-card", subjectId: "text-id", title: "Notes", width: 280, height: 180,
    artifact: item.artifact ? { ...item.artifact, id: "text-id", kind: "TEXT", title: "Notes",
      resourceDefaultVersionId: "text-v2", resourceDefaultVersion: { ...item.artifact.resourceDefaultVersion!, id: "text-v2",
        content: { format: "PLAIN_TEXT", text: "直接在节点里写" }, inputReferences: [] } } : null };
}

describe("ProjectWorkspacePage", () => {
  beforeEach(() => {
    useCanvasStore.setState({ selectedIds: [], drafts: {}, saveState: "saved" });
    server.use(
      http.get("/api/v1/projects/:projectId/assets/:assetId", ({ params }) => HttpResponse.json({
        id: params.assetId, width: 1024, height: 1024,
      })),
      http.get("/api/v1/projects/:projectId/agents/:agentId/conversations", ({ params }) => HttpResponse.json({
        items: [{ id: `conversation-${params.agentId}`, projectId: params.projectId, agentInstanceId: params.agentId,
          title: "之前的创作", version: 0, turnCount: 0, createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z" }],
        currentConversationId: `conversation-${params.agentId}`, nextCursor: null,
      })),
      http.get("/api/v1/projects/:projectId/agents/:agentId/conversations/:conversationId/runs", () => HttpResponse.json({ items: [], nextCursor: null })),
      http.get("/api/v1/projects/:projectId/runs", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/runs/:runId/actions", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/runs/:runId/plans", () => HttpResponse.json([])),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        connections: [{ id: "mock", name: "Mock", platform: "MOCK", enabled: true,
          version: 0, connectionVersion: 1, origin: null, keyMask: null,
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
          capabilities: [{ id: "mock-image", name: "Mock 图片", enabled: true,
            version: 0, capabilityVersion: 1, adapterId: "MOCK_IMAGE",
            kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
            mappingSha256: "a".repeat(64), settings: {} }] }],
        defaults: [{ kind: "IMAGE_GENERATION", capabilityId: "mock-image", version: 0 }],
      })),
      http.get("/api/v1/projects/:projectId/artifacts", () => HttpResponse.json({ items: [], nextCursor: null })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: false, version: 0 })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({ llmMode: "MOCK" })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/run", () =>
        HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", ({ params }) =>
        HttpResponse.json({ projectId: params.projectId, canvasItemId: params.canvasItemId,
          prompt: "", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [],
          durationSeconds: null, capabilityId: null,
          displayMode: "RESULT", version: 0, createdAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:00Z" })),
      http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/snapshot", ({ params }) =>
        HttpResponse.json({
        project: {
          id: params.projectId,
          name: "Workspace snapshot",
          aspectRatio: "LANDSCAPE_16_9",
          status: "ACTIVE",
          version: 0,
          createdAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:00Z",
          archivedAt: null,
        },
        canvas: { items: [] },
        agents: [],
        activeRun: null,
        activeTasks: [],
        unknownTasks: [],
        snapshotSeq: 0,
        }),
      ),
    );
  });

  it("dismisses a failed media creation notice and shows a later failure again", async () => {
    let attempts = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({ id: "project-1", name: "Feedback project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
      http.post("/api/v1/projects/:projectId/artifacts", () => {
        attempts++;
        return HttpResponse.json({ code: "ARTIFACT_CREATE_FAILED", title: "合成创建错误", detail: "合成创建错误", retryable: false }, { status: 422, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);
    await screen.findByText("Feedback project");
    for (let attempt = 1; attempt <= 2; attempt++) {
      await user.click(screen.getByRole("button", { name: "添加卡片" }));
      await user.click(screen.getByRole("option", { name: "图片" }));
      const notice = (await screen.findByText("合成创建错误")).closest('[role="alert"]') as HTMLElement;
      expect(within(notice).getByText("创建卡片 · 图片")).toBeVisible();
      await user.click(within(notice).getByRole("button", { name: "关闭提示" }));
      expect(screen.queryByText("合成创建错误")).not.toBeInTheDocument();
      expect(attempts).toBe(attempt);
    }
  });

  it("names and locates the audio node responsible for a failed Agent conversation", async () => {
    const source = imageCard();
    const audio: CanvasItem = { ...source, id: "audio-card", title: "Voice", artifact: { ...source.artifact!, kind: "AUDIO", title: "Voice" } };
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({ id: "project-1", name: "Feedback project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [audio] })),
      http.get("/api/v1/projects/:projectId/agents", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/projects/project-1/assets/asset-id/content", () => HttpResponse.error()),
      http.post("/api/v1/projects/:projectId/agents", () => HttpResponse.json({ code: "AGENT_CREATE_FAILED", title: "合成对话错误", detail: "合成对话错误", retryable: false }, { status: 422, headers: { "Content-Type": "application/problem+json" } })),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);
    fireEvent.click(await screen.findByRole("article", { name: "Voice · 音频" }));
    await user.click(await screen.findByRole("button", { name: "Agent 对话" }));
    const notice = (await screen.findByText("合成对话错误")).closest('[role="alert"]') as HTMLElement;
    expect(within(notice).getByText("Agent 对话 · Voice")).toBeVisible();
    fireEvent.click(screen.getByLabelText("项目画布").querySelector(".react-flow__pane")!);
    await user.click(within(notice).getByRole("button", { name: "定位节点" }));
    expect(useCanvasStore.getState().selectedIds).toEqual([audio.id]);
    await user.click(within(notice).getByRole("button", { name: "关闭提示" }));
    expect(screen.queryByText("合成对话错误")).not.toBeInTheDocument();
  });

  it("opens an idle audio conversation bound to the exact selected version and reuses it", async () => {
    const source = imageCard();
    const audio: CanvasItem = { ...source, id: "audio-card", title: "Voice",
      artifact: { ...source.artifact!, kind: "AUDIO", title: "Voice" } };
    let items = [audio];
    let agents: Agent[] = [];
    let creates = 0;
    let runs = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({ id: "project-1", name: "Audio project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
      http.get("/api/v1/projects/:projectId/agents", () => HttpResponse.json({ items: agents })),
      // Audio retrieval can fail independently without blocking exact-version conversations.
      http.get("/api/v1/projects/project-1/assets/asset-id/content", () => HttpResponse.error()),
      http.post("/api/v1/projects/:projectId/agents", async ({ request }) => {
        creates++;
        const input = await request.json() as { name: string; instruction: string; bindings: { artifactId: string; selectedVersionId: string }[] };
        expect(input.bindings).toEqual([{ artifactId: audio.artifact!.id, selectedVersionId: imageVersionId }]);
        expect(input.instruction).toContain("不代表你已听到");
        const now = "2026-09-30T00:00:00Z";
        const agent: Agent = { ...input, id: "audio-agent", projectId: "project-1", profileKey: "creator", profileVersion: 1,
          outputGroupId: "audio-output", version: 0, createdAt: now, updatedAt: now,
          bindings: input.bindings.map((binding) => ({ ...binding, id: "audio-binding" })) };
        agents = [agent];
        return HttpResponse.json(agent, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: CanvasCommand[] };
        const command = body.commands[0];
        if (!command || command.type !== "PLACE_AGENT") throw new Error("Expected idle Agent placement");
        items = [...items, { id: command.itemId, subjectType: "AGENT", subjectId: command.agentId,
          title: agents[0]!.name, x: command.x, y: command.y, width: command.width, height: command.height,
          zIndex: command.zIndex, groupId: null, locked: false, version: 0, selectedVersionId: null, selectedVersion: null,
          artifact: null, agent: agents[0]! }];
        return HttpResponse.json({ items });
      }),
      http.post("/api/v1/projects/:projectId/runs", () => { runs++; return HttpResponse.json({}); }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);
    fireEvent.click(await screen.findByRole("article", { name: "Voice · 音频" }));
    expect(await screen.findByText(/波形暂不可用/)).toBeVisible();
    await user.click(await screen.findByRole("button", { name: "Agent 对话" }));
    expect(await screen.findByRole("heading", { name: "Voice · 对话" })).toBeVisible();
    expect(creates).toBe(1);
    expect(runs).toBe(0);
    fireEvent.click(screen.getByRole("article", { name: "Voice · 音频" }));
    await user.click(await screen.findByRole("button", { name: "Agent 对话" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "Agent 对话" })).not.toBeInTheDocument());
    expect(creates).toBe(1);
    expect(items).toHaveLength(2);
    expect(runs).toBe(0);
  });

  it.each(["doubleClick", "contextMenu"] as const)("creates and selects empty text at the %s menu's canvas position without a drawer", async (gesture) => {
    const original = textCard();
    const blank: CanvasItem = { ...original, title: "新文字", selectedVersionId: null, selectedVersion: null,
      artifact: { ...original.artifact!, title: "新文字", version: 0,
        resourceDefaultVersion: { ...original.artifact!.resourceDefaultVersion!, versionNo: 1,
          content: { format: "PLAIN_TEXT", text: "" } } } };
    let items: CanvasItem[] = [];
    const creates: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", () =>
        HttpResponse.json({ id: "project-1", name: "Text project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        creates.push(await request.json());
        return HttpResponse.json(blank.artifact, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: CanvasCommand[] };
        const command = body.commands[0];
        expect(command).toMatchObject({ type: "PLACE_ARTIFACT", artifactId: "text-id",
          x: 235, y: 165, width: 280, height: 180 });
        if (!command || command.type !== "PLACE_ARTIFACT") throw new Error("Missing placement");
        items = [{ ...blank, id: command.itemId, x: command.x, y: command.y }];
        return HttpResponse.json({ items });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);
    await screen.findByText("Text project");
    const pane = screen.getByLabelText("项目画布").querySelector(".react-flow__pane")!;
    if (gesture === "doubleClick") {
      fireEvent.doubleClick(pane, { clientX: 235, clientY: 165 });
      await user.click(screen.getByRole("option", { name: "文字" }));
    } else {
      fireEvent.contextMenu(pane, { clientX: 235, clientY: 165 });
      await user.click(screen.getByRole("menuitem", { name: "添加" }));
      expect(screen.getByRole("menuitem", { name: "图片" })).toBeVisible();
      expect(screen.getByRole("menuitem", { name: "视频" })).toBeVisible();
      expect(screen.getByRole("menuitem", { name: "音频" })).toBeVisible();
      expect(screen.queryByRole("menuitem", { name: "Agent" })).not.toBeInTheDocument();
      // jsdom has no Popper geometry for the pointer corridor between parent and submenu.
      await user.keyboard("{ArrowRight}{ArrowDown}{ArrowDown}{Enter}");
    }
    expect(await screen.findByRole("article", { name: "新文字 · 文字" })).toHaveClass("is-selected");
    expect(creates).toEqual([{ kind: "TEXT", title: "新文字",
      content: { format: "PLAIN_TEXT", text: "" } }]);
    expect(screen.queryByRole("complementary", { name: "创建与工具" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "添加到画布" })).not.toBeInTheDocument();
    const prompt = screen.getByRole("textbox", { name: "文字生成提示词" });
    await user.type(prompt, "写一段开场白");
    await waitFor(() => expect(screen.getByRole("button", { name: "生成文字" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    const content = screen.getByRole("textbox", { name: "内容" });
    expect(content).toHaveValue("");
    await user.type(content, "直接在节点里填写");
    expect(screen.getByRole("button", { name: "保存新版本" })).toBeEnabled();
  });

  it("creates an empty image card from the canvas menu and autosaves its bottom draft", async () => {
    const artifactId = crypto.randomUUID();
    const itemId = crypto.randomUUID();
    let prompt = "";
    let draftVersion = 0;
    let created = 0;
    let submittedDraftVersion: number | null = null;
    const now = "2026-09-25T00:00:00Z";
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", ({ params }) => HttpResponse.json({
        id: params.projectId, name: "Draft project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        expect(await request.json()).toEqual({
          kind: "IMAGE", title: "新图片", content: null,
        });
        created++;
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ itemId: string }> };
        expect(body.commands[0]?.itemId).toBeTruthy();
        return HttpResponse.json({ items: [{
          id: itemId, subjectType: "ARTIFACT", subjectId: artifactId,
          title: "新图片",
          x: 80, y: 80, width: 280, height: 240, zIndex: 0, groupId: null,
          locked: false, selectedVersionId: null, selectedVersion: null, version: 0, agent: null,
          artifact: { id: artifactId, projectId: "project-1", kind: "IMAGE",
            title: "新图片", resourceDefaultVersionId: null, resourceDefaultVersion: null,
            version: 0, createdAt: now, updatedAt: now },
        }] });
      }),
      http.get("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", ({ params }) =>
        HttpResponse.json({ projectId: "project-1", canvasItemId: params.canvasItemId, prompt,
          parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [],
          durationSeconds: null, capabilityId: null,
          version: draftVersion, createdAt: now, updatedAt: now })),
      http.put("/api/v1/projects/:projectId/canvas-items/:canvasItemId/media-draft", async ({ request, params }) => {
        const body = await request.json() as { expectedVersion: number; prompt: string };
        expect(body.expectedVersion).toBe(draftVersion);
        prompt = body.prompt;
        draftVersion++;
        return HttpResponse.json({ projectId: "project-1", canvasItemId: params.canvasItemId, prompt,
          parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [],
          durationSeconds: null, capabilityId: null,
          version: draftVersion, createdAt: now, updatedAt: now });
      }),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/run", async ({ request }) => {
        const body = await request.json() as { expectedDraftVersion: number };
        submittedDraftVersion = body.expectedDraftVersion;
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        return HttpResponse.json({ id: "direct-task", status: "READY" });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);

    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    expect(screen.getAllByRole("option")).toHaveLength(5);
    expect(screen.getByRole("option", { name: "文字" })).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("option")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    await user.click(screen.getByRole("option", { name: "图片" }));
    await waitFor(() => expect(created).toBe(1));
    fireEvent.click(await screen.findByText("新图片"));
    const editor = await screen.findByLabelText("图片提示词");
    await user.type(editor, "A red kite over a lake");
    await waitFor(() => expect(prompt).toBe("A red kite over a lake"), { timeout: 3000 });
    expect(screen.getAllByText("已保存").length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(submittedDraftVersion).toBe(1));
  });

  it.each([
    { count: 6, failBatch: 0 }, { count: 6, failBatch: 1 }, { count: 101, failBatch: 2 },
  ])("arranges $count connected cards and retains unsaved drafts on batch $failBatch failure", async ({ count, failBatch }) => {
    let items = Array.from({ length: count }, (_, index): CanvasItem => ({
      ...textCard(), id: `card-${index}`, title: `Note ${index}`, x: count - index, y: count - index,
      version: 7, locked: false,
    }));
    const batches: CanvasCommand[][] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({ id: "project-1", name: "Arrange project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
      http.get("/api/v1/projects/:projectId/canvas/connections", () => HttpResponse.json({ items:
        items.slice(1).map((item, index) => ({ id: `edge-${index}`, sourceCanvasItemId: `card-${index}`,
          targetCanvasItemId: item.id, sourceArtifactVersionId: "text-v2", relationType: "MEDIA_DERIVATION" })) })),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const { commands } = await request.json() as { commands: CanvasCommand[] };
        batches.push(commands);
        if (batches.length === failBatch) return HttpResponse.json({ code: "CANVAS_VERSION_CONFLICT", title: "合成布局冲突", detail: "合成布局冲突" },
          { status: 409, headers: { "Content-Type": "application/problem+json" } });
        items = items.map((item) => {
          const command = commands.find((candidate) => candidate.itemId === item.id);
          return command?.type === "UPDATE_LAYOUT" ? { ...item, ...command, version: item.version + 1 } : item;
        });
        return HttpResponse.json({ items });
      }),
    );
    const user = userEvent.setup();
    const client = createQueryClient();
    render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);
    await screen.findByText("Arrange project");
    const pane = screen.getByLabelText("项目画布").querySelector(".react-flow__pane")!;
    fireEvent.contextMenu(pane, { clientX: 300, clientY: 200 });
    await waitFor(() => expect(screen.getByRole("menuitem", { name: "一键整理" })).not.toHaveAttribute("aria-disabled"));
    await user.click(screen.getByRole("menuitem", { name: "一键整理" }));
    await waitFor(() => expect(useCanvasStore.getState().saveState).toBe(failBatch ? "conflict" : "saved"));
    await waitFor(() => expect(batches.length).toBe(Math.ceil(count / 100)));
    const commands = batches.flat();
    const first = commands.find((command) => command.itemId === "card-0");
    const fifth = commands.find((command) => command.itemId === "card-4");
    const sixth = commands.find((command) => command.itemId === "card-5");
    expect(first).toMatchObject({ type: "UPDATE_LAYOUT", expectedVersion: 7, x: 1, y: 1, width: 280, height: 180 });
    if (!first || first.type !== "UPDATE_LAYOUT" || !fifth || fifth.type !== "UPDATE_LAYOUT" || !sixth || sixth.type !== "UPDATE_LAYOUT")
      throw new Error("Missing layout commands");
    expect(fifth.y).toBe(first.y);
    expect(fifth.x).toBeGreaterThan(first.x);
    expect(sixth.x).toBe(first.x);
    expect(sixth.y).toBeGreaterThan(first.y + first.height);
    if (failBatch) {
      expect(Object.keys(useCanvasStore.getState().drafts)).toHaveLength(failBatch === 2 ? 1 : count);
      expect(await screen.findByText("合成布局冲突")).toBeVisible();
      if (failBatch === 2) {
        expect(client.getQueryData<{ items: CanvasItem[] }>(["canvas", "project-1"])?.items.find((item) => item.id === "card-0")?.version).toBe(8);
        expect(useCanvasStore.getState().drafts["card-100"]).toBeDefined();
      }
    } else {
      expect(useCanvasStore.getState().drafts).toEqual({});
      expect(client.getQueryData<{ items: CanvasItem[] }>(["canvas", "project-1"])?.items[0]?.version).toBe(8);
    }
  }, 15000);

  it("closes the creation menu on an outside click and on Escape without focus inside it", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", ({ params }) => HttpResponse.json({
        id: params.projectId, name: "Menu project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] })),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);

    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    expect(screen.getAllByRole("option")).toHaveLength(5);
    await user.click(screen.getByLabelText("项目画布"));
    expect(screen.queryAllByRole("option")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    expect(screen.getAllByRole("option")).toHaveLength(5);
    screen.getByRole("option", { name: "文字" }).blur();
    await user.keyboard("{Escape}");
    expect(screen.queryAllByRole("option")).toHaveLength(0);
  });

  it("keeps the selected image when Escape closes its model menu and protects smart edit input", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({ id: "project-1", name: "Smart edit project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [imageCard()] })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [{
        id: "image-provider", name: "Image provider", platform: "OPENAI", enabled: true, version: 0,
        capabilities: [{ id: "image-model", name: "Image model", enabled: true, version: 0,
          capabilityVersion: 1, adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION",
          minimumSeconds: 0, maximumSeconds: 0, maxReferenceImages: 4, supportsImageMask: true,
          supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"],
          supportedImageQualities: ["high"], settings: {} }],
      }], defaults: [] })),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter></QueryClientProvider>);
    fireEvent.click(await screen.findByRole("article", { name: "Hero · 图片" }));
    await user.click(await screen.findByRole("button", { name: "智能编辑" }));
    await user.type(screen.getByRole("textbox", { name: "智能编辑提示词" }), "保留这段要求");
    await user.click(screen.getByRole("combobox", { name: "图片能力" }));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(useCanvasStore.getState().selectedIds).toEqual(["image-card"]);
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留这段要求");
    await user.keyboard("{Escape}");
    const confirmation = await screen.findByRole("dialog", { name: "有未保存的修改" });
    await user.click(within(confirmation).getByRole("button", { name: "继续编辑" }));
    expect(screen.getByRole("textbox", { name: "智能编辑提示词" })).toHaveValue("保留这段要求");
    expect(useCanvasStore.getState().selectedIds).toEqual(["image-card"]);
  });

  it("closes the bottom editor from its close button and from Escape", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
        id: "project-1", name: "Close project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [textCard()] })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([])),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    fireEvent.doubleClick(await screen.findByRole("article", { name: "Notes · 文字" }));
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("文字生成提示词")).toHaveFocus());
    screen.getByLabelText("文字生成提示词").blur();
    await user.keyboard("{Escape}");
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("article", { name: "Notes · 文字" }));
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "关闭编辑区" }));
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
  });

  it("uses the lower prompt for model generation and the toolbar for direct output editing", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
        id: "project-1", name: "Text project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [textCard()] })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({ configured: true,
        version: 2, endpoint: "https://model.example/v1", modelId: "text-model",
        keyMask: "****", toolCallingVerified: false, updatedAt: "2026-09-27T00:00:00Z" })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-27T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/text-generations", () =>
        HttpResponse.json([])),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByRole("article", { name: "Notes · 文字" }));
    expect(screen.getByRole("region", { name: "文字正文" })).toHaveTextContent("直接在节点里写");
    expect(screen.getByLabelText("所选卡片编辑区")).toBeInTheDocument();
    expect(screen.getByLabelText("文字生成提示词")).toBeInTheDocument();
    expect(await screen.findByText("text-model")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "编辑内容" }));
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("直接在节点里写");
  });

  it("keeps one SSE connection across snapshots and invalidates auxiliary views after a gap", async () => {
    const sources: TrackingEventSource[] = [];
    let snapshotReads = 0;
    class TrackingEventSource extends EventTarget {
      onopen: (() => void) | null = null;
      onerror: (() => void) | null = null;
      closed = false;

      constructor(url: string) {
        super();
        expect(url).toContain("/events?after=");
        sources.push(this);
      }

      close() { this.closed = true; }
    }
    vi.stubGlobal("EventSource", TrackingEventSource);
    try {
      server.use(
        http.get("/api/v1/auth/me", () =>
          HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
        http.get("/api/v1/projects/:projectId", ({ params }) =>
          HttpResponse.json({ id: params.projectId, name: "SSE project", status: "ACTIVE" })),
        http.get("/api/v1/projects/:projectId/canvas/items", () =>
          HttpResponse.json({ items: [] })),
        http.get("/api/v1/projects/:projectId/snapshot", ({ params }) => {
          snapshotReads++;
          return HttpResponse.json({
            project: { id: params.projectId, name: "SSE project", status: "ACTIVE" },
            canvas: { items: [] }, agents: [], activeRun: null, activeTasks: [],
            unknownTasks: [], snapshotSeq: 0,
          });
        }),
      );
      const queryClient = createQueryClient();
      const { unmount } = render(
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={["/projects/project-1"]}>
            <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
          </MemoryRouter>
        </QueryClientProvider>,
      );
      await waitFor(() => expect(sources).toHaveLength(1));
      const header = screen.getByRole("banner");
      expect(within(header).queryByText("创作画布")).not.toBeInTheDocument();
      expect(within(header).queryByRole("button", { name: "导入素材" })).not.toBeInTheDocument();
      expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
      expect(within(header).getByRole("link", { name: "项目" })).toHaveAttribute("href", "/projects");
      expect(within(header).getByRole("button", { name: "资源" })).toBeInTheDocument();
      sources[0]?.onopen?.();
      await waitFor(() => expect(within(header).queryByRole("status")).not.toBeInTheDocument());
      // 导出清单是普通下载链接：画布不再读取导出或导出提案接口。
      expect(screen.getByRole("link", { name: "导出清单" })).toHaveAttribute("href",
        "/api/v1/projects/project-1/export-manifest");
      const current = queryClient.getQueryData<ProjectSnapshot>(["snapshot", "project-1"]);
      expect(current).toBeDefined();
      queryClient.setQueryData(["snapshot", "project-1"], { ...current, snapshotSeq: 5 });
      await screen.findByText("SSE project");
      expect(sources).toHaveLength(1);
      expect(sources[0]?.closed).toBe(false);
      const auxiliaryKeys = [
        ["agent-conversations", "project-1", "agent-1"],
        ["conversation-runs", "project-1", "agent-1", "conversation-1"],
        ["run-history", "project-1", "agent-1"],
        ["run-history-tasks", "project-1", "run-1"],
        ["run-actions", "project-1", "run-1"],
        ["run-tasks", "project-1", "run-1"],
        ["direct-media-tasks", "project-1"],
        ["media-draft", "project-1"],
        ["project-usage", "project-1"],
      ];
      for (const key of auxiliaryKeys) queryClient.setQueryData(key, { staleView: true });
      sources[0]?.dispatchEvent(new MessageEvent("agent.conversation.changed", {
        data: JSON.stringify({
          projectId: "project-1", seq: 1, eventId: crypto.randomUUID(),
          type: "agent.conversation.changed", schemaVersion: 1,
          aggregateId: "conversation-1", aggregateVersion: 0,
          payload: { agentId: "agent-1", conversationId: "conversation-1", currentConversationId: "conversation-1" },
          occurredAt: "2026-09-24T00:00:00Z",
        }), lastEventId: "1",
      }));
      for (const key of auxiliaryKeys.slice(0, 2)) {
        await waitFor(() => expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true));
        queryClient.setQueryData(key, { staleView: true });
      }
      const selectedCardDraftKey = ["media-draft", "project-1", "card-1"];
      const selectedArtifactVersionsKey = ["artifact-versions", "project-1", "artifact-1"];
      queryClient.setQueryData(selectedCardDraftKey, { staleView: true });
      queryClient.setQueryData(selectedArtifactVersionsKey, { staleView: true });
      sources[0]?.dispatchEvent(new MessageEvent("canvas.item.selected_version.changed", {
        data: JSON.stringify({
          projectId: "project-1", seq: 2, eventId: crypto.randomUUID(),
          type: "canvas.item.selected_version.changed", schemaVersion: 1,
          aggregateId: "card-1", aggregateVersion: 4,
          payload: { canvasItemId: "card-1", artifactId: "artifact-1",
            selectedVersionId: "version-2" }, occurredAt: "2026-09-24T00:00:00Z",
        }),
        lastEventId: "2",
      }));
      await waitFor(() => expect(queryClient.getQueryState(selectedCardDraftKey)?.isInvalidated)
        .toBe(true));
      expect(queryClient.getQueryState(selectedArtifactVersionsKey)?.isInvalidated).toBe(true);
      expect(sources).toHaveLength(1);
      const readsBeforeGap = snapshotReads;
      sources[0]?.dispatchEvent(new MessageEvent("task.status.changed", {
        data: JSON.stringify({
          projectId: "project-1", seq: 7, eventId: crypto.randomUUID(),
          type: "task.status.changed", schemaVersion: 1,
          aggregateId: crypto.randomUUID(), aggregateVersion: 1,
          payload: { status: "SUCCEEDED" }, occurredAt: "2026-09-24T00:00:00Z",
        }),
        lastEventId: "7",
      }));
      await waitFor(() => expect(sources).toHaveLength(2));
      // 序号缺口必须重新取回一致性快照，而不是只重连。
      await waitFor(() => expect(snapshotReads).toBeGreaterThan(readsBeforeGap));
      expect(sources[0]?.closed).toBe(true);
      for (const key of auxiliaryKeys) {
        expect(queryClient.getQueryState(key)?.isInvalidated).toBe(true);
      }
      unmount();
      expect(sources[1]?.closed).toBe(true);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it.each([
    { kind: "IMAGE", endpoint: "assets", filename: "Product reference.webp", mime: "image/webp" },
    { kind: "AUDIO", endpoint: "assets/audio", filename: "Product reference.mp3", mime: "audio/mpeg" },
  ] as const)("uploads $kind directly and places an exact-version card", async ({ kind, endpoint, filename, mime }) => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    let uploaded = false;
    let created = false;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Reference project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        const body = await request.json() as { kind: string; title: string; content: unknown };
        expect(body).toMatchObject({ kind, title: "Product reference",
          content: { sourceType: "UPLOAD", assetId } });
        created = true;
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ artifactId: string }> };
        expect(body.commands[0]?.artifactId).toBe(artifactId);
        const selectedVersion = { id: versionId, versionNo: 1, schemaVersion: 1 as const,
          content: { sourceType: "UPLOAD" as const, assetId }, inputReferences: [],
          createdByKind: "USER" as const, runId: null, createdAt: "2026-09-23T00:00:00Z" };
        return HttpResponse.json({ items: [{
          id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
          title: "Product reference",
          x: 80, y: 80, width: 280, height: 240, zIndex: 0, groupId: null,
          locked: false, selectedVersionId: versionId, selectedVersion, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind,
            title: "Product reference", resourceDefaultVersionId: versionId, version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            resourceDefaultVersion: selectedVersion,
          },
        }] });
      }),
    );
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === `/api/v1/projects/project-1/${endpoint}`) {
        expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("test-token");
        expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
        expect(init?.body).toBeInstanceOf(FormData);
        expect((init?.body as FormData).get("file")).toHaveProperty("name", filename);
        uploaded = true;
        return HttpResponse.json({ id: assetId, mediaKind: kind }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const input = await openLocalUpload(user);
    fireEvent.change(input, { target: { files: [] } });
    expect(uploaded).toBe(false);
    expect(created).toBe(false);
    await user.upload(screen.getByLabelText("上传"),
      new File(["real bytes checked by backend"], filename, { type: mime }));
    const preview = kind === "IMAGE" ? await screen.findByAltText("Product reference 的预览")
      : await screen.findByLabelText("Product reference 的音频");
    expect(preview).toHaveAttribute("src", `/api/v1/projects/project-1/assets/${assetId}/content`);
    expect(preview.closest(".react-flow__node")).toHaveStyle({ visibility: "visible" });
    expect(uploaded).toBe(true);
    expect(created).toBe(true);
    expect(screen.getByLabelText("上传")).toHaveValue("");
  });

  it("uploads MP4 directly from the local picker and places a VIDEO card at the menu point", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    let uploaded = false;
    let created = false;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Reference project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        const body = await request.json() as { kind: string; title: string; content: unknown };
        expect(body).toMatchObject({ kind: "VIDEO", title: "Uploaded video",
          content: { sourceType: "UPLOAD", assetId } });
        created = true;
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ artifactId: string; x: number; y: number }> };
        expect(body.commands[0]?.artifactId).toBe(artifactId);
        expect(body.commands[0]).toMatchObject({ x: 235, y: 165 });
        const selectedVersion = { id: versionId, versionNo: 1, schemaVersion: 1 as const,
          content: { sourceType: "UPLOAD" as const, assetId }, inputReferences: [],
          createdByKind: "USER" as const, runId: null, createdAt: "2026-09-23T00:00:00Z" };
        return HttpResponse.json({ items: [{
          id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
          title: "Uploaded video",
          x: 80, y: 80, width: 280, height: 240, zIndex: 0, groupId: null,
          locked: false, selectedVersionId: versionId, selectedVersion, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "VIDEO",
            title: "Uploaded video", resourceDefaultVersionId: versionId, version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            resourceDefaultVersion: selectedVersion,
          },
        }] });
      }),
    );
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === "/api/v1/projects/project-1/assets/video") {
        expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("test-token");
        expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
        expect(init?.body).toBeInstanceOf(FormData);
        expect((init?.body as FormData).get("file")).toHaveProperty("name", "Uploaded video.mp4");
        uploaded = true;
        return HttpResponse.json({ id: assetId, mediaKind: "VIDEO" }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await openLocalUpload(user);
    await user.upload(screen.getByLabelText("上传"),
      new File(["real bytes checked by backend"], "Uploaded video.mp4", { type: "video/mp4" }));
    const poster = await screen.findByRole("img", { name: "Uploaded video 的视频封面" });
    expect(poster).toHaveAttribute("src", `/api/v1/projects/project-1/assets/${assetId}/thumbnail`);
    expect(document.querySelector("video")).toBeNull();
    expect(uploaded).toBe(true);
    expect(created).toBe(true);
    expect(screen.getByLabelText("上传")).toHaveValue("");
  });

  it("offers explicit retry after a rejected local upload", async () => {
    let creates = 0;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Reference project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", () => {
        creates++;
        return HttpResponse.json({}, { status: 201 });
      }),
    );
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === "/api/v1/projects/project-1/assets") {
        return HttpResponse.json({ title: "素材无效", detail: "图片解码失败。",
          code: "ASSET_INVALID_IMAGE", retryable: false },
        { status: 422, headers: { "Content-Type": "application/problem+json" } });
      }
      return interceptedFetch(input, init);
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await openLocalUpload(user);
    await user.upload(screen.getByLabelText("上传"),
      new File(["bad image"], "broken.webp", { type: "image/webp" }));
    expect(await screen.findByText("图片解码失败。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeEnabled();
    expect(creates).toBe(0);
  });

  it.each(["placementConflict", "artifactUncertain", "placementUncertain"])("reuses confirmed stages and identities after %s", async (stage) => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    let uploads = 0;
    let artifacts = 0;
    let placements = 0;
    let placedItemId: string | undefined;
    let createKey: string | null = null;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Reference project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", ({ request }) => {
        const key = request.headers.get("Idempotency-Key");
        expect(key).toBeTruthy();
        if (createKey) expect(key).toBe(createKey);
        createKey = key;
        artifacts++;
        if (stage === "artifactUncertain" && artifacts === 1) return HttpResponse.json({
          title: "保存失败", detail: "请求响应不确定。", code: "UPSTREAM_UNAVAILABLE", retryable: true,
        }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        placements++;
        const body = await request.json() as { commands: Array<{ itemId: string }> };
        expect(body.commands[0]).toMatchObject({ x: 235, y: 165 });
        if (placements === 1) placedItemId = body.commands[0]?.itemId;
        else expect(body.commands[0]?.itemId).toBe(placedItemId);
        if (stage !== "artifactUncertain" && placements === 1) return HttpResponse.json({ title: "冲突", detail: "画布版本冲突。",
          code: "CANVAS_CONFLICT", retryable: true },
        { status: stage === "placementUncertain" ? 503 : 409, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ items: [] });
      }),
    );
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === "/api/v1/projects/project-1/assets") {
        uploads++;
        return HttpResponse.json({ id: assetId, mediaKind: "IMAGE" }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await openLocalUpload(user);
    await user.upload(screen.getByLabelText("上传"),
      new File(["bytes"], "retry.webp", { type: "image/webp" }));

    expect(await screen.findByText(stage === "artifactUncertain" ? "请求响应不确定。" : "画布版本冲突。")).toBeInTheDocument();
    const pane = screen.getByLabelText("项目画布").querySelector(".react-flow__pane")!;
    fireEvent.contextMenu(pane, { clientX: 600, clientY: 400 });
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(placements).toBe(stage === "artifactUncertain" ? 1 : 2));
    expect(uploads).toBe(1);
    expect(artifacts).toBe(stage === "artifactUncertain" ? 2 : 1);
    expect(screen.getByLabelText("上传")).toHaveValue("");
  });

  it.each(["artifact", "placement"])("reuses the text creation intent after an uncertain %s response", async (stage) => {
    const artifactId = crypto.randomUUID();
    const keys: string[] = [];
    const placements: unknown[] = [];
    let placedItemId: string | undefined;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Text project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key") ?? "");
        if (stage === "artifact" && keys.length === 1) return HttpResponse.error();
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ itemId: string; artifactId: string }> };
        placements.push(body);
        if (stage === "placement" && placements.length === 1) return HttpResponse.error();
        placedItemId = body.commands[0]?.itemId;
        expect(body.commands[0]?.artifactId).toBe(artifactId);
        return HttpResponse.json({ items: [] });
      }),
    );
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    await user.click(screen.getByRole("option", { name: "文字" }));
    await screen.findByRole("alert");
    expect(screen.queryByRole("complementary")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "重试创建文字节点" }));
    await waitFor(() => expect(placedItemId).toBeTruthy());
    expect(keys).toHaveLength(stage === "artifact" ? 2 : 1);
    expect(keys[0]).toBeTruthy();
    if (stage === "artifact") expect(keys[1]).toBe(keys[0]);
    if (stage === "placement") expect(placements[1]).toEqual(placements[0]);
    expect(placedItemId).toBeTruthy();
  });

  it("keeps the card while Delete edits a focused text input", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({
          id: params.projectId,
          name: "Canvas test",
          aspectRatio: "LANDSCAPE_16_9",
          status: "ACTIVE",
          version: 0,
          createdAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:00Z",
          archivedAt: null,
        }),
      ),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({
          items: [
            {
              id: crypto.randomUUID(),
              subjectType: "ARTIFACT",
              subjectId: crypto.randomUUID(),
              title: "Existing card",
              x: 10,
              y: 10,
              width: 280,
              height: 180,
              zIndex: 0,
              groupId: null,
              locked: false, selectedVersionId: null, selectedVersion: null,
              version: 0,
              artifact: {
                id: crypto.randomUUID(),
                projectId: crypto.randomUUID(),
                kind: "TEXT",
                title: "Existing card",
                resourceDefaultVersionId: crypto.randomUUID(),
                version: 0,
                createdAt: "2026-09-23T00:00:00Z",
                updatedAt: "2026-09-23T00:00:00Z",
                resourceDefaultVersion: {
                  id: crypto.randomUUID(),
                  versionNo: 1,
                  schemaVersion: 1,
                  content: { format: "PLAIN_TEXT", text: "Persisted" },
                  inputReferences: [],
                  createdByKind: "USER",
                  runId: null,
                  createdAt: "2026-09-23T00:00:00Z",
                },
              },
              agent: null,
            },
          ],
        }),
      ),
    );
    const user = userEvent.setup();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes>
            <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByText("Existing card")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("article", { name: "Existing card · 文字" }));
    await user.click(screen.getByRole("button", { name: "编辑内容" }));
    const content = screen.getByRole("textbox", { name: "内容" });
    await user.type(content, "draft");
    await user.keyboard("{Delete}");

    expect(screen.getByText("Existing card")).toBeInTheDocument();
    expect(content).toHaveFocus();
  });

  it.each(["planned", "direct"])("keeps %s UNKNOWN records out of the canvas without a call log shortcut", async (source) => {
    let attemptReads = 0;
    let generationRequests = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId/snapshot", ({ params }) => HttpResponse.json({
        project: { id: params.projectId, name: "Unknown test", aspectRatio: "LANDSCAPE_16_9",
          status: "ACTIVE", version: 0, createdAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:00Z", archivedAt: null },
        canvas: { items: [] }, agents: [], activeRun: null, activeTasks: [],
        unknownTasks: [{ id: "task-1", kind: "IMAGE_GENERATION", stepKey: "image-2",
          planId: source === "planned" ? "plan-1" : null,
          runId: source === "planned" ? "run-1" : null,
          attemptNo: 1, status: "UNKNOWN", cancelRequested: false, version: 3,
          providerRequestId: null, errorCode: "PROVIDER_SUBMISSION_UNKNOWN",
          updatedAt: "2026-09-23T00:00:00Z", input: { privatePrompt: "not for UI" } }],
        snapshotSeq: 1,
      })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Unknown test" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () => {
        attemptReads += 1;
        return HttpResponse.json([]);
      }),
      http.post("/api/v1/projects/:projectId/tasks/:taskId/new-attempt", () => {
        generationRequests += 1;
        return HttpResponse.json({});
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);
    expect(await screen.findByRole("heading", { name: "Unknown test" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "调用日志" })).not.toBeInTheDocument();
    expect(screen.queryByText(/个任务的外部提交结果未知/)).not.toBeInTheDocument();
    expect(screen.queryByText("查看待核对任务")).not.toBeInTheDocument();
    expect(screen.queryByText(/not for UI/)).not.toBeInTheDocument();
    expect(attemptReads).toBe(0);
    expect(generationRequests).toBe(0);
  });

  it.each(["WAITING_TASKS", "BLOCKED"])(
    "does not show an external %s notice when the Agent is not on the canvas", async (status) => {
      let detailReads = 0;
      server.use(
        http.get("/api/v1/auth/me", () => HttpResponse.json({
          id: "admin", loginName: "admin", role: "ADMIN",
        })),
        http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({
          project: { id: "project-1", name: "Hidden Agent", status: "ACTIVE" },
          canvas: { items: [] }, agents: [],
          activeRun: { id: "run-1", agentInstanceId: "hidden-agent", status },
          activeTasks: [], unknownTasks: [], snapshotSeq: 1,
        })),
        http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
          id: "project-1", name: "Hidden Agent", status: "ACTIVE",
        })),
        http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
        http.get("/api/v1/projects/:projectId/runs/:runId/tasks", () => {
          detailReads++;
          return HttpResponse.json([]);
        }),
        http.get("/api/v1/projects/:projectId/runs/:runId/actions", () => {
          detailReads++;
          return HttpResponse.json([]);
        }),
      );
      render(<QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>);

      await screen.findByRole("heading", { name: "Hidden Agent" });
      for (const label of ["运行已阻断", "任务对话"]) {
        expect(screen.queryByRole("region", { name: label })).not.toBeInTheDocument();
      }
      expect(screen.queryByRole("link", { name: "调用日志" })).not.toBeInTheDocument();
      expect(detailReads).toBe(0);
    },
  );

  it("restores the existing Agent from resources with its original conversation", async () => {
    const now = "2026-09-26T00:00:00Z";
    const agent: Agent = { id: "original-agent", projectId: "project-1", profileKey: "creator",
      profileVersion: 1, name: "Original creator", instruction: "Keep the existing conversation",
      outputGroupId: "original-output", version: 0, createdAt: now, updatedAt: now, bindings: [] };
    const activeRun = { id: "original-run", agentInstanceId: agent.id, status: "RUNNING",
      conversationId: `conversation-${agent.id}`, conversationTurn: 1,
      instruction: "Create an image of the garden", createdAt: now };
    let items: CanvasItem[] = [];
    let placedCommand: CanvasCommand | undefined;
    let createdAgents = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: "admin", loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({
        project: { id: "project-1", name: "Original project", status: "ACTIVE" },
        canvas: { items }, agents: [agent], activeRun, activeTasks: [], unknownTasks: [], snapshotSeq: 1,
      })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
        id: "project-1", name: "Original project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items })),
      http.post("/api/v1/projects/:projectId/agents", () => {
        createdAgents++;
        return HttpResponse.json(agent, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: CanvasCommand[] };
        placedCommand = body.commands[0];
        expect(placedCommand).toMatchObject({ type: "PLACE_AGENT", agentId: agent.id,
          width: 460, height: 600 });
        items = [{ id: placedCommand!.itemId, subjectType: "AGENT", subjectId: agent.id,
          title: agent.name,
          x: 80, y: 80, width: 460, height: 600, zIndex: 0, groupId: null,
          locked: false, selectedVersionId: null, selectedVersion: null, version: 0, artifact: null, agent }];
        return HttpResponse.json({ items });
      }),
      http.get("/api/v1/projects/:projectId/runs/:runId/tasks", ({ params }) => {
        expect(params.runId).toBe(activeRun.id);
        return HttpResponse.json([]);
      }),
      http.get("/api/v1/projects/:projectId/runs/:runId/actions", () => HttpResponse.json([])),
    );
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);
    await screen.findByRole("heading", { name: "Original project" });
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "资源" }));
    const resource = (await screen.findByText("Original creator · Agent")).closest("li");
    expect(resource).not.toBeNull();
    await user.click(within(resource!).getByRole("button", { name: "放回画布" }));

    const conversation = await screen.findByRole("region", { name: "任务对话" });
    expect(conversation.closest(".agent-chat-card")).toBeInTheDocument();
    expect(conversation).toHaveTextContent(activeRun.instruction);
    expect(screen.getByRole("button", { name: "停止" })).toBeEnabled();
    expect(screen.queryByRole("link", { name: "调用日志" })).not.toBeInTheDocument();
    expect(screen.queryByRole("complementary", { name: "项目资源" })).not.toBeInTheDocument();
    expect(placedCommand).toMatchObject({ type: "PLACE_AGENT", agentId: agent.id });
    expect(createdAgents).toBe(0);
  });

  it("shows exact Agent bindings and saves editable card configuration", async () => {
    const agentId = crypto.randomUUID();
    const itemId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const outputGroupId = crypto.randomUUID();
    const bindingId = crypto.randomUUID();
    let updated = false;
    let activeRun: { id: string; agentInstanceId: string; status: string; conversationId: string; conversationTurn: number; instruction: string; createdAt: string } | null = null;
    let starts = 0;
    let modelAvailable = false;
    const idempotencyKeys: string[] = [];
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({
          id: params.projectId,
          name: "Agent canvas",
          aspectRatio: "LANDSCAPE_16_9",
          status: "ACTIVE",
          version: 0,
          createdAt: "2026-09-23T00:00:00Z",
          updatedAt: "2026-09-23T00:00:00Z",
          archivedAt: null,
        }),
      ),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" }),
      ),
      http.post("/api/v1/projects/:projectId/runs/preflight", async ({ request }) => {
        expect(await request.json()).toMatchObject({agentId,skillSelection:{mode:"NONE",inputs:[]}});
        return HttpResponse.json({
          agentId, agentVersion: updated ? 1 : 0, agentName: updated ? "Agent Beta" : "Agent Alpha",
          conversationId: `conversation-${agentId}`, conversationVersion: 0,
          conversationTurnCount: 0, inheritedBindingCount: 0, memoryTruncated: false,
          agentInstruction: updated ? "Updated instruction" : "Initial instruction",
          bindings: [{ artifactId, selectedVersionId: versionId,
            artifactTitle: "Bound brief", artifactKind: "TEXT" }],
          modelAvailable, providerAdapter: modelAvailable ? "FakeChatModel" : null,
          modelId: modelAvailable ? "fixture-model" : null,
          toolCalling: true,
          policySnapshot: { schemaVersion: 2, systemPromptVersion: 2, modelConfigSource: "fixture",
            modelConfigVersion: 7, maxModelTurns: 12, maxToolExecutions: 40 },
        });
      }),
      http.get("/api/v1/projects/:projectId/snapshot", ({ params }) =>
        HttpResponse.json({
          project: { id: params.projectId, name: "Agent canvas", status: "ACTIVE" },
          canvas: { items: [] }, agents: [], activeRun, activeTasks: [], unknownTasks: [],
          snapshotSeq: 0,
        }),
      ),
      http.post("/api/v1/projects/:projectId/runs", async ({ request }) => {
        const body = (await request.json()) as {
          agentId: string; instruction: string; expectedAgentVersion: number;
          expectedModelConfigSource: string; expectedModelConfigVersion: number;
          expectedSystemPromptVersion: number;
          selectedItemIds: string[];
        };
        expect(body).toMatchObject({ agentId, instruction: "规划三个镜头",
          conversationId: `conversation-${agentId}`, expectedConversationVersion: 0,
          expectedAgentVersion: 1, expectedModelConfigSource: "fixture",
          expectedModelConfigVersion: 7, expectedSystemPromptVersion: 2 });
        expect(Array.isArray(body.selectedItemIds)).toBe(true);
        idempotencyKeys.push(request.headers.get("Idempotency-Key") ?? "");
        starts += 1;
        if (starts === 1) return new HttpResponse(null, { status: 503 });
        if (starts === 2) return HttpResponse.json({
          code: "SYSTEM_PROMPT_CONFLICT", title: "系统提示词已变化",
          detail: "请重新检查运行范围。", retryable: false,
        }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
        activeRun = { id: crypto.randomUUID(), agentInstanceId: agentId, status: "RUNNING",
          conversationId: `conversation-${agentId}`, conversationTurn: 1,
          instruction: "规划三个镜头", createdAt: "2026-09-23T00:00:00Z" };
        return HttpResponse.json(activeRun, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/runs/:runId/cancel", () => {
        const canceled = { ...activeRun, status: "CANCEL_REQUESTED" };
        activeRun = null;
        return HttpResponse.json(canceled);
      }),
      http.get("/api/v1/projects/:projectId/runs", ({ request }) => {
        expect(new URL(request.url).searchParams.get("agentId")).toBe(agentId);
        return HttpResponse.json({ items: [{
          id: "historical-run", agentInstanceId: agentId, status: "CANCELED",
          instruction: "之前的创作", createdAt: "2026-09-22T10:00:00Z",
          updatedAt: "2026-09-22T10:01:00Z", completedAt: "2026-09-22T10:01:00Z",
        }], nextCursor: null });
      }),
      http.patch("/api/v1/projects/:projectId/agents/:agentId", async ({ request }) => {
        const body = (await request.json()) as { name: string; instruction: string };
        updated = body.name === "Agent Beta" && body.instruction === "Updated instruction";
        return HttpResponse.json(agent(updated));
      }),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({
          items: [
            {
              id: itemId,
              subjectType: "AGENT",
              subjectId: agentId,
              title: "Agent Alpha",
              x: 10,
              y: 10,
              width: 340,
              height: 320,
              zIndex: 0,
              groupId: null,
              locked: false, selectedVersionId: null, selectedVersion: null,
              version: 0,
              artifact: null,
              agent: agent(updated),
            },
          ],
        }),
      ),
    );
    function agent(isUpdated: boolean) {
      return {
        id: agentId,
        projectId: "project-1",
        profileKey: "creator",
        profileVersion: 1,
        name: isUpdated ? "Agent Beta" : "Agent Alpha",
        instruction: isUpdated ? "Updated instruction" : "Initial instruction",
        outputGroupId,
        version: isUpdated ? 1 : 0,
        createdAt: "2026-09-23T00:00:00Z",
        updatedAt: "2026-09-23T00:00:00Z",
        bindings: [
          {
            id: bindingId,
            artifactId,
            selectedVersionId: versionId,
          },
        ],
      };
    }
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes>
            <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const heading = await screen.findByText("Agent Alpha");
    const card = heading.closest("article");
    expect(card).not.toBeNull();
    expect(card).toHaveClass("agent-chat-card");
    fireEvent.click(within(card as HTMLElement).getByRole("button", { name: "Agent 设置" }));
    expect(within(card as HTMLElement).getByText(/明确输入（1）/)).toBeInTheDocument();
    expect(within(card as HTMLElement).getByText(new RegExp(artifactId))).toBeInTheDocument();
    expect(within(card as HTMLElement).getByText(new RegExp(versionId))).toBeInTheDocument();
    fireEvent.click(heading);
    const editorArea = heading.closest("article") as HTMLElement;
    const name = within(editorArea).getByDisplayValue("Agent Alpha");
    const instruction = within(editorArea).getByDisplayValue("Initial instruction");
    await changeControl(name, { target: { value: "Agent Beta" } });
    await changeControl(instruction, { target: { value: "Updated instruction" } });
    fireEvent.submit(name.closest("form") as HTMLFormElement);

    expect(await screen.findByText("Agent Beta")).toBeInTheDocument();
    expect(updated).toBe(true);

    const taskInput = within(editorArea).getByLabelText("本次任务");
    await changeControl(taskInput, { target: { value: "规划三个镜头" } });
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    expect(await within(editorArea).findByText("确认开始")).toBeInTheDocument();
    expect(within(editorArea).getByText("确认开始")).toBeDisabled();
    expect(starts).toBe(0);
    modelAvailable = true;
    await changeControl(taskInput, { target: { value: "规划三个镜头！" } });
    await changeControl(taskInput, { target: { value: "规划三个镜头" } });
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    await waitFor(() => expect(within(editorArea).getByText("确认开始")).not.toBeDisabled());
    expect(within(editorArea).getByText(/首轮只发送有上限的内容预览/)).toBeInTheDocument();
    expect(within(editorArea).getByText(/当前模型看不到图片像素、视频帧或音频/)).toBeInTheDocument();
    expect(within(editorArea).getByText(/Bound brief/)).toBeInTheDocument();
    fireEvent.click(within(editorArea).getByText("确认开始"));
    await waitFor(() => expect(starts).toBe(1));
    await waitFor(() => expect(within(editorArea).getByText("确认开始")).not.toBeDisabled());
    fireEvent.click(within(editorArea).getByText("确认开始"));
    await waitFor(() => expect(starts).toBe(2));
    expect(idempotencyKeys[0]).toBeTruthy();
    expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
    await waitFor(() => expect(within(editorArea).queryByText("确认开始")).not.toBeInTheDocument());
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    await waitFor(() => expect(within(editorArea).getByText("确认开始")).not.toBeDisabled());
    fireEvent.click(within(editorArea).getByText("确认开始"));
    await waitFor(() => expect(starts).toBe(3));
    expect(idempotencyKeys[2]).not.toBe(idempotencyKeys[1]);
    expect(await within(editorArea).findByRole("button", { name: "停止" })).toBeEnabled();
    fireEvent.click(within(editorArea).getByRole("button", { name: "停止" }));
    await waitFor(() => expect(screen.getByText("准备就绪")).toBeInTheDocument());
    fireEvent.click(within(card as HTMLElement).getByRole("button", { name: "会话列表" }));
    expect(await within(card as HTMLElement).findByRole("button", { name: /之前的创作/ })).toBeInTheDocument();
  });

  it("loads the archived original for a generated image card", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const selectedVersion = {
      id: versionId, versionNo: 1, schemaVersion: 1,
      content: { assetId, prompt: "Ridge sunrise",
        workflowVersion: "mock-image-v1", sourceTaskId: crypto.randomUUID(),
        parameters: { mock: true, displayLabel: "演示素材" } },
      inputReferences: [], createdByKind: "TASK", runId: crypto.randomUUID(),
      createdAt: "2026-09-23T00:00:00Z",
    } as const;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Image canvas", status: "ACTIVE" }),
      ),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [{
          id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
          title: "Demo still",
          x: 10, y: 10, width: 300, height: 300, zIndex: 0, groupId: crypto.randomUUID(),
          locked: false, selectedVersionId: versionId, selectedVersion, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "IMAGE",
            title: "Demo still", resourceDefaultVersionId: versionId, version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            resourceDefaultVersion: selectedVersion,
          },
        }] }),
      ),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // React Flow marks unmeasured nodes visibility:hidden in jsdom; the image still exists in the DOM.
    const preview = await screen.findByAltText("Demo still 的预览");
    expect(preview).toHaveAttribute("src",
      `/api/v1/projects/project-1/assets/${assetId}/content`);
    expect(screen.getByLabelText("放大图片")).toBeInTheDocument();
    expect(screen.getByText("演示素材")).toBeInTheDocument();
    fireEvent.error(preview);
    expect(screen.getByText("预览暂不可用")).toBeInTheDocument();
  });

  it("keeps generated video on its poster until the user chooses playback", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    const selectedVersion = {
      id: versionId, versionNo: 1, schemaVersion: 1,
      content: { assetId, prompt: "Coffee",
        workflowVersion: "mock-video-v1", sourceTaskId: crypto.randomUUID(),
        parameters: { mock: true } },
      inputReferences: [], createdByKind: "TASK", runId: crypto.randomUUID(),
      createdAt: "2026-09-23T00:00:00Z",
    } as const;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Video canvas", status: "ACTIVE" }),
      ),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [{
          id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
          title: "Demo clip",
          x: 10, y: 10, width: 300, height: 300, zIndex: 0, groupId: null,
          locked: false, selectedVersionId: versionId, selectedVersion, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "VIDEO",
            title: "Demo clip", resourceDefaultVersionId: versionId, version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            resourceDefaultVersion: selectedVersion,
          },
        }] }),
      ),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByAltText("Demo clip 的视频封面")).toHaveAttribute("src",
      `/api/v1/projects/project-1/assets/${assetId}/thumbnail`);
    expect(screen.queryByLabelText("Demo clip 的视频")).not.toBeInTheDocument();
    fireEvent.click(screen.getByText("播放视频"));
    expect(screen.getByLabelText("Demo clip 的视频")).toHaveAttribute("src",
      `/api/v1/projects/project-1/assets/${assetId}/content`);
    expect(screen.getByText("演示视频")).toBeInTheDocument();
  });
});
