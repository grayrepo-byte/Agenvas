import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, renderHook, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { PropsWithChildren } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CanvasEditRequest, CanvasItem, MediaDraft, SaveMediaDraftRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { flushCanvasEditors, useCanvasEditingGuard } from "./canvasEditingGuards";
import { useCanvasStore } from "./canvasStore";
import { TextCanvasEditor } from "./TextCanvasEditor";
import { useCanvasOrganization } from "./useCanvasOrganization";
import { fieldsFromMediaDraft, useMediaDraftSession, useMediaDraftSessionLifecycle } from "./useMediaDraftSession";
import type { VersionedArtifact } from "./versionedArtifact";

const PROJECT = "guard-project";
const ITEM = "guard-item";
const RECOVERY_KEY = `${PROJECT}:${ITEM}`;
const NOW = "2026-10-23T00:00:00Z";
const DRAFT_URL = `/api/v1/projects/${PROJECT}/canvas-items/${ITEM}/media-draft`;
const EDITOR_URL = `/api/v1/projects/${PROJECT}/canvas/editor`;
const EDIT_URL = `/api/v1/projects/${PROJECT}/canvas/edits`;
const initialDraft: MediaDraft = { projectId: PROJECT, canvasItemId: ITEM, prompt: "Original", parameters: {},
  durationSeconds: null, capabilityId: null, styleId: null, videoInputMode: null, mediaInputs: [], mentions: [],
  displayMode: "DRAFT", version: 0, createdAt: NOW, updatedAt: NOW };
const textArtifact: VersionedArtifact = { id: "text-artifact", projectId: PROJECT, kind: "TEXT", title: "Script",
  version: 3, resourceDefaultVersionId: "text-v1", createdAt: NOW, updatedAt: NOW,
  resourceDefaultVersion: { id: "text-v1", versionNo: 1, schemaVersion: 1,
    content: { format: "PLAIN_TEXT", text: "Original text" }, inputReferences: [], createdByKind: "USER",
    runId: null, createdAt: NOW } };
const mediaItem: CanvasItem = { id: ITEM, subjectType: "ARTIFACT", subjectId: "image-artifact", title: "Image",
  x: 0, y: 0, width: 280, height: 180, zIndex: 0, groupId: null, locked: false,
  selectedVersionId: null, selectedVersion: null, version: 2,
  artifact: { id: "image-artifact", projectId: PROJECT, kind: "IMAGE", title: "Image", version: 0,
    resourceDefaultVersionId: null, resourceDefaultVersion: null, createdAt: NOW, updatedAt: NOW } };
const requestFor = (draft = initialDraft): SaveMediaDraftRequest => ({ ...fieldsFromMediaDraft(draft), expectedVersion: draft.version });

function gate() {
  let release = () => {};
  const promise = new Promise<void>((resolve) => { release = resolve; });
  return { promise, release };
}
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  const wrapper = ({ children }: PropsWithChildren) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return { client, wrapper };
}

beforeEach(() => {
  useCanvasStore.setState({ drafts: {}, mediaDraftRecoveries: {}, imageRatioDrafts: {}, saveState: "saved" });
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })));
});

