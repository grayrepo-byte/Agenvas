import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { PropsWithChildren } from "react";
import { beforeEach, expect, it, vi } from "vitest";
import type { CanvasEditRequest, CanvasItem, CanvasConnection } from "../../shared/api/client";
import { server } from "../../test/server";
import { useCanvasStore } from "./canvasStore";
import { useCanvasOrganization } from "./useCanvasOrganization";

const project = "subgraph";
const card = (id: string, kind: "TEXT" | "IMAGE"): CanvasItem => ({ id, subjectType: "ARTIFACT", subjectId: `${id}-artifact`,
  title: id, x: 10, y: 20, width: 280, height: 180, zIndex: 0, groupId: "group", locked: false, version: 0,
  selectedVersionId: null, selectedVersion: null,
  artifact: { id: `${id}-artifact`, projectId: project, kind, title: id, version: 0,
    resourceDefaultVersionId: null, resourceDefaultVersion: null, createdAt: "2026-11-07T00:00:00Z", updatedAt: "2026-11-07T00:00:00Z" } });
const items = [card("text", "TEXT"), card("image", "IMAGE")];
const edge = (id: string, sourceCanvasItemId: string, targetCanvasItemId: string): CanvasConnection => ({ id, projectId: project,
  sourceCanvasItemId, targetCanvasItemId, relationType: "PROMPT_INPUT", sourceArtifactVersionId: "body", version: 0,
  createdAt: "2026-11-07T00:00:00Z", updatedAt: "2026-11-07T00:00:00Z" });
beforeEach(() => { useCanvasStore.setState({ drafts: {}, mediaDraftRecoveries: {}, selectedIds: [], saveState: "saved" }); });

