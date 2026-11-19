import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, expect, it, vi } from "vitest";
import { MemoryRouter, Routes, Route } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { ImageStudioPage } from "./ImageStudioPage";
import { useImageStudioDraftStore } from "./imageStudioDraft";

const capability = {
  capabilityId: "image-capability",
  capabilityVersion: 3,
  name: "静态图片模型",
  connectionName: "合成连接",
  adapterId: "OPENAI_GPT_IMAGE_2",
  defaultParameters: { aspectRatio: "1:1", resolution: "1K", quality: "medium", generationCount: 1 },
  aspectRatios: ["1:1", "16:9"],
  resolutions: ["1K", "2K"],
  qualities: ["low", "medium", "high"],
  maxReferenceImages: 4,
  supportsTransparentBackground: true,
  complexity: null,
};
const request = {
  capabilityId: "image-capability",
  expectedCapabilityVersion: 3,
  prompt: "清晨的海边小屋",
  parameters: { aspectRatio: "16:9", resolution: "2K", quality: "high", generationCount: 2 },
  referenceVersionIds: ["reference-version"],
};
const result = {
  artifactId: "result-artifact",
  artifactVersionId: "result-version",
  assetId: "result-asset",
  width: 1024,
  height: 1024,
  contentType: "image/png",
};
const history = {
  id: "historic-batch",
  projectId: "studio-scope",
  capabilityId: "image-capability",
  capabilityVersion: 3,
  prompt: request.prompt,
  parameters: request.parameters,
  referenceVersionIds: request.referenceVersionIds,
  references: [
    {
      artifactId: "reference-artifact",
      versionId: "reference-version",
      assetId: "reference-asset",
      projectId: "studio-scope",
      width: 512,
      height: 512,
    },
  ],
  createdAt: "2026-11-19T08:00:00Z",
  tasks: [
    { id: "task-success", status: "SUCCEEDED", version: 5, errorCode: null, results: [result] },
    { id: "task-failed", status: "FAILED", version: 4, errorCode: "PROVIDER_REJECTED", results: [] },
  ],
};

beforeEach(() => {
  useImageStudioDraftStore.getState().clear();
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "studio-owner", loginName: "creator", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/image-studio/context", () =>
      HttpResponse.json({
        projectId: "studio-scope",
        defaultCapabilityId: capability.capabilityId,
        capabilities: [capability],
      }),
    ),
    http.get("/api/v1/image-studio/batches", () => HttpResponse.json({ items: [], nextCursor: null })),
  );
});
function show(path = "/image-studio") {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/image-studio" element={<ImageStudioPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

it("opens from main navigation without reading or choosing a canvas", async () => {
  show();
  expect(await screen.findByRole("heading", { name: "生图工作台" })).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "生图工作台" })).toHaveAttribute("aria-current", "page");
  expect(await screen.findByRole("textbox", { name: "提示词" })).toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: "模型" })).toHaveTextContent(capability.name);
  expect(screen.queryByText("复杂度")).not.toBeInTheDocument();
  expect(screen.queryByRole("combobox", { name: "画布" })).not.toBeInTheDocument();
});

