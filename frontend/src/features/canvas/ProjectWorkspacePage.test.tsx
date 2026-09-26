import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";
import { useCanvasStore } from "./canvasStore";
import type { CanvasItem, ProjectSnapshot } from "../../shared/api/client";

const imageVersionId = "11111111-1111-4111-8111-111111111111";

/** One persisted character with an optional exact-version reference. */
function referenceCard(linked: boolean): CanvasItem {
  const now = "2026-09-24T00:00:00Z";
  return {
    id: "character-card", subjectType: "ARTIFACT", subjectId: "character-id",
    x: 20, y: 20, width: 320, height: 240, zIndex: 0, groupId: null,
    locked: false, version: 0, agent: null,
    artifact: {
      id: "character-id", projectId: "project-1", kind: "CHARACTER", title: "Hero",
      currentVersionId: linked ? "character-v1" : "character-v2",
      version: linked ? 3 : 4, createdAt: now, updatedAt: now,
      currentVersion: {
        id: linked ? "character-v1" : "character-v2", versionNo: linked ? 1 : 2,
        schemaVersion: 1, content: { name: "Hero", description: "Lead",
          appearance: "Blue coat", referenceVersionIds: linked ? [imageVersionId] : [] },
        inputReferences: linked ? [{ versionId: imageVersionId, role: "referenceImage",
          order: 0, kind: "IMAGE" }] : [],
        createdByKind: "USER", runId: null, createdAt: now,
      },
    },
  };
}