it("copies only internal topology with new identities on every paste and preserves full groups", async () => {
  const requests: CanvasEditRequest[] = [];
  const nodes = [...items, { ...card("loose", "TEXT"), groupId: null }];
  const select = vi.fn();
  server.use(
    http.get(`/api/v1/projects/${project}/canvas/editor`, () => HttpResponse.json({ items: nodes,
      groups: [{ id: "group", projectId: project, name: "Workflow", collapsed: true, version: 0 }],
      connections: [edge("inside", "text", "image"), edge("outside-in", "external", "image"), edge("outside-out", "text", "external")],
      characterConnections: [] })),
    http.get(`/api/v1/projects/${project}/canvas-items/image/media-draft`, () => HttpResponse.json({ version: 3 })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.post(`/api/v1/projects/${project}/canvas/edits`, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest; requests.push(input);
      return HttpResponse.json({ items: (input.copies ?? []).map((copy) => ({ ...items[0], id: copy.targetItemId })), groups: [],
        receipt: { id: input.commandId, operation: input.operation, state: "APPLIED", revision: 0 } });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result } = renderHook(() => useCanvasOrganization({ projectId: project, items: nodes, enabled: true,
    selectedIds: nodes.map((item) => item.id), select, effectiveNodeSize: () => ({ width: 280, height: 180 }) }), { wrapper });
  await waitFor(() => expect(result.current.editor.isSuccess).toBe(true));
  await act(async () => { await result.current.copyItems(); });
  await act(async () => { await result.current.pasteItems(); });
  await act(async () => { await result.current.pasteItems(); });
  expect(requests).toHaveLength(2);
  for (const request of requests) {
    expect(request).toMatchObject({ connectionCopies: [{ sourceConnectionId: "inside", expectedVersion: 0, kind: "ARTIFACT", targetConnectionId: expect.any(String) }] });
    expect(request.copies).toHaveLength(3);
    expect(request.groupChanges).toHaveLength(1);
    expect(request.copies?.every((copy) => copy.groupId === (copy.sourceItemId === "loose" ? null : request.groupChanges?.[0]?.groupId))).toBe(true);
  }
  expect(requests[0]?.copies?.[0]?.targetItemId).not.toBe(requests[1]?.copies?.[0]?.targetItemId);
  expect(requests[0]).not.toEqual(requests[1]);
  expect(select).toHaveBeenLastCalledWith([
    `canvas-group:${requests[1]?.groupChanges?.[0]?.groupId}`,
    requests[1]?.copies?.find((copy) => copy.sourceItemId === "loose")?.targetItemId,
  ]);
  client.clear();
});

it("keeps the clipboard after a failed paste and retries the same graph command", async () => {
  const requests: CanvasEditRequest[] = [];
  server.use(
    http.get(`/api/v1/projects/${project}/canvas/editor`, () => HttpResponse.json({ items, groups: [],
      connections: [edge("inside", "text", "image")], characterConnections: [] })),
    http.get(`/api/v1/projects/${project}/canvas-items/image/media-draft`, () => HttpResponse.json({ version: 3 })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.post(`/api/v1/projects/${project}/canvas/edits`, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest; requests.push(input);
      if (requests.length === 1) return HttpResponse.json({ title: "Conflict", detail: "Source changed", status: 409, code: "CANVAS_EDIT_CONFLICT" }, { status: 409 });
      return HttpResponse.json({ items: [], groups: [], receipt: { id: input.commandId, operation: "DUPLICATE", state: "APPLIED", revision: 0 } });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result } = renderHook(() => useCanvasOrganization({ projectId: project, items, enabled: true, selectedIds: ["text", "image"],
    select: vi.fn(), effectiveNodeSize: () => ({ width: 280, height: 180 }) }), { wrapper });
  await act(async () => { await result.current.copyItems(); });
  await act(async () => { await expect(result.current.pasteItems()).rejects.toThrow(); });
  expect(result.current.canPaste).toBe(true);
  expect(result.current.actionError).toBeTruthy();
  await act(async () => { await result.current.editing.retry(); });
  expect(requests[1]).toEqual(requests[0]);
  client.clear();
});

it("rejects an empty selection without submitting a copy", async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result } = renderHook(() => useCanvasOrganization({ projectId: project, items: [], enabled: true, selectedIds: [],
    select: vi.fn(), effectiveNodeSize: () => ({ width: 280, height: 180 }) }), { wrapper });
  await act(async () => { await expect(result.current.copyItems()).rejects.toThrow(); });
  expect(result.current.canPaste).toBe(false);
  expect(result.current.actionError).toBeTruthy();
  client.clear();
});

it("pins character selection and internal logical edges while partial groups become loose nodes", async () => {
  const role: CanvasItem = { ...card("role", "TEXT"), subjectType: "CHARACTER", subjectId: "profile", groupId: null, artifact: null,
    characterNode: { canvasItemId: "role", version: 5, newerVersionAvailable: false, connections: [],
      selection: { characterId: "profile", characterVersion: 2, lookId: "look", viewVersionIds: [], useAppearance: true, useVoice: false },
      snapshot: { characterId: "profile", characterVersion: 2, name: "Hero", description: "Fixed identity", useAppearance: true, useVoice: false,
        look: { id: "look", name: "Coat", clothing: "Blue", expression: "", description: "", views: [] }, voice: null } } };
  const nodes = [...items, role];
  const requests: CanvasEditRequest[] = [];
  server.use(
    http.get(`/api/v1/projects/${project}/canvas/editor`, () => HttpResponse.json({ items: [...nodes, card("not-selected", "TEXT")],
      groups: [{ id: "group", projectId: project, name: "Partial", collapsed: false, version: 0 }],
      connections: [edge("prompt", "text", "image")], characterConnections: [
        { id: "character", sourceCanvasItemId: "role", targetCanvasItemId: "image", version: 2 },
        { id: "external-character", sourceCanvasItemId: "role", targetCanvasItemId: "not-selected", version: 0 },
      ] })),
    http.get(`/api/v1/projects/${project}/canvas-items/image/media-draft`, () => HttpResponse.json({ version: 3 })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.post(`/api/v1/projects/${project}/canvas/edits`, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest; requests.push(input);
      return HttpResponse.json({ items: [], groups: [], receipt: { id: input.commandId, operation: "DUPLICATE", state: "APPLIED", revision: 0 } });
    }),
  );
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result } = renderHook(() => useCanvasOrganization({ projectId: project, items: nodes, enabled: true,
    selectedIds: nodes.map((item) => item.id), select: vi.fn(), effectiveNodeSize: () => ({ width: 280, height: 180 }) }), { wrapper });
  await act(async () => { await result.current.duplicateItems(); });
  expect(requests[0]?.groupChanges).toEqual([]);
  expect(requests[0]?.copies).toEqual(expect.arrayContaining([
    expect.objectContaining({ sourceItemId: "role", expectedSourceDraftVersion: 5, groupId: null }),
  ]));
  expect(requests[0]?.copies?.every((copy) => copy.groupId === null)).toBe(true);
  expect(requests[0]?.connectionCopies).toEqual([
    expect.objectContaining({ kind: "ARTIFACT", sourceConnectionId: "prompt", expectedVersion: 0 }),
    expect.objectContaining({ kind: "CHARACTER", sourceConnectionId: "character", expectedVersion: 2 }),
  ]);
  client.clear();
});
