import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { beforeEach, describe, expect, it } from "vitest";
import type { Artifact, MediaDraft, MediaSettings, SaveMediaDraftRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { klingProfiles } from "../../test/klingProfiles";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { useCanvasStore } from "./canvasStore";
import { projectCacheCallbacks } from "./projectCache";

const PROJECT_ID = "persistence-project";
const ITEM_ID = "persistence-card";
const ARTIFACT_ID = "persistence-artifact";
const REFERENCE_ID = "reference-artifact";
const REFERENCE_VERSION = "reference-version";
const NOW = "2026-10-11T00:00:00Z";
const DRAFT_URL = `/api/v1/projects/${PROJECT_ID}/canvas-items/${ITEM_ID}/media-draft`;
const RECOVERY_KEY = `${PROJECT_ID}:${ITEM_ID}`;
const QUERY_KEY = ["media-draft", PROJECT_ID, ITEM_ID] as const;
const artifact: Artifact = {
  id: ARTIFACT_ID, projectId: PROJECT_ID, kind: "IMAGE", title: "Synthetic image",
  resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0,
  createdAt: NOW, updatedAt: NOW,
};
const initialDraft: MediaDraft = {
  projectId: PROJECT_ID, canvasItemId: ITEM_ID, prompt: "Initial prompt", parameters: {},
  durationSeconds: null, capabilityId: null, styleId: null, videoInputMode: null,
  mediaInputs: [], mentions: [], displayMode: "DRAFT", version: 0, createdAt: NOW, updatedAt: NOW,
};
const settings: MediaSettings = {
  connections: [{ id: "provider", name: "Synthetic provider", platform: "MOCK", enabled: true,
    version: 0, connectionVersion: 1, origin: null, keyMask: null,
    connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [{ id: "image-model", name: "Synthetic model", enabled: true,
      version: 0, capabilityVersion: 1, adapterId: "MOCK_IMAGE", kind: "IMAGE_GENERATION",
      minimumSeconds: 0, maximumSeconds: 0, maxReferenceImages: 4, maxReferenceVideos: 0,
      maxReferenceAudios: 0, supportedVideoInputModes: [], defaultVideoInputMode: null,
      supportsEndFrame: false, supportedImageAspectRatios: ["AUTO", "1:1", "9:16"],
      supportedImageResolutions: ["1K"], supportedImageQualities: [],
      supportsTransparentBackground: false, supportsImageMask: false, mappingSha256: "0".repeat(64), settings: {} }] }],
  defaults: [{ kind: "IMAGE_GENERATION", capabilityId: "image-model", version: 0 }],
};

function conflict() {
  return HttpResponse.json({ code: "MEDIA_DRAFT_CONFLICT", title: "Synthetic conflict",
    detail: "Synthetic conflict", retryable: false }, { status: 409 });
}

function delayedResponse() {
  let release = () => {};
  const ready = new Promise<void>((resolve) => { release = resolve; });
  return { ready, release };
}

function savedDraft(input: SaveMediaDraftRequest): MediaDraft {
  return { ...initialDraft, ...input, version: input.expectedVersion + 1,
    mediaInputs: input.mediaInputs.map((item, order) => ({ ...item, order,
      artifactId: REFERENCE_ID,
      sources: [{ id: "manual-source", type: "MANUAL", connectionId: null }] })) };
}

/** The button reproduces closing the selected card's editor, including its real unmount cleanup. */
function SelectedEditor() {
  const [open, setOpen] = useState(true);
  return <>
    <button onClick={() => setOpen((current) => !current)}>{open ? "关闭测试编辑区" : "重新打开测试编辑区"}</button>
    {open ? <MediaDraftEditor artifact={artifact} canvasItemId={ITEM_ID} /> : null}
  </>;
}

function renderDraft(draft: MediaDraft = initialDraft, mediaSettings: MediaSettings = settings) {
  const state = { draft };
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(mediaSettings)),
    http.get(DRAFT_URL, () => HttpResponse.json(state.draft)),
    http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/${ARTIFACT_ID}/run`, () => HttpResponse.json([])),
    http.get(`/api/v1/projects/${PROJECT_ID}/canvas/items`, () => HttpResponse.json({ items: [] })),
    http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
      id: REFERENCE_ID, title: "Synthetic reference", resourceDefaultVersionId: REFERENCE_VERSION }] })),
    http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/${REFERENCE_ID}/versions`, () => HttpResponse.json({ items: [{
      id: REFERENCE_VERSION, versionNo: 1, schemaVersion: 1, content: { sourceType: "UPLOAD", assetId: "synthetic-asset" },
      inputReferences: [], createdByKind: "USER", runId: null, createdAt: NOW,
    }] })),
  );
  const client = new QueryClient({ defaultOptions: {
    queries: { retry: false, staleTime: Infinity }, mutations: { retry: false },
  } });
  const view = render(<QueryClientProvider client={client}><SelectedEditor /></QueryClientProvider>);
  return { state, client, ...view };
}