describe("ProjectWorkspacePage", () => {
  beforeEach(() => {
    useCanvasStore.setState({ selectedIds: [] });
    server.use(
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
            maxConcurrent: 2, mappingSha256: "a".repeat(64), settings: {} }] }],
        defaults: [{ kind: "IMAGE_GENERATION", capabilityId: "mock-image", version: 0 }],
      })),
      http.get("/api/v1/projects/:projectId/artifacts", () => HttpResponse.json({ items: [], nextCursor: null })),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/run", () =>
        HttpResponse.json([])),
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
          x: 80, y: 80, width: 280, height: 240, zIndex: 0, groupId: null,
          locked: false, version: 0, agent: null,
          artifact: { id: artifactId, projectId: "project-1", kind: "IMAGE",
            title: "新图片", currentVersionId: null, currentVersion: null,
            version: 0, createdAt: now, updatedAt: now },
        }] });
      }),
      http.get("/api/v1/projects/:projectId/artifacts/:artifactId/draft", () =>
        HttpResponse.json({ projectId: "project-1", artifactId, prompt,
          inputImageVersionId: null, durationSeconds: null, capabilityId: null,
          version: draftVersion, createdAt: now, updatedAt: now })),
      http.put("/api/v1/projects/:projectId/artifacts/:artifactId/draft", async ({ request }) => {
        const body = await request.json() as { expectedVersion: number; prompt: string };
        expect(body.expectedVersion).toBe(draftVersion);
        prompt = body.prompt;
        draftVersion++;
        return HttpResponse.json({ projectId: "project-1", artifactId, prompt,
          inputImageVersionId: null, durationSeconds: null, capabilityId: null,
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
    expect(screen.getAllByRole("menuitem")).toHaveLength(7);
    expect(screen.getByRole("menuitem", { name: "文字" })).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menuitem")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    await user.click(screen.getByRole("menuitem", { name: "图片" }));
    await waitFor(() => expect(created).toBe(1));
    fireEvent.click(await screen.findByText("新图片"));
    const editor = await screen.findByLabelText("图片提示词");
    await user.type(editor, "A red kite over a lake");
    await waitFor(() => expect(prompt).toBe("A red kite over a lake"), { timeout: 3000 });
    expect(screen.getAllByText("已保存").length).toBeGreaterThan(0);
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(submittedDraftVersion).toBe(1));
  });

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
    expect(screen.getAllByRole("menuitem")).toHaveLength(7);
    await user.click(screen.getByLabelText("项目画布"));
    expect(screen.queryAllByRole("menuitem")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    expect(screen.getAllByRole("menuitem")).toHaveLength(7);
    screen.getByRole("menuitem", { name: "文字" }).blur();
    await user.keyboard("{Escape}");
    expect(screen.queryAllByRole("menuitem")).toHaveLength(0);
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
        HttpResponse.json({ items: [referenceCard(true)] })),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const user = userEvent.setup();
    fireEvent.doubleClick(await screen.findByRole("article", { name: "Hero · 角色" }));
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("名称")).toHaveFocus());
    screen.getByLabelText("名称").blur();
    await user.keyboard("{Escape}");
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("article", { name: "Hero · 角色" }));
    expect(await screen.findByLabelText("所选卡片编辑区")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "关闭编辑区" }));
    expect(screen.queryByLabelText("所选卡片编辑区")).not.toBeInTheDocument();
  });

  it("keeps one SSE connection across snapshots and invalidates auxiliary views after a gap", async () => {
    const sources: TrackingEventSource[] = [];
    let proposalReads = 0;
    let exportReads = 0;
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
        http.get("/api/v1/projects/:projectId/export-proposals", () => {
          proposalReads++;
          return HttpResponse.json([]);
        }),
        http.get("/api/v1/projects/:projectId/exports", () => {
          exportReads++;
          return HttpResponse.json([]);
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
      await userEvent.setup().click(screen.getByRole("button", { name: "导出" }));
      await waitFor(() => expect([proposalReads, exportReads]).toEqual([1, 1]));
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
        ["run-history-plans", "project-1", "run-1"],
        ["run-history-tasks", "project-1", "run-1"],
        ["provider-attempts", "project-1", "task-1"],
        ["plans", "project-1", "run-1"],
        ["run-tasks", "project-1", "run-1"],
        ["keyframe-selection", "project-1", "run-1"],
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
      expect(sources).toHaveLength(1);
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
      await waitFor(() => expect([proposalReads, exportReads]).toEqual([2, 2]));
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

  it("uploads a reference image and places an exact-version IMAGE card", async () => {
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
        expect(body).toMatchObject({ kind: "IMAGE", title: "Product reference",
          content: { sourceType: "UPLOAD", assetId } });
        created = true;
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ artifactId: string }> };
        expect(body.commands[0]?.artifactId).toBe(artifactId);
        return HttpResponse.json({ items: [{
          id: crypto.randomUUID(), subjectType: "ARTIFACT", subjectId: artifactId,
          x: 80, y: 80, width: 280, height: 240, zIndex: 0, groupId: null,
          locked: false, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "IMAGE",
            title: "Product reference", currentVersionId: versionId, version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            currentVersion: { id: versionId, versionNo: 1, schemaVersion: 1,
              content: { sourceType: "UPLOAD", assetId }, inputReferences: [],
              createdByKind: "USER", runId: null, createdAt: "2026-09-23T00:00:00Z" },
          },
        }] });
      }),
    );
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === "/api/v1/projects/project-1/assets") {
        expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("test-token");
        expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
        expect(init?.body).toBeInstanceOf(FormData);
        expect((init?.body as FormData).get("file")).toHaveProperty("name", "reference.webp");
        uploaded = true;
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

    await user.click(screen.getByRole("button", { name: "导入素材" }));
    await user.type(screen.getByLabelText("图片标题"), "Product reference");
    await user.upload(screen.getByLabelText("参考图片"),
      new File(["real bytes checked by backend"], "reference.webp", { type: "image/webp" }));
    expect((screen.getByLabelText("参考图片") as HTMLInputElement).files).toHaveLength(1);
    fireEvent.submit(screen.getByRole("button", { name: "上传并放到画布" }).closest("form")!);
    const preview = await screen.findByAltText("Product reference 的预览");
    expect(preview).toHaveAttribute("src", `/api/v1/projects/project-1/assets/${assetId}/thumbnail`);
    expect(preview.closest(".react-flow__node")).toHaveStyle({ visibility: "visible" });
    expect(uploaded).toBe(true);
    expect(created).toBe(true);
    expect(screen.getByLabelText("图片标题")).toHaveValue("");
  });

  it("keeps the chosen reference file after a rejected upload", async () => {
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
    await user.click(screen.getByRole("button", { name: "导入素材" }));
    await user.type(screen.getByLabelText("图片标题"), "Broken reference");
    await user.upload(screen.getByLabelText("参考图片"),
      new File(["bad image"], "broken.webp", { type: "image/webp" }));
    expect((screen.getByLabelText("参考图片") as HTMLInputElement).files).toHaveLength(1);
    fireEvent.submit(screen.getByRole("button", { name: "上传并放到画布" }).closest("form")!);
    expect(await screen.findByText("图片解码失败。")).toBeInTheDocument();
    expect(screen.getByLabelText("图片标题")).toHaveValue("Broken reference");
    expect((screen.getByLabelText("参考图片") as HTMLInputElement).files?.[0]?.name)
      .toBe("broken.webp");
    expect(creates).toBe(0);
  });

  it("reuses the confirmed upload and artifact when canvas placement is retried", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
    let uploads = 0;
    let artifacts = 0;
    let placements = 0;
    let placedItemId: string | undefined;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Reference project", status: "ACTIVE" })),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [] })),
      http.get("/api/v1/auth/csrf", () =>
        HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/projects/:projectId/artifacts", ({ request }) => {
        expect(request.headers.get("Idempotency-Key")).toBeTruthy();
        artifacts++;
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        placements++;
        const body = await request.json() as { commands: Array<{ itemId: string }> };
        if (placements === 1) placedItemId = body.commands[0]?.itemId;
        else expect(body.commands[0]?.itemId).toBe(placedItemId);
        if (placements === 1) return HttpResponse.json({ title: "冲突", detail: "画布版本冲突。",
          code: "CANVAS_CONFLICT", retryable: true },
        { status: 409, headers: { "Content-Type": "application/problem+json" } });
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
    await user.click(screen.getByRole("button", { name: "导入素材" }));
    await user.type(screen.getByLabelText("图片标题"), "Retry reference");
    await user.upload(screen.getByLabelText("参考图片"),
      new File(["bytes"], "retry.webp", { type: "image/webp" }));
    const form = screen.getByRole("button", { name: "上传并放到画布" }).closest("form")!;
    fireEvent.submit(form);
    expect(await screen.findByText("画布版本冲突。")).toBeInTheDocument();
    expect(screen.getByText(/图片和产物已创建，但画布放置未完成/)).toBeInTheDocument();
    fireEvent.submit(form);
    await waitFor(() => expect(placements).toBe(2));
    expect(uploads).toBe(1);
    expect(artifacts).toBe(1);
    expect(screen.getByLabelText("图片标题")).toHaveValue("");
  });

  it("reuses the text creation key after an uncertain response", async () => {
    const artifactId = crypto.randomUUID();
    const keys: string[] = [];
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
        if (keys.length === 1) return HttpResponse.error();
        return HttpResponse.json({ id: artifactId }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        const body = await request.json() as { commands: Array<{ itemId: string; artifactId: string }> };
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
    await user.click(screen.getByRole("menuitem", { name: "文字" }));
    const form = screen.getByRole("button", { name: "添加到画布" }).closest("form")!;
    await user.type(within(form).getByLabelText("标题"), "Retry note");
    await user.type(within(form).getByLabelText("内容"), "A stable note");
    fireEvent.submit(form);
    await screen.findByRole("alert");
    expect(within(form).getByLabelText("标题")).toHaveValue("Retry note");
    fireEvent.submit(form);
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
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
              x: 10,
              y: 10,
              width: 280,
              height: 180,
              zIndex: 0,
              groupId: null,
              locked: false,
              version: 0,
              artifact: {
                id: crypto.randomUUID(),
                projectId: crypto.randomUUID(),
                kind: "TEXT",
                title: "Existing card",
                currentVersionId: crypto.randomUUID(),
                version: 0,
                createdAt: "2026-09-23T00:00:00Z",
                updatedAt: "2026-09-23T00:00:00Z",
                currentVersion: {
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
    await user.click(screen.getByRole("button", { name: "添加卡片" }));
    await user.click(screen.getByRole("menuitem", { name: "文字" }));
    const content = within(screen.getByRole("complementary")).getByLabelText("内容");
    await user.type(content, "draft");
    await user.keyboard("{Delete}");

    expect(screen.getByText("Existing card")).toBeInTheDocument();
    expect(content).toHaveFocus();
  });

  it("warns that UNKNOWN submissions may have external cost and will not retry automatically", async () => {
    let attemptReads = 0;
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId/snapshot", ({ params }) =>
        HttpResponse.json({
          project: { id: params.projectId, name: "Unknown test", aspectRatio: "LANDSCAPE_16_9",
            status: "ACTIVE", version: 0, createdAt: "2026-09-23T00:00:00Z",
            updatedAt: "2026-09-23T00:00:00Z", archivedAt: null },
          canvas: { items: [] }, agents: [], activeRun: null, activeTasks: [],
          unknownTasks: [{ id: "task-1", kind: "IMAGE_GENERATION", stepKey: "image-2",
            attemptNo: 1, status: "UNKNOWN", cancelRequested: true,
            providerRequestId: null, errorCode: "PROVIDER_SUBMISSION_UNKNOWN",
            updatedAt: "2026-09-23T00:00:00Z", input: { privatePrompt: "not for UI" } }],
          snapshotSeq: 1,
        }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Unknown test" }),
      ),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] }),
      ),
      http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () => {
        attemptReads += 1;
        return HttpResponse.json([{ id: "attempt-1", taskId: "task-1", status: "UNKNOWN",
          requestKey: "e6422a3f-c91d-4874-b684-118fd6be068a", reconcilable: false,
          providerRequestId: null,
          createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z" }]);
      }),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("可能已开始执行并产生费用");
    expect(screen.getByRole("alert")).toHaveTextContent("不会自动重复提交");
    expect(screen.getByRole("alert")).toHaveTextContent("任务 ID：task-1");
    expect(screen.getByRole("alert")).toHaveTextContent("原 Provider 请求 ID 未保存");
    expect(screen.getByRole("alert")).not.toHaveTextContent("not for UI");
    expect(attemptReads).toBe(0);
    const user = userEvent.setup();
    await user.click(screen.getByText("查看待核对任务"));
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    expect(await screen.findByText(/提交关联键：e6422a3f/)).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("不能证明请求已受理");
    expect(screen.getByRole("alert")).not.toHaveTextContent("not for UI");
    expect(attemptReads).toBe(1);
  });

  /** 直接媒体任务没有 planId，重试入口只能靠 kind 判定；漏判时按钮不渲染，用户无从重跑。 */
  it("offers a manual retry for a direct media task with no plan", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ id: crypto.randomUUID(), loginName: "admin", role: "ADMIN" }),
      ),
      http.get("/api/v1/projects/:projectId/snapshot", ({ params }) =>
        HttpResponse.json({
          project: { id: params.projectId, name: "Direct retry", aspectRatio: "LANDSCAPE_16_9",
            status: "ACTIVE", version: 0, createdAt: "2026-09-23T00:00:00Z",
            updatedAt: "2026-09-23T00:00:00Z", archivedAt: null },
          canvas: { items: [] }, agents: [], activeRun: null, activeTasks: [],
          unknownTasks: [{ id: "task-direct", kind: "IMAGE_GENERATION", stepKey: "draft-1",
            attemptNo: 1, status: "UNKNOWN", cancelRequested: false, planId: null, runId: null,
            providerRequestId: null, errorCode: "PROVIDER_SUBMISSION_UNKNOWN", version: 3,
            updatedAt: "2026-09-23T00:00:00Z", input: {} }],
          snapshotSeq: 1,
        }),
      ),
      http.get("/api/v1/projects/:projectId", ({ params }) =>
        HttpResponse.json({ id: params.projectId, name: "Direct retry" }),
      ),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [] }),
      ),
      http.get("/api/v1/projects/:projectId/tasks/:taskId/attempts", () =>
        HttpResponse.json([{ id: "attempt-1", taskId: "task-direct", status: "UNKNOWN",
          requestKey: "e6422a3f-c91d-4874-b684-118fd6be068a", reconcilable: false,
          providerRequestId: null,
          createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z" }]),
      ),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    await user.click(await screen.findByText("查看待核对任务"));
    await user.click(screen.getByRole("button", { name: "查看提交账本并处理重试" }));
    expect(await screen.findByRole("button", { name: "明确风险后创建新尝试" }))
        .toBeInTheDocument();
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
      http.get("/api/v1/projects/:projectId/runs/preflight", ({ request }) => {
        expect(new URL(request.url).searchParams.get("agentId")).toBe(agentId);
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
            modelConfigVersion: 7, maxModelTurns: 12, maxToolExecutions: 40,
            maxImages: 8, maxVideos: 6, maxShots: 6 },
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
              x: 10,
              y: 10,
              width: 340,
              height: 320,
              zIndex: 0,
              groupId: null,
              locked: false,
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
            bindingType: "INPUT",
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
    fireEvent.change(name, { target: { value: "Agent Beta" } });
    fireEvent.change(instruction, { target: { value: "Updated instruction" } });
    fireEvent.submit(name.closest("form") as HTMLFormElement);

    expect(await screen.findByText("Agent Beta")).toBeInTheDocument();
    expect(updated).toBe(true);

    const taskInput = within(editorArea).getByLabelText("本次任务");
    fireEvent.change(taskInput, { target: { value: "规划三个镜头" } });
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    expect(await within(editorArea).findByText("确认开始规划")).toBeInTheDocument();
    expect(within(editorArea).getByText("确认开始规划")).toBeDisabled();
    expect(starts).toBe(0);
    modelAvailable = true;
    fireEvent.change(taskInput, { target: { value: "规划三个镜头！" } });
    fireEvent.change(taskInput, { target: { value: "规划三个镜头" } });
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    await waitFor(() => expect(within(editorArea).getByText("确认开始规划")).not.toBeDisabled());
    expect(within(editorArea).getByText(/首轮只发送有上限的内容预览/)).toBeInTheDocument();
    expect(within(editorArea).getByText(/当前模型看不到图片像素、视频帧或音频/)).toBeInTheDocument();
    expect(within(editorArea).getByText(/Bound brief/)).toBeInTheDocument();
    fireEvent.click(within(editorArea).getByText("确认开始规划"));
    await waitFor(() => expect(starts).toBe(1));
    await waitFor(() => expect(within(editorArea).getByText("确认开始规划")).not.toBeDisabled());
    fireEvent.click(within(editorArea).getByText("确认开始规划"));
    await waitFor(() => expect(starts).toBe(2));
    expect(idempotencyKeys[0]).toBeTruthy();
    expect(idempotencyKeys[1]).toBe(idempotencyKeys[0]);
    await waitFor(() => expect(within(editorArea).queryByText("确认开始规划")).not.toBeInTheDocument());
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    await waitFor(() => expect(within(editorArea).getByText("确认开始规划")).not.toBeDisabled());
    fireEvent.click(within(editorArea).getByText("确认开始规划"));
    await waitFor(() => expect(starts).toBe(3));
    expect(idempotencyKeys[2]).not.toBe(idempotencyKeys[1]);
    expect(await within(editorArea).findByRole("button", { name: "停止" })).toBeEnabled();
    fireEvent.click(within(editorArea).getByRole("button", { name: "停止" }));
    await waitFor(() => expect(screen.getByText("准备就绪")).toBeInTheDocument());
    fireEvent.click(within(card as HTMLElement).getByRole("button", { name: "会话列表" }));
    expect(await within(card as HTMLElement).findByRole("button", { name: /之前的创作/ })).toBeInTheDocument();
  });

  it("starts an explicitly scoped redo Run only for a bound current shot", async () => {
    const agentId = crypto.randomUUID();
    const agentItemId = crypto.randomUUID();
    const shotId = crypto.randomUUID();
    const shotItemId = crypto.randomUUID();
    const versionId = crypto.randomUUID();
    let submitted: unknown = null;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", ({ params }) => HttpResponse.json({
        id: params.projectId, name: "Redo canvas", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
      http.get("/api/v1/projects/:projectId/canvas/items", () => HttpResponse.json({ items: [
        { id: agentItemId, subjectType: "AGENT", subjectId: agentId,
          x: 10, y: 10, width: 340, height: 320, zIndex: 0, groupId: null,
          locked: false, version: 0, artifact: null,
          agent: { id: agentId, projectId: "project-1", profileKey: "creator",
            profileVersion: 1, name: "Redo agent", instruction: "Work only on the chosen shot",
            outputGroupId: crypto.randomUUID(), version: 0,
            bindings: [{ id: crypto.randomUUID(), artifactId: shotId,
              selectedVersionId: versionId, bindingType: "INPUT" }] } },
        { id: shotItemId, subjectType: "ARTIFACT", subjectId: shotId,
          x: 380, y: 10, width: 280, height: 180, zIndex: 1, groupId: null,
          locked: false, version: 0, agent: null,
          artifact: { id: shotId, projectId: "project-1", kind: "SHOT", title: "Second shot",
            currentVersionId: versionId, version: 1,
            currentVersion: { id: versionId, versionNo: 2, schemaVersion: 1,
              content: { order: 2, durationMs: 5000, description: "Revised",
                camera: "Close", action: "Pour", characterVersionIds: [],
                sceneVersionId: crypto.randomUUID() }, inputReferences: [],
              createdByKind: "USER", runId: null } } },
      ] })),
      http.get("/api/v1/projects/:projectId/runs/preflight", () => HttpResponse.json({
        agentId, agentVersion: 0, agentName: "Redo agent",
        conversationId: `conversation-${agentId}`, conversationVersion: 0,
        conversationTurnCount: 0, inheritedBindingCount: 0, memoryTruncated: false,
        agentInstruction: "Work only on the chosen shot",
        bindings: [{ artifactId: shotId, selectedVersionId: versionId,
          artifactTitle: "Second shot", artifactKind: "SHOT" }],
        modelAvailable: true, providerAdapter: "Mock", modelId: "mock-storyboard-v1",
        toolCalling: true, policySnapshot: { schemaVersion: 2, systemPromptVersion: 2,
          modelConfigSource: "mock", modelConfigVersion: 1,
          maxModelTurns: 12, maxToolExecutions: 40,
          maxImages: 8, maxVideos: 6, maxShots: 6 },
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/runs", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ id: crypto.randomUUID(), agentInstanceId: agentId,
          conversationId: `conversation-${agentId}`, conversationTurn: 1,
          instruction: "只重做第二镜头", createdAt: "2026-09-23T00:00:00Z", status: "QUEUED" }, { status: 202 });
      }),
    );
    const user = userEvent.setup();
    render(<QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/projects/project-1"]}>
        <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>);
    const heading = await screen.findByText("Redo agent");
    fireEvent.click(heading);
    const editorArea = heading.closest("article") as HTMLElement;
    await user.selectOptions(within(editorArea).getByLabelText("运行范围"), shotId);
    await user.type(within(editorArea).getByLabelText("本次任务"), "只重做第二镜头");
    fireEvent.click(within(editorArea).getByRole("button", { name: "发送" }));
    fireEvent.click(await within(editorArea).findByText("确认开始规划"));
    await waitFor(() => expect(submitted).toMatchObject({ agentId,
      redoShotArtifactId: shotId, expectedAgentVersion: 0,
      instruction: "只重做第二镜头", selectedItemIds: [agentItemId] }));
  });

  it("shows an authorized thumbnail for a generated image without fetching the original", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
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
          x: 10, y: 10, width: 300, height: 300, zIndex: 0, groupId: crypto.randomUUID(),
          locked: false, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "IMAGE",
            title: "Demo still", currentVersionId: crypto.randomUUID(), version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            currentVersion: {
              id: crypto.randomUUID(), versionNo: 1, schemaVersion: 1,
              content: { assetId, prompt: "Ridge sunrise", providerConfigVersion: 1,
                workflowVersion: "mock-image-v1", sourceTaskId: crypto.randomUUID(),
                parameters: { mock: true, displayLabel: "演示素材" } },
              inputReferences: [], createdByKind: "TASK", runId: crypto.randomUUID(),
              createdAt: "2026-09-23T00:00:00Z",
            },
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
      `/api/v1/projects/project-1/assets/${assetId}/thumbnail`);
    expect(screen.getByText("打开原图").closest("a")).toHaveAttribute("href",
      `/api/v1/projects/project-1/assets/${assetId}/content`);
    expect(screen.getByText("演示素材")).toBeInTheDocument();
    fireEvent.error(preview);
    expect(screen.getByText("预览暂不可用")).toBeInTheDocument();
  });

  it("keeps generated video on its poster until the user chooses playback", async () => {
    const assetId = crypto.randomUUID();
    const artifactId = crypto.randomUUID();
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
          x: 10, y: 10, width: 300, height: 300, zIndex: 0, groupId: null,
          locked: false, version: 0, agent: null,
          artifact: {
            id: artifactId, projectId: "project-1", kind: "VIDEO",
            title: "Demo clip", currentVersionId: crypto.randomUUID(), version: 0,
            createdAt: "2026-09-23T00:00:00Z", updatedAt: "2026-09-23T00:00:00Z",
            currentVersion: {
              id: crypto.randomUUID(), versionNo: 1, schemaVersion: 1,
              content: { assetId, prompt: "Coffee", providerConfigVersion: 1,
                workflowVersion: "mock-video-v1", sourceTaskId: crypto.randomUUID(),
                keyframeVersionId: crypto.randomUUID(), parameters: { mock: true } },
              inputReferences: [], createdByKind: "TASK", runId: crypto.randomUUID(),
              createdAt: "2026-09-23T00:00:00Z",
            },
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

  it("removes an optional card reference only after the CAS revision is saved", async () => {
    let linked = true;
    let revisions = 0;
    let rejectOnce = true;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({
        id: crypto.randomUUID(), loginName: "admin", role: "ADMIN",
      })),
      http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
        id: "project-1", name: "Reference project", status: "ACTIVE",
      })),
      http.get("/api/v1/projects/:projectId/canvas/items", () =>
        HttpResponse.json({ items: [referenceCard(linked)] })),
      http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
        async ({ request, params }) => {
          revisions++;
          expect(params.artifactId).toBe("character-id");
          expect(await request.json()).toEqual({ expectedVersion: 3, content: {
            name: "Hero", description: "Lead", appearance: "Blue coat",
            referenceVersionIds: [],
          } });
          if (rejectOnce) {
            rejectOnce = false;
            return HttpResponse.json({ title: "版本冲突", detail: "请刷新后重试。",
              code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
            { status: 409, headers: { "Content-Type": "application/problem+json" } });
          }
          linked = false;
          return HttpResponse.json(referenceCard(false).artifact, { status: 201 });
        }),
    );
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/projects/project-1"]}>
          <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole("article", { name: "Hero · 角色" }));
    fireEvent.click(await screen.findByRole("button", { name: "卡片详情" }));
    expect(screen.getByText("素材引用（1 个精确版本）")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "移除引用" }));
    expect(await screen.findByText("请刷新后重试。")).toBeInTheDocument();
    expect(screen.getByText("素材引用（1 个精确版本）")).toBeInTheDocument();
    expect(screen.getByText("内容有冲突，当前修改未保存")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "移除引用" }));
    await waitFor(() => expect(screen.queryByText("素材引用（1 个精确版本）"))
      .not.toBeInTheDocument());
    expect(revisions).toBe(2);
    expect(screen.getAllByText("已保存").length).toBeGreaterThan(0);
  });
});
