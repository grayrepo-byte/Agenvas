import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem, CharacterProfile, ProjectSnapshot } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { server } from "../../test/server";
import { ProjectWorkspacePage } from "./ProjectWorkspacePage";
import { projectCacheCallbacks } from "./projectCache";
import { useCanvasStore } from "./canvasStore";

const NOW = "2026-11-06T00:00:00Z";
const profile: CharacterProfile = { id: "hero", projectId: "project", version: 3, archived: false, createdAt: NOW, updatedAt: NOW,
  content: { name: "Mira", description: "Pilot", looks: [{ id: "look", name: "Flight suit", clothing: "", expression: "", description: "", views: [] }], voice: null } };
const role: CanvasItem = { id: "role", subjectType: "CHARACTER", subjectId: "hero", title: "Mira", x: 20, y: 30, width: 320, height: 300, zIndex: 0,
  locked: false, version: 1, artifact: null, selectedVersion: null, selectedVersionId: null,
  characterNode: { canvasItemId: "role", version: 2, newerVersionAvailable: false, connections: [],
    selection: { characterId: "hero", characterVersion: 3, lookId: "look", viewVersionIds: [], useAppearance: true, useVoice: false },
    snapshot: { characterId: "hero", characterVersion: 3, name: "Mira", description: "Pilot", look: profile.content.looks[0] ?? null, voice: null, useAppearance: true, useVoice: false } } };
const snapshot: ProjectSnapshot = { project: { id: "project", name: "Character workspace", status: "ACTIVE", aspectRatio: "LANDSCAPE_16_9", version: 0, createdAt: NOW, updatedAt: NOW },
  canvas: { items: [role] }, director: null, connections: [], activeRun: null, activeTasks: [], unknownTasks: [], snapshotSeq: 7 };
beforeEach(() => {
  useCanvasStore.setState({ drafts: {}, selectedIds: [], saveState: "saved", mediaDraftRecoveries: {} });
  server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "owner", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/projects/project/snapshot", () => HttpResponse.json(snapshot)),
    http.get("/api/v1/projects/project/characters", () => HttpResponse.json({ characters: [profile], bindings: [] })),
    http.get("/api/v1/projects/project/canvas/editor", () => HttpResponse.json({ items: [role], groups: [] })),
    http.get("/api/v1/projects/project/canvas/items", () => HttpResponse.json(snapshot.canvas)),
    http.get("/api/v1/projects/project/artifacts", () => HttpResponse.json({ items: [] })),
  );
});
function show() {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/projects/project"]}><Routes>
    <Route path="/projects/:projectId" element={<ProjectWorkspacePage />} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
it("renders the real character card from a nullable-artifact snapshot and opens its fixed configuration", async () => {
  show();
  expect(await screen.findByText("Flight suit")).toBeVisible();
  expect(screen.getByText("v3")).toBeVisible();
  expect(screen.getByText(t("characterNode.counts", { images: 0, audio: 0 }))).toBeVisible();
  act(() => useCanvasStore.getState().setSelectedIds([role.id]));
  await userEvent.click(await screen.findByRole("button", { name: t("characterNode.fixedSelection") }));
  expect(await screen.findByRole("dialog", { name: t("characterNode.fixedSelection") })).toBeVisible();
  expect(await screen.findByLabelText(t("character.useAppearance"))).toBeChecked();
});
it("restores the fixed character projection after a snapshot recovery without converting it into media", async () => {
  const client = show();
  await screen.findByText("Flight suit");
  if (!role.characterNode) throw new Error("Missing role fixture");
  const recovered: CanvasItem = { ...role, characterNode: { ...role.characterNode, newerVersionAvailable: true } };
  act(() => projectCacheCallbacks(client, "project").onSnapshot({ ...snapshot, canvas: { items: [recovered] }, snapshotSeq: 42 }));
  await waitFor(() => expect(screen.getByText(t("character.outdated"))).toBeVisible());
  expect(screen.getByText("v3")).toBeVisible();
  expect(client.getQueryData<{ items: CanvasItem[] }>(["canvas", "project"])?.items[0]?.artifact).toBeNull();
});

it("copies a character through the ordinary editor with its selection CAS and projects the fixed configuration", async () => {
  const copies: unknown[] = [];
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/project/canvas/edits", async ({ request }) => {
      const input = await request.json() as { copies: { sourceItemId: string; targetItemId: string; expectedSourceVersion: number; expectedSourceDraftVersion: number }[] };
      copies.push(...input.copies);
      const copy = input.copies[0];
      if (!copy || !role.characterNode) throw new Error("Missing character copy");
      return HttpResponse.json({ items: [role, { ...role, id: copy.targetItemId, title: "Mira copy", characterNode: { ...role.characterNode, canvasItemId: copy.targetItemId, connections: [] } }], groups: [],
        receipt: { id: "copy-receipt", operation: "DUPLICATE", revision: 1, state: "APPLIED" } });
    }));
  show();
  await screen.findByText("Flight suit");
  act(() => useCanvasStore.getState().setSelectedIds([role.id]));
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.edit.duplicate") }));
  expect(await screen.findByRole("article", { name: `Mira copy · ${t("character.title")}` })).toBeVisible();
  expect(copies).toEqual([expect.objectContaining({ sourceItemId: "role", expectedSourceVersion: 1, expectedSourceDraftVersion: 2, expectedSourceContentVersion: null })]);
  expect(screen.getAllByText("v3")).toHaveLength(2);
});
it("deletes only canvas topology and explains that applied frozen drafts remain", async () => {
  const removals: unknown[] = [];
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/project/canvas/commands", async ({ request }) => {
      removals.push(await request.json()); return HttpResponse.json({ items: [] });
    }));
  show();
  await screen.findByText("Flight suit");
  act(() => useCanvasStore.getState().setSelectedIds([role.id]));
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.card.remove") }));
  expect(await screen.findByText(t("characterNode.retained"))).toBeVisible();
  expect(removals).toEqual([{ commands: [{ type: "REMOVE", itemId: "role", expectedVersion: 1 }] }]);
});
