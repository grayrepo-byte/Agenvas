import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, expect, it, vi } from "vitest";
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
  expect(await screen.findByText(t("characterNode.referenceSource"))).toBeVisible();
  expect(screen.queryByText(t("canvas.studio.draft"))).not.toBeInTheDocument();
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
  await waitFor(() => expect(screen.getByText(t("characterNode.updateAvailable"))).toBeVisible());
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

it("offers direct edge configuration without selecting the node", async () => {
  show();
  const configure = await screen.findByRole("button", { name: t("characterNode.configure") });
  expect(configure).toBeVisible();
  expect(screen.getByText(t("characterNode.textOnly"))).toBeVisible();
  await userEvent.click(configure);
  expect(await screen.findByRole("dialog", { name: t("characterNode.fixedSelection") })).toBeVisible();
  const description = screen.getByText(t("character.characterDescription"));
  expect(screen.getByText("Pilot")).not.toBeVisible();
  await userEvent.click(description);
  expect(screen.getByText("Pilot")).toBeVisible();
  await userEvent.click(screen.getByRole("button", {name:t("ui.dialog.close")}));
  expect(configure).toHaveFocus();
});

it("offers a keyboard connection menu that previews without generating", async () => {
  const target: CanvasItem = { ...role, id: "target", subjectType: "ARTIFACT", subjectId: "image", characterNode: null, title: "Scene image",
    artifact: { id: "image", projectId: "project", kind: "IMAGE", title: "Scene image", version: 0, resourceDefaultVersion: null, resourceDefaultVersionId: null, createdAt: NOW, updatedAt: NOW } };
  const items = [role, target]; const connections: unknown[] = [];
  server.use(http.get("/api/v1/projects/project/snapshot", () => HttpResponse.json({ ...snapshot, canvas: {items} })),
    http.get("/api/v1/projects/project/canvas/editor", () => HttpResponse.json({items,groups:[]})),
    http.get("/api/v1/projects/project/canvas/items", () => HttpResponse.json({items})),
    http.get("/api/v1/projects/project/canvas-items/target/media-draft", () => HttpResponse.json({projectId:"project",canvasItemId:"target",version:0,createdAt:NOW,updatedAt:NOW,prompt:"",parameters:{},durationSeconds:null,capabilityId:null,videoInputMode:null,styleId:null,mediaInputs:[],mentions:[],displayMode:"DRAFT"})),
    http.post("/api/v1/projects/project/canvas/character-connections",async({request})=>{connections.push(await request.json());return HttpResponse.json({id:"edge",sourceCanvasItemId:"role",targetCanvasItemId:"target",version:0});}),
    http.post("/api/v1/projects/project/canvas-items/target/character-node-bindings/preview",()=>HttpResponse.json({title:"Unsupported",detail:"Synthetic model rejects references",code:"VALIDATION_ERROR"},{status:400,headers:{"Content-Type":"application/problem+json"}})));
  const client = show();
  const connect = await screen.findByRole("button", { name: t("characterNode.chooseTarget") });
  connect.focus(); await userEvent.keyboard("{Enter}");
  await screen.findByRole("menuitem", {name:"Scene image"});
  expect(screen.getByText(t("characterNode.chooseTarget"))).toBeVisible();
  expect(screen.getByText(t("characterNode.targetHint"))).toBeVisible();
  expect(screen.getByRole("menu")).toHaveAccessibleDescription(t("characterNode.targetHint"));
  await userEvent.keyboard("{ArrowDown}{Enter}");
  expect(await screen.findByRole("dialog", {name:t("characterNode.connectPreview")})).toBeVisible();
  await screen.findByText("Synthetic model rejects references");
  expect(connections).toHaveLength(1);
  expect(screen.getByRole("button", {name:t("character.applyDraft")})).toBeDisabled();
  await userEvent.click(screen.getByRole("button", {name:t("ui.dialog.close")}));
  expect(connect).toHaveFocus();
  act(() => client.setQueryData(["canvas", "project"], {items:[{...role, characterNode:{...role.characterNode, connections:[{id:"edge",sourceCanvasItemId:"role",targetCanvasItemId:"target",version:0}]}},target]}));
  await userEvent.click(screen.getByRole("button", {name:t("characterNode.chooseTarget")}));
  const existingTarget = await screen.findByRole("menuitem", {name:"Scene image"});
  expect(existingTarget).toHaveAccessibleDescription(t("characterNode.alreadyConnected"));
  await userEvent.click(existingTarget);
  await screen.findByText("Synthetic model rejects references");
  expect(connections).toHaveLength(1);
});

it("keeps character placement and fixed selection read-only on narrow viewports", async () => {
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
  try {
    const target: CanvasItem = {...role, id:"target", subjectType:"ARTIFACT", subjectId:"image", characterNode:null, title:"Scene image",
      artifact:{id:"image",projectId:"project",kind:"IMAGE",title:"Scene image",version:0,resourceDefaultVersion:null,resourceDefaultVersionId:null,createdAt:NOW,updatedAt:NOW}};
    const items = [role,target];
    server.use(http.get("/api/v1/projects/project/snapshot", () => HttpResponse.json({...snapshot,canvas:{items}})),
      http.get("/api/v1/projects/project/canvas/editor", () => HttpResponse.json({items,groups:[]})),
      http.get("/api/v1/projects/project/canvas/items", () => HttpResponse.json({items})));
    show();
    await screen.findByText("Flight suit");
    await userEvent.click(screen.getByRole("button", { name: t("characterNode.chooseTarget") }));
    expect(await screen.findByRole("menuitem", {name:"Scene image"})).toHaveAttribute("aria-disabled","true");
    await userEvent.keyboard("{Escape}");
    await userEvent.click(screen.getByRole("button", { name: t("characterNode.configure") }));
    expect(await screen.findByRole("button", { name: t("character.confirmSave") })).toBeDisabled();
    expect(screen.getByLabelText(t("character.lookName"))).toBeDisabled();
    await userEvent.click(screen.getByRole("button", { name: t("ui.dialog.close") }));
    await userEvent.click(screen.getByRole("button", { name: t("characterNode.place") }));
    expect(await screen.findByRole("button", { name: t("canvas.place") })).toBeDisabled();
  } finally { vi.unstubAllGlobals(); }
});

it("distinguishes a library update from the node's fixed project revision", async () => {
  server.use(http.get("/api/v1/projects/project/character-library/links", () => HttpResponse.json({items:[{
    characterId:"hero",libraryId:"library",libraryVersion:1,currentLibraryVersion:2,libraryArchived:false,projectCharacterVersion:3,hasUpdate:true,
  }]})));
  show();
  const update = await screen.findByText(t("characterNode.libraryUpdate"));
  expect(update).toHaveAttribute("title", t("characterNode.libraryUpdateHint"));
  expect(screen.getByText("v3")).toBeVisible();
  expect(screen.queryByText(t("characterNode.updateAvailable"))).not.toBeInTheDocument();
});

it("keeps the edge target picker reachable and restores focus after keyboard cancellation", async () => {
  show();
  const actions = await screen.findByRole("button", {name:t("characterNode.chooseTarget")});
  actions.focus(); await userEvent.keyboard("{Enter}");
  expect(await screen.findByRole("menuitem", {name:t("characterNode.noTarget")})).toHaveAttribute("aria-disabled","true");
  await userEvent.keyboard("{Escape}");
  expect(actions).toHaveFocus();
  expect(screen.queryByRole("menu")).not.toBeInTheDocument();
});