describe("canvas editor flush registration", () => {
  it("uses the latest callback, isolates projects and unregisters the editor on unmount", async () => {
    const first = vi.fn(async () => {});
    const latest = vi.fn(async () => {});
    const { rerender, unmount } = renderHook(({ flush }) => useCanvasEditingGuard(PROJECT, ITEM, flush),
      { initialProps: { flush: first } });
    await flushCanvasEditors("another-project", [ITEM]);
    expect(first).not.toHaveBeenCalled();
    rerender({ flush: latest });
    await flushCanvasEditors(PROJECT, [ITEM]);
    expect(first).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledTimes(1);
    unmount();
    await flushCanvasEditors(PROJECT, [ITEM]);
    expect(latest).toHaveBeenCalledTimes(1);
  });

  it("waits for an unmounted editor's close-save and refuses a retained recovery draft", async () => {
    const request = requestFor();
    useCanvasStore.getState().setMediaDraftRecovery(RECOVERY_KEY, { request, saving: true, error: null });
    let finished = false;
    const flushing = flushCanvasEditors(PROJECT, [ITEM]).then(() => { finished = true; });
    await Promise.resolve();
    expect(finished).toBe(false);
    useCanvasStore.getState().clearMediaDraftRecovery(RECOVERY_KEY);
    await flushing;
    expect(finished).toBe(true);
    useCanvasStore.getState().setMediaDraftRecovery(RECOVERY_KEY, { request, saving: false, error: new Error("Save failed") });
    await expect(flushCanvasEditors(PROJECT, [ITEM])).rejects.toThrow("未完成");
    expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.request).toEqual(request);
  });

  it("stops copying when a close-save settles with a failure instead of dropping its input", async () => {
    const request = { ...requestFor(), prompt: "Preserve this complete input" };
    useCanvasStore.getState().setMediaDraftRecovery(RECOVERY_KEY, { request, saving: true, error: null });
    const flushing = flushCanvasEditors(PROJECT, [ITEM]);
    const rejected = expect(flushing).rejects.toThrow("未完成");
    useCanvasStore.getState().setMediaDraftRecovery(RECOVERY_KEY, { request, saving: false, error: new Error("Conflict") });
    await rejected;
    expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.request.prompt).toBe(request.prompt);
  });
});

describe("text copy flush uses immutable revisions", () => {
  it("refuses an empty local text draft without creating a revision", async () => {
    const revisions = vi.fn();
    server.use(http.post(`/api/v1/projects/${PROJECT}/artifacts/${textArtifact.id}/revisions`, () => {
      revisions();
      return HttpResponse.json(textArtifact);
    }));
    const { wrapper } = setup();
    render(<TextCanvasEditor artifact={textArtifact} canvasItemId={ITEM} locked={false} onDone={() => {}} />, { wrapper });
    fireEvent.change(screen.getByRole("textbox", { name: "内容" }), { target: { value: "   " } });
    await expect(flushCanvasEditors(PROJECT, [ITEM])).rejects.toThrow("未完成");
    expect(revisions).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("   ");
  });

  it("saves the current text once before allowing a copy and refuses a second flush while saving", async () => {
    const pending = gate();
    const revisions: unknown[] = [];
    server.use(http.post(`/api/v1/projects/${PROJECT}/artifacts/${textArtifact.id}/revisions`, async ({ request }) => {
      revisions.push(await request.json());
      await pending.promise;
      return HttpResponse.json({ ...textArtifact, version: 4, resourceDefaultVersionId: "text-v2",
        resourceDefaultVersion: { ...textArtifact.resourceDefaultVersion, id: "text-v2", versionNo: 2,
          content: { format: "PLAIN_TEXT", text: "Revised for this copy" } } });
    }));
    const { wrapper } = setup();
    const view = render(<TextCanvasEditor artifact={textArtifact} canvasItemId={ITEM} locked={false} onDone={() => {}} />, { wrapper });
    fireEvent.change(screen.getByRole("textbox", { name: "内容" }), { target: { value: "  Revised for this copy  " } });
    let flushing: Promise<void> | undefined;
    act(() => { flushing = flushCanvasEditors(PROJECT, [ITEM]); });
    try {
      await waitFor(() => expect(revisions).toHaveLength(1));
      expect(revisions[0]).toEqual({ expectedVersion: 3, title: "Script",
        content: { format: "PLAIN_TEXT", text: "Revised for this copy" } });
      await act(async () => { await expect(flushCanvasEditors(PROJECT, [ITEM])).rejects.toThrow("未完成"); });
      expect(revisions).toHaveLength(1);
      await act(async () => { pending.release(); await flushing; });
      expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("Revised for this copy");
      await act(async () => { await flushCanvasEditors(PROJECT, [ITEM]); });
      expect(revisions).toHaveLength(1);
      view.unmount();
      await flushCanvasEditors(PROJECT, [ITEM]);
      expect(revisions).toHaveLength(1);
    } finally { pending.release(); await flushing; }
  });

  it("retains a conflicted text draft and blocks subsequent copy without silently resubmitting", async () => {
    let revisions = 0;
    server.use(http.post(`/api/v1/projects/${PROJECT}/artifacts/${textArtifact.id}/revisions`, () => {
      revisions += 1;
      return HttpResponse.json({ code: "ARTIFACT_VERSION_CONFLICT", detail: "A concurrent text revision" },
        { status: 409, headers: { "Content-Type": "application/problem+json" } });
    }));
    const { wrapper } = setup();
    render(<TextCanvasEditor artifact={textArtifact} canvasItemId={ITEM} locked={false} onDone={() => {}} />, { wrapper });
    fireEvent.change(screen.getByRole("textbox", { name: "内容" }), { target: { value: "Keep my text" } });
    await act(async () => { await expect(flushCanvasEditors(PROJECT, [ITEM])).rejects.toThrow("A concurrent text revision"); });
    expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("Keep my text");
    await act(async () => { await expect(flushCanvasEditors(PROJECT, [ITEM])).rejects.toThrow("未完成"); });
    expect(revisions).toBe(1);
  });
});