it("uploads an exact reference and submits the whole batch without any canvas identity", async () => {
  const accepted: { key: string | null; body: unknown }[] = [];
  server.use(
    http.post("/api/v1/image-studio/batches", async ({ request: submission }) => {
      accepted.push({ key: submission.headers.get("Idempotency-Key"), body: await submission.json() });
      return HttpResponse.json({
        ...history,
        id: "new-batch",
        prompt: "新的提示词",
        parameters: { ...capability.defaultParameters, generationCount: 4 },
      });
    }),
    http.get("/api/v1/image-studio/batches/new-batch", () => HttpResponse.json({ ...history, id: "new-batch" })),
  );
  // As in existing upload tests, inspect browser FormData before Node serializes jsdom File.
  const interceptedFetch = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === "/api/v1/image-studio/references") {
      expect(new Headers(init?.headers).get("X-XSRF-TOKEN")).toBe("synthetic");
      expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
      expect((init?.body as FormData).get("file")).toHaveProperty("name", "reference.png");
      return HttpResponse.json({
        artifactId: "reference-artifact",
        versionId: "reference-version",
        assetId: "reference-asset",
        projectId: "studio-scope",
        width: 512,
        height: 512,
      });
    }
    return interceptedFetch(input, init);
  });
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: "提示词" }), "新的提示词");
  await waitFor(() => expect(screen.getByLabelText("上传参考图")).not.toBeDisabled());
  await user.upload(
    screen.getByLabelText("上传参考图"),
    new File(["synthetic"], "reference.png", { type: "image/png" }),
  );
  await screen.findByRole("button", { name: "移除参考图 1" });
  await user.click(screen.getByRole("combobox", { name: "数量" }));
  await user.click(screen.getByRole("option", { name: "4 张" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled());
  await user.click(screen.getByRole("button", { name: "生成图片" }));
  await screen.findByRole("link", { name: "下载图片 1" });
  expect(accepted).toHaveLength(1);
  expect(accepted[0]?.key).toMatch(/^[a-f0-9]{32}$/);
  expect(accepted[0]?.body).toEqual({
    capabilityId: capability.capabilityId,
    expectedCapabilityVersion: 3,
    prompt: "新的提示词",
    parameters: { ...capability.defaultParameters, generationCount: 4 },
    referenceVersionIds: ["reference-version"],
  });
  expect(screen.getByRole("link", { name: "下载图片 1" })).toHaveAttribute(
    "href",
    "/api/v1/projects/studio-scope/assets/result-asset/content",
  );
  expect(screen.getByText("PROVIDER_REJECTED")).toBeInTheDocument();
});

it("retries uncertain acceptance with its frozen request and idempotency key", async () => {
  const accepted: { key: string | null; body: unknown }[] = [];
  server.use(
    http.post("/api/v1/image-studio/batches", async ({ request: submission }) => {
      accepted.push({ key: submission.headers.get("Idempotency-Key"), body: await submission.json() });
      return accepted.length === 1
        ? HttpResponse.json({ detail: "合成连接中断", code: "UNAVAILABLE", retryable: true }, { status: 503 })
        : HttpResponse.json(history);
    }),
  );
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: "提示词" }), "保留这份受理命令");
  await waitFor(() => expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled());
  await user.click(screen.getByRole("button", { name: "生成图片" }));
  await user.click(await screen.findByRole("button", { name: "重试受理" }));
  await screen.findByRole("link", { name: "下载图片 1" });
  expect(accepted).toHaveLength(2);
  expect(accepted[1]).toEqual(accepted[0]);
});

it("restores historical exact parameters and preserves partial failures after reload", async () => {
  server.use(http.get("/api/v1/image-studio/batches", () => HttpResponse.json({ items: [history], nextCursor: null })));
  show();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: /清晨的海边小屋/ }));
  expect(await screen.findByText("PROVIDER_REJECTED")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "回填参数" }));
  expect(screen.getByRole("textbox", { name: "提示词" })).toHaveValue(request.prompt);
  expect(screen.getByRole("combobox", { name: "比例" })).toHaveTextContent("16:9");
  expect(screen.getByRole("combobox", { name: "分辨率" })).toHaveTextContent("2K");
  expect(screen.getByRole("combobox", { name: "质量" })).toHaveTextContent("高");
  expect(screen.getByRole("combobox", { name: "数量" })).toHaveTextContent("2 张");
  expect(screen.getByRole("button", { name: "移除参考图 1" })).toBeInTheDocument();
  expect(screen.getByRole("img", { name: "参考图 1" })).toHaveAttribute(
    "src",
    "/api/v1/projects/studio-scope/assets/reference-asset/thumbnail",
  );
});

it("shows UNKNOWN without automatically creating another attempt", async () => {
  let writes = 0;
  server.use(
    http.get("/api/v1/image-studio/batches", () =>
      HttpResponse.json({
        items: [
          { ...history, tasks: [{ id: "unknown-task", status: "UNKNOWN", version: 7, errorCode: null, results: [] }] },
        ],
        nextCursor: null,
      }),
    ),
    http.post("/api/v1/image-studio/batches", () => {
      writes++;
      return HttpResponse.json(history);
    }),
  );
  show();
  await userEvent.setup().click(await screen.findByRole("button", { name: /清晨的海边小屋/ }));
  expect(await screen.findByText(/供应商是否受理尚不确定/)).toBeInTheDocument();
  expect(writes).toBe(0);
});

