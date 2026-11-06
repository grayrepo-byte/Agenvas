import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, it, vi } from "vitest";
import type { CanvasItem, CharacterProfile, MediaDraft } from "../../shared/api/client";
import type { ApplyCharacterNodeBindings, SaveCharacterNodeSelection } from "../../shared/api/characterNodes";
import { t } from "../../shared/i18n";
import { server } from "../../test/server";
import { CharacterNodeEditor, CharacterNodePlacementDialog } from "./CharacterCanvasCard";
import { useCharacterNodeRecovery } from "./characterNodeRecovery";
import { CharacterConnectionDialog } from "./CharacterConnectionDialog";

const BASE = "/api/v1/projects/project";
const NOW = "2026-11-06T00:00:00Z";
const profile: CharacterProfile = { id: "hero", projectId: "project", version: 3, archived: false, createdAt: NOW, updatedAt: NOW,
  content: { name: "Hero", description: "Pilot", voice: null, looks: [{ id: "look", name: "Flight suit", clothing: "", expression: "", description: "", views: [{ versionId: "front", purpose: "Front", description: "" }, { versionId: "side", purpose: "Side", description: "" }] }] } };
const role: CanvasItem = { id: "role", subjectType: "CHARACTER", subjectId: profile.id, title: "Hero", x: 0, y: 0, width: 320, height: 300,
  zIndex: 0, locked: false, groupId: null, version: 1, artifact: null, selectedVersionId: null, selectedVersion: null,
  characterNode: { canvasItemId: "role", version: 2, newerVersionAvailable: false, connections: [],
    selection: { characterId: profile.id, characterVersion: 3, lookId: "look", viewVersionIds: ["front"], useAppearance: true, useVoice: false },
    snapshot: { characterId: profile.id, characterVersion: 3, name: "Hero", description: "Pilot", look: profile.content.looks[0] ?? null, voice: null, useAppearance: true, useVoice: false } } };
const target: CanvasItem = { ...role, id: "shot", subjectType: "ARTIFACT", subjectId: "video", title: "Shot", characterNode: null,
  artifact: { id: "video", projectId: "project", kind: "VIDEO", title: "Shot", version: 0, resourceDefaultVersion: null, resourceDefaultVersionId: null, createdAt: NOW, updatedAt: NOW } };
const draft: MediaDraft = { projectId: "project", canvasItemId: "shot", version: 4, createdAt: NOW, updatedAt: NOW,
  prompt: "Manual prompt", parameters: {}, durationSeconds: 5, capabilityId: "video-cap", videoInputMode: "TEXT", styleId: null, mediaInputs: [], mentions: [], displayMode: "DRAFT" };
