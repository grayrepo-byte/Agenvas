import { QueryClient,QueryClientProvider } from "@tanstack/react-query";
import { act,cleanup,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse,type RequestHandler } from "msw";
import { afterEach,beforeEach,describe,expect,it,vi } from "vitest";
import type { Artifact,MediaCapability,MediaDraft,MediaSettings,SaveMediaDraftRequest,Task } from "../../shared/api/client";
import { changeControl,clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { useCanvasStore } from "./canvasStore";

const NOW = "2026-09-26T00:00:00Z";
const AUTOSAVE_SETTLE_MS = 750;
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
  parameters: {}, durationSeconds: null, capabilityId: null, videoInputMode: null, styleId: null,
  mediaInputs: [], mentions: [],
  displayMode: "DRAFT", version: 0, createdAt: NOW, updatedAt: NOW,
};
const imageCapability: MediaCapability = {
  id: "image-capability", name: "细节生图", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION", minimumSeconds: 0,
  maximumSeconds: 0, maxReferenceAudios: 0, maxReferenceVideos: 0, maxReferenceImages: 4, supportedVideoInputModes: [],
  defaultVideoInputMode: null, supportsEndFrame: false,
  supportedImageAspectRatios: ["AUTO", "1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9"],
  supportedImageResolutions: ["1K", "2K", "4K"], supportedImageQualities: ["low", "medium", "high"],
  supportsTransparentBackground: true, supportsImageMask: true,
  mappingSha256: "a".repeat(64), settings: { quality: "high" },
};
const videoCapability: MediaCapability = {
  ...imageCapability, id: "video-capability", name: "镜头视频", adapterId: "ARK_SEEDANCE_2_I2V",
  kind: "VIDEO_GENERATION", minimumSeconds: 2, maximumSeconds: 10,
  maxReferenceAudios: 0, maxReferenceVideos: 0, maxReferenceImages: 2, supportedVideoInputModes: ["START_END"],
  defaultVideoInputMode: "START_END", supportsEndFrame: true, supportedImageAspectRatios: [],
  supportedImageResolutions: [], supportedImageQualities: [], supportsTransparentBackground: false,
  supportsImageMask: false, settings: {},
};
const versatileVideoCapability: MediaCapability = {
  ...videoCapability, id: "versatile-video-capability", name: "全能视频",
  maxReferenceAudios: 0, maxReferenceVideos: 0, maxReferenceImages: 4,
  supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"],
  defaultVideoInputMode: "TEXT", supportsEndFrame: true,
};
const audioCapability: MediaCapability = {
  ...imageCapability, id: "audio-capability", name: "Seed Audio 1.0",
  kind: "AUDIO_GENERATION", adapterId: "VOLC_SEED_AUDIO_1", maxReferenceImages: 1, maxReferenceAudios: 3, maxReferenceVideos: 0,
  supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], settings: {},
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
const audioSettings: MediaSettings = {
  connections: [{ ...settings.connections[0]!, platform: "VOLCENGINE", capabilities: [audioCapability] }],
  defaults: [{ kind: "AUDIO_GENERATION", capabilityId: audioCapability.id, version: 0 }],
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
  it.each((["IMAGE", "VIDEO"] as const).flatMap((kind) =>
    (["READY", "SUBMITTING", "RUNNING", "WAITING_PROVIDER"] as const).flatMap((status) =>
      [null, "approved-agent-run"].map((runId) => ({ kind, status, runId })))))("locks $kind inputs during $status (run: $runId) and unlocks after completion", async ({ kind, status, runId }) => {
      const active = { ...task(status), runId,
        kind: kind === "IMAGE" ? "IMAGE_GENERATION" as const : "VIDEO_GENERATION" as const };
      const { client, saves, setTasks } = setup({ kind, tasks: [active],
        draft: { ...initialDraft, durationSeconds: kind === "VIDEO" ? 5 : null,
          videoInputMode: kind === "VIDEO" ? "START_END" : null } });
      const prompt = await screen.findByRole("textbox", { name: kind === "IMAGE" ? "图片提示词" : "视频提示词" });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "false"));
      expect(prompt).toHaveAttribute("aria-readonly", "true");
      expect(screen.getByRole("button", { name: "选择生成模型" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "尺寸与画质" })).toBeDisabled();
      expect(screen.getByRole("button", { name: "模板" })).toBeDisabled();
      expect(screen.getByLabelText("选择本地图片")).toBeDisabled();
      if (kind === "VIDEO") expect(screen.getByRole("button", { name: "选择视频输入模式" })).toBeDisabled();
      await userEvent.setup().click(screen.getByRole("button", { name: "选择生成模型" }));
      expect(screen.queryByRole("menuitemradio")).not.toBeInTheDocument();
      expect(saves).toHaveLength(0);
      setTasks([{ ...active, status: "SUCCEEDED" }]);
      await client.invalidateQueries({ queryKey: ["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID] });
      await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
      expect(screen.getByRole("button", { name: "选择生成模型" })).toBeEnabled();
    });

  it("selects the actual default model without a duplicate project-default entry", async () => {
    setup(); const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "图片提示词" });
    await user.click(screen.getByRole("button", { name: "选择生成模型" }));
    expect(screen.queryByRole("menuitemradio", { name: /项目默认能力/ })).not.toBeInTheDocument();
    expect(screen.getByRole("menuitemradio", { name: /细节生图/ })).toHaveAttribute("aria-checked", "true");
  });

  it("closes an already open video mode menu when an Agent task starts", async () => {
    const { client, setTasks, saves } = setup({ kind: "VIDEO",
      draft: { ...initialDraft, capabilityId: versatileVideoCapability.id,
        durationSeconds: 5, videoInputMode: "TEXT" },
      settings: { ...settings, connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }] } });
    const user = userEvent.setup();
    const trigger = await screen.findByRole("button", { name: "选择视频输入模式" });
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);
    expect(await screen.findByRole("menu", { name: "视频输入模式" })).toBeVisible();
    setTasks([{ ...task("WAITING_PROVIDER"), kind: "VIDEO_GENERATION", runId: "agent-run" }]);
    await client.invalidateQueries({ queryKey: ["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID] });
    await waitFor(() => expect(screen.queryByRole("menu", { name: "视频输入模式" })).not.toBeInTheDocument());
    expect(trigger).toBeDisabled();
    expect(saves).toHaveLength(0);
  });

  it("keeps inputs locked until the initial task status is known", async () => {
    let release: () => void = () => {};
    const pending = new Promise<void>((resolve) => { release = resolve; });
    setup({ handlers: [http.get(`${BASE}/run`, async () => { await pending; return HttpResponse.json([]); })] });
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    expect(prompt).toHaveAttribute("aria-readonly", "true");
    expect(screen.getByRole("button", { name: "选择生成模型" })).toBeDisabled();
    release();
    await waitFor(() => expect(prompt).toHaveAttribute("aria-readonly", "false"));
  });

  it("keeps inputs locked after task status fails while allowing a read retry", async () => {
    setup({ handlers: [http.get(`${BASE}/run`, () => HttpResponse.json({ code: "INTERNAL_ERROR" }, { status: 500 }))] });
    const retry = await screen.findByRole("button", { name: "重试检查任务" });
    expect(screen.getByRole("textbox", { name: "图片提示词" })).toHaveAttribute("aria-readonly", "true");
    expect(retry).toBeEnabled();
    server.use(http.get(`${BASE}/run`, () => HttpResponse.json([])));
    await userEvent.setup().click(retry);
    await waitFor(() => expect(screen.getByRole("textbox", { name: "图片提示词" }))
      .toHaveAttribute("aria-readonly", "false"));
  });

  it("does not normalize or autosave a running video's frozen draft", async () => {
    const { saves } = setup({ kind: "VIDEO",
      draft: { ...initialDraft, durationSeconds: 5, videoInputMode: "TEXT" },
      tasks: [{ ...task("RUNNING"), kind: "VIDEO_GENERATION", runId: "agent-run" }] });
    const prompt = await screen.findByRole("textbox", { name: "视频提示词" });
    await waitFor(() => expect(prompt).toHaveAttribute("aria-readonly", "true"));
    // Longer than the editor's autosave debounce: opening a running draft must not save a mode repair.
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, AUTOSAVE_SETTLE_MS)); });
    expect(saves).toHaveLength(0);
  });

  it.each(["IMAGE", "VIDEO"] as const)("locks RunningHub %s dynamic parameters during generation", async (kind) => {
    const capability: MediaCapability = { ...(kind === "IMAGE" ? imageCapability : videoCapability),
      id: "runninghub-locked", adapterId: kind === "IMAGE" ? "RUNNINGHUB_IMAGE" : "RUNNINGHUB_VIDEO",
      settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
        usePersonalQueue: false, addMetadata: false,
        fields: [{ key: "strength", label: "变化强度", type: "NUMBER", nodeId: "1", fieldName: "strength",
          required: true, advanced: false, minimum: 0, maximum: 1 }],
        outputs: [{ kind, primary: true, maxCount: 1 }] } } };
    const { saves } = setup({ kind, draft: { ...initialDraft, capabilityId: capability.id,
      parameters: { dynamicValues: { strength: 0.5 } } },
      tasks: [{ ...task("RUNNING"), kind: capability.kind, runId: "agent-run" }],
      settings: { connections: [{ ...settings.connections[0]!, platform: "RUNNINGHUB", capabilities: [capability] }],
        defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] } });
    const extended = await screen.findByRole("button", { name: "扩展参数" });
    expect(extended).toBeDisabled();
    expect(screen.queryByRole("spinbutton", { name: "变化强度 *" })).not.toBeInTheDocument();
    expect(saves).toHaveLength(0);
  });

  it("pauses pending autosave and retains local edits when a task starts before closing", async () => {
    const { client, saves } = setup();
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    await waitFor(() => expect(prompt).toHaveAttribute("contenteditable", "true"));
    prompt.textContent = "Unsaved local prompt";
    fireEvent.input(prompt);
    act(() => { client.setQueryData(["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID],
      [{ ...task("RUNNING"), runId: "agent-run" }]); });
    await waitFor(() => expect(prompt).toHaveAttribute("aria-readonly", "true"));
    cleanup();
    await act(async () => { await new Promise((resolve) => window.setTimeout(resolve, AUTOSAVE_SETTLE_MS)); });
    expect(saves).toHaveLength(0);
    expect(useCanvasStore.getState().mediaDraftRecoveries[`${PROJECT_ID}:${CANVAS_ITEM_ID}`]?.request.prompt)
      .toBe("Unsaved local prompt");
  });

  it.each([null, "versatile-video-capability"])("initializes an image-only video model in its supported reference mode (selection: %s)", async (capabilityId) => {
    const capability = { ...versatileVideoCapability, supportedVideoInputModes: ["GENERAL_REFERENCE"] as MediaCapability["supportedVideoInputModes"],
      defaultVideoInputMode: "GENERAL_REFERENCE" as const };
    const { saves } = setup({ kind: "VIDEO", draft: { ...initialDraft, durationSeconds: 5, videoInputMode: "TEXT", capabilityId }, settings: {
      ...settings, connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }],
    } });
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: "GENERAL_REFERENCE", mediaInputs: [] }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent("全能参考");
  });

  it("shows input validation only when the user submits and never creates an invalid task", async () => {
    const generate = vi.fn();
    const { saves } = setup({ kind: "VIDEO", draft: { ...initialDraft, durationSeconds: 5 },
      handlers: [http.post(`${BASE}/run`, () => { generate(); return HttpResponse.json(task("READY")); })] });
    await waitFor(() => expect(saves.length).toBeGreaterThan(0));
    const message = "当前媒体输入不满足所选视频模式或模型能力，请调整后再运行。";
    expect(screen.queryByText(message)).not.toBeInTheDocument();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByText(message)).toBeVisible();
    expect(generate).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "关闭" }));
    expect(screen.queryByRole("dialog", { name: "请检查生成输入" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "运行" }));
    fireEvent.input(screen.getByRole("textbox", { name: "视频提示词" }), { target: { textContent: "A new scene" } });
    expect(screen.queryByRole("dialog", { name: "请检查生成输入" })).not.toBeInTheDocument();
    expect(generate).not.toHaveBeenCalled();
  });

  it("blocks image generation on submit when persisted references exceed the model capacity", async () => {
    const generate = vi.fn();
    const versions = ["image-v1", "image-v2"];
    setup({ settings: { ...settings, connections: [{ ...settings.connections[0]!, capabilities: [{ ...imageCapability, maxReferenceImages: 1 }] }] },
      draft: { ...initialDraft, mediaInputs: versions.map((versionId, order) => ({ versionId, artifactId: "reference-image",
        role: "REFERENCE", order, color: "#F15CAF", sources: [{ id: `manual-${order}`, type: "MANUAL", connectionId: null }] })) },
      handlers: [http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact, id: "reference-image" }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: versions.map((id) => ({ id, versionNo: 1, content: { assetId: `asset-${id}` } })) })),
        http.post(`${BASE}/run`, () => { generate(); return HttpResponse.json(task("READY")); })] });
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: "运行" }));
    expect(screen.getByRole("dialog", { name: "请检查生成输入" })).toHaveTextContent("所选模型最多支持 1 张图片");
    expect(generate).not.toHaveBeenCalled();
  });

  it("keeps an image-only model in reference mode after the last connected input is removed", async () => {
    const capability = { ...versatileVideoCapability, supportedVideoInputModes: ["GENERAL_REFERENCE"] as MediaCapability["supportedVideoInputModes"] };
    const { saves } = setup({ kind: "VIDEO", settings: {
      ...settings, connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }],
    }, draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "GENERAL_REFERENCE", durationSeconds: 5,
      parameters: { aspectRatio: "AUTO" }, mediaInputs: [{ versionId: "connected-version", artifactId: "connected-artifact",
        role: "REFERENCE", order: 0, color: "#F15CAF", sources: [{ id: "connection-source", type: "CONNECTION", connectionId: "line-1" }] }] },
    handlers: [http.post(`${DRAFT_URL}/media-inputs/connected-version/remove`, () => HttpResponse.json({ ...initialDraft,
      capabilityId: capability.id, videoInputMode: "TEXT", durationSeconds: 5, parameters: { aspectRatio: "AUTO" }, version: 1 }))] });
    await userEvent.setup().click(await screen.findByRole("button", { name: "取消引入 图片输入 1" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ expectedVersion: 1, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [] }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent("全能参考");
  });

  it("dismisses the upload source menu on prompt clicks and a second trigger click", async () => {
    setup(); const user = userEvent.setup();
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    const add = screen.getByRole("button", { name: "添加图片输入" });
    await user.click(add);
    expect(screen.getByRole("menu", { name: "图片来源" })).toBeVisible();
    await user.click(prompt);
    await waitFor(() => expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument());
    expect(prompt).toHaveFocus();
    await user.click(add);
    await user.click(add);
    await waitFor(() => expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument());
  });

  it.each(["IMAGE", "VIDEO"] as const)("persists an illustrated style for %s without changing the prompt", async (kind) => {
    const user = userEvent.setup();
    const { saves } = setup({ kind, ...(kind === "VIDEO" ? {
      draft: { ...initialDraft, durationSeconds: 5, videoInputMode: "TEXT" },
      settings: { ...settings, connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }],
        defaults: [{ kind: "VIDEO_GENERATION", capabilityId: versatileVideoCapability.id, version: 0 }] },
    } : {}), handlers: [http.get("/api/v1/media-styles", () => HttpResponse.json([
      { id: "style-watercolor", name: "水彩", category: "绘画", enabled: true, version: 1,
        thumbnailUrl: "/api/v1/media-styles/style-watercolor/thumbnail", builtIn: true },
      { id: "style-photo", name: "写实摄影", category: "摄影", enabled: true, version: 1,
        thumbnailUrl: "/api/v1/media-styles/style-photo/thumbnail", builtIn: true },
    ]))] });
    await user.click(await screen.findByRole("button", { name: "选择风格" }));
    const modal = screen.getByRole("dialog", { name: "选择风格" });
    expect(await within(modal).findByRole("img", { name: "水彩效果预览" })).toHaveAttribute("src", "/api/v1/media-styles/style-watercolor/thumbnail");
    await user.type(within(modal).getByRole("searchbox"), "水彩");
    expect(within(modal).queryByRole("button", { name: "写实摄影" })).not.toBeInTheDocument();
    await user.click(within(modal).getByRole("button", { name: "水彩" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ styleId: "style-watercolor", prompt: initialDraft.prompt }));
    expect(screen.getByRole("textbox", { name: kind === "IMAGE" ? "图片提示词" : "视频提示词" })).toHaveTextContent(initialDraft.prompt);
    expect(screen.getByRole("button", { name: "选择风格" })).toHaveTextContent("水彩");
    await user.click(screen.getByRole("button", { name: "清除风格" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ styleId: null, prompt: initialDraft.prompt }));
  });

  it("retains a disabled style, blocks generation and supports clearing it", async () => {
    const user = userEvent.setup();
    const { saves } = setup({ draft: { ...initialDraft, styleId: "style-retired" }, handlers: [
      http.get("/api/v1/media-styles", () => HttpResponse.json([{ id: "style-retired", name: "旧风格", category: "绘画",
        enabled: false, version: 2, thumbnailUrl: null, builtIn: false }])),
    ] });
    expect(await screen.findByText("所选风格已停用或不可用，请清除或重新选择后运行。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "选择风格" })).toHaveTextContent("旧风格");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "清除风格" }));
    await waitFor(() => expect(saves.at(-1)?.styleId).toBeNull());
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("does not offer styles for audio generation", async () => {
    setup({ kind: "AUDIO", settings: audioSettings });
    await screen.findByRole("textbox", { name: "音频提示词" });
    expect(screen.queryByRole("button", { name: "选择风格" })).not.toBeInTheDocument();
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
    await user.click(await screen.findByRole("button", { name: "用作参考：旅馆" }));
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
  it("keeps the library reference picker open when choosing a media type", async () => {
    setup({ kind: "AUDIO", settings: audioSettings, handlers: [
      http.get("/api/v1/library/entries", ({ request }) => {
        const kind = new URL(request.url).searchParams.get("kind");
        return HttpResponse.json({ items: [{ id: `library-${kind}`, name: kind === "AUDIO" ? "音频资产" : "图片资产",
          category: "OTHER", kind, version: 0, source: {}, favorite: false, createdAt: NOW, hasThumbnail: false }],
          total: 1, categoryCounts: {} });
      }),
    ] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "音频提示词" });
    await user.click(screen.getByRole("button", { name: "添加图片或音频输入" }));
    await user.click(screen.getByRole("menuitem", { name: /从我的资产选择/ }));
    expect(await screen.findByRole("button", { name: "用作参考：图片资产" })).toBeVisible();
    await changeControl(screen.getByLabelText("媒体类型"), { target: { value: "AUDIO" } });
    expect(screen.queryByRole("dialog", { name: "我的资产参考" })).toBeVisible();
    expect(await screen.findByRole("button", { name: "用作参考：音频资产" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "用作参考：图片资产" })).not.toBeInTheDocument();
  });
  it.each(["outside click", "Escape", "unowned listbox"] as const)("closes the idle library picker on %s", async (action) => {
    setup({ handlers: [http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [], total: 0, categoryCounts: {} }))] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "图片提示词" });
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: /从我的资产选择/ }));
    expect(screen.getByRole("dialog", { name: "我的资产参考" })).toBeVisible();
    if (action === "Escape") await user.keyboard("{Escape}");
    else if (action === "outside click") await user.click(screen.getByRole("textbox", { name: "图片提示词" }));
    else {
      const unrelated = document.createElement("div");
      unrelated.setAttribute("role", "listbox");
      unrelated.id = "unrelated-listbox";
      document.body.append(unrelated);
      try { await user.click(unrelated); } finally { unrelated.remove(); }
    }
    expect(screen.queryByRole("dialog", { name: "我的资产参考" })).not.toBeInTheDocument();
  });
  it.each(["RUNNINGHUB_VIDEO", "COMFY_VIDEO_V1"])("uses the existing reference source menu for %s named slots", async (adapterId) => {
    const fields = [{ key: "hero", label: "人物图", type: "IMAGE" as const, nodeId: "1", fieldName: "image", required: true, advanced: false }];
    const capability: MediaCapability = { ...versatileVideoCapability, id: "workflow-menu", adapterId,
      settings: adapterId === "COMFY_VIDEO_V1" ? { comfyInputs: fields } : { runningHub: {
        schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false,
        addMetadata: false, fields, outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }],
      } } };
    setup({ kind: "VIDEO", draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT" },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
        defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] } });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "选择人物图" }));
    const menu = screen.getByRole("menu", { name: "图片来源" });
    expect(menu).toHaveClass("media-draft-reference-sources");
    for (const name of ["从设备上传", "从资源库选择", "从画布选择", "从我的资产选择"]) {
      expect(within(menu).getByRole("menuitem", { name })).toBeEnabled();
    }
    expect(screen.queryByText("项目资源")).not.toBeInTheDocument();
    await user.click(within(menu).getByRole("menuitem", { name: "从资源库选择" }));
    expect(screen.getByRole("searchbox", { name: "搜索资源图片" })).toBeVisible();
  });

  it.each(["IMAGE", "VIDEO", "AUDIO"] as const)("filters both existing pickers by the %s workflow slot and binds its exact version", async (kind) => {
    const capability: MediaCapability = { ...imageCapability, id: "typed-workflow", adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
        usePersonalQueue: false, addMetadata: false,
        fields: [{ key: "input", label: "工作流素材", type: kind, nodeId: "1", fieldName: "input", required: true, advanced: false }],
        outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
      } } };
    const sources = (["IMAGE", "VIDEO", "AUDIO"] as const).map((type) => ({ ...artifact,
      id: `source-${type}`, kind: type, title: `合成${type}`, resourceDefaultVersionId: `${type}-v2` }));
    const { saves } = setup({ draft: { ...initialDraft, capabilityId: capability.id },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
        defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] },
      handlers: [http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: sources })),
        ...sources.map((source) => http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/${source.id}/versions`, () => HttpResponse.json({ items: [1, 2].map((versionNo) => ({
          id: `${source.kind}-v${versionNo}`, versionNo, content: { assetId: `${source.kind}-asset-${versionNo}` },
        })) }))),
        http.get(`/api/v1/projects/${PROJECT_ID}/canvas/items`, () => HttpResponse.json({ items: sources.map((source) => ({
          id: `canvas-${source.kind}`, subjectType: "ARTIFACT", title: source.title, artifact: source,
          selectedVersion: { id: `${source.kind}-v1`, versionNo: 1, content: { assetId: `${source.kind}-asset-1` } },
        })) })),
      ] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "选择工作流素材" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    for (const other of sources.filter((source) => source.kind !== kind)) {
      expect(screen.queryByRole("button", { name: `选择 ${other.title} · v2` })).not.toBeInTheDocument();
    }
    await user.click(await screen.findByRole("button", { name: `选择 合成${kind} · v2` }));
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ input: `${kind}-v2` }));
    await user.click(screen.getByRole("button", { name: "选择工作流素材" }));
    await user.click(screen.getByRole("menuitem", { name: "从画布选择" }));
    const picker = screen.getByRole("dialog", { name: kind === "IMAGE" ? "从画布选择图片" : "从画布选择媒体" });
    expect(within(picker).getAllByRole("button")).toHaveLength(1);
    await user.click(within(picker).getByRole("button"));
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ input: `${kind}-v1` }));
    expect(saves.at(-1)?.mediaInputs).toEqual([expect.objectContaining({ versionId: `${kind}-v1`,
      role: kind === "IMAGE" ? "REFERENCE" : kind === "VIDEO" ? "VIDEO_REFERENCE" : "AUDIO_REFERENCE" })]);
  });

  it.each(["RUNNINGHUB_VIDEO", "COMFY_VIDEO_V1"])("imports a personal asset into the named %s slot without a video-mode confirmation", async (adapterId) => {
    const fields = [{ key: "hero", label: "人物图", type: "IMAGE" as const, nodeId: "1", fieldName: "image", required: true, advanced: false }];
    const capability: MediaCapability = { ...versatileVideoCapability, id: "workflow-library", adapterId,
      settings: adapterId === "COMFY_VIDEO_V1" ? { comfyInputs: fields } : { runningHub: {
        schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false,
        addMetadata: false, fields, outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }],
      } } };
    let submitted: Record<string, unknown> | undefined;
    setup({ kind: "VIDEO", draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT" },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
        defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] },
      handlers: [http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "library-hero", name: "合成人物", kind: "IMAGE", category: "OTHER", version: 1,
        source: {}, favorite: false, createdAt: NOW, hasThumbnail: false }], total: 1, categoryCounts: { OTHER: 1 } })),
        http.post(`/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/library-references`, async ({ request }) => {
          submitted = await request.json() as Record<string, unknown>;
          return HttpResponse.json({ id: "library-workflow-command", status: "ACCEPTED" }, { status: 202 });
        }),
        http.get("/api/v1/library/commands/library-workflow-command", () => HttpResponse.json({ id: "library-workflow-command", status: "ARCHIVING" })),
      ] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "选择人物图" }));
    await user.click(screen.getByRole("menuitem", { name: "从我的资产选择" }));
    await user.click(await screen.findByRole("button", { name: "用作参考：合成人物" }));
    await waitFor(() => expect(submitted).toEqual(expect.objectContaining({ slotKey: "hero", entryId: "library-hero" })));
    expect(screen.queryByRole("button", { name: "确认切换并添加" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "选择人物图" })).toBeDisabled());
  });

  it("keeps personal-asset transfer disabled until uploads into other named slots finish", async () => {
    const capability: MediaCapability = { ...imageCapability, id: "workflow-upload-transfer", adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
        fields: ["hero", "detail"].map((key) => ({ key, label: key, type: "IMAGE", nodeId: key, fieldName: "image", required: true, advanced: false })),
        outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
      } } };
    let finishUpload: (() => void) | undefined;
    setup({ draft: { ...initialDraft, capabilityId: capability.id },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
        defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] },
      handlers: [http.post(`/api/v1/projects/${PROJECT_ID}/assets`, async () => {
          await new Promise<void>((resolve) => { finishUpload = resolve; });
          return HttpResponse.json({ id: "pending-upload-asset", mediaKind: "IMAGE" }, { status: 201 });
        }),
        http.post(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ ...artifact, id: "pending-upload", resourceDefaultVersionId: "pending-upload-version" })),
      ] });
    const user = userEvent.setup();
    await screen.findByRole("button", { name: "选择hero" });
    await changeControl(screen.getByLabelText("上传hero"), { target: { files: [new File(["synthetic"], "hero.png", { type: "image/png" })] } });
    await waitFor(() => expect(finishUpload).toBeDefined());
    await user.click(screen.getByRole("button", { name: "选择detail" }));
    expect(screen.getByRole("menuitem", { name: "从我的资产选择" })).toBeDisabled();
    finishUpload?.();
    await waitFor(() => expect(screen.getByRole("menuitem", { name: "从我的资产选择" })).toBeEnabled());
    expect(screen.queryByRole("dialog", { name: "我的资产参考" })).not.toBeInTheDocument();
  });

  it("uses RunningHub fields for a promptless video app and persists named exact video slots", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "rh-video", adapterId: "RUNNINGHUB_VIDEO", name: "视频换背景", supportedVideoInputModes: ["TEXT", "GENERAL_REFERENCE"],
      settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false, fields: [
        { key: "clip", label: "参考视频", type: "VIDEO", nodeId: "1", fieldName: "video", required: true, advanced: false },
        { key: "strength", label: "变化强度", type: "NUMBER", nodeId: "2", fieldName: "strength", required: true, advanced: false, defaultValue: 0.5, minimum: 0, maximum: 1 },
      ], outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }] } } };
    const { saves } = setup({ kind: "VIDEO", draft: { ...initialDraft, prompt: "", parameters: { dynamicValues: {} }, capabilityId: capability.id, videoInputMode: "TEXT" },
      settings: { connections: [{ ...settings.connections[0]!, platform: "RUNNINGHUB", capabilities: [capability] }], defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      handlers: [http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact, kind: "VIDEO", id: "video-reference", title: "原片段", resourceDefaultVersionId: "video-v1" }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/video-reference/versions`, () => HttpResponse.json({ items: [{ id: "video-v1", versionNo: 1, content: { assetId: "video-asset" } }] }))] });
    const user = userEvent.setup();
    const slot = await screen.findByRole("button", { name: "选择参考视频" });
    expect(screen.getByRole("textbox", { name: "视频提示词" })).toHaveAttribute("aria-readonly", "true");
    expect(screen.queryByRole("button", { name: "选择视频输入模式" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "选择风格" })).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton", { name: "变化强度 *" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    expect(screen.getByRole("dialog", { name: "请检查生成输入" })).toHaveTextContent("参考视频");
    await user.click(slot);
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    await user.click(await screen.findByRole("button", { name: "选择 原片段 · v1" }));
    expect(screen.queryByRole("dialog", { name: "请检查生成输入" })).not.toBeInTheDocument();
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ clip: "video-v1" }));
    expect(saves.at(-1)?.mediaInputs).toEqual([{ versionId: "video-v1", role: "VIDEO_REFERENCE", color: "#F15CAF" }]);
    expect(saves.at(-1)?.durationSeconds).toBeNull();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });


  it("keeps shared slot references and removes a replaced version only after its last assignment", async () => {
    const capability: MediaCapability = { ...imageCapability, id: "rh-slots", adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: {
      schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: ["hero", "detail"].map((key, index) => ({ key, label: index ? "细节图" : "主体图", type: "IMAGE",
        nodeId: String(index + 1), fieldName: "image", required: true, advanced: false })),
      outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
    } } };
    const { saves } = setup({ draft: { ...initialDraft, capabilityId: capability.id, parameters: { dynamicValues: {} } },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] },
      handlers: [http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact, id: "source", title: "合成图片", resourceDefaultVersionId: "source-v2" }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/source/versions`, () => HttpResponse.json({ items: [1, 2].map((versionNo) => ({ id: `source-v${versionNo}`, versionNo, content: { assetId: `source-asset-${versionNo}` } })) }))] });
    const user = userEvent.setup();
    async function choose(label: string, version: number) {
      await user.click(await screen.findByRole("button", { name: `选择${label}` }));
      await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
      await user.click(await screen.findByRole("button", { name: `选择 合成图片 · v${version}` }));
    }
    await choose("主体图", 1); await choose("细节图", 1);
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ hero: "source-v1", detail: "source-v1" }));
    expect(saves.at(-1)?.mediaInputs).toHaveLength(1);
    await choose("主体图", 2);
    await waitFor(() => expect(saves.at(-1)?.mediaInputs).toHaveLength(2));
    await user.click(screen.getByRole("button", { name: "清空细节图" }));
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ hero: "source-v2" }));
    expect(saves.at(-1)?.mediaInputs.map((input) => input.versionId)).toEqual(["source-v2"]);
  });

  it("shows a mapped prompt default in the shared editor and saves its edits as the draft prompt", async () => {
    const capability: MediaCapability = { ...imageCapability, id: "rh-prompt", adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: {
      schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "positive", label: "正向提示词", type: "STRING", source: "PROMPT", nodeId: "1", fieldName: "text", required: true,
        advanced: false, defaultValue: "Synthetic default prompt", maxLength: 80 }], outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
    } } };
    const { saves } = setup({ draft: { ...initialDraft, prompt: "", capabilityId: capability.id },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] } });
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    expect(prompt).toHaveTextContent("Synthetic default prompt");
    prompt.textContent = "Edited workflow prompt"; fireEvent.input(prompt);
    await waitFor(() => expect(saves.at(-1)?.prompt).toBe("Edited workflow prompt"));
    expect(saves.at(-1)?.mentions).toEqual([]);
    expect(screen.queryByRole("textbox", { name: "正向提示词 *" })).not.toBeInTheDocument();
  });

  it("uses the Comfy input summary for a disabled prompt and persists extended scalar edits", async () => {
    const capability: MediaCapability = { ...imageCapability, id: "comfy-scalars", adapterId: "COMFY_IMAGE_V1", settings: { comfyInputs: [
      { key: "seed", label: "随机种子", type: "INTEGER", nodeId: "3", fieldName: "seed", required: false, advanced: false, defaultValue: 42 },
    ] } };
    const { saves, client } = setup({ draft: { ...initialDraft, prompt: "", capabilityId: capability.id },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] } });
    const prompt = await screen.findByRole("textbox", { name: "图片提示词" });
    expect(prompt).toHaveAttribute("aria-readonly", "true");
    expect(screen.queryByRole("button", { name: "选择风格" })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "扩展参数" }));
    const seed = screen.getByRole("spinbutton", { name: "随机种子" });
    expect(seed).toHaveValue(42);
    await changeControl(seed, { target: { value: "11" } });
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ seed: 11 }));
    act(() => client.setQueryData(["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID], [task("RUNNING")]));
    await waitFor(() => expect(seed).toBeDisabled());
  });

  it("blocks an unsupported AutoDL ratio and enables the saved supported ratio", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "autodl", adapterId: "AUTODL_COMFY_VIDEO",
      name: "H3 text", minimumSeconds: 1, maximumSeconds: 15, maxReferenceImages: 0,
      supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT", supportsEndFrame: false,
      settings: { workflowId: "minimax_h3_z0901", videoResolution: "480p" } };
    setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, platform: "AUTODL", capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT", durationSeconds: 1, parameters: { aspectRatio: "1:1" } } });
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByText("当前 AutoDL 工作流不支持此画幅，请选择支持的比例。")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    const picker = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    expect(within(picker).queryByRole("radio", { name: "1:1" })).not.toBeInTheDocument();
    await user.click(within(picker).getByRole("radio", { name: "16:9" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("saves a selected AutoDL tier and updates its exact estimate", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "autodl", adapterId: "AUTODL_COMFY_VIDEO",
      name: "H3 text", minimumSeconds: 1, maximumSeconds: 15, maxReferenceImages: 0,
      supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT", supportsEndFrame: false,
      settings: { workflowId: "minimax_h3_z0901", videoResolution: "480p", videoResolutions: ["480p", "768p"],
        pricingByResolution: { "480p": { amount: "0.1", currency: "CNY", unit: "SECOND" }, "768p": { amount: "0.3", currency: "CNY", unit: "SECOND" } } } };
    const { saves } = setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, platform: "AUTODL", capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT", durationSeconds: 5, parameters: { aspectRatio: "16:9" } } });
    expect(await screen.findByText("预计 CNY 0.5")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    await user.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" })).getByRole("radio", { name: "768p" }));
    expect(screen.getByText("预计 CNY 1.5")).toBeInTheDocument();
    await waitFor(() => expect(saves.at(-1)?.parameters).toEqual({ aspectRatio: "16:9", videoResolution: "768p" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" })).getByRole("radio", { name: "9:16" }));
    await waitFor(() => expect(saves.at(-1)?.parameters).toEqual({ aspectRatio: "9:16", videoResolution: "768p" }));
  });


  it("uses the published definition for a new AutoDL target and its new resolution tier", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "autodl-new", adapterId: "AUTODL_COMFY_VIDEO",
      name: "Future video", minimumSeconds: 1, maximumSeconds: 20, maxReferenceImages: 0,
      supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT", supportsEndFrame: false,
      settings: { workflowId: "future_video_v1", videoResolution: "720p", videoResolutions: ["720p", "2160p"],
        workflowDefinition: { schemaVersion: 1, id: "future_video_v1", label: "Future video", minimumSeconds: 1, maximumSeconds: 20,
          promptLimit: 10000, mode: "TEXT", imageFields: [], audioFields: [], minimumImages: 0, minimumAudios: 0,
          resolutions: ["720p横(1280*720)", "2160p横(3840*2160)"], defaultResolution: "720p", supportsSeed: false } } };
    const { saves } = setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, platform: "AUTODL", capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT", durationSeconds: 18, parameters: { aspectRatio: "16:9" } } });
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    await user.click(within(screen.getByRole("dialog", { name: "尺寸与画质设置" })).getByRole("radio", { name: "2160p" }));
    await waitFor(() => expect(saves.at(-1)?.parameters.videoResolution).toBe("2160p"));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("preserves a removed AutoDL tier and blocks running until the user selects an allowed tier", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "autodl", adapterId: "AUTODL_COMFY_VIDEO",
      name: "H3 text", minimumSeconds: 1, maximumSeconds: 15, maxReferenceImages: 0,
      supportedVideoInputModes: ["TEXT"], defaultVideoInputMode: "TEXT", supportsEndFrame: false,
      settings: { workflowId: "minimax_h3_z0901", videoResolution: "480p", videoResolutions: ["480p"] } };
    const { saves } = setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, platform: "AUTODL", capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "TEXT", durationSeconds: 5,
        parameters: { aspectRatio: "16:9", videoResolution: "768p" } } });
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByText("当前能力不支持已选分辨率，请重新选择。")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    expect(saves).toHaveLength(0);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    const picker = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    expect(within(picker).queryByRole("radio", { name: "768p" })).not.toBeInTheDocument();
    await user.click(within(picker).getByRole("radio", { name: "480p" }));
    await waitFor(() => expect(saves.at(-1)?.parameters.videoResolution).toBe("480p"));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("explains required AutoDL mixed references before generation", async () => {
    const capability: MediaCapability = { ...videoCapability, id: "autodl", adapterId: "AUTODL_COMFY_VIDEO",
      name: "H3 mixed", minimumSeconds: 1, maximumSeconds: 15, maxReferenceImages: 6, maxReferenceAudios: 3, maxReferenceVideos: 0,
      supportedVideoInputModes: ["GENERAL_REFERENCE"], defaultVideoInputMode: "GENERAL_REFERENCE", supportsEndFrame: false,
      settings: { workflowId: "minimax_h3_z0903", videoResolution: "480p" } };
    setup({ kind: "VIDEO", settings: { connections: [{ ...settings.connections[0]!, platform: "AUTODL", capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }] },
      draft: { ...initialDraft, capabilityId: capability.id, videoInputMode: "GENERAL_REFERENCE", durationSeconds: 1 } });
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByText(/此 AutoDL 工作流至少需要 1 张图片、1 条音频/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("selects a searchable audio voice, persists its controls and allows audio generation without video duration", async () => {
    const { saves } = setup({ kind: "AUDIO", settings: audioSettings });
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
    await changeControl(screen.getByLabelText("语速"), { target: { value: "-20" } });
    await waitFor(() => expect(saves.at(-1)?.parameters.speechRate).toBe(-20));
  });

  it.each([
    { name: "reserves an audio slot for the selected voice", roles: ["AUDIO_REFERENCE", "AUDIO_REFERENCE"], speaker: "zh_female_xiaohe_uranus_bigtts", kind: "AUDIO", allowed: false },
    { name: "rejects an image alongside an audio reference", roles: ["AUDIO_REFERENCE"], speaker: "", kind: "IMAGE", allowed: false },
    { name: "rejects an audio reference alongside an image", roles: ["REFERENCE"], speaker: "", kind: "AUDIO", allowed: false },
    { name: "rejects an image alongside the selected voice", roles: [], speaker: "zh_female_xiaohe_uranus_bigtts", kind: "IMAGE", allowed: false },
    { name: "accepts a third audio reference when no voice is selected", roles: ["AUDIO_REFERENCE", "AUDIO_REFERENCE"], speaker: "", kind: "AUDIO", allowed: true },
  ] as const)("$name when selecting a library asset", async ({ roles, speaker, kind, allowed }) => {
    const mediaInputs = roles.map((role, order) => ({ versionId: `reference-v${order}`, artifactId: `reference-${order}`,
      role, order, color: "#F15CAF", sources: [] }));
    const archived = vi.fn();
    setup({ kind: "AUDIO", settings: audioSettings,
      draft: { ...initialDraft, parameters: { speaker }, mediaInputs },
      handlers: [
        http.get("/api/v1/library/entries", ({ request }) => {
          const items = new URL(request.url).searchParams.get("kind") === kind
            ? [{ id: "library-reference", name: "资产参考", category: "OTHER", kind, version: 2,
              source: {}, favorite: false, createdAt: NOW, hasThumbnail: false }] : [];
          return HttpResponse.json({ items, total: items.length, categoryCounts: {} });
        }),
        http.post(`/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/library-references`, async ({ request }) => {
          archived(await request.json());
          return HttpResponse.json({ id: "library-command", status: "ARCHIVING" }, { status: 202 });
        }),
        http.get("/api/v1/library/commands/library-command", () => HttpResponse.json({ id: "library-command", status: "ARCHIVING" })),
      ] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "音频提示词" });
    await user.click(screen.getByRole("button", { name: "添加图片或音频输入" }));
    await user.click(screen.getByRole("menuitem", { name: /从我的资产选择/ }));
    if (kind === "AUDIO") await changeControl(screen.getByLabelText("媒体类型"), { target: { value: kind } });
    await user.click(await screen.findByRole("button", { name: "用作参考：资产参考" }));
    if (allowed) {
      await waitFor(() => expect(archived).toHaveBeenCalledOnce());
      expect(archived).toHaveBeenCalledWith(expect.objectContaining({ entryId: "library-reference", expectedVersion: 2,
        role: "AUDIO_REFERENCE", draft: expect.objectContaining({ mediaInputs: mediaInputs.map(({ versionId, role, color }) => ({ versionId, role, color })),
          parameters: { speaker }, expectedVersion: initialDraft.version }) }));
    } else {
      expect(await screen.findByText("所选能力或当前模式不能再添加这个类型的参考，请先调整模式或移除已有输入。")).toBeVisible();
      expect(archived).not.toHaveBeenCalled();
    }
  });

  it("confirms removal of audio when switching mixed references to frames and updates surviving mention roles", async () => {
    const capability = { ...versatileVideoCapability, adapterId: "MOCK_VIDEO", maxReferenceAudios: 3, maxReferenceVideos: 0 };
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
    await user.click(within(parameters).getByRole("radio", { name: "9:16" }));
    await user.click(within(parameters).getByRole("radio", { name: "2K" }));
    await user.click(within(parameters).getByRole("radio", { name: "低" }));
    await user.click(within(parameters).getByRole("radio", { name: "4" }));
    await user.click(within(parameters).getByRole("switch", { name: "透明背景" }));
    await waitFor(() => expect(saves.at(-1)?.parameters).toEqual({
      aspectRatio: "9:16", resolution: "2K", quality: "low", transparentBackground: true,
      generationCount: 4,
    }));
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
    // The portaled menu must remain open when the pointer leaves its trigger.
    await user.hover(menu);
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 250)); });
    expect(menu).toBeVisible();
    const uploadFromDevice = within(menu).getByRole("menuitem", { name: "从设备上传" });
    expect(uploadFromDevice).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: "从资源库选择" })).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: "从画布选择" })).toBeEnabled();
    const drawReference = within(menu).getByRole("menuitem", { name: "绘制引用图（暂未接入）" });
    expect(drawReference).toHaveAttribute("aria-disabled", "true");
    uploadFromDevice.focus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu", { name: "图片来源" })).not.toBeInTheDocument();
    expect(addImage).toHaveFocus();
  });

  it.each([
    { kind: "IMAGE", type: "image/png", filename: "reference.png" },
    { kind: "AUDIO", type: "", filename: "reference.WAV" },
    { kind: "AUDIO", type: "audio/ogg", filename: "reference.bin" },
  ] as const)("uploads $kind ($type, $filename) as a reusable exact-version reference", async ({ kind, type, filename }) => {
    const audio = kind === "AUDIO";
    const uploadPath = `/api/v1/projects/${PROJECT_ID}/assets${audio ? "/audio" : ""}`;
    const artifactRequests: { key: string | null; body: unknown }[] = [];
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === uploadPath) {
        expect(init?.body).toBeInstanceOf(FormData);
        return HttpResponse.json({ id: "uploaded-asset", mediaKind: kind }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const capability = { ...versatileVideoCapability, maxReferenceAudios: 2, maxReferenceVideos: 0 };
    const { saves } = setup({ kind: audio ? "VIDEO" : "IMAGE", settings: audio ? {
      ...settings, connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }],
    } : settings, handlers: [
      http.post(`/api/v1/projects/${PROJECT_ID}/artifacts`, async ({ request }) => {
        artifactRequests.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        return HttpResponse.json({ ...artifact, kind, id: "uploaded-artifact", title: "reference",
          resourceDefaultVersionId: "uploaded-version" });
      }),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText(audio ? "视频提示词" : "图片提示词");
    await user.hover(screen.getByRole("button", { name: audio ? "添加图片或音频输入" : "添加图片输入" }));
    const uploadInput = screen.getByLabelText(audio ? "选择本地图片或音频" : "选择本地图片");
    const openPicker = vi.spyOn(uploadInput, "click");
    await clickControl(screen.getByRole("menuitem", { name: "从设备上传" }));
    expect(openPicker).toHaveBeenCalledOnce();
    await changeControl(uploadInput, { target: { files: [
      new File(["synthetic media bytes"], filename, { type }),
    ] } });
    await waitFor(() => expect(artifactRequests).toHaveLength(1));
    expect(artifactRequests[0]).toMatchObject({ body: { kind, title: "reference",
      content: { sourceType: "UPLOAD", assetId: "uploaded-asset" } } });
    expect(artifactRequests[0]?.key).toBeTruthy();
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ mediaInputs: [{
      versionId: "uploaded-version", role: audio ? "AUDIO_REFERENCE" : "REFERENCE", color: "#F15CAF",
    }] }));
  });

  it.each([false, true])("reuses archived bytes and the creation key after an artifact failure (RunningHub: %s)", async (dynamic) => {
    const kind = dynamic ? "VIDEO" : "IMAGE";
    const uploadPath = `/api/v1/projects/${PROJECT_ID}/assets${dynamic ? "/video" : ""}`;
    let uploads = 0;
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === uploadPath) {
        uploads++;
        return HttpResponse.json({ id: "retry-asset", mediaKind: kind }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const capability: MediaCapability = { ...videoCapability, id: "rh-upload", adapterId: "RUNNINGHUB_VIDEO",
      settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
        usePersonalQueue: false, addMetadata: false,
        fields: [{ key: "clip", label: "参考视频", type: "VIDEO", nodeId: "1", fieldName: "video", required: true, advanced: false }],
        outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }] } } };
    const writes: { key: string | null; body: unknown }[] = [];
    const { saves } = setup({ kind,
      ...(dynamic ? { settings: { connections: [{ ...settings.connections[0]!, platform: "RUNNINGHUB", capabilities: [capability] }],
        defaults: [{ kind: "VIDEO_GENERATION" as const, capabilityId: capability.id, version: 0 }] },
        draft: { ...initialDraft, prompt: "", capabilityId: capability.id, parameters: { dynamicValues: {} }, videoInputMode: "TEXT" as const } } : {}),
      handlers: [http.post(`/api/v1/projects/${PROJECT_ID}/artifacts`, async ({ request }) => {
        writes.push({ key: request.headers.get("Idempotency-Key"), body: await request.json() });
        if (writes.length === 1) return HttpResponse.json({ code: "TEMPORARY", detail: "产物暂未创建" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ ...artifact, kind, id: "retry-artifact", resourceDefaultVersionId: "retry-version" });
      })],
    });
    const user = userEvent.setup();
    if (!dynamic) {
      await screen.findByLabelText("图片提示词");
      await user.hover(screen.getByRole("button", { name: "添加图片输入" }));
    }
    if (dynamic) await user.click(await screen.findByRole("button", { name: "选择参考视频" }));
    const input = await screen.findByLabelText(dynamic ? "上传参考视频" : "选择本地图片");
    await changeControl(input, { target: { files: [new File(["synthetic media"], dynamic ? "clip.mp4" : "reference.png", { type: dynamic ? "video/mp4" : "image/png" })] } });
    expect(await screen.findByRole("alert")).toHaveTextContent("产物暂未创建");
    await user.click(screen.getByRole("button", { name: dynamic ? "重试上传" : "重试失败图片" }));
    await waitFor(() => expect(saves.at(-1)?.mediaInputs).toEqual([{ versionId: "retry-version",
      role: dynamic ? "VIDEO_REFERENCE" : "REFERENCE", color: "#F15CAF" }]));
    expect(uploads).toBe(1);
    expect(writes).toHaveLength(2);
    expect(writes[0]?.key).toBeTruthy();
    expect(writes[1]).toEqual(writes[0]);
    if (dynamic) expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ clip: "retry-version" });
  });

  it("retains a workflow upload for retry when a task locks the editor before slot assignment", async () => {
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    let uploads = 0;
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === `/api/v1/projects/${PROJECT_ID}/assets`) {
        uploads++; return HttpResponse.json({ id: "locked-upload-asset", mediaKind: "IMAGE" }, { status: 201 });
      }
      return interceptedFetch(input, init);
    });
    const capability: MediaCapability = { ...imageCapability, id: "locked-upload", adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: {
      schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "image", label: "参考图", type: "IMAGE", nodeId: "1", fieldName: "image", required: true, advanced: false }],
      outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
    } } };
    let writes = 0;
    const { client, saves } = setup({ draft: { ...initialDraft, capabilityId: capability.id },
      settings: { connections: [{ ...settings.connections[0]!, capabilities: [capability] }], defaults: [{ kind: capability.kind, capabilityId: capability.id, version: 0 }] },
      handlers: [http.post(`/api/v1/projects/${PROJECT_ID}/artifacts`, async () => {
        writes++; if (writes === 1) await pending;
        return HttpResponse.json({ ...artifact, id: "locked-upload-artifact", resourceDefaultVersionId: "locked-upload-version" });
      })] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "选择参考图" }));
    await changeControl(screen.getByLabelText("上传参考图"), { target: { files: [new File(["synthetic"], "reference.png", { type: "image/png" })] } });
    await waitFor(() => expect(writes).toBe(1));
    act(() => client.setQueryData(["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID], [task("RUNNING")]));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "图片提示词" })).toHaveAttribute("aria-readonly", "true"));
    release?.();
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时只读");
    expect(saves).toHaveLength(0);
    act(() => client.setQueryData(["direct-media-tasks", PROJECT_ID, CANVAS_ITEM_ID], []));
    await waitFor(() => expect(screen.getByRole("button", { name: "重试上传" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "重试上传" }));
    await waitFor(() => expect(saves.at(-1)?.parameters.dynamicValues).toEqual({ image: "locked-upload-version" }));
    expect(uploads).toBe(1);
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
      expect(within(menu).getAllByRole("menuitemradio")).toHaveLength(1);
      expect(within(menu).getByText(/gpt-image-2/)).toBeVisible();
      expect(within(menu).queryByText(/真实生成验证/)).not.toBeInTheDocument();
      expect(within(menu).queryByText("镜头视频")).not.toBeInTheDocument();
      expect(within(menu).queryByText("停用模型")).not.toBeInTheDocument();
      expect(within(menu).queryByText("隐藏模型")).not.toBeInTheDocument();
      await user.keyboard("{ArrowDown}{Enter}");
      await waitFor(() => expect(saves).toHaveLength(1));
      expect(saves[0]).toEqual({ expectedVersion: 0, prompt: initialDraft.prompt, styleId: null,
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

  it.each(["READY", "UNKNOWN"] as const)("keeps Agent %s tasks occupied without direct task controls", async (status) => {
    const queueRequest = vi.fn();
    setup({ tasks: [{ ...task(status), runId: "agent-run" }], handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/tasks/task-direct/queue`, () => {
        queueRequest();
        return HttpResponse.json({}, { status: 400 });
      }),
    ] });
    await screen.findByLabelText("图片提示词");
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeDisabled());
    expect(screen.queryByRole("button", { name: "取消排队" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "重试" })).not.toBeInTheDocument();
    expect(queueRequest).not.toHaveBeenCalled();
    if (status === "UNKNOWN") expect(screen.getByText("结果未知")).toBeVisible();
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
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
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
    await waitFor(() => expect(saves.at(-1)?.videoInputMode).toBe("START_END"));
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
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it.each([
    { mode: "GENERAL_REFERENCE", label: /全能参考/ },
    { mode: "START_END", label: /首尾帧/ },
  ] as const)("selects and saves empty $mode video drafts before adding required images", async ({ mode, label }) => {
    const videoSettings: MediaSettings = {
      connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: versatileVideoCapability.id, version: 0 }],
    };
    const { client, saves } = setup({ kind: "VIDEO", settings: videoSettings,
      draft: { ...initialDraft, durationSeconds: 5 }, handlers: [
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
          id: "reference-image", title: "海边灯塔", resourceDefaultVersionId: "image-v1" }] })),
        http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () =>
          HttpResponse.json({ items: [{ id: "image-v1", versionNo: 1, content: { assetId: "asset-image-v1" } }] })),
      ] });
    const user = userEvent.setup();

    await waitFor(() => expect(saves.at(-1)).toMatchObject({
      videoInputMode: "TEXT", parameters: { aspectRatio: "AUTO" }, mediaInputs: [],
    }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent("文生视频");
    expect(screen.getByRole("button", { name: "运行" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "选择视频输入模式" }));
    expect(screen.getByRole("menuitemradio", { name: /文生视频/ })).toBeEnabled();
    const option = screen.getByRole("menuitemradio", { name: label });
    expect(option).not.toHaveAttribute("aria-disabled", "true");
    await user.click(option);
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: mode, mediaInputs: [] }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent(label);
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    fireEvent.input(screen.getByRole("textbox", { name: "视频提示词" }), { target: { textContent: "Updated video prompt" } });
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: mode, prompt: "Updated video prompt", mediaInputs: [] }));
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent(label);
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await act(async () => { await client.invalidateQueries({ queryKey: ["media-draft", PROJECT_ID, CANVAS_ITEM_ID] }); });
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent(label);
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    await user.click(await screen.findByRole("checkbox", { name: "选择 海边灯塔 · v1" }));
    await user.click(screen.getByRole("button", { name: "添加所选图片（1）" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: mode,
      mediaInputs: [expect.objectContaining({ versionId: "image-v1", role: mode === "START_END" ? "START_FRAME" : "REFERENCE" })] }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    expect(screen.getByRole("button", { name: "选择视频输入模式" })).toHaveTextContent(label);
  });

  it.each(["GENERAL_REFERENCE", "START_END"] as const)("keeps a persisted empty %s draft when choosing the first personal library reference", async (mode) => {
    const archived = vi.fn();
    setup({ kind: "VIDEO", settings: {
      connections: [{ ...settings.connections[0]!, capabilities: [versatileVideoCapability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: versatileVideoCapability.id, version: 0 }],
    }, draft: { ...initialDraft, videoInputMode: mode, durationSeconds: 5, parameters: { aspectRatio: "AUTO" } }, handlers: [
      http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "library-image", name: "资产图片", category: "OTHER", kind: "IMAGE",
        version: 2, source: {}, favorite: false, createdAt: NOW, hasThumbnail: false }], total: 1, categoryCounts: {} })),
      http.post(`/api/v1/projects/${PROJECT_ID}/canvas-items/${CANVAS_ITEM_ID}/library-references`, async ({ request }) => {
        archived(await request.json());
        return HttpResponse.json({ id: "library-command", status: "ARCHIVING" }, { status: 202 });
      }),
      http.get("/api/v1/library/commands/library-command", () => HttpResponse.json({ id: "library-command", status: "ARCHIVING" })),
    ] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "视频提示词" });
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: /从我的资产选择/ }));
    await user.click(await screen.findByRole("button", { name: "用作参考：资产图片" }));
    await waitFor(() => expect(archived).toHaveBeenCalledOnce());
    expect(archived).toHaveBeenCalledWith(expect.objectContaining({ entryId: "library-image",
      role: mode === "START_END" ? "START_FRAME" : "REFERENCE",
      draft: expect.objectContaining({ videoInputMode: mode, mediaInputs: [] }) }));
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
    await user.click(within(parameters).getByRole("radio", { name: "9:16" }));
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
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await userEvent.setup().click(screen.getByRole("button", { name: "运行" }));
    expect(await screen.findByText(/无法确认一个或多个已固定媒体版本/)).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "添加图片输入" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    expect(screen.getByRole("searchbox", { name: "搜索资源图片" })).toHaveValue("");
    expect(screen.queryByRole("checkbox", { name: /选择 新首帧 · v1/ })).not.toBeInTheDocument();
    expect(saves.every((saved) => saved.mediaInputs.every((input) => input.versionId === "image-gone"))).toBe(true);
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
      : HttpResponse.json(configured ? settings : { connections: [], defaults: [
        { kind: "IMAGE_GENERATION", capabilityId: null, version: 0 },
      ] }))] });
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

  it("applies bundled image templates atomically after a delayed import and retains the draft on CAS failure", async () => {
    let finishImport: (() => void) | undefined;
    const delayed = new Promise<void>((resolve) => { finishImport = resolve; });
    let imported = false; let conflict = true;
    const replacements: SaveMediaDraftRequest[] = []; const generate = vi.fn();
    const ownTemplate = { id: "template-image", targetKind: "IMAGE", scope: "PERSONAL", name: "Synthetic style", prompt: "New style",
      images: [{ id: "template-image-file", contentType: "image/png", byteSize: 100, width: 100, height: 100, thumbnailUrl: "/synthetic.png", contentUrl: "/synthetic.png" }],
      version: 1, createdAt: NOW, updatedAt: NOW };
    const original = { ...initialDraft, prompt: "Retain this until replacement", styleId: "selected-style", parameters: { aspectRatio: "9:16" as const, quality: "high" as const },
      mediaInputs: [{ versionId: "old-version", artifactId: "old-reference", role: "REFERENCE" as const, order: 0, color: "#F15CAF",
        sources: [{ id: "line-source", type: "CONNECTION" as const, connectionId: "line" }] }] };
    const { saves } = setup({ draft: original, handlers: [
      http.get("/api/v1/media-styles", () => HttpResponse.json([{ id: "selected-style", name: "Selected watercolor", category: "Painting",
        enabled: true, version: 1, thumbnailUrl: null, builtIn: false }])),
      http.get("/api/v1/media-templates", () => HttpResponse.json({ items: [ownTemplate] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [
        { ...artifact, id: "old-reference", title: "Old", resourceDefaultVersionId: "old-version" },
        ...(imported ? [{ ...artifact, id: "new-reference", title: "Imported", resourceDefaultVersionId: "new-version" }] : []),
      ] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/old-reference/versions`, () => HttpResponse.json({ items: [{ id: "old-version", versionNo: 1, content: { assetId: "old-asset" } }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/new-reference/versions`, () => HttpResponse.json({ items: [{ id: "new-version", versionNo: 1, content: { assetId: "new-asset" } }] })),
      http.post(`/api/v1/projects/${PROJECT_ID}/media-templates/template-image/import`, async () => { await delayed; imported = true; return HttpResponse.json({
        templateId: ownTemplate.id, templateVersion: 1, targetKind: "IMAGE", prompt: ownTemplate.prompt,
        images: [{ versionId: "new-version", assetId: "new-asset", title: "Imported", contentType: "image/png", byteSize: 100, width: 100, height: 100, thumbnailUrl: "/synthetic.png" }],
      }); }),
      http.post(`${DRAFT_URL}/replace-inputs`, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest; replacements.push(input);
        if (conflict) return HttpResponse.json({ title: "Synthetic CAS conflict", status: 409, code: "MEDIA_DRAFT_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ ...original, ...input, version: 1, mediaInputs: input.mediaInputs.map((entry, index) => ({ ...entry, artifactId: "new-reference", order: index,
          sources: [{ id: "manual", type: "MANUAL", connectionId: null }] })) });
      }),
      http.post(`${BASE}/run`, () => { generate(); return HttpResponse.json(task("READY")); }),
    ] });
    const user = userEvent.setup();
    await screen.findByRole("textbox", { name: "图片提示词" });
    await user.click(screen.getByRole("button", { name: "模板" }));
    await user.click(await screen.findByRole("button", { name: "Synthetic style" }));
    await user.click(screen.getByRole("button", { name: "使用模板" }));
    expect(screen.getByRole("button", { name: "关闭窗口" })).toBeDisabled();
    expect(replacements).toHaveLength(0); finishImport?.();
    await screen.findByText("Synthetic CAS conflict");
    expect(screen.getByRole("dialog", { name: "图片模板" })).toBeInTheDocument();
    expect(saves).toHaveLength(0); expect(generate).not.toHaveBeenCalled();
    conflict = false; await user.click(screen.getByRole("button", { name: "使用模板" }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "图片模板" })).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "图片提示词" })).toHaveTextContent("New style");
    expect(replacements.at(-1)).toMatchObject({ expectedVersion: 0, prompt: "New style", mentions: [], styleId: original.styleId,
      parameters: original.parameters, mediaInputs: [{ versionId: "new-version", role: "REFERENCE" }] });
    expect(screen.getByRole("button", { name: "选择风格" })).toHaveTextContent("Selected watercolor");
    expect(generate).not.toHaveBeenCalled();
  });
  it("adds an exact video reference, previews its thumbnail and permits video-only Seedance input", async () => {
    const capability = { ...versatileVideoCapability, maxReferenceVideos: 3, maxReferenceAudios: 3 };
    const { saves } = setup({ kind: "VIDEO", settings: {
      connections: [{ ...settings.connections[0]!, capabilities: [capability] }],
      defaults: [{ kind: "VIDEO_GENERATION", capabilityId: capability.id, version: 0 }],
    }, draft: { ...initialDraft, durationSeconds: 5, videoInputMode: "GENERAL_REFERENCE", parameters: { aspectRatio: "AUTO" } }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        kind: "VIDEO", id: "reference-video", title: "合成视频", resourceDefaultVersionId: "video-v1" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-video/versions`, () =>
        HttpResponse.json({ items: [{ id: "video-v1", versionNo: 1, content: { assetId: "asset-video-v1" } }] })),
    ] });
    const user = userEvent.setup();
    const input = await screen.findByLabelText("选择本地图片、视频或音频");
    expect(input).toHaveAttribute("accept", expect.stringContaining("video/mp4"));
    await user.click(screen.getByRole("button", { name: "添加参考素材" }));
    await user.click(screen.getByRole("menuitem", { name: "从资源库选择" }));
    await user.click(await screen.findByRole("checkbox", { name: "选择 合成视频 · v1" }));
    await user.click(screen.getByRole("button", { name: "添加所选素材（1）" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ videoInputMode: "GENERAL_REFERENCE",
      mediaInputs: [expect.objectContaining({ versionId: "video-v1", role: "VIDEO_REFERENCE" })] }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    const chip = screen.getByRole("listitem", { name: /合成视频 · v1，序号 1/ });
    expect(chip.querySelector("img")?.getAttribute("src")).toContain("asset-video-v1/thumbnail");
    expect(screen.getByText(/本地视频参考需要管理员配置/)).toBeVisible();
  });

});