it("keeps the prompt when capabilities cannot load and offers a read retry", async () => {
  show();
  const prompt = await screen.findByRole("textbox", { name: "提示词" });
  fireEvent.change(prompt, { target: { value: "未保存的草稿" } });
  server.use(
    http.get("/api/v1/image-studio/context", () => HttpResponse.json({ detail: "合成读取错误" }, { status: 503 })),
  );
  await userEvent.setup().click(screen.getByRole("button", { name: "刷新工作台" }));
  expect(await screen.findByRole("button", { name: "重试读取模型" })).toBeInTheDocument();
  expect(prompt).toHaveValue("未保存的草稿");
});

it("provides a model configuration destination when no verified image capability is available", async () => {
  server.use(
    http.get("/api/v1/image-studio/context", () =>
      HttpResponse.json({ projectId: "studio-scope", defaultCapabilityId: null, capabilities: [] }),
    ),
  );
  show();
  const notice = await screen.findByText("还没有可用的生图模型");
  expect(
    within(notice.closest('[role="alert"]') ?? document.body).getByRole("link", { name: "配置媒体模型" }),
  ).toHaveAttribute("href", "/settings/media");
  expect(screen.getByRole("button", { name: "生成图片" })).toBeDisabled();
});

it("unlocks the preserved draft after a definitive validation rejection", async () => {
  server.use(
    http.post("/api/v1/image-studio/batches", () =>
      HttpResponse.json(
        { detail: "当前参考图不受支持", code: "STUDIO_INVALID_REFERENCE", retryable: false },
        { status: 422, headers: { "Content-Type": "application/problem+json" } },
      ),
    ),
  );
  show();
  const user = userEvent.setup();
  const prompt = await screen.findByRole("textbox", { name: "提示词" });
  await user.type(prompt, "待修正的草稿");
  await waitFor(() => expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled());
  await user.click(screen.getByRole("button", { name: "生成图片" }));
  await screen.findByText("当前参考图不受支持");
  expect(prompt).not.toBeDisabled();
  expect(prompt).toHaveValue("待修正的草稿");
  expect(screen.queryByRole("button", { name: "重试受理" })).not.toBeInTheDocument();
});

it("requires explicit confirmation when historical capability settings changed", async () => {
  server.use(
    http.get("/api/v1/image-studio/batches", () =>
      HttpResponse.json({ items: [{ ...history, capabilityVersion: 1 }], nextCursor: null }),
    ),
  );
  show();
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "回填参数" }));
  expect(screen.getByRole("textbox", { name: "提示词" })).toHaveValue(history.prompt);
  expect(screen.getByRole("button", { name: "生成图片" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "使用当前模型配置" }));
  expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled();
});

