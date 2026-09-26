import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type RequestHandler } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { Artifact, MediaCapability, MediaDraft, MediaSettings, SaveMediaDraftRequest, Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaDraftEditor } from "./MediaDraftEditor";

const NOW = "2026-09-26T00:00:00Z";
const PROJECT_ID = "project-media-editor";
const ARTIFACT_ID = "artifact-media-editor";
const BASE = `/api/v1/projects/${PROJECT_ID}/artifacts/${ARTIFACT_ID}`;
const artifact: Artifact = {
  id: ARTIFACT_ID, projectId: PROJECT_ID, kind: "IMAGE", title: "新图片",
  currentVersionId: null, currentVersion: null, version: 0, createdAt: NOW, updatedAt: NOW,
};
const initialDraft: MediaDraft = {
  projectId: PROJECT_ID, artifactId: ARTIFACT_ID, prompt: "A lighthouse at dawn",
  inputImageVersionId: null, durationSeconds: null, capabilityId: null,
  displayMode: "DRAFT", version: 0, createdAt: NOW, updatedAt: NOW,
};
const imageCapability: MediaCapability = {
  id: "image-capability", name: "细节生图", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION", minimumSeconds: 0,
  maximumSeconds: 0, maxConcurrent: 2, mappingSha256: "a".repeat(64), settings: { quality: "high" },
};
const videoCapability: MediaCapability = {
  ...imageCapability, id: "video-capability", name: "镜头视频", adapterId: "ARK_SEEDANCE_2_I2V",
  kind: "VIDEO_GENERATION", minimumSeconds: 2, maximumSeconds: 10, settings: {},
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
  return { id: "task-direct", projectId: PROJECT_ID, runId: null, planId: null,
    stepKey: "direct", kind: "IMAGE_GENERATION", status, cancelRequested: false,
    input: {}, providerRequestId: null, attemptNo: 1, nextActionAt: NOW, version: 0,
    createdAt: NOW, updatedAt: NOW };
}

function setup(options: { draft?: MediaDraft; tasks?: Task[]; settings?: MediaSettings; kind?: "IMAGE" | "VIDEO"; handlers?: RequestHandler[] } = {}) {
  let draft = options.draft ?? { ...initialDraft };
  let tasks = options.tasks ?? [];
  const saves: SaveMediaDraftRequest[] = [];
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "token" })),
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(options.settings ?? settings)),
    http.get(`${BASE}/draft`, () => HttpResponse.json(draft)),
    http.put(`${BASE}/draft`, async ({ request }) => {
      const input = await request.json() as SaveMediaDraftRequest;
      saves.push(input);
      draft = { ...draft, ...input, version: draft.version + 1 };
      return HttpResponse.json(draft);
    }),
    http.get(`${BASE}/run`, () => HttpResponse.json(tasks)),
    http.get(`/api/v1/projects/${PROJECT_ID}/tasks/task-direct/queue`, () =>
      HttpResponse.json({ waitingAhead: 2, reason: "PROJECT_CAPACITY" })),
    http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [] })),
  );
  if (options.handlers) server.use(...options.handlers);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}><MediaDraftEditor artifact={{ ...artifact, kind: options.kind ?? "IMAGE" }} /></QueryClientProvider>);
  return { client, saves, setTasks: (next: Task[]) => { tasks = next; } };
}

