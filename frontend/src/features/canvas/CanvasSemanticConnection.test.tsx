import type { Connection, Edge, Node } from "@xyflow/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";

// Drive the canvas callback deterministically; the physical browser gesture is a separate E2E gate.
vi.mock("@xyflow/react", async (importOriginal) => {
  const real = await importOriginal<typeof import("@xyflow/react")>();
  return { ...real, ReactFlow: ({ onConnect, edges, nodes }: {
    onConnect?: (connection: Connection) => void; edges?: Edge[]; nodes?: Node[];
  }) => <div>
    <button onClick={() => onConnect?.({ source: "image-card", sourceHandle: "artifact-output",
      target: "character-card", targetHandle: "artifact-input" })} type="button">连接图片到角色</button>
    <button onClick={() => onConnect?.({ source: "image-card", sourceHandle: "artifact-output",
      target: "image-card", targetHandle: "artifact-input" })} type="button">连接图片到自身</button>
    <span data-testid="semantic-edges">{edges?.length ?? 0}</span>
    <span data-testid="canvas-nodes">{nodes?.length ?? 0}</span>
  </div> };
});

const createdAt = "2026-09-24T00:00:00Z";

/** Server projections before and after the immutable content revision. */
function cards(referenced: boolean): CanvasItem[] {
  const reference = referenced ? ["image-version"] : [];
  return [
    { id: "image-card", subjectType: "ARTIFACT", subjectId: "image-id",
      title: "参考图",
      x: 0, y: 0, width: 280, height: 180, zIndex: 0, groupId: null,
      locked: false, version: 0, agent: null,
      artifact: { id: "image-id", projectId: "project-1", kind: "IMAGE", title: "参考图",
        currentVersionId: "image-version", version: 0, createdAt, updatedAt: createdAt,
        currentVersion: { id: "image-version", versionNo: 1, schemaVersion: 1,
          content: { sourceType: "UPLOAD", assetId: "asset-id" }, inputReferences: [],
          createdByKind: "USER", runId: null, createdAt } } },
    { id: "character-card", subjectType: "ARTIFACT", subjectId: "character-id",
      title: "角色",
      x: 400, y: 0, width: 280, height: 180, zIndex: 1, groupId: null,
      locked: false, version: 0, agent: null,
      artifact: { id: "character-id", projectId: "project-1", kind: "CHARACTER", title: "角色",
        currentVersionId: referenced ? "character-v2" : "character-v1",
        version: referenced ? 8 : 7, createdAt, updatedAt: createdAt,
        currentVersion: { id: referenced ? "character-v2" : "character-v1",
          versionNo: referenced ? 2 : 1, schemaVersion: 1,
          content: { name: "角色", description: "Lead", appearance: "Blue coat",
            referenceVersionIds: reference },
          inputReferences: referenced ? [{ versionId: "image-version", role: "referenceImage",
            order: 0, kind: "IMAGE" }] : [],
          createdByKind: "USER", runId: null, createdAt } } },
  ];
}

function renderWorkspace() {
  return render(<QueryClientProvider client={createQueryClient()}>
    <MemoryRouter initialEntries={["/projects/project-1"]}>
      <Routes><Route path="/projects/:projectId" element={<ProjectWorkspacePage />} /></Routes>
    </MemoryRouter>
  </QueryClientProvider>);
}

/** Installs the minimum authenticated project API needed by the workspace. */
function stubProject(getCards: () => CanvasItem[]) {
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({
      id: "owner-id", loginName: "admin", role: "ADMIN",
    })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({
      headerName: "X-XSRF-TOKEN", token: "test-token",
    })),
    http.get("/api/v1/projects/:projectId", () => HttpResponse.json({
      id: "project-1", name: "连线项目", status: "ACTIVE",
    })),
    http.get("/api/v1/projects/:projectId/snapshot", () => HttpResponse.json({
      project: { id: "project-1", name: "连线项目", status: "ACTIVE" },
      canvas: { items: getCards() }, agents: [], activeRun: null,
      activeTasks: [], unknownTasks: [], snapshotSeq: 0,
    })),
    http.get("/api/v1/projects/:projectId/canvas/items", () =>
      HttpResponse.json({ items: getCards() })),
    http.get("/api/v1/projects/:projectId/exports", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/export-proposals", () => HttpResponse.json([])),
    http.get("/api/v1/projects/:projectId/usage", () => HttpResponse.json([])),
  );
}

describe("manual semantic connection", () => {
  it("posts a complete CAS revision and redraws only the saved exact-version edge", async () => {
    let saved = false;
    let revisions = 0;
    let reads = 0;
    stubProject(() => { reads++; return cards(saved); });
    server.use(http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions",
      async ({ request, params }) => {
        revisions++;
        expect(params.artifactId).toBe("character-id");
        expect(new Headers(request.headers).get("X-XSRF-TOKEN")).toBe("test-token");
        expect(await request.json()).toEqual({ expectedVersion: 7, content: {
          name: "角色", description: "Lead", appearance: "Blue coat",
          referenceVersionIds: ["image-version"],
        } });
        saved = true;
        return HttpResponse.json(cards(true)[1]?.artifact, { status: 201 });
      }));
    renderWorkspace();
    await waitFor(() => expect(screen.getByTestId("canvas-nodes")).toHaveTextContent("2"));
    expect(screen.getByTestId("semantic-edges")).toHaveTextContent("0");
    fireEvent.click(screen.getByRole("button", { name: "连接图片到角色" }));
    await waitFor(() => expect(screen.getByTestId("semantic-edges")).toHaveTextContent("1"));
    expect(revisions).toBe(1);
    const readsBeforeRepeat = reads;
    fireEvent.click(screen.getByRole("button", { name: "连接图片到角色" }));
    await waitFor(() => expect(reads).toBeGreaterThan(readsBeforeRepeat));
    expect(revisions).toBe(1);
  });

  it("keeps the old server projection and shows a version conflict", async () => {
    stubProject(() => cards(false));
    server.use(http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions", () =>
      HttpResponse.json({ title: "产物版本已变化", detail: "请刷新后重试。",
        code: "ARTIFACT_VERSION_CONFLICT", retryable: false },
      { status: 409, headers: { "Content-Type": "application/problem+json" } })));
    renderWorkspace();
    await waitFor(() => expect(screen.getByTestId("canvas-nodes")).toHaveTextContent("2"));
    expect(screen.getByTestId("semantic-edges")).toHaveTextContent("0");
    fireEvent.click(screen.getByRole("button", { name: "连接图片到角色" }));
    expect(await screen.findByText("请刷新后重试。")).toBeInTheDocument();
    expect(screen.getByTestId("semantic-edges")).toHaveTextContent("0");
  });

  it("explains an unsupported gesture without sending a revision", async () => {
    let revisions = 0;
    stubProject(() => cards(false));
    server.use(http.post("/api/v1/projects/:projectId/artifacts/:artifactId/revisions", () => {
      revisions++;
      return HttpResponse.json({}, { status: 201 });
    }));
    renderWorkspace();
    await waitFor(() => expect(screen.getByTestId("canvas-nodes")).toHaveTextContent("2"));
    fireEvent.click(screen.getByRole("button", { name: "连接图片到自身" }));
    expect(await screen.findByText("仅支持图片→角色/场景、角色/场景→镜头的精确版本语义关系。"))
      .toBeInTheDocument();
    expect(revisions).toBe(0);
    expect(screen.getByTestId("semantic-edges")).toHaveTextContent("0");
  });
});