function mount(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  client.setQueryData(["canvas", "project"], { items: [role, target] });
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
  return client;
}
beforeEach(() => {
  useCharacterNodeRecovery.setState({ pending: {}, placements: {} });
  server.use(
  http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
  http.get(`${BASE}/characters`, () => HttpResponse.json({ characters: [profile], bindings: [] })),
  http.get(`${BASE}/artifacts`, () => HttpResponse.json({ items: [] })),
  http.get(`${BASE}/canvas-items/shot/media-draft`, () => HttpResponse.json(draft)),
);
});
function installPreview() {
  const connections: unknown[] = [], previews: unknown[] = [], applications: ApplyCharacterNodeBindings[] = [];
  server.use(http.post(`${BASE}/canvas/character-connections`, async ({ request }) => {
    connections.push(await request.json()); return HttpResponse.json({ id: "edge", sourceCanvasItemId: "role", targetCanvasItemId: "shot", version: 1 });
  }), http.post(`${BASE}/canvas-items/shot/character-node-bindings/preview`, async ({ request }) => {
    previews.push(await request.json()); return HttpResponse.json({ previewHash: "node-topology-hash", connections: [],
      characterPreview: { previewHash: "inner-character-hash", expectedBindingVersion: 0, expectedDraftVersion: 4, snapshots: [], warnings: [], changes: ["REFERENCES_ADDED"],
        candidateDraft: { ...draft, prompt: "Manual prompt\nHero", videoInputMode: "GENERAL_REFERENCE", mediaInputs: [{ role: "REFERENCE", versionId: "front", artifactId: "front-image", color: "#123456", order: 0, sources: [] }] } } });
  }), http.post(`${BASE}/canvas-items/shot/character-node-bindings/apply`, async ({ request }) => {
    applications.push(await request.json() as ApplyCharacterNodeBindings);
    return HttpResponse.json({ draft: { ...draft, version: 5 }, binding: { canvasItemId: "shot", version: 1 } });
  }), http.get(`${BASE}/canvas/character-connections/:connectionId/receipt`, ({ params }) => HttpResponse.json({ id: params.connectionId, sourceCanvasItemId: "role", targetCanvasItemId: "shot", version: 1 })),
  http.get(`${BASE}/canvas-items/shot/character-node-bindings/receipts/:commandId`, () => HttpResponse.json({ draft, binding: { canvasItemId: "shot", version: 1 } })));
  return { connections, previews, applications };
}
it("creates topology and previews before explicit application, using the topology hash and preserving the first-frame meaning", async () => {
  const calls = installPreview();
  mount(<CharacterConnectionDialog projectId="project" source={role} target={target} readOnly={false} onClose={() => {}} />);
  expect(await screen.findByLabelText(t("character.promptAfter"))).toHaveValue("Manual prompt\nHero");
  expect(calls.connections).toHaveLength(1); expect(calls.previews).toHaveLength(1); expect(calls.applications).toHaveLength(0);
  expect(screen.getByText(t("characterNode.counts", { images: 1, audio: 0 }))).toBeVisible();
  expect(screen.getByText(t("character.modePreview", { "0": "TEXT", "1": "GENERAL_REFERENCE" }))).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: t("character.applyDraft") }));
  await screen.findByText(t("character.appliedDraftOnly"));
  expect(calls.applications[0]).toMatchObject({ previewHash: "node-topology-hash", expectedDraftVersion: 4, clearMode: "REMOVE_OWNED" });
});
it("shows capability rejection without applying or changing the target draft", async () => {
  const calls = installPreview();
  server.use(http.post(`${BASE}/canvas-items/shot/character-node-bindings/preview`, () => HttpResponse.json({ title: "Unsupported", detail: "Synthetic model accepts first frames only; general references unsupported", code: "CHARACTER_UNSUPPORTED", retryable: false }, { status: 400, headers: { "Content-Type": "application/problem+json" } })));
  const client = mount(<CharacterConnectionDialog projectId="project" source={role} target={target} readOnly={false} onClose={() => {}} />);
  await screen.findByText("Synthetic model accepts first frames only; general references unsupported");
  expect(calls.applications).toHaveLength(0); expect(screen.getByRole("button", { name: t("character.applyDraft") })).toBeDisabled();
  expect(client.getQueryData(["media-draft", "project", "shot"])).toBeUndefined();
});
it("reconciles an uncertain application using the original frozen command", async () => {
  installPreview(); const commands: ApplyCharacterNodeBindings[] = [];
  server.use(http.post(`${BASE}/canvas-items/shot/character-node-bindings/apply`, async ({ request }) => {
    commands.push(await request.json() as ApplyCharacterNodeBindings);
    return commands.length === 1 ? HttpResponse.error() : HttpResponse.json({ draft, binding: { canvasItemId: "shot", version: 1 } });
  }));
  const close = vi.fn();
  mount(<CharacterConnectionDialog projectId="project" target={target} readOnly={false} onClose={close} />);
  expect(await screen.findByLabelText(t("character.promptAfter"))).toHaveValue("Manual prompt\nHero");
  await userEvent.click(screen.getByRole("button", { name: t("character.applyDraft") }));
  await screen.findByText(t("characterNode.reconcile"));
  expect(screen.getByRole("button", { name: t("ui.dialog.close") })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("character.appliedDraftOnly"));
  expect(commands).toHaveLength(1); expect(close).not.toHaveBeenCalled();
});
it("keeps reference choices after selection CAS failure and never writes a disabled node", async () => {
  const writes: SaveCharacterNodeSelection[] = [];
  server.use(http.put(`${BASE}/canvas/character-nodes/role/selection`, async ({ request }) => {
    writes.push(await request.json() as SaveCharacterNodeSelection);
    return HttpResponse.json({ title: "Conflict", detail: "Synthetic selection conflict", code: "CHARACTER_NODE_VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
  }));
  mount(<CharacterNodeEditor projectId="project" item={role} readOnly={false} onClose={() => {}} />);
  await userEvent.click(await screen.findByLabelText(/Side/));
  await userEvent.click(screen.getByRole("button", { name: t("character.confirmSave") }));
  await screen.findByText("Synthetic selection conflict");
  expect(screen.getByLabelText(/Side/)).toBeChecked();
  expect(writes[0]?.selection.viewVersionIds).toEqual(["front", "side"]);
});
it("keeps a placement identity and frozen configuration when reconciling a lost response", async () => {
  const paths: string[] = [], requests: unknown[] = [];
  server.use(http.put(`${BASE}/canvas/character-nodes/:itemId`, async ({ params, request }) => {
    paths.push(String(params.itemId)); requests.push(await request.json());
    return HttpResponse.error();
  }), http.get(`${BASE}/canvas/items`, () => HttpResponse.json({ items: [{ ...role, id: paths[0] }] })));
  const placed = vi.fn();
  mount(<CharacterNodePlacementDialog projectId="project" point={{ x: 20, y: 40 }} zIndex={3} readOnly={false} onPlaced={placed} onManage={() => {}} onClose={() => {}} />);
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.place") }));
  await screen.findByText(t("characterNode.reconcile"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(placed).toHaveBeenCalledOnce());
  expect(paths).toHaveLength(1); expect(requests).toHaveLength(1);
});

it("acknowledges an already committed selection by reading its projection instead of repeating the stale CAS", async () => {
  let writes = 0;
  let saved: SaveCharacterNodeSelection | null = null;
  const close = vi.fn();
  server.use(http.put(`${BASE}/canvas/character-nodes/role/selection`, async ({ request }) => {
    writes++; saved = await request.json() as SaveCharacterNodeSelection; return HttpResponse.error();
  }), http.get(`${BASE}/canvas/items`, () => HttpResponse.json({ items: saved && role.characterNode ? [{ ...role, characterNode: { ...role.characterNode, version: 3,
    // JSON member order is not business identity.
    selection: { viewVersionIds: saved.selection.viewVersionIds, useVoice: saved.selection.useVoice, useAppearance: saved.selection.useAppearance,
      characterId: saved.selection.characterId, characterVersion: saved.selection.characterVersion, lookId: saved.selection.lookId } } }] : [role] })));
  mount(<CharacterNodeEditor projectId="project" item={role} readOnly={false} onClose={close} />);
  await userEvent.click(await screen.findByLabelText(/Side/));
  await userEvent.click(screen.getByRole("button", { name: t("character.confirmSave") }));
  await screen.findByText(t("characterNode.reconcile"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(close).toHaveBeenCalledOnce());
  expect(writes).toBe(1);
});

it.each(["locked", "archived"] as const)("keeps %s character configuration read-only", async (state) => {
  let writes = 0;
  server.use(http.put(`${BASE}/canvas/character-nodes/role/selection`, () => { writes++; return HttpResponse.json(role.characterNode); }));
  if (state === "archived") server.use(http.get(`${BASE}/characters`, () => HttpResponse.json({ characters: [{ ...profile, archived: true }], bindings: [] })));
  mount(<CharacterNodeEditor projectId="project" item={{ ...role, locked: state === "locked" }} readOnly={false} onClose={() => {}} />);
  await screen.findByLabelText(/Side/);
  await waitFor(() => expect(screen.getByLabelText(/Side/)).toBeDisabled());
  expect(screen.getByRole("button", { name: t("character.confirmSave") })).toBeDisabled();
  expect(writes).toBe(0);
});

it("edits unselected images from a complete pinned revision without silently upgrading to the current profile", async () => {
  const oldLook = profile.content.looks[0];
  if (!oldLook || !role.characterNode) throw new Error("Missing role fixture");
  const pinned: CanvasItem = { ...role, characterNode: { ...role.characterNode, newerVersionAvailable: true,
    snapshot: { ...role.characterNode.snapshot, look: { ...oldLook, views: oldLook.views.slice(0, 1) } } } };
  const writes: SaveCharacterNodeSelection[] = [];
  server.use(http.get(`${BASE}/characters`, () => HttpResponse.json({ characters: [{ ...profile, version: 4, content: { ...profile.content, looks: [] } }], bindings: [] })),
    http.get(`${BASE}/characters/hero/revisions/3`, () => HttpResponse.json(profile)),
    http.put(`${BASE}/canvas/character-nodes/role/selection`, async ({ request }) => { writes.push(await request.json() as SaveCharacterNodeSelection); return HttpResponse.json(role.characterNode); }));
  mount(<CharacterNodeEditor projectId="project" item={pinned} readOnly={false} onClose={() => {}} />);
  await userEvent.click(await screen.findByLabelText(/Side/));
  await userEvent.click(screen.getByRole("button", { name: t("character.confirmSave") }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]?.selection).toMatchObject({ characterVersion: 3, viewVersionIds: ["front", "side"] });
});
it("asks before closing failed selection edits and retains them when the user keeps editing", async () => {
  const close = vi.fn();
  server.use(http.put(`${BASE}/canvas/character-nodes/role/selection`, () => HttpResponse.json({ title: "Conflict", detail: "Synthetic conflict", code: "CHARACTER_NODE_VERSION_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } })));
  mount(<CharacterNodeEditor projectId="project" item={role} readOnly={false} onClose={close} />);
  await userEvent.click(await screen.findByLabelText(/Side/));
  await userEvent.click(screen.getByRole("button", { name: t("character.confirmSave") }));
  await screen.findByText("Synthetic conflict");
  await userEvent.keyboard("{Escape}");
  expect(await screen.findByRole("dialog", { name: t("common.unsavedChanges") })).toBeVisible();
  expect(close).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: t("common.continueEditing") }));
  expect(screen.getByLabelText(/Side/)).toBeChecked();
  await userEvent.click(screen.getByRole("button", { name: t("ui.dialog.close") }));
  await userEvent.click(await screen.findByRole("button", { name: t("characterNode.discard") }));
  expect(close).toHaveBeenCalledOnce();
});

it("keeps an unconfirmed connection identity when no receipt exists and allows explicit exit", async () => {
  const requests: unknown[] = []; const close = vi.fn();
  server.use(http.post(`${BASE}/canvas/character-connections`, async ({ request }) => {
    requests.push(await request.json()); return HttpResponse.error();
  }), http.get(`${BASE}/canvas/character-connections/:connectionId/receipt`, () => HttpResponse.json({ title: "Missing", detail: "Synthetic missing receipt", code: "RESOURCE_NOT_FOUND" }, { status: 404, headers: { "Content-Type": "application/problem+json" } })));
  mount(<CharacterConnectionDialog projectId="project" source={role} target={target} readOnly={false} onClose={close} />);
  await screen.findByText(t("characterNode.reconcile"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("characterNode.receiptMissing"));
  expect(screen.getByText(t("characterNode.reconcile"))).toBeVisible();
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.pendingExit") }));
  expect(close).toHaveBeenCalledOnce(); expect(requests).toHaveLength(1);
  expect(useCharacterNodeRecovery.getState().pending["project:shot"]?.kind).toBe("connection");
});
it.each(["text", "voice"] as const)("places a %s-only character with an intentional supported purpose", async (purpose) => {
  const requests: { selection: { lookId: string | null; useAppearance: boolean; useVoice: boolean; viewVersionIds: string[] } }[] = [];
  server.use(http.get(`${BASE}/characters`, () => HttpResponse.json({ characters: [{ ...profile, content: { ...profile.content, looks: [], voice: purpose === "voice" ? { versionId: null, speakerId: "speaker", description: "Voice" } : null } }], bindings: [] })),
    http.put(`${BASE}/canvas/character-nodes/:itemId`, async ({ request }) => {
      requests.push(await request.json() as typeof requests[number]);
      return HttpResponse.json({ item: role, artifact: null, selectedVersion: null, characterNode: role.characterNode });
    }));
  mount(<CharacterNodePlacementDialog projectId="project" point={{ x: 0, y: 0 }} zIndex={0} readOnly={false} onPlaced={() => {}} onManage={() => {}} onClose={() => {}} />);
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.place") }));
  await waitFor(() => expect(requests).toHaveLength(1));
  expect(requests[0]?.selection).toMatchObject({ lookId: null, viewVersionIds: [], useAppearance: purpose === "text", useVoice: purpose === "voice" });
});

it.each(["source", "target", "archived"] as const)("reconciles only the original uncertain connection after the %s node becomes locked", async (locked) => {
  const calls = installPreview(); const requests: unknown[] = [];
  server.use(http.post(`${BASE}/canvas/character-connections`, async ({ request }) => {
    const input = await request.json() as { connectionId: string }; requests.push(input);
    return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ id: input.connectionId, sourceCanvasItemId: "role", targetCanvasItemId: "shot", version: 1 });
  }), http.get(`${BASE}/canvas/character-connections/:connectionId/receipt`, ({ params }) => {
    expect(requests[0]).toMatchObject({ connectionId: params.connectionId });
    return HttpResponse.json({ id: params.connectionId, sourceCanvasItemId: "role", targetCanvasItemId: "shot", version: 1 });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const close = vi.fn();
  const renderDialog = (changed: boolean) => <QueryClientProvider client={client}><CharacterConnectionDialog projectId="project"
    source={{ ...role, locked: changed && locked === "source" }} target={{ ...target, locked: changed && locked === "target" }} readOnly={changed && locked === "archived"} onClose={close} /></QueryClientProvider>;
  const view = render(renderDialog(false));
  await screen.findByText(t("characterNode.reconcile"));
  view.rerender(renderDialog(true));
  const reconcile = screen.getByRole("button", { name: t("characterNode.reconcileAction") });
  expect(reconcile).toBeEnabled();
  await userEvent.click(reconcile);
  await waitFor(() => expect(screen.queryByText(t("characterNode.reconcile"))).not.toBeInTheDocument());
  expect(requests).toHaveLength(1);
  expect(calls.previews).toHaveLength(0); expect(calls.applications).toHaveLength(0);
  expect(screen.getByRole("button", { name: t("character.applyDraft") })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: t("ui.dialog.close") }));
  expect(close).toHaveBeenCalledOnce();
});
it.each(["source", "target", "archived"] as const)("reconciles only the original uncertain application after the %s node becomes locked", async (locked) => {
  const calls = installPreview(); const requests: ApplyCharacterNodeBindings[] = [];
  server.use(http.post(`${BASE}/canvas-items/shot/character-node-bindings/apply`, async ({ request }) => {
    requests.push(await request.json() as ApplyCharacterNodeBindings);
    return requests.length === 1 ? HttpResponse.error() : HttpResponse.json({ draft, binding: { canvasItemId: "shot", version: 1 } });
  }), http.get(`${BASE}/canvas-items/shot/character-node-bindings/receipts/:commandId`, ({ params }) => {
    expect(requests[0]?.commandId).toBe(params.commandId);
    return HttpResponse.json({ draft, binding: { canvasItemId: "shot", version: 1 } });
  }));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const renderDialog = (changed: boolean) => <QueryClientProvider client={client}><CharacterConnectionDialog projectId="project"
    source={{ ...role, locked: changed && locked === "source" }} target={{ ...target, locked: changed && locked === "target" }} readOnly={changed && locked === "archived"} onClose={() => {}} /></QueryClientProvider>;
  const view = render(renderDialog(false));
  await screen.findByLabelText(t("character.promptAfter"));
  await userEvent.click(screen.getByRole("button", { name: t("character.applyDraft") }));
  await screen.findByText(t("characterNode.reconcile"));
  view.rerender(renderDialog(true));
  expect(screen.getByRole("button", { name: t("characterNode.reconcileAction") })).toBeEnabled();
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("character.appliedDraftOnly"));
  expect(requests).toHaveLength(1);
  expect(calls.previews).toHaveLength(1); expect(calls.connections).toHaveLength(1);
  expect(screen.getByRole("button", { name: t("ui.dialog.close") })).toBeEnabled();
  expect(screen.getByRole("button", { name: t("character.previewApply") })).toBeDisabled();
});

it.each(["connection", "apply"] as const)("keeps the original %s identity after a missing receipt and explicit exit, then restores it without another write", async (kind) => {
  const calls = installPreview(); const submitted: { connectionId?: string; commandId?: string }[] = [];
  const lookups: string[] = [];
  const close = vi.fn();
  const loseResponse = async ({ request }: { request: Request }) => {
    submitted.push(await request.json() as typeof submitted[number]); return HttpResponse.error();
  };
  const receipt = ({ params }: { params: Record<string, string | readonly string[]> }) => {
    const identity = String(kind === "connection" ? params.connectionId : params.commandId); lookups.push(identity);
    if (lookups.length === 1) return HttpResponse.json({ title: "Missing", detail: "No receipt yet", code: "RESOURCE_NOT_FOUND" }, { status: 404, headers: { "Content-Type": "application/problem+json" } });
    return kind === "connection" ? HttpResponse.json({ id: identity, sourceCanvasItemId: "role", targetCanvasItemId: "shot", version: 1 })
      : HttpResponse.json({ draft, binding: { canvasItemId: "shot", version: 1 } });
  };
  server.use(kind === "connection" ? http.post(`${BASE}/canvas/character-connections`, loseResponse) : http.post(`${BASE}/canvas-items/shot/character-node-bindings/apply`, loseResponse),
    kind === "connection" ? http.get(`${BASE}/canvas/character-connections/:connectionId/receipt`, receipt) : http.get(`${BASE}/canvas-items/shot/character-node-bindings/receipts/:commandId`, receipt));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const renderDialog = (archived: boolean) => <QueryClientProvider client={client}><CharacterConnectionDialog projectId="project" source={role}
    target={{ ...target, locked: archived }} readOnly={archived} onClose={close} /></QueryClientProvider>;
  const first = render(renderDialog(false));
  if (kind === "apply") {
    await screen.findByLabelText(t("character.promptAfter"));
    await userEvent.click(screen.getByRole("button", { name: t("character.applyDraft") }));
  }
  await screen.findByText(t("characterNode.reconcile"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("characterNode.receiptMissing"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.pendingExit") }));
  expect(close).toHaveBeenCalledOnce();
  first.unmount();
  render(renderDialog(true));
  expect(screen.getByRole("button", { name: t("characterNode.reconcileAction") })).toBeEnabled();
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(useCharacterNodeRecovery.getState().pending["project:shot"]).toBeUndefined());
  expect(submitted).toHaveLength(1);
  const identity = kind === "connection" ? submitted[0]?.connectionId : submitted[0]?.commandId;
  expect(lookups).toEqual([identity, identity]);
  expect(calls.previews).toHaveLength(kind === "connection" ? 0 : 1);
  expect(screen.getByRole("button", { name: t("ui.dialog.close") })).toBeEnabled();
});

it.each(["archived", "locked"] as const)("reads an uncertain creation by its original ID after it becomes %s, without creating again", async (state) => {
  const writes: { id: string; request: { selection: NonNullable<CanvasItem["characterNode"]>["selection"] } }[] = [];
  server.use(http.put(`${BASE}/canvas/character-nodes/:itemId`, async ({ params, request }) => {
    writes.push({ id: String(params.itemId), request: await request.json() as typeof writes[number]["request"] }); return HttpResponse.error();
  }), http.get(`${BASE}/canvas/items`, () => {
    const original = writes[0];
    return HttpResponse.json({ items: original && role.characterNode ? [{ ...role, id: original.id, locked: state === "locked",
      characterNode: { ...role.characterNode, canvasItemId: original.id, selection: original.request.selection } }] : [] });
  }));
  const placed = vi.fn(); const close = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const dialog = (changed: boolean) => <QueryClientProvider client={client}><CharacterNodePlacementDialog projectId="project"
    point={{ x: 0, y: 0 }} zIndex={0} readOnly={changed && state === "archived"} onPlaced={placed} onManage={() => {}} onClose={close} /></QueryClientProvider>;
  const view = render(dialog(false));
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.place") }));
  await screen.findByText(t("characterNode.reconcile"));
  view.rerender(dialog(true));
  expect(screen.getByRole("button", { name: t("characterNode.reconcileAction") })).toBeEnabled();
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(placed).toHaveBeenCalledOnce());
  expect(writes).toHaveLength(1);
  expect(placed.mock.calls[0]?.[0]).toMatchObject({ id: writes[0]?.id, locked: state === "locked" });
});
it("retains an uncertain creation with no projected record across explicit exit and archived reopening without a second POST", async () => {
  const writes: { id: string; request: unknown }[] = [];
  let visible = false;
  server.use(http.put(`${BASE}/canvas/character-nodes/:itemId`, async ({ params, request }) => {
    writes.push({ id: String(params.itemId), request: await request.json() }); return HttpResponse.error();
  }), http.get(`${BASE}/canvas/items`, () => HttpResponse.json({ items: visible ? [{ ...role, id: writes[0]?.id }] : [] })));
  const placed = vi.fn(); const close = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  const dialog = (archived: boolean) => <QueryClientProvider client={client}><CharacterNodePlacementDialog projectId="project"
    point={{ x: 0, y: 0 }} zIndex={0} readOnly={archived} onPlaced={placed} onManage={() => {}} onClose={close} initialProfileId={archived ? profile.id : undefined} /></QueryClientProvider>;
  const view = render(dialog(false));
  await userEvent.click(await screen.findByRole("button", { name: t("canvas.place") }));
  await screen.findByText(t("characterNode.reconcile"));
  view.rerender(dialog(true));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await screen.findByText(t("characterNode.receiptMissing"));
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.pendingExit") }));
  expect(close).toHaveBeenCalledOnce(); expect(placed).not.toHaveBeenCalled();
  view.unmount();
  render(dialog(true));
  expect(screen.getByText(writes[0]?.id ?? "missing-original-id")).toBeVisible();
  visible = true;
  await userEvent.click(screen.getByRole("button", { name: t("characterNode.reconcileAction") }));
  await waitFor(() => expect(placed).toHaveBeenCalledOnce());
  expect(writes).toHaveLength(1);
});
