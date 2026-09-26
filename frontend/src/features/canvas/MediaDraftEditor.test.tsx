import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
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
    const { client, setTasks } = setup({ settings: { connections: [], defaults: [] } });
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
        { id: "image-v1", versionNo: 1 }, { id: "image-v2", versionNo: 2 },
      ] })),
    ] });
    const user = userEvent.setup();
    await screen.findByLabelText("视频提示词");
    expect(screen.getByRole("button", { name: "运行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "选择输入图片版本" }));
    await screen.findByRole("option", { name: "海边灯塔 · v1" });
    await user.selectOptions(screen.getByRole("combobox", { name: "输入图片版本" }), "image-v1");
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "尺寸与画质" }));
    await user.type(screen.getByRole("spinbutton", { name: "时长（秒）" }), "4");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(saves.at(-1)).toMatchObject({ inputImageVersionId: "image-v1", durationSeconds: 4 }));
    expect(screen.getByText("海边灯塔 · v1")).toBeVisible();
    await waitFor(() => expect(screen.getByRole("button", { name: "运行" })).toBeEnabled());
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
