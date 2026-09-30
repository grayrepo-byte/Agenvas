import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type RequestHandler } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Artifact, MediaCapability, MediaDraft, MediaSettings, SaveMediaDraftRequest, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { useCanvasStore } from "./canvasStore";

const NOW = "2026-09-26T00:00:00Z";
const PROJECT_ID = "project-media-editor";
const ARTIFACT_ID = "artifact-media-editor";
const CANVAS_ITEM_ID = "canvas-item-media-editor";
const BASE = `/api/v1/projects/${PROJECT_ID}/artifacts/${ARTIFACT_ID}`;
const DRAFT_URL = `/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/media-draft`;
const artifact: Artifact = {
  id: ARTIFACT_ID, projectId: PROJECT_ID, kind: "IMAGE", title: "新图片",
  resourceDefaultVersionId: null, resourceDefaultVersion: null, version: 0, createdAt: NOW, updatedAt: NOW,
};
const initialDraft: MediaDraft = {
  projectId: PROJECT_ID, canvasItemId: CANVAS_ITEM_ID, prompt: "A lighthouse at dawn",
  parameters: {}, durationSeconds: null, capabilityId: null, videoInputMode: null,
  mediaInputs: [], mentions: [],
  displayMode: "DRAFT", version: 0, createdAt: NOW, updatedAt: NOW,
};
const imageCapability: MediaCapability = {
  id: "image-capability", name: "细节生图", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION", minimumSeconds: 0,
  maximumSeconds: 0, maxReferenceAudios: 0, maxReferenceImages: 4, supportedVideoInputModes: [],
  defaultVideoInputMode: null, supportsEndFrame: false,
  supportedImageAspectRatios: ["AUTO", "1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"],
  supportedImageResolutions: ["1K", "2K", "4K"], supportedImageQualities: ["low", "medium", "high"],
  supportsTransparentBackground: true, supportsImageMask: true,
  mappingSha256: "a".repeat(64), settings: { quality: "high" },
};
const videoCapability: MediaCapability = {
  ...imageCapability, id: "video-capability", name: "镜头视频", adapterId: "ARK_SEEDANCE_2_I2V",
  kind: "VIDEO_GENERATION", minimumSeconds: 2, maximumSeconds: 10,
  maxReferenceAudios: 0, maxReferenceImages: 2, supportedVideoInputModes: ["START_END"],
  defaultVideoInputMode: "START_END", supportsEndFrame: true, supportedImageAspectRatios: [],
  supportedImageResolutions: [], supportedImageQualities: [], supportsTransparentBackground: false,
  supportsImageMask: false, settings: {},
};
const versatileVideoCapability: MediaCapability = {
  ...videoCapability, id: "versatile-video-capability", name: "全能视频",
  maxReferenceAudios: 0, maxReferenceImages: 4,
  supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"],
  defaultVideoInputMode: "TEXT", supportsEndFrame: true,
};
const settings: MediaSettings = {
  connections: [{ id: "connection", name: "我的媒体连接", platform: "OPENAI", enabled: true,
    version: 0, connectionVersion: 1, origin: null, keyMask: null,
    connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [imageCapability, videoCapability,
      { ...imageCapability, id: "disabled-capability", name: "停用模型", enabled: false }] },
  { id: "disabled-connection", name: "停用连接", platform: "OPENAI", enabled: false,
    version: 0, connectionVersion: 1, origin: null, keyMask: null,
    connectivityStatus: "NOT_CHECKED", realGenerationTested: true,
    capabilities: [{ ...imageCapability, id: "hidden-capability", name: "隐藏模型" }] }],
  defaults: [{ kind: "IMAGE_GENERATION", capabilityId: imageCapability.id, version: 0 },
    { kind: "VIDEO_GENERATION", capabilityId: videoCapability.id, version: 0 }],
};
function task(status: Task["status"]): Task {
  return { id: "task-direct", projectId: PROJECT_ID, runId: null,
    stepKey: "direct", kind: "IMAGE_GENERATION", status, cancelRequested: false,
    input: {}, providerRequestId: null, attemptNo: 1, nextActionAt: NOW, version: 0,
    createdAt: NOW, updatedAt: NOW };
}

function setup(options: { draft?: MediaDraft; tasks?: Task[]; settings?: MediaSettings; kind?: "IMAGE" | "VIDEO" | "AUDIO"; handlers?: RequestHandler[] } = {}) {
  let draft = options.draft ?? { ...initialDraft };
  let tasks = options.tasks ?? [];
  const saves: SaveMediaDraftRequest[] = [];
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "token" })),
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(options.settings ?? settings)),
    http.get(DRAFT_URL, () => HttpResponse.json(draft)),
    http.put(DRAFT_URL, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      saves.push(input);
      draft = { ...draft, ...input, mediaInputs: input.mediaInputs.map((item, order) => ({
        ...item, artifactId: "reference-image", order,
        sources: [{ id: `manual-${order}`, type: "MANUAL" as const, connectionId: null }],
      })), version: draft.version + 1 };
      return HttpResponse.json(draft);
    }),
    http.get(`${BASE}/run`, () => HttpResponse.json(tasks)),
    http.get(`/api/v1/projects/${PROJECT_ID}/tasks/task-direct/queue`, () =>
      HttpResponse.json({ waitingAhead: 2, reason: "WAITING_WORKER" })),
    http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [] })),
    http.get(`/api/v1/projects/${PROJECT_ID}/canvas/items`, () => HttpResponse.json({ items: [] })),
  );
  if (options.handlers) server.use(...options.handlers);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MediaDraftEditor
    artifact={{ ...artifact, kind: options.kind ?? "IMAGE" }} canvasItemId={CANVAS_ITEM_ID} />
  </QueryClientProvider>);
  return { client, saves, setTasks: (next: Task[]) => { tasks = next; } };
}