function mediaCopyHarness(autosaveBlocked: boolean) {
  const state = { draft: initialDraft };
  const saves: SaveMediaDraftRequest[] = [];
  const copies: CanvasEditRequest[] = [];
  server.use(
    http.get(DRAFT_URL, () => HttpResponse.json(state.draft)),
    http.get(EDITOR_URL, () => HttpResponse.json({ items: [mediaItem], groups: [] })),
    http.post(EDIT_URL, async ({ request }) => {
      const input = await request.json() as CanvasEditRequest;
      copies.push(input);
      return HttpResponse.json({ items: [mediaItem, { ...mediaItem, id: input.copies![0]!.targetItemId }], groups: [],
        receipt: { id: input.commandId, operation: input.operation, revision: 0, state: "APPLIED" } });
    }),
  );
  const { wrapper, client } = setup();
  const hook = renderHook(() => {
    const session = useMediaDraftSession(PROJECT, ITEM);
    useMediaDraftSessionLifecycle(session, "IMAGE", { readOnly: false, refreshBlocked: false, autosaveBlocked, closeBlocked: false });
    const organization = useCanvasOrganization({ projectId: PROJECT, items: [mediaItem], enabled: true,
      selectedIds: [ITEM], select: () => {}, effectiveNodeSize: (item) => ({ width: item.width, height: item.height }) });
    return { session, organization };
  }, { wrapper });
  return { ...hook, state, saves, copies, client };
}