it("keeps a capability's compatible defaults when quality has no user-selectable dimension", async () => {
  server.use(
    http.get("/api/v1/image-studio/context", () =>
      HttpResponse.json({
        projectId: "studio-scope",
        defaultCapabilityId: capability.capabilityId,
        capabilities: [{ ...capability, adapterId: "GOOGLE_NANO_BANANA_2", qualities: [] }],
      }),
    ),
  );
  show();
  const user = userEvent.setup();
  await user.type(await screen.findByRole("textbox", { name: "提示词" }), "没有质量选项的图片模型");
  await waitFor(() => expect(screen.getByRole("combobox", { name: "模型" })).toHaveTextContent(capability.name));
  expect(screen.queryByRole("combobox", { name: "质量" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled();
});

it.each([
  ["image/jpeg", "jpg"],
  ["image/webp", "webp"],
  ["image/png", "png"],
  ["image/avif", ""],
])("uses the archived %s type for download filenames", async (contentType, extension) => {
  server.use(
    http.get("/api/v1/image-studio/batches", () =>
      HttpResponse.json({
        items: [{ ...history, tasks: [{ ...history.tasks[0], results: [{ ...result, contentType }] }] }],
        nextCursor: null,
      }),
    ),
  );
  show();
  const download = await screen.findByRole("link", { name: "下载图片 1" });
  expect(download).toHaveAttribute("download", extension ? `image-1.${extension}` : "image-1");
});

it("keeps Chinese composition confirmation and a newline without submitting until Generate is clicked", async () => {
  const submissions: unknown[] = [];
  server.use(
    http.post("/api/v1/image-studio/batches", async ({ request }) => {
      submissions.push(await request.json());
      return HttpResponse.json({ ...history, id: "ime-batch" });
    }),
    http.get("/api/v1/image-studio/batches/ime-batch", () => HttpResponse.json({ ...history, id: "ime-batch" })),
  );
  show();
  const user = userEvent.setup();
  const prompt = await screen.findByRole<HTMLTextAreaElement>("textbox", { name: "提示词" });
  await user.click(prompt);
  // jsdom cannot run a system IME. These composition/input events model candidate confirmation.
  fireEvent.compositionStart(prompt);
  fireEvent.input(prompt, {
    target: { value: "晨光下的海边小屋" },
    data: "晨光下的海边小屋",
    inputType: "insertCompositionText",
    isComposing: true,
  });
  fireEvent.keyDown(prompt, { key: "Enter", code: "Enter", keyCode: 229, isComposing: true });
  fireEvent.keyUp(prompt, { key: "Enter", code: "Enter", keyCode: 229, isComposing: true });
  fireEvent.compositionEnd(prompt, { data: "晨光下的海边小屋" });
  fireEvent.input(prompt, {
    target: { value: "晨光下的海边小屋" },
    data: "晨光下的海边小屋",
    inputType: "insertFromComposition",
    isComposing: false,
  });
  expect(prompt).toHaveValue("晨光下的海边小屋");
  expect(submissions).toHaveLength(0);
  prompt.setSelectionRange(prompt.value.length, prompt.value.length);
  await user.keyboard("{Enter}柔和自然光");
  expect(prompt).toHaveValue("晨光下的海边小屋\n柔和自然光");
  expect(submissions).toHaveLength(0);
  await waitFor(() => expect(screen.getByRole("button", { name: "生成图片" })).toBeEnabled());
  await user.click(screen.getByRole("button", { name: "生成图片" }));
  await waitFor(() => expect(submissions).toHaveLength(1));
  expect(submissions[0]).toMatchObject({ prompt: "晨光下的海边小屋\n柔和自然光" });
});

it.each([
  ["WAITING_PROVIDER", "STUDIO_IMAGE_ARCHIVE_RETRY", "图片已生成，正在归档"],
  ["BLOCKED", "STUDIO_IMAGE_ARCHIVE_MISSING", "结果归档不完整"],
])("explains the %s archive state without suggesting another generation", async (status, errorCode, title) => {
  server.use(
    http.get("/api/v1/image-studio/batches", () =>
      HttpResponse.json({
        items: [{ ...history, tasks: [{ id: "archive-task", status, version: 8, errorCode, results: [] }] }],
        nextCursor: null,
      }),
    ),
  );
  show();
  expect(await screen.findByText(title)).toBeInTheDocument();
  expect(screen.getByText(errorCode)).toBeInTheDocument();
});

it("browses thumbnail history independently of the prompt draft and restores only on request", async () => {
  const unknown = {
    ...history,
    id: "other",
    prompt: "第二批",
    tasks: [{ id: "unresolved", status: "UNKNOWN", version: 1, results: [], errorCode: null }],
  };
  server.use(
    http.get("/api/v1/image-studio/batches", () => HttpResponse.json({ items: [history, unknown], nextCursor: null })),
  );
  show();
  const user = userEvent.setup();
  const prompt = await screen.findByRole("textbox", { name: "提示词" });
  await user.type(prompt, "尚未提交的草稿");
  const rail = await screen.findByRole("complementary", { name: "历史记录" });
  const first = within(rail).getByRole("button", { name: /清晨的海边小屋/ });
  expect(first.querySelector("img")).toHaveAttribute(
    "src",
    "/api/v1/projects/studio-scope/assets/result-asset/thumbnail",
  );
  expect(first).toHaveTextContent("部分完成");
  const second = within(rail).getByRole("button", { name: /第二批/ });
  expect(second).toHaveTextContent("受理状态未知");
  await user.click(second);
  expect(await screen.findByText(/供应商是否受理尚不确定/)).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "收起历史记录" }));
  expect(screen.queryByRole("complementary", { name: "历史记录" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "展开历史记录" }));
  expect(screen.getByRole("button", { name: /第二批/ })).toHaveAttribute("aria-pressed", "true");
  expect(prompt).toHaveValue("尚未提交的草稿");
  await user.click(screen.getByRole("button", { name: "回填参数" }));
  expect(prompt).toHaveValue("第二批");
});