describe("MediaDraftEditor", () => {
  it("shows read-only size and configured quality without inventing draft parameters", async () => {
    const { saves } = setup();
    const user = userEvent.setup();
    await screen.findByLabelText("图片提示词");
    expect(screen.getByRole("button", { name: "添加参考图（暂不支持）" })).toBeDisabled();
    expect(screen.getByText("文生图 · 暂不支持参考图")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    const parameters = screen.getByRole("dialog", { name: "尺寸与画质设置" });
    expect(within(parameters).getByText("由模型决定")).toBeVisible();
    expect(within(parameters).getByText("高")).toBeVisible();
    expect(within(parameters).queryByRole("combobox")).not.toBeInTheDocument();
    expect(within(parameters).queryByRole("spinbutton")).not.toBeInTheDocument();
    expect(saves).toHaveLength(0);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "尺寸与画质" })).toHaveFocus();
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
      expect(within(menu).getByText("尚未完成真实生成验证")).toBeVisible();
      expect(within(menu).queryByText("镜头视频")).not.toBeInTheDocument();
      expect(within(menu).queryByText("停用模型")).not.toBeInTheDocument();
      expect(within(menu).queryByText("隐藏模型")).not.toBeInTheDocument();
      await user.keyboard("{ArrowDown}{Enter}");
      await waitFor(() => expect(saves).toHaveLength(1));
      expect(saves[0]).toEqual({ expectedVersion: 0, prompt: initialDraft.prompt,
        inputImageVersionId: null, durationSeconds: null, capabilityId: imageCapability.id });
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
      http.get(`${BASE}/draft`, () => HttpResponse.json({ ...initialDraft, prompt: "Remote prompt", version: remoteVersion })),
      http.put(`${BASE}/draft`, async ({ request }) => {
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
    expect(prompt).toHaveValue("Keep this local prompt");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重新读取版本" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]).toMatchObject({ expectedVersion: 4, prompt: "Keep this local prompt" });
    expect(await screen.findByText("已保存")).toBeVisible();
    expect(prompt).toHaveValue("Keep this local prompt");
  });

  it("runs the saved default draft and invalidates the actual snapshot, canvas and draft caches", async () => {
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
    await waitFor(() => expect(submitted).toEqual({ expectedDraftVersion: 0 }));
    await waitFor(() => expect(invalidation).toHaveBeenCalledWith({ queryKey: ["media-draft", PROJECT_ID, ARTIFACT_ID] }));
    expect(invalidation).toHaveBeenCalledWith({ queryKey: ["snapshot", PROJECT_ID] });
    expect(invalidation).toHaveBeenCalledWith({ queryKey: ["canvas", PROJECT_ID] });
    expect(await screen.findByText(/前方 2 项/)).toHaveTextContent("项目并发已满");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("keeps UNKNOWN blocked and offers the original-request investigation controls", async () => {
    setup({ tasks: [task("UNKNOWN")] });
    await screen.findByLabelText("图片提示词");
    expect(await screen.findByText("结果待核实")).toBeVisible();
    expect(screen.getByText("请先核对原请求，避免重复生成。")).toBeVisible();
    expect(screen.getByRole("button", { name: "查看提交账本并处理重试" })).toBeVisible();
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
    expect(await screen.findByText("已取消")).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("pins a video input version and saves its whole-second duration", async () => {
    const { saves } = setup({ kind: "VIDEO", handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "海边灯塔", currentVersionId: "image-v2" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: [
        { id: "image-v1", versionNo: 1, content: { assetId: "asset-old-frame" } },
        { id: "image-v2", versionNo: 2, content: { assetId: "asset-new-frame" } },
        { id: "image-empty", versionNo: 3, content: { prompt: "not generated yet" } },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "选择输入图片版本" }));
    await screen.findByRole("option", { name: "海边灯塔 · v1" });
    expect(screen.queryByRole("option", { name: /v3/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "使用 海边灯塔 · v1" })).toBeVisible();
    await user.selectOptions(screen.getByRole("combobox", { name: "输入图片版本" }), "image-v1");
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    expect(screen.getByRole("spinbutton", { name: "时长（秒）" })).toHaveAttribute("min", "2");
    expect(screen.getByRole("spinbutton", { name: "时长（秒）" })).toHaveAttribute("max", "10");
    await user.type(screen.getByRole("spinbutton", { name: "时长（秒）" }), "4");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ inputImageVersionId: "image-v1", durationSeconds: 4 }));
    expect(screen.getByText("海边灯塔 · v1")).toBeVisible();
    expect(screen.getByRole("img", { name: "海边灯塔 · v1 首帧缩略图" })).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-old-frame/thumbnail`);
    expect(screen.queryByRole("img", { name: /v2/ })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "替换视频首帧" }));
    await user.click(screen.getByRole("button", { name: "使用 海边灯塔 · v2" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ inputImageVersionId: "image-v2", durationSeconds: 4 }));
    expect(screen.getByRole("img", { name: "海边灯塔 · v2 首帧缩略图" })).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-new-frame/thumbnail`);
    await user.click(screen.getByRole("button", { name: "清除视频首帧" }));
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ inputImageVersionId: null, durationSeconds: 4 }));
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
  });

  it("retains an unavailable pinned video frame without silently using the current version", async () => {
    const { saves } = setup({ kind: "VIDEO", draft: { ...initialDraft, inputImageVersionId: "image-gone", durationSeconds: 4 }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "新首帧", currentVersionId: "image-v2" }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-image/versions`, () => HttpResponse.json({ items: [
        { id: "image-gone", versionNo: 1, content: {} },
        { id: "image-v2", versionNo: 2, content: { assetId: "asset-current-frame" } },
      ] })),
    ] });
    const user = userEvent.setup();
    expect(await screen.findByText(/无法确认已固定的首帧版本/)).toBeVisible();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "选择输入图片版本" }));
    expect(screen.getByRole("combobox", { name: "输入图片版本" })).toHaveValue("image-gone");
    expect(screen.queryByRole("button", { name: /使用 新首帧 · v1/ })).not.toBeInTheDocument();
    expect(saves).toHaveLength(0);
  });

  it("retries video image-history failures and restores the exact old thumbnail", async () => {
    let attempts = 0;
    setup({ kind: "VIDEO", draft: { ...initialDraft, inputImageVersionId: "image-v1", durationSeconds: 4 }, handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-image", title: "海边灯塔", currentVersionId: "image-v2" }] })),
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
    await user.click(screen.getByRole("button", { name: "选择输入图片版本" }));
    expect(await screen.findByText("无法读取图片版本。")).toBeVisible();
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "重试读取图片" }));
    expect(await screen.findByRole("option", { name: "海边灯塔 · v1" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("img", { name: "海边灯塔 · v1 首帧缩略图" })).toHaveAttribute("src",
      `/api/v1/projects/${PROJECT_ID}/assets/asset-old-frame/thumbnail`);
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
  });

  it("shows the empty video-reference state without offering image drafts as usable frames", async () => {
    setup({ kind: "VIDEO", handlers: [
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts`, () => HttpResponse.json({ items: [{ ...artifact,
        id: "reference-draft", title: "尚未生成", currentVersionId: null }] })),
      http.get(`/api/v1/projects/${PROJECT_ID}/artifacts/reference-draft/versions`, () => HttpResponse.json({ items: [
        { id: "draft-v1", versionNo: 1, content: { assetId: "" } },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    await user.click(screen.getByRole("button", { name: "选择输入图片版本" }));
    expect(await screen.findByText("暂无已生成或上传的图片，请先添加图片。")).toBeVisible();
    expect(screen.getAllByRole("option")).toHaveLength(1);
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
      http.get(`${BASE}/draft`, () => HttpResponse.json({ ...initialDraft, version: remoteVersion })),
      http.post(`${BASE}/run`, () => {
        remoteVersion = 4;
        return HttpResponse.json(task("SUCCEEDED"));
      }),
      http.put(`${BASE}/draft`, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        saved.push(input);
        return HttpResponse.json({ ...initialDraft, ...input, version: input.expectedVersion + 1 });
      }),
    ] });
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
    await user.type(screen.getByLabelText("图片提示词"), " in the rain");
    await waitFor(() => expect(saved).toHaveLength(1));
    expect(saved[0]).toMatchObject({ expectedVersion: 4, prompt: `${initialDraft.prompt} in the rain` });
  });

  it("ignores a stale background response that arrives after a newer save was acknowledged", async () => {
    let reads = 0;
    let releaseStale: (() => void) | undefined;
    const saved: SaveMediaDraftRequest[] = [];
    const { client } = setup({ handlers: [
      http.get(`${BASE}/draft`, async () => {
        reads += 1;
        if (reads === 1) return HttpResponse.json(initialDraft);
        await new Promise<void>((resolve) => { releaseStale = resolve; });
        return HttpResponse.json(initialDraft);
      }),
      http.put(`${BASE}/draft`, async ({ request }) => {
        const input = await request.json() as SaveMediaDraftRequest;
        saved.push(input);
        return HttpResponse.json({ ...initialDraft, ...input, version: input.expectedVersion + 1 });
      }),
    ] });
    const user = userEvent.setup();
    const prompt = await screen.findByLabelText("图片提示词");
    const slowRefresh = client.invalidateQueries({ queryKey: ["media-draft", PROJECT_ID, ARTIFACT_ID] });
    await waitFor(() => expect(releaseStale).toBeDefined());
    await user.clear(prompt);
    await user.type(prompt, "Newer saved prompt");
    await waitFor(() => expect(saved).toHaveLength(1));
    await screen.findByText("已保存");
    await act(async () => { releaseStale?.(); await slowRefresh; });
    expect(prompt).toHaveValue("Newer saved prompt");
    await user.type(prompt, " and next edit");
    await waitFor(() => expect(saved).toHaveLength(2));
    expect(saved[1]).toMatchObject({ expectedVersion: 1, prompt: "Newer saved prompt and next edit" });
  });

  it("retries a lost run response with its original key and payload after the server draft advances", async () => {
    const keys: (string | null)[] = [];
    const payloads: unknown[] = [];
    let remoteVersion = 0;
    const { client } = setup({ handlers: [
      http.get(`${BASE}/draft`, () => HttpResponse.json({ ...initialDraft, version: remoteVersion })),
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
    await act(async () => { await client.invalidateQueries({ queryKey: ["media-draft", PROJECT_ID, ARTIFACT_ID] }); });
    expect(client.getQueryData<MediaDraft>(["media-draft", PROJECT_ID, ARTIFACT_ID])?.version).toBe(1);
    await user.click(screen.getByRole("button", { name: "运行" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
    expect(payloads).toEqual([{ expectedDraftVersion: 0 }, { expectedDraftVersion: 0 }]);
  });

  it("retries a failed initial draft read", async () => {
    let reads = 0;
    setup({ handlers: [http.get(`${BASE}/draft`, () => {
      reads += 1;
      return reads === 1 ? HttpResponse.json({ code: "TEMPORARY", detail: "暂时无法读取", retryable: true }, { status: 503 })
        : HttpResponse.json(initialDraft);
    })] });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重试读取草稿" }));
    expect(await screen.findByLabelText("图片提示词")).toHaveValue(initialDraft.prompt);
  });
});