describe("media copy waits for acknowledged drafts", () => {
  it("blocks the copy when a retained recovery still owns unsaved input", async () => {
    const { result, copies } = mediaCopyHarness(true);
    await waitFor(() => expect(result.current.session.state.fields).not.toBeNull());
    const request = { ...requestFor(), prompt: "Retained after close" };
    act(() => useCanvasStore.getState().setMediaDraftRecovery(RECOVERY_KEY, {
      request, saving: false, error: new Error("Close-save failed"),
    }));
    await act(async () => { await expect(result.current.organization.duplicateItems()).rejects.toThrow("未完成"); });
    expect(copies).toEqual([]);
    expect(result.current.organization.actionError?.message).toContain("未完成");
    expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.request).toEqual(request);
  });

  it("waits for autosave, saves edits made during it, then pins the latest draft version for duplication", async () => {
    const pending = gate();
    const { result, state, saves, copies } = mediaCopyHarness(false);
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      saves.push(input);
      if (saves.length === 1) await pending.promise;
      if (input.expectedVersion !== state.draft.version) return HttpResponse.json({ code: "MEDIA_DRAFT_CONFLICT", detail: "Stale media draft CAS" },
        { status: 409, headers: { "Content-Type": "application/problem+json" } });
      state.draft = { ...initialDraft, ...input, mediaInputs: [], version: input.expectedVersion + 1 };
      return HttpResponse.json(state.draft);
    }));
    await waitFor(() => expect(result.current.session.state.fields).not.toBeNull());
    act(() => result.current.session.edit((fields) => ({ ...fields, prompt: "Autosave in progress" })));
    await waitFor(() => expect(saves).toHaveLength(1));
    act(() => result.current.session.edit((fields) => ({ ...fields, prompt: "Latest complete prompt" })));
    let copying: ReturnType<typeof result.current.organization.duplicateItems> | undefined;
    act(() => { copying = result.current.organization.duplicateItems(); });
    try {
      expect(copies).toEqual([]);
      expect(saves).toHaveLength(1);
      await act(async () => { pending.release(); await copying; });
      expect(saves.map((input) => ({ version: input.expectedVersion, prompt: input.prompt }))).toEqual([
        { version: 0, prompt: "Autosave in progress" }, { version: 1, prompt: "Latest complete prompt" },
      ]);
      expect(copies).toHaveLength(1);
      expect(copies[0]?.copies?.[0]).toMatchObject({ sourceItemId: ITEM,
        expectedSourceVersion: 2, expectedSourceDraftVersion: 2 });
      expect(state.draft.prompt).toBe("Latest complete prompt");
      expect(result.current.session.state.dirty).toBe(false);
    } finally { pending.release(); await copying; }
  });

  it("waits for a business write to merge its acknowledged CAS before saving later edits for the copy", async () => {
    const { result, state, saves, copies } = mediaCopyHarness(true);
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      saves.push(input);
      state.draft = { ...initialDraft, ...input, mediaInputs: [], version: input.expectedVersion + 1 };
      return HttpResponse.json(state.draft);
    }));
    await waitFor(() => expect(result.current.session.state.fields).not.toBeNull());
    let finish: (error: Error | null) => void = () => {};
    act(() => {
      finish = result.current.session.beginBusinessWrite();
      result.current.session.edit((fields) => ({ ...fields, prompt: "Edit made during reference transfer" }));
    });
    let copying: ReturnType<typeof result.current.organization.duplicateItems> | undefined;
    act(() => { copying = result.current.organization.duplicateItems(); });
    expect(saves).toEqual([]);
    expect(copies).toEqual([]);
    await act(async () => {
      state.draft = { ...initialDraft, capabilityId: "accepted-capability", version: 4 };
      result.current.session.acceptSaved(state.draft, { fields: { ...result.current.session.getLatest().fields!,
        capabilityId: "accepted-capability" }, dirty: true });
      finish(null);
      await copying;
    });
    expect(saves).toHaveLength(1);
    expect(saves[0]).toMatchObject({ expectedVersion: 4, capabilityId: "accepted-capability",
      prompt: "Edit made during reference transfer" });
    expect(copies[0]?.copies?.[0]?.expectedSourceDraftVersion).toBe(5);
  });

  it("refuses duplication when a pending business write fails and preserves the local media fields", async () => {
    const { result, saves, copies } = mediaCopyHarness(true);
    await waitFor(() => expect(result.current.session.state.fields).not.toBeNull());
    let finish: (error: Error | null) => void = () => {};
    act(() => {
      finish = result.current.session.beginBusinessWrite();
      result.current.session.edit((fields) => ({ ...fields, prompt: "Do not drop this prompt" }));
    });
    let copying: ReturnType<typeof result.current.organization.duplicateItems> | undefined;
    act(() => { copying = result.current.organization.duplicateItems(); });
    await act(async () => {
      const rejected = expect(copying).rejects.toThrow("Reference transfer failed");
      const failure = new Error("Reference transfer failed");
      result.current.session.setError(failure);
      finish(failure);
      await rejected;
    });
    expect(saves).toEqual([]);
    expect(copies).toEqual([]);
    expect(result.current.session.state.fields?.prompt).toBe("Do not drop this prompt");
    expect(result.current.session.state.dirty).toBe(true);
    expect(result.current.organization.actionError?.message).toBe("Reference transfer failed");
  });
});