it("changes capability-specific advanced parameters on demand and retains the draft when dismissed", async () => {
  show();
  const user = userEvent.setup();
  expect(screen.queryByRole("checkbox", { name: "透明背景" })).not.toBeInTheDocument();
  await user.click(await screen.findByRole("button", { name: "高级设置" }));
  const checkbox = screen.getByRole("checkbox", { name: "透明背景" });
  await user.click(checkbox);
  await user.keyboard("{Escape}");
  expect(screen.queryByRole("checkbox", { name: "透明背景" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "高级设置" }));
  expect(screen.getByRole("checkbox", { name: "透明背景" })).toBeChecked();
});

it("closes the narrow history drawer after selection and returns focus without replacing the draft", async () => {
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
  try {
    server.use(
      http.get("/api/v1/image-studio/batches", () => HttpResponse.json({ items: [history], nextCursor: null })),
    );
    show();
    const user = userEvent.setup();
    const prompt = await screen.findByRole("textbox", { name: "提示词" });
    await user.type(prompt, "手机草稿");
    const toggle = screen.getByRole("button", { name: "展开历史记录" });
    await user.click(toggle);
    const drawer = screen.getByRole("dialog", { name: "历史记录" });
    await user.click(within(drawer).getByRole("button", { name: /清晨的海边小屋/ }));
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "历史记录" })).not.toBeInTheDocument());
    expect(prompt).toHaveValue("手机草稿");
    expect(toggle).toHaveFocus();
  } finally {
    vi.unstubAllGlobals();
  }
});

it.each(["/image-studio/", "/IMAGE-STUDIO"])(
  "opens the same immersive workspace when directly entering %s",
  async path => {
    show(path);
    await screen.findByRole("heading", { name: "生图工作台" });
    expect(screen.getByRole("main").closest(".app-shell")).toHaveClass("app-shell--image-studio");
    expect(screen.getByRole("link", { name: "生图工作台" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { name: "生图工作台" })).toHaveClass("sr-only");
  },
);

it.each([
  ["batches", "无法读取历史记录", "重试读取"],
  ["context", "无法读取生图模型", "重试读取模型"],
])("reports loading and retries a failed %s read inside the mobile drawer", async (endpoint, title, retry) => {
  vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener() {}, removeEventListener() {} }));
  let finishFirstRead!: () => void;
  const firstRead = new Promise<void>(resolve => {
    finishFirstRead = resolve;
  });
  let reads = 0;
  server.use(
    http.get(`/api/v1/image-studio/${endpoint}`, async () => {
      reads++;
      if (reads === 1) {
        await firstRead;
        return HttpResponse.json(
          { detail: "暂时无法读取批次" },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        );
      }
      return endpoint === "batches"
        ? HttpResponse.json({ items: [history], nextCursor: null })
        : HttpResponse.json({
            projectId: "studio-scope",
            defaultCapabilityId: capability.capabilityId,
            capabilities: [capability],
          });
    }),
    http.get("/api/v1/image-studio/batches", () => HttpResponse.json({ items: [history], nextCursor: null })),
  );
  try {
    show();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "展开历史记录" }));
    const drawer = within(screen.getByRole("dialog", { name: "历史记录" }));
    expect(await drawer.findByText("读取生成记录…")).toBeInTheDocument();
    finishFirstRead();
    expect(await drawer.findByRole("alert")).toHaveTextContent(title);
    expect(drawer.getByText("暂时无法读取批次")).toBeInTheDocument();
    await user.click(drawer.getByRole("button", { name: retry }));
    expect(await drawer.findByRole("button", { name: /清晨的海边小屋/ })).toBeInTheDocument();
    expect(reads).toBe(2);
  } finally {
    finishFirstRead();
    vi.unstubAllGlobals();
  }
});