describe("MediaDraftEditor", () => {
  beforeEach(() => useCanvasStore.setState({ mediaDraftRecoveries: {}, imageRatioDrafts: {} }));
  afterEach(async () => {
    cleanup();
    // Closing can flush a debounced save; let it settle before the shared HTTP handlers reset.
    await waitFor(() => expect(Object.values(useCanvasStore.getState().mediaDraftRecoveries)
      .some((recovery) => recovery.saving)).toBe(false));
  });
  it("keeps the library picker open on Escape or outside clicks while a reference is archiving", async () => {
    const { saves } = setup({ handlers: [
      http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "library-image", name: "旅馆", category: "SCENE", kind: "IMAGE", version: 0, source: {}, favorite: false, createdAt: NOW, hasThumbnail: false }], total: 1, categoryCounts: { SCENE: 1 } })),
      http.post(`/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/library-references`, () => HttpResponse.json({ id: "library-command", status: "ARCHIVING" }, { status: 202 })),
      http.get("/api/v1/library/commands/library-command", () => HttpResponse.json({ id: "library-command", status: "ARCHIVING" })),
    ] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "图片提示词" });
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: /从我的资产选择/ }));
    await user.click(await screen.findByRole("button", { name: "查看 旅馆" }));
    await screen.findByText("正在转存，请稍候…");
    expect(screen.getByRole("button", { name: "关闭资产选择" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog", { name: "我的资产参考" })).toBeVisible();
    await user.click(screen.getByRole("textbox", { name: "图片提示词" }));
    expect(screen.getByRole("dialog", { name: "我的资产参考" })).toBeVisible();
    await user.click(screen.getByRole("button", { name: "选择生成模型" }));
    expect(screen.getByRole("dialog", { name: "我的资产参考" })).toBeVisible();
    await user.click(screen.getByRole("textbox", { name: "图片提示词" }));
    await user.keyboard(" while archiving");
    expect(saves).toHaveLength(0);
    cleanup();
    const recovery = useCanvasStore.getState().mediaDraftRecoveries[`${PROJECT_ID}:${CANVAS_ITEM_ID}`];
    expect(recovery?.saving).toBe(false);
    expect(recovery?.error?.message).toContain("参考转存");
  });
  it("selects a searchable audio voice, persists its controls and allows audio generation without video duration", async () => {
    const audioCapability: MediaCapability = { ...imageCapability, id: "audio-capability", name: "Seed Audio 1.0",
      kind: "AUDIO_GENERATION", adapterId: "VOLC_SEED_AUDIO_1", maxReferenceImages: 1, maxReferenceAudios: 3,
      supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], settings: {} };
    const { saves } = setup({ kind: "AUDIO", settings: { connections: [{ ...settings.connections[0]!, platform: "VOLCENGINE",
      capabilities: [audioCapability] }], defaults: [{ kind: "AUDIO_GENERATION", capabilityId: audioCapability.id, version: 0 }] } });
    expect(await screen.findByRole("textbox", { name: "音频提示词" })).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "选择音色" }));
    await user.type(screen.getByRole("searchbox", { name: "搜索音色" }), "小何");
    expect(screen.getByText("小何 2.0")).toBeVisible();
    expect(screen.queryByText("云舟 2.0")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /小何 2.0.*中文/ }));
    await waitFor(() => expect(saves.at(-1)?.parameters.speaker).toBe("zh_female_xiaohe_uranus_bigtts"));
    expect(saves.at(-1)?.durationSeconds).toBeNull();
    expect(saves.at(-1)?.videoInputMode).toBeNull();
    await user.click(screen.getByRole("button", { name: "音频参数" }));
    fireEvent.change(screen.getByLabelText("语速"), { target: { value: "-20" } });
    await waitFor(() => expect(saves.at(-1)?.parameters.speechRate).toBe(-20));
  });

  it("confirms removal of audio when switching mixed references to frames and updates surviving mention roles", async () => {
    const capability = { ...versatileVideoCapability, adapterId: "MOCK_VIDEO", maxReferenceAudios: 3 };
    const { saves } = setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, parameters: { aspectRatio: "AUTO" }, durationSeconds: 5, videoInputMode: "GENERAL_REFERENCE",
        prompt: "参考 \uFFFC 与 \uFFFC", mediaInputs: [
          { versionId: "audio-v1", artifactId: "reference-audio", order: 0, role: "AUDIO_REFERENCE", color: "#67C7F3", sources: [] },
          { versionId: "image-v1", artifactId: "reference-image", order: 1, role: "REFERENCE", color: "#F15CAF", sources: [] }],
        mentions: [{ versionId: "audio-v1", role: "AUDIO_REFERENCE" }, { versionId: "image-v1", role: "REFERENCE" }] },
      handlers: [http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [
        { ...artifact, id: "reference-audio", kind: "AUDIO", title: "节奏", resourceDefaultVersionId: "audio-v1" },
        { ...artifact, id: "reference-image", title: "画面", resourceDefaultVersionId: "image-v1" }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-audio/versions`, () => HttpResponse.json({ items: [{ id: "audio-v1", versionNo: 1, content: { assetId: "audio-asset" } }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: [{ id: "image-v1", versionNo: 1, content: { assetId: "image-asset" } }] }))] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    await user.click(screen.getByRole("button", { name: "选择视频输入模式" }));
    const confirmation = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    try {
      await user.click(screen.getByRole("menuitemradio", { name: /首尾帧/ }));
      expect(confirmation).toHaveBeenCalledWith(expect.stringContaining("音频"));
      expect(saves).toHaveLength(0);
      await user.click(screen.getByRole("menuitemradio", { name: /首尾帧/ }));
      await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: "START_END",
        mediaInputs: [{ versionId: "image-v1", role: "START_FRAME" }],
        prompt: "参考  与 \uFFFC", mentions: [{ versionId: "image-v1", role: "START_FRAME" }] }));
    } finally { confirmation.mockRestore(); }
  });

  it("shows configured defaults and the exact batch estimate, with custom model names", async () => {
    const pricedCapability = { ...imageCapability, settings: { model: "custom-image-model",
      defaultParameters: { aspectRatio: "16:9", resolution: "2K", generationCount: 4 },
      pricing: { amount: "0.125", currency: "USD", unit: "IMAGE" } } };
    setup({ handlers: [http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
      ...settings, connections: [{ ...settings.connections[0], capabilities: [pricedCapability] }],
    }))] });
    expect(await screen.findByText("预计 USD 0.5")).toBeVisible();
    expect(screen.getByRole("button", { name: "尺寸与画质" })).toHaveTextContent("16:9 · 2K");
    expect(screen.getByRole("button", { name: "尺寸与画质" })).toHaveTextContent("4 张");
    await userEvent.setup().click(screen.getByRole("button", { name: "选择生成模型" }));
    expect(screen.getByText(/custom-image-model/)).toBeVisible();
  });

  it("inserts an exact-version image token inside the prompt instead of a separate tag row", async () => {
    const { saves } = setup({ draft: { ...initialDraft, prompt: "修改为红色衣服 ",
      mediaInputs: [{ versionId: "image-v1", artifactId: "reference-image", role: "REFERENCE",
        order: 0, color: "#F15CAF",
        sources: [{ id: "manual", type: "MANUAL", connectionId: null }] }] }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{
        ...artifact, id: "reference-image", title: "新图片", resourceDefaultVersionId: "image-v1",
      }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () =>
        HttpResponse.json({ items: [{ id: "image-v1", versionNo: 1,
          content: { assetId: "asset-image-v1" } }] })),
    ] });
    const user = userEvent.setup();
    const referenceRow = await screen.findByLabelText("图片输入");
    expect(within(referenceRow).getByText("1")).toBeVisible();
    expect(within(referenceRow).queryByText("Image 1")).not.toBeInTheDocument();
    expect(within(referenceRow).queryByText(/新图片 · v1/)).not.toBeInTheDocument();
    expect(within(referenceRow).queryByRole("button", { name: /向左移动|向右移动/ }))
      .not.toBeInTheDocument();
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    await user.click(prompt);
    await user.type(prompt, "@");
    const choices = await screen.findByRole("listbox", { name: "图片引用" });
    await user.click(within(choices).getByRole("option", { name: /Image 1/ }));
    expect(within(prompt).getByText("@Image 1")).toBeVisible();
    expect(screen.queryByLabelText("图片标签")).not.toBeInTheDocument();
    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      prompt: `修改为红色衣服 \uFFFC`,
      mentions: [{ versionId: "image-v1", role: "REFERENCE" }],
    }));
    await user.click(screen.getByRole("button", { name: /取消引入 新图片/ }));
    expect(within(prompt).queryByText("@Image 1")).not.toBeInTheDocument();
    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      prompt: "修改为红色衣服 ", mentions: [], mediaInputs: [],
    }));
  });

  it("disconnects a connection-only image directly from its hover close button", async () => {
    const removals: unknown[] = [];
    setup({ draft: { ...initialDraft,
      mediaInputs: [{ versionId: "connected-version", artifactId: "connected-artifact",
        role: "REFERENCE", order: 0, color: "#F15CAF",
        sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] }] },
      handlers: [http.post(`${DRAFT_URL}/media-inputs/connected-version/remove`,
        async ({ request }) => {
          removals.push(await request.json());
          return HttpResponse.json({ ...initialDraft, version: 1 });
        })] });
    const user = userEvent.setup();
    const remove = await screen.findByRole("button", { name: "取消引入 图片输入 1" });
    expect(remove).toBeEnabled();
    expect(remove).toHaveAttribute("title", "取消引入并断开画布连线");
    await user.click(remove);
    await waitFor(() => expect(removals).toEqual([{ expectedVersion: 0 }]));
    await waitFor(() => expect(screen.queryByRole("button", {
      name: "取消引入 图片输入 1",
    })).not.toBeInTheDocument());
  });

  it("retries a failed atomic connection-input removal instead of saving an unrelated draft", async () => {
    let attempts = 0;
    setup({ draft: { ...initialDraft,
      mediaInputs: [{ versionId: "connected-version", artifactId: "connected-artifact",
        role: "REFERENCE", order: 0, color: "#F15CAF",
        sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] }] },
      handlers: [http.post(`${DRAFT_URL}/media-inputs/connected-version/remove`, () => {
        attempts += 1;
        return attempts === 1
          ? HttpResponse.json({ code: "TEMPORARY", detail: "暂时无法取消引入" }, { status: 503 })
          : HttpResponse.json({ ...initialDraft, version: 1 });
      })] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "取消引入 图片输入 1" }));
    expect(await screen.findByText("取消引入失败")).toBeVisible();
    await user.click(await screen.findByRole("button", { name: "重试取消引入" }));
    await waitFor(() => expect(attempts).toBe(2));
    await waitFor(() => expect(screen.queryByRole("button", {
      name: "取消引入 图片输入 1",
    })).not.toBeInTheDocument());
  });

  it("preserves prompt edits made while a connected image is being removed", async () => {
    let releaseRemoval = () => {};
    const removalGate = new Promise<void>((resolve) => { releaseRemoval = resolve; });
    const { saves } = setup({ draft: { ...initialDraft,
      mediaInputs: [{ versionId: "connected-version", artifactId: "connected-artifact",
        role: "REFERENCE", order: 0, color: "#F15CAF",
        sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] }] },
      handlers: [http.post(`${DRAFT_URL}/media-inputs/connected-version/remove`, async () => {
        await removalGate;
        return HttpResponse.json({ ...initialDraft, version: 1 });
      })] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "取消引入 图片输入 1" }));
    const prompt = screen.getByRole("textbox", { name: "图片提示词" });
    await user.clear(prompt);
    await user.type(prompt, "继续输入提示词");
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 700)); });
    expect(saves).toHaveLength(0);
    releaseRemoval();
    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      expectedVersion: 1, prompt: "继续输入提示词", mediaInputs: [],
    }));
    expect(prompt).toHaveTextContent("继续输入提示词");
  });

  it("saves edits before retrying a connected-image removal that failed after typing", async () => {
    let releaseFailure = () => {};
    const failureGate = new Promise<void>((resolve) => { releaseFailure = resolve; });
    let releaseSave = () => {};
    const saveGate = new Promise<void>((resolve) => { releaseSave = resolve; });
    let attempts = 0;
    const saveRequests: SaveMediaDraftRequest[] = [];
    setup({ draft: { ...initialDraft,
      mediaInputs: [{ versionId: "connected-version", artifactId: "connected-artifact",
        role: "REFERENCE", order: 0, color: "#F15CAF",
        sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] }] },
      handlers: [
        http.put(DRAFT_URL, async ({ request }) => {
          const input = await request.json() as SaveMediaDraftRequest;
          saveRequests.push(input);
          if (saveRequests.length === 1) await saveGate;
          return HttpResponse.json({ ...initialDraft, prompt: input.prompt,
            mediaInputs: input.mediaInputs.map((item, order) => ({ ...item,
              artifactId: "connected-artifact", order,
              sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] })),
            mentions: input.mentions, version: saveRequests.length === 1 ? 1 : 3 });
        }),
        http.post(`${DRAFT_URL}/media-inputs/connected-version/remove`, async () => {
          attempts += 1;
          if (attempts === 1) {
            await failureGate;
            return HttpResponse.json({ code: "TEMPORARY", detail: "暂时无法取消引入" }, { status: 503 });
          }
          return HttpResponse.json({ ...initialDraft, prompt: "失败期间继续输入", version: 2 });
        }),
      ] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "取消引入 图片输入 1" }));
    const prompt = screen.getByRole("textbox", { name: "图片提示词" });
    await user.clear(prompt);
    await user.type(prompt, "失败期间继续输入");
    releaseFailure();
    await user.click(await screen.findByRole("button", { name: "重试取消引入" }));
    await waitFor(() => expect(saveRequests[0]).toMatchObject({
      expectedVersion: 0, prompt: "失败期间继续输入",
    }));
    await user.click(prompt);
    await user.keyboard("{End}");
    await user.type(prompt, "，保存时继续输入");
    releaseSave();
    await waitFor(() => expect(attempts).toBe(2));
    await waitFor(() => expect(screen.queryByRole("button", {
      name: "取消引入 图片输入 1",
    })).not.toBeInTheDocument());
    await waitFor(() => expect(saveRequests[1]).toMatchObject({
      expectedVersion: 2, prompt: "失败期间继续输入，保存时继续输入", mediaInputs: [],
    }));
    expect(prompt).toHaveTextContent("失败期间继续输入，保存时继续输入");
  });

  it("keeps image-only thumbnails keyboard and drag reorderable without visible move controls", async () => {
    const { saves } = setup({ draft: { ...initialDraft, mediaInputs: [
      { versionId: "image-v1", artifactId: "reference-image", role: "REFERENCE",
        order: 0, color: "#F15CAF", sources: [{ id: "manual-1", type: "MANUAL", connectionId: null }] },
      { versionId: "image-v2", artifactId: "reference-image", role: "REFERENCE",
        order: 1, color: "#67C7F3", sources: [{ id: "manual-2", type: "MANUAL", connectionId: null }] },
    ] }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{
        ...artifact, id: "reference-image", title: "海边灯塔", resourceDefaultVersionId: "image-v2",
      }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () =>
        HttpResponse.json({ items: [
          { id: "image-v1", versionNo: 1, content: { assetId: "asset-image-v1" } },
          { id: "image-v2", versionNo: 2, content: { assetId: "asset-image-v2" } },
        ] })),
    ] });
    const user = userEvent.setup();
    const first = await screen.findByLabelText("海边灯塔 · v1，序号 1");
    expect(screen.queryByRole("button", { name: /向左移动|向右移动/ })).not.toBeInTheDocument();
    await user.click(first);
    await user.keyboard("{ArrowRight}");
    await waitFor(() => expect(saves.at(-1)?.mediaInputs.map((input) => input.versionId))
      .toEqual(["image-v2", "image-v1"]));
    const movedFirst = screen.getByLabelText("海边灯塔 · v1，序号 2");
    const movedSecond = screen.getByLabelText("海边灯塔 · v2，序号 1");
    expect(movedFirst).toHaveAttribute("data-reorderable", "true");
    fireEvent.pointerDown(movedFirst, { button: 0 });
    fireEvent.pointerUp(movedSecond, { button: 0 });
    await waitFor(() => expect(saves.at(-1)?.mediaInputs.map((input) => input.versionId))
      .toEqual(["image-v1", "image-v2"]));
  });

  it("persists image ratio, resolution, quality, count and transparency", async () => {
    const { saves } = setup();
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    const addImage = screen.getByRole("button", { name: "添加图片输入" });
    expect(addImage).toBeEnabled();
    expect(screen.queryByText("添加图片作为精确版本输入")).not.toBeInTheDocument();
    await user.click(addImage);
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    const imagePicker = screen.getByRole("dialog", { name: "输入图片版本" });
    const selectedImages = screen.getByRole("list", { name: "已选择的图片" });
    expect(imagePicker).toBeVisible();
    expect(selectedImages).not.toContainElement(addImage);
    expect(selectedImages).not.toContainElement(imagePicker);
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    const parameters = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    await user.click(within(parameters).getByRole("button", { name: "9:16" }));
    await user.click(within(parameters).getByRole("button", { name: "2K" }));
    await user.click(within(parameters).getByRole("button", { name: "低" }));
    await user.click(within(parameters).getByRole("button", { name: "4" }));
    await user.click(within(parameters).getByRole("switch", { name: "透明背景" }));
    await waitFor(() => expect(saves.at(-1)?.parameters).toEqual({
      aspectRatio: "9:16", resolution: "2K", quality: "low", transparentBackground: true,
      generationCount: 4,
    }));
    expect(within(parameters).getByText("空节点首个结果留在当前节点，其余结果创建独立节点")).toBeVisible();
    expect(screen.getByText("9:16 · 2K · 低 · 4 张")).toBeVisible();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "尺寸与画质" })).toHaveFocus();
  });

  it("keeps a stale resource batch atomic and refreshes candidates after rejection", async () => {
    let resourceReads = 0;
    let saveAttempts = 0;
    setup({ handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => {
        resourceReads += 1;
        return HttpResponse.json({ items: [{ ...artifact, id: "reference-image",
          title: "森林远景", resourceDefaultVersionId: "image-v2" }] });
      }),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () =>
        HttpResponse.json({ items: [
          { id: "image-v1", versionNo: 1, content: { assetId: "asset-forest-old" } },
          { id: "image-v2", versionNo: 2, content: { assetId: "asset-forest-current" } },
        ] })),
      http.put(DRAFT_URL, () => {
        saveAttempts += 1;
        return HttpResponse.json({ code: "ARTIFACT_VERSION_NOT_AVAILABLE",
          detail: "所选图片版本已不可用" }, { status: 400 });
      }),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    const search = screen.getByRole("searchbox", { name: "搜索资源图片" });
    await user.type(search, "森林");
    await user.click(await screen.findByRole("checkbox", { name: "选择 森林远景 · v1" }));
    await user.click(screen.getByRole("checkbox", { name: "选择 森林远景 · v2" }));
    expect(within(screen.getByRole("list", { name: "已选择的图片" })).queryByRole("listitem"))
      .not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "添加所选图片（2）" }));

    expect(await screen.findByText("整批未添加；资源已刷新，请重新选择。")).toBeVisible();
    expect(saveAttempts).toBe(1);
    expect(within(screen.getByRole("list", { name: "已选择的图片" })).queryByRole("listitem"))
      .not.toBeInTheDocument();
    await waitFor(() => expect(resourceReads).toBeGreaterThan(1));
    expect(screen.getByRole("checkbox", { name: "选择 森林远景 · v1" })).toHaveAttribute("aria-checked", "false");
    expect(screen.getByRole("checkbox", { name: "选择 森林远景 · v2" })).toHaveAttribute("aria-checked", "false");
  });

  it("shows the image-source choices when the add button is hovered", async () => {
    setup();
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    const addImage = screen.getByRole("button", { name: "添加图片输入" });
    await user.hover(addImage);
    const menu = screen.getByRole("menu", { name: "图片来源" });
    const uploadFromDevice = within(menu).getByRole("menuitem", { name: "从设备上传" });
    expect(uploadFromDevice).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: "从资源库选择" })).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: "从画布选择" })).toBeEnabled();
    const drawReference = within(menu).getByRole("menuitem", { name: "绘制引用图（暂未接入）" });
    expect(drawReference).toBeDisabled();
    uploadFromDevice.focus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument();
    expect(addImage).toHaveFocus();
  });

  it("uploads a device image as a reusable artifact and adds its exact version", async () => {
    const artifactRequests: { key: string | null; body: unknown }[] = [];
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === `/api/v1/projects/${PROJECT_ID}/assets`) {
        expect(init?.body).toBeInstanceOf(FormData);
        return HttpResponse.json({ id: "uploaded-asset", mediaKind: "IMAGE" }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const { saves } = setup({ handlers: [
      http.post(`/api/v1/projects/${PROJECT_ID}/artifacts`, async ({ request }) => {
        artifactRequests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json({ ...artifact, id: "uploaded-artifact", title: "reference",
          resourceDefaultVersionId: "uploaded-version" });
      }),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    await user.hover(screen.getByRole("button", { name: "添加图片输入" }));
    const uploadInput = screen.getByLabelText("选择本地图片");
    const openPicker = vi.spyOn(uploadInput, "click");
    fireEvent.click(screen.getByRole("menuitem", { name: "从设备上传" }));
    expect(openPicker).toHaveBeenCalledOnce();
    fireEvent.change(uploadInput, { target: { files: [
      new File(["image bytes"], "reference.png", { type: "image/png" }),
    ] } });
    await waitFor(() => expect(artifactRequests).toHaveLength(1));
    expect(artifactRequests[0]).toMatchObject({ body: { kind: "IMAGE", title: "reference",
      content: { sourceType: "UPLOAD", assetId: "uploaded-asset" } } });
    expect(artifactRequests[0]?.key).toBeTruthy();
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ mediaInputs: [{
      versionId: "uploaded-version", role: "REFERENCE", color: "#F15CAF",
    }] }));
  });

  it("excludes the current card when choosing an image from the canvas", async () => {
    const otherArtifact = { ...artifact, id: "other-artifact", title: "其他图片",
      resourceDefaultVersionId: "other-version" };
    const { saves } = setup({ handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [otherArtifact] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/other-artifact/versions`, () => HttpResponse.json({ items: [{
        id: "other-version", versionNo: 2, content: { assetId: "other-asset" },
      }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/canvas/items`, () => HttpResponse.json({ items: [
        { id: CANVAS_ITEM_ID, subjectType: "ARTIFACT", subjectId: ARTIFACT_ID, title: "当前图片",
          selectedVersionId: "self-version", selectedVersion: { id: "self-version", versionNo: 1,
            content: { assetId: "self-asset" } }, artifact, agent: null },
        { id: "other-canvas-item", subjectType: "ARTIFACT", subjectId: "other-artifact", title: "其他图片",
          selectedVersionId: "other-version", selectedVersion: { id: "other-version", versionNo: 2,
            content: { assetId: "other-asset" } }, artifact: otherArtifact, agent: null },
        { id: "text-canvas-item", subjectType: "ARTIFACT", subjectId: "text-artifact", title: "文字卡片",
          selectedVersionId: "text-version", selectedVersion: { id: "text-version", versionNo: 1,
            content: { text: "hello" } }, artifact: { ...artifact, id: "text-artifact", kind: "TEXT" }, agent: null },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    await user.hover(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从画布选择" }));
    const picker = await screen.findByRole("dialog", { name: "从画布选择图片" });
    expect(within(picker).queryByRole("button", { name: /当前图片/ })).not.toBeInTheDocument();
    expect(within(picker).queryByRole("button", { name: /文字卡片/ })).not.toBeInTheDocument();
    await user.click(within(picker).getByRole("button", { name: "使用画布图片 其他图片" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ mediaInputs: [{
      versionId: "other-version", role: "REFERENCE", color: "#F15CAF",
    }] }));
  });

  it("filters enabled image models, exposes the fixed model and saves keyboard selection", async () => {
    const { saves } = setup();
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    const outerEscape = vi.fn();
    document.addEventListener("keydown", outerEscape);
    try {
      await user.click(screen.getByRole("button", { name: "选择生成模型" }));
      const menu = screen.getByRole("menu", { name: "生成模型" });
      expect(within(menu).getAllByRole("menuitemradio")).toHaveLength(2);
      expect(within(menu).getByText(/gpt-image-2/)).toBeVisible();
      expect(within(menu).queryByText(/真实生成验证/)).not.toBeInTheDocument();
      expect(within(menu).queryByText("镜头视频")).not.toBeInTheDocument();
      expect(within(menu).queryByText("停用模型")).not.toBeInTheDocument();
      expect(within(menu).queryByText("隐藏模型")).not.toBeInTheDocument();
      await user.keyboard("{ArrowDown}{Enter}");
      await waitFor(() => expect(saves).toHaveLength(1));
      expect(saves[0]).toEqual({ expectedVersion: 0, prompt: initialDraft.prompt,
        parameters: { aspectRatio: "AUTO", resolution: "1K", quality: "high",
          transparentBackground: false, generationCount: 1 },
        videoInputMode: null, mediaInputs: [], mentions: [],
        durationSeconds: null, capabilityId: imageCapability.id });
      await user.click(screen.getByRole("button", { name: "选择生成模型" }));
      outerEscape.mockClear();
      await user.keyboard("{Escape}");
      expect(outerEscape).not.toHaveBeenCalled();
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "选择生成模型" }));
      await user.click(screen.getByLabelText("图片提示词"));
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    } finally { document.removeEventListener("keydown", outerEscape); }
  });

  it("retains local input after a CAS conflict and saves it against the explicitly refreshed version", async () => {
    setup();
    const requests: SaveMediaDraftRequest[] = [];
    let remoteVersion = 0;
    server.use(
      http.get(DRAFT_URL, () => HttpResponse.json({ ...initialDraft, prompt: "Remote prompt", version: remoteVersion })),
      http.put(DRAFT_URL, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        requests.push(input);
        if (requests.length === 1) {
          remoteVersion = 4;
          return HttpResponse.json({ code: "VERSION_CONFLICT", detail: "草稿已更新", retryable: true }, { status: 409 });
        }
        return HttpResponse.json({ ...initialDraft, ...input, version: 5 });
      }),
    );
    const user = userEvent.setup();
    const prompt = await screen.findByLabelText("图片提示词");
    await user.clear(prompt);
    await user.type(prompt, "Keep this local prompt");
    await screen.findByText("保存失败，本地输入已保留");
    expect(prompt).toHaveTextContent("Keep this local prompt");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重新读取版本" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toMatchObject({ expectedVersion: 4, prompt: "Keep this local prompt" });
    expect(await screen.findByText("已保存")).toBeVisible();
    expect(prompt).toHaveTextContent("Keep this local prompt");
  });

  it("runs the saved draft and refreshes the result node and derivation line immediately", async () => {
    const { client, setTasks } = setup();
    const invalidation = vi.spyOn(client, "invalidateQueries");
    let submitted: unknown;
    server.use(http.post(`${BASE}/run`, async ({ request }) => {
      submitted = await request.json();
      expect(request.headers.get("Idempotency-Key")).toBeTruthy();
      const accepted = task("READY");
      setTasks([accepted]);
      return HttpResponse.json(accepted);
    }));
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(submitted).toEqual({
      canvasItemId: CANVAS_ITEM_ID, expectedDraftVersion: 0,
    }));
    await waitFor(() => expect(invalidation).toHaveBeenCalledWith({ queryKey: ["media-draft", PROJECT_ID, CANVAS_ITEM_ID] }));
    expect(invalidation).toHaveBeenCalledWith({ queryKey: ["snapshot", PROJECT_ID] });
    expect(invalidation).toHaveBeenCalledWith({ queryKey: ["canvas", PROJECT_ID] });
    expect(invalidation).toHaveBeenCalledWith({ queryKey: ["canvas-connections", PROJECT_ID] });
    expect(await screen.findByText(/前方 2 项/)).toHaveTextContent("等待执行器");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("keeps UNKNOWN blocked and offers a single retry control", async () => {
    setup({ tasks: [task("UNKNOWN")] });
    await screen.findByLabelText("图片提示词");
    expect(await screen.findByRole("button", { name: "重试" })).toBeVisible();
    expect(screen.getAllByText("结果未知").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("cancels queued work and re-enables a subsequent explicit run", async () => {
    const { setTasks } = setup({ tasks: [task("READY")] });
    server.use(http.post(`/api/v1/projects/${PROJECT_ID}/tasks/task-direct/cancel-queued`, () => {
      const canceled = task("CANCELED");
      setTasks([canceled]);
      return HttpResponse.json(canceled);
    }));
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "取消排队" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: "取消排队" })).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("pins ordered start/end versions and saves their stable colors with whole-second duration", async () => {
    const { saves } = setup({ kind: "VIDEO", handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "海边灯塔", resourceDefaultVersionId: "image-v2" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: [
        { id: "image-v1", versionNo: 1, content: { assetId: "asset-old-frame" } },
        { id: "image-v2", versionNo: 2, content: { assetId: "asset-new-frame" } },
        { id: "image-empty", versionNo: 3, content: { prompt: "not generated yet" } },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    const search = screen.getByRole("searchbox", { name: "搜索资源图片" });
    await user.type(search, "v1");
    expect(await screen.findByRole("checkbox", { name: "选择 海边灯塔 · v1" })).toBeVisible();
    expect(screen.queryByRole("checkbox", { name: /v2/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: /v3/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "选择 海边灯塔 · v1" }));
    await user.clear(search);
    await user.click(screen.getByRole("checkbox", { name: "选择 海边灯塔 · v2" }));
    expect(saves).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "添加所选图片（2）" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ mediaInputs: [
      { versionId: "image-v1", role: "START_FRAME", color: "#F15CAF" },
      { versionId: "image-v2", role: "END_FRAME", color: "#67C7F3" },
    ] }));
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    expect(screen.getByRole("spinbutton", { name: "时长（秒）" })).toHaveAttribute("min", "2");
    expect(screen.getByRole("spinbutton", { name: "时长（秒）" })).toHaveAttribute("max", "10");
    await user.type(screen.getByRole("spinbutton", { name: "时长（秒）" }), "4");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      mediaInputs: [
        { versionId: "image-v1", role: "START_FRAME", color: "#F15CAF" },
        { versionId: "image-v2", role: "END_FRAME", color: "#67C7F3" },
      ], durationSeconds: 4 }));
    const firstFrame = screen.getByLabelText("海边灯塔 · v1，序号 1");
    expect(within(firstFrame).getByText("1")).toBeVisible();
    expect(firstFrame.querySelector("img")).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-old-frame/content`);
    expect(screen.getByLabelText("海边灯塔 · v2，序号 2")).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    expect(screen.getByLabelText("海边灯塔 · v2，序号 2").querySelector("img")).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-new-frame/content`);
    await user.click(screen.getByRole("button", { name: "取消引入 海边灯塔 · v1" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ mediaInputs: [
      { versionId: "image-v2", role: "END_FRAME", color: "#67C7F3" },
    ], durationSeconds: 4 }));
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("defaults an empty video draft to text-to-video and disables image-required modes", async () => {
    const videoSettings: MediaSettings = {
      connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: versatileVideoCapability.id, version: 0 }],
    };
    const { saves } = setup({ kind: "VIDEO", settings: videoSettings });
    const user = userEvent.setup();

    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      videoInputMode: "TEXT", parameters: { aspectRatio: "AUTO" }, mediaInputs: [],
    }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent("文生视频");
    await user.click(screen.getByRole("button", { name: "选择视频输入模式" }));
    expect(screen.getByRole("menuitemradio", { name: /文生视频/ })).toBeEnabled();
    expect(screen.getByRole("menuitemradio", { name: /全能参考/ })).toBeDisabled();
    expect(screen.getByRole("menuitemradio", { name: /首尾帧/ })).toBeDisabled();
  });

  it("switches the first image to general reference and persists a video aspect ratio", async () => {
    const videoSettings: MediaSettings = {
      connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: versatileVideoCapability.id, version: 0 }],
    };
    const { saves } = setup({ kind: "VIDEO", settings: videoSettings, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "海边灯塔", resourceDefaultVersionId: "image-v1" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () =>
        HttpResponse.json({ items: [{ id: "image-v1", versionNo: 1,
          content: { assetId: "asset-image-v1" } }] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    await user.click(await screen.findByRole("checkbox", { name: "选择 海边灯塔 · v1" }));
    await user.click(screen.getByRole("button", { name: "添加所选图片（1）" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      videoInputMode: "GENERAL_REFERENCE",
      mediaInputs: [{ versionId: "image-v1", role: "REFERENCE" }],
    }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent("全能参考");

    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    const parameters = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    await user.click(within(parameters).getByRole("button", { name: "9:16" }));
    await waitFor(() => expect(saves.at(-1)?.parameters).toEqual({ aspectRatio: "9:16" }));
    expect(screen.getByRole("button", { name: "尺寸与画质" })).toHaveTextContent("9:16");
  });

  it("retains an unavailable pinned video frame without silently using the current version", async () => {
    const { saves } = setup({ kind: "VIDEO", draft: { ...initialDraft, videoInputMode: "START_END",
      mediaInputs: [{ versionId: "image-gone", artifactId: "reference-image", role: "START_FRAME",
        order: 0, color: "#F15CAF", sources: [{ id: "manual", type: "MANUAL", connectionId: null }] }], durationSeconds: 4 }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "新首帧", resourceDefaultVersionId: "image-v2" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: [
        { id: "image-gone", versionNo: 1, content: {} },
        { id: "image-v2", versionNo: 2, content: { assetId: "asset-current-frame" } },
      ] })),
    ] });
    const user = userEvent.setup();
    expect(await screen.findByText(/无法确认一个或多个已固定媒体版本/)).toBeVisible();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    expect(screen.getByRole("searchbox", { name: "搜索资源图片" })).toHaveValue("");
    expect(screen.queryByRole("checkbox", { name: /选择 新首帧 · v1/ })).not.toBeInTheDocument();
    expect(saves).toHaveLength(0);
  });

  it("retries video image-history failures and restores the exact old frame", async () => {
    let attempts = 0;
    setup({ kind: "VIDEO", draft: { ...initialDraft, videoInputMode: "START_END",
      mediaInputs: [{ versionId: "image-v1", artifactId: "reference-image", role: "START_FRAME",
        order: 0, color: "#F15CAF", sources: [{ id: "manual", type: "MANUAL", connectionId: null }] }], durationSeconds: 4 }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "海边灯塔", resourceDefaultVersionId: "image-v2" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => {
        attempts += 1;
        return attempts === 1 ? HttpResponse.json({ code: "TEMPORARY", detail: "暂不可用" }, { status: 503 })
          : HttpResponse.json({ items: [
            { id: "image-v1", versionNo: 1, content: { assetId: "asset-old-frame" } },
            { id: "image-v2", versionNo: 2, content: { assetId: "asset-new-frame" } },
          ] });
      }),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    expect(await screen.findByText("无法读取图片版本。")).toBeVisible();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重试读取图片" }));
    expect(await screen.findByRole("checkbox", { name: "选择 海边灯塔 · v1" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.getByLabelText("海边灯塔 · v1，序号 1").querySelector("img")).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-old-frame/content`);
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("shows the empty video-reference state without offering image drafts as usable frames", async () => {
    setup({ kind: "VIDEO", handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-draft", title: "尚未生成", resourceDefaultVersionId: null }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-draft/versions`, () => HttpResponse.json({ items: [
        { id: "draft-v1", versionNo: 1, content: { assetId: "" } },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    expect(await screen.findByText("暂无已生成或上传的图片，请先添加图片。")).toBeVisible();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("blocks unconfigured models and retries an unavailable configuration before enabling run", async () => {
    let configured = false;
    let failed = false;
    const { client } = setup({ handlers: [http.get("/api/v1/settings/media-connections", () => failed
      ? HttpResponse.json({ code: "TEMPORARY", detail: "配置读取失败" }, { status: 503 })
      : HttpResponse.json(configured ? settings : { connections: [], defaults: [] }))] });
    const user = userEvent.setup();
    await screen.findByText("尚未配置默认模型，请选择可用模型或先在媒体设置中配置。");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    configured = true;
    failed = true;
    await client.invalidateQueries({ queryKey: ["media-settings"] });
    expect(await screen.findByText("无法读取模型配置。")).toBeVisible();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    failed = false;
    await user.click(screen.getByRole("button", { name: "重试读取模型" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("adopts a refreshed draft CAS version after running while preserving subsequent local edits", async () => {
    let remoteVersion = 0;
    const saved: SaveMediaDraftRequest[] = [];
    setup({ handlers: [
      http.get(DRAFT_URL, () => HttpResponse.json({ ...initialDraft, version: remoteVersion })),
      http.post(`${BASE}/run`, () => {
        remoteVersion = 4;
        return HttpResponse.json(task("SUCCEEDED"));
      }),
      http.put(DRAFT_URL, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        saved.push(input);
        return HttpResponse.json({ ...initialDraft, ...input, version: input.expectedVersion + 1 });
      }),
    ] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByLabelText("图片提示词"));
    await user.keyboard("{End} in the rain");
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toMatchObject({ expectedVersion: 4, prompt: `${initialDraft.prompt} in the rain` });
  });

  it("ignores a stale background response that arrives after a newer save was acknowledged", async () => {
    let reads = 0;
    let releaseStale: (() => void) | undefined;
    const saved: SaveMediaDraftRequest[] = [];
    const { client } = setup({ handlers: [
      http.get(DRAFT_URL, async () => {
        reads += 1;
        if (reads === 1) return HttpResponse.json(initialDraft);
        await new Promise<void>((resolve) => { releaseStale = resolve; });
        return HttpResponse.json(initialDraft);
      }),
      http.put(DRAFT_URL, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        saved.push(input);
        return HttpResponse.json({ ...initialDraft, ...input, version: input.expectedVersion + 1 });
      }),
    ] });
    const user = userEvent.setup();
    const prompt = await screen.findByLabelText("图片提示词");
    const slowRefresh = client.invalidateQueries({ queryKey: ["media-draft", PROJECT_ID, CANVAS_ITEM_ID] });
    await waitFor(() => expect(releaseStale).toBeDefined());
    await user.clear(prompt);
    await user.type(prompt, "Newer saved prompt");
    await waitFor(() => expect(saved).toHaveLength(1));
    await screen.findByText("已保存");
    await act(async () => { releaseStale?.(); await slowRefresh; });
    expect(prompt).toHaveTextContent("Newer saved prompt");
    await user.type(prompt, " and next edit");
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(saved[1]).toMatchObject({ expectedVersion: 1, prompt: "Newer saved prompt and next edit" });
  });

  it("retries a lost run response with its original key and payload after the server draft advances", async () => {
    const keys: (string | null)[] = [];
    const payloads: unknown[] = [];
    let remoteVersion = 0;
    const { client } = setup({ handlers: [
      http.get(DRAFT_URL, () => HttpResponse.json({ ...initialDraft, version: remoteVersion })),
      http.post(`${BASE}/run`, async ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key"));
        payloads.push(await request.json());
        remoteVersion = 1;
        return keys.length === 1 ? HttpResponse.error() : HttpResponse.json(task("READY"));
      }),
    ] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("运行失败：");
    await act(async () => { await client.invalidateQueries({ queryKey: ["media-draft", PROJECT_ID, CANVAS_ITEM_ID] }); });
    expect(client.getQueryData<MediaDraft>(["media-draft", PROJECT_ID, CANVAS_ITEM_ID])?.version).toBe(1);
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(payloads).toEqual([
      { canvasItemId: CANVAS_ITEM_ID, expectedDraftVersion: 0 },
      { canvasItemId: CANVAS_ITEM_ID, expectedDraftVersion: 0 },
    ]);
  });

  it("retries a failed initial draft read", async () => {
    let reads = 0;
    setup({ handlers: [http.get(DRAFT_URL, () => {
      reads += 1;
      return reads === 1 ? HttpResponse.json({ code: "TEMPORARY", detail: "暂时无法读取", retryable: true }, { status: 503 })
        : HttpResponse.json(initialDraft);
    })] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重试读取草稿" }));
    expect(await screen.findByLabelText("图片提示词")).toHaveTextContent(initialDraft.prompt);
  });
});