async function closeAndSettle(client: QueryClient, release: () => void = () => {}) {
  release();
  await act(async () => { cleanup(); });
  await waitFor(() => {
    expect(client.isMutating()).toBe(0);
    expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.saving).not.toBe(true);
  });
  client.clear();
}

describe("media draft persistence boundaries", () => {
  beforeEach(() => useCanvasStore.setState({ mediaDraftRecoveries: {}, imageRatioDrafts: {} }));

  it("projects a native image ratio through the extracted session and clears its clean preview on close", async () => {
    const profile = klingProfiles.find((item) => item.adapterId === "KLING_IMAGE" && item.operation === "GENERATE")!;
    const capability = { ...settings.connections[0]!.capabilities[0]!, adapterId: profile.adapterId,
      settings: { model: profile.model ?? "", operation: profile.operation, klingInputs: profile.inputs } };
    const nativeSettings: MediaSettings = { ...settings,
      connections: [{ ...settings.connections[0]!, platform: "KLING", capabilities: [capability] }] };
    const { client } = renderDraft({ ...initialDraft, capabilityId: capability.id,
      parameters: { dynamicValues: { aspect_ratio: "9:16" } } }, nativeSettings);
    try {
      await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(useCanvasStore.getState().imageRatioDrafts[RECOVERY_KEY]).toBe("9:16"));
      await userEvent.setup().click(screen.getByRole("button", { name: "关闭测试编辑区" }));
      await waitFor(() => expect(useCanvasStore.getState().imageRatioDrafts[RECOVERY_KEY]).toBeUndefined());
      expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]).toBeUndefined();
    } finally { await closeAndSettle(client); }
  });

  it("keeps the original CAS after a committed PUT loses its response, until an explicit refresh", async () => {
    const { state, client } = renderDraft();
    const requests: SaveMediaDraftRequest[] = [];
    const keys: (string | null)[] = [];
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      requests.push(input);
      keys.push(request.headers.get("Idempotency-Key"));
      if (input.expectedVersion !== state.draft.version) return conflict();
      state.draft = savedDraft(input);
      return requests.length === 1 ? HttpResponse.error() : HttpResponse.json(state.draft);
    }));
    try {
      const user = userEvent.setup();
      const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      prompt.textContent = "Keep the complete local prompt";
      fireEvent.input(prompt);
      await screen.findByText("保存失败，本地输入已保留");
      expect(state.draft.version).toBe(1);
      expect(prompt).toHaveTextContent("Keep the complete local prompt");
      expect(requests.map((input) => input.expectedVersion)).toEqual([0]);
      await user.click(screen.getByRole("button", { name: "重试保存" }));
      await screen.findByRole("button", { name: "重新读取版本" });
      expect(requests.map((input) => input.expectedVersion)).toEqual([0, 0]);
      expect(prompt).toHaveTextContent("Keep the complete local prompt");
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests).toHaveLength(2);
      await user.click(screen.getByRole("button", { name: "重新读取版本" }));
      await screen.findByText("已保存");
      expect(requests.map((input) => input.expectedVersion)).toEqual([0, 0, 1]);
      expect(keys).toEqual([null, null, null]);
      expect(state.draft.prompt).toBe("Keep the complete local prompt");
    } finally { await closeAndSettle(client); }
  });

  it("waits for a pending resource commit before closing subsequent edits with its new CAS", async () => {
    const { state, client } = renderDraft();
    const pending = delayedResponse();
    const requests: SaveMediaDraftRequest[] = [];
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      requests.push(input);
      await pending.ready;
      if (input.expectedVersion !== state.draft.version) return conflict();
      state.draft = savedDraft(input);
      return HttpResponse.json(state.draft);
    }));
    try {
      const user = userEvent.setup();
      const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      await user.click(screen.getByRole("button", { name: "添加图片输入" }));
      await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
      prompt.textContent = "Dirty prompt included in the resource commit";
      fireEvent.input(prompt);
      await user.click(await screen.findByRole("checkbox", { name: "选择 Synthetic reference · v1" }));
      await user.click(screen.getByRole("button", { name: "添加所选图片（1）" }));
      await waitFor(() => expect(requests).toHaveLength(1));
      expect(requests[0]).toMatchObject({ expectedVersion: 0,
        prompt: "Dirty prompt included in the resource commit", mediaInputs: [{ versionId: REFERENCE_VERSION }] });
      await user.clear(prompt);
      await user.type(prompt, "Edited again while the resource commit was pending");
      await user.click(screen.getByRole("button", { name: "关闭测试编辑区" }));
      // Keep the accepted commit unresolved past the autosave delay to expose any competing close-save.
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests.map((input) => ({ expectedVersion: input.expectedVersion,
        versions: input.mediaInputs.map((item) => item.versionId) })))
        .toEqual([{ expectedVersion: 0, versions: [REFERENCE_VERSION] }]);
      pending.release();
      await waitFor(() => expect(requests).toHaveLength(2));
      expect(requests[1]).toMatchObject({ expectedVersion: 1,
        prompt: "Edited again while the resource commit was pending",
        parameters: {}, mentions: [], mediaInputs: [{ versionId: REFERENCE_VERSION }] });
      await waitFor(() => expect(client.getQueryData<MediaDraft>(QUERY_KEY)?.version).toBe(2));
      await waitFor(() => expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]).toBeUndefined());
      expect(state.draft.prompt).toBe("Edited again while the resource commit was pending");
      expect(state.draft.mediaInputs.map((input) => input.versionId)).toEqual([REFERENCE_VERSION]);
      await user.click(screen.getByRole("button", { name: "重新打开测试编辑区" }));
      expect(await screen.findByRole("textbox", { name: "图片提示词" }))
        .toHaveTextContent("Edited again while the resource commit was pending");
      expect(await screen.findByRole("button", { name: /^取消引入 Synthetic reference/ })).toBeEnabled();
    } finally { await closeAndSettle(client, pending.release); }
  });

  it("waits for connected-input removal before closing edits with its new CAS", async () => {
    const connectedDraft: MediaDraft = { ...initialDraft, parameters: { aspectRatio: "9:16" }, mediaInputs: [{ versionId: REFERENCE_VERSION,
      artifactId: REFERENCE_ID, role: "REFERENCE", order: 0, color: "#F15CAF",
      sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "connection-1" }] }] };
    const { state, client } = renderDraft(connectedDraft);
    const pending = delayedResponse();
    const removals: unknown[] = [];
    const requests: SaveMediaDraftRequest[] = [];
    server.use(
      http.post(`${DRAFT_URL}/media-inputs/${REFERENCE_VERSION}/remove`, async ({ request }) => {
        removals.push(await request.json());
        await pending.ready;
        state.draft = { ...state.draft, mediaInputs: [], version: 1 };
        return HttpResponse.json(state.draft);
      }),
      http.put(DRAFT_URL, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        requests.push(input);
        await pending.ready;
        if (input.expectedVersion !== state.draft.version) return conflict();
        state.draft = savedDraft(input);
        return HttpResponse.json(state.draft);
      }),
    );
    try {
      const user = userEvent.setup();
      await user.click(await screen.findByRole("button", { name: /^取消引入 Synthetic reference/ }));
      await waitFor(() => expect(removals).toEqual([{ expectedVersion: 0 }]));
      const prompt = screen.getByRole("textbox", { name: "图片提示词" });
      prompt.textContent = "Typed while the connected input was being removed";
      fireEvent.input(prompt);
      await user.click(screen.getByRole("button", { name: "关闭测试编辑区" }));
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests.map((input) => ({ expectedVersion: input.expectedVersion,
        versions: input.mediaInputs.map((item) => item.versionId) }))).toEqual([]);
      pending.release();
      await waitFor(() => expect(requests).toHaveLength(1));
      expect(requests[0]).toMatchObject({ expectedVersion: 1,
        prompt: "Typed while the connected input was being removed",
        parameters: { aspectRatio: "9:16" }, mentions: [], mediaInputs: [] });
      await waitFor(() => expect(client.getQueryData<MediaDraft>(QUERY_KEY)?.version).toBe(2));
      await waitFor(() => expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]).toBeUndefined());
      expect(state.draft.prompt).toBe("Typed while the connected input was being removed");
      expect(state.draft.mediaInputs).toEqual([]);
      await user.click(screen.getByRole("button", { name: "重新打开测试编辑区" }));
      expect(await screen.findByRole("textbox", { name: "图片提示词" }))
        .toHaveTextContent("Typed while the connected input was being removed");
      expect(screen.queryByRole("button", { name: /^取消引入 Synthetic reference/ })).not.toBeInTheDocument();
    } finally { await closeAndSettle(client, pending.release); }
  });

  it("keeps dirty input and its original CAS when a media-draft event refreshes the server cache", async () => {
    const { state, client } = renderDraft();
    const requests: SaveMediaDraftRequest[] = [];
    let refreshedReads = 0;
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      requests.push(input);
      if (input.expectedVersion !== state.draft.version) return conflict();
      state.draft = savedDraft(input);
      return HttpResponse.json(state.draft);
    }));
    try {
      const user = userEvent.setup();
      const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      server.use(http.get(DRAFT_URL, () => {
        refreshedReads++;
        return HttpResponse.json(state.draft);
      }));
      prompt.textContent = "Keep the dirty local prompt after SSE";
      fireEvent.input(prompt);
      state.draft = { ...initialDraft, version: 4, prompt: "New remote prompt" };
      act(() => projectCacheCallbacks(client, PROJECT_ID).onChange({
        projectId: PROJECT_ID, type: "media.draft.changed", payload: { canvasItemId: ITEM_ID },
        seq: 1, eventId: "media-draft-event", schemaVersion: 1, aggregateId: ITEM_ID,
        aggregateVersion: 4, occurredAt: NOW,
      }));
      await waitFor(() => expect(client.getQueryData<MediaDraft>(QUERY_KEY)?.version).toBe(4));
      expect(refreshedReads).toBe(1);
      expect(prompt).toHaveTextContent("Keep the dirty local prompt after SSE");
      await screen.findByRole("button", { name: "重新读取版本" });
      expect(requests).toHaveLength(1);
      expect(requests[0]).toMatchObject({ expectedVersion: 0, prompt: "Keep the dirty local prompt after SSE" });
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests).toHaveLength(1);
      await user.click(screen.getByRole("button", { name: "重新读取版本" }));
      await screen.findByText("已保存");
      expect(requests.map((input) => input.expectedVersion)).toEqual([0, 4]);
      expect(state.draft).toMatchObject({ version: 5, prompt: "Keep the dirty local prompt after SSE" });
      expect(prompt).toHaveTextContent("Keep the dirty local prompt after SSE");
    } finally { await closeAndSettle(client); }
  });

  it("retains the last input and original CAS when a pending resource commit fails after closing", async () => {
    const { state, client } = renderDraft();
    const pending = delayedResponse();
    const requests: SaveMediaDraftRequest[] = [];
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      requests.push(await request.json() as SaveMediaDraftRequest);
      await pending.ready;
      return HttpResponse.error();
    }));
    try {
      const user = userEvent.setup();
      const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      await user.click(screen.getByRole("button", { name: "添加图片输入" }));
      await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
      await user.click(await screen.findByRole("checkbox", { name: "选择 Synthetic reference · v1" }));
      await user.click(screen.getByRole("button", { name: "添加所选图片（1）" }));
      await waitFor(() => expect(requests).toHaveLength(1));
      await user.clear(prompt);
      await user.type(prompt, "Retain these edits after the resource commit fails");
      await user.click(screen.getByRole("button", { name: "关闭测试编辑区" }));
      expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.saving).toBe(true);
      pending.release();
      await waitFor(() => expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.saving).toBe(false));
      const recovery = useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY];
      expect(recovery?.request).toMatchObject({ expectedVersion: 0,
        prompt: "Retain these edits after the resource commit fails", parameters: {}, mediaInputs: [], mentions: [] });
      expect(recovery?.error).toBeInstanceOf(Error);
      expect(state.draft.version).toBe(0);
      await user.click(screen.getByRole("button", { name: "重新打开测试编辑区" }));
      expect(await screen.findByRole("textbox", { name: "图片提示词" }))
        .toHaveTextContent("Retain these edits after the resource commit fails");
      expect(await screen.findByText("保存失败，本地输入已保留")).toBeVisible();
      expect(screen.getByRole("alert")).toHaveTextContent(recovery?.error?.message ?? "Missing failure");
      expect(screen.getByRole("button", { name: "重试保存" })).toBeEnabled();
      expect(screen.queryByRole("button", { name: /^取消引入 Synthetic reference/ })).not.toBeInTheDocument();
      // Failure retains the input for the user; neither closing nor reopening retries the business write.
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests).toHaveLength(1);
    } finally { await closeAndSettle(client, pending.release); }
  });

  it("retains acknowledged references and CAS when the serialized close-save conflicts", async () => {
    const { state, client } = renderDraft();
    const pending = delayedResponse();
    const requests: SaveMediaDraftRequest[] = [];
    server.use(http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      const attempt = requests.push(input);
      await pending.ready;
      if (attempt === 2) {
        state.draft = { ...state.draft, version: 2, prompt: "Concurrent server edit" };
        return conflict();
      }
      if (input.expectedVersion !== state.draft.version) return conflict();
      state.draft = savedDraft(input);
      return HttpResponse.json(state.draft);
    }));
    try {
      const user = userEvent.setup();
      const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      await user.click(screen.getByRole("button", { name: "添加图片输入" }));
      await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
      await user.click(await screen.findByRole("checkbox", { name: "选择 Synthetic reference · v1" }));
      await user.click(screen.getByRole("button", { name: "添加所选图片（1）" }));
      await waitFor(() => expect(requests).toHaveLength(1));
      await user.clear(prompt);
      await user.type(prompt, "Retain these edits after the close-save conflict");
      await user.click(screen.getByRole("button", { name: "关闭测试编辑区" }));
      pending.release();
      await waitFor(() => expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]?.saving).toBe(false));
      expect(requests).toHaveLength(2);
      expect(requests[1]).toMatchObject({ expectedVersion: 1,
        prompt: "Retain these edits after the close-save conflict", mediaInputs: [{ versionId: REFERENCE_VERSION }] });
      const recovery = useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY];
      expect(recovery?.request).toMatchObject({ expectedVersion: 1,
        prompt: "Retain these edits after the close-save conflict", parameters: {}, mentions: [],
        mediaInputs: [{ versionId: REFERENCE_VERSION }] });
      expect(recovery?.error).toMatchObject({ status: 409 });
      await user.click(screen.getByRole("button", { name: "重新打开测试编辑区" }));
      expect(await screen.findByRole("textbox", { name: "图片提示词" }))
        .toHaveTextContent("Retain these edits after the close-save conflict");
      expect(await screen.findByRole("button", { name: /^取消引入 Synthetic reference/ })).toBeInTheDocument();
      await screen.findByRole("button", { name: "重新读取版本" });
      await act(async () => { await new Promise((resolve) => setTimeout(resolve, 750)); });
      expect(requests).toHaveLength(2);
      await user.click(screen.getByRole("button", { name: "重新读取版本" }));
      await screen.findByText("已保存");
      expect(requests.map((input) => input.expectedVersion)).toEqual([0, 1, 2]);
      expect(state.draft).toMatchObject({ version: 3,
        prompt: "Retain these edits after the close-save conflict", mediaInputs: [{ versionId: REFERENCE_VERSION }] });
      expect(useCanvasStore.getState().mediaDraftRecoveries[RECOVERY_KEY]).toBeUndefined();
    } finally { await closeAndSettle(client, pending.release); }
  });
});
