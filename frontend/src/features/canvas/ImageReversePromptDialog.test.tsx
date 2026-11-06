import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { ImageReversePromptDialog } from "./ImageReversePromptDialog";

const path = "/api/v1/projects/p/artifacts/a/image-prompts";
const request = { canvasItemId: "i", sourceVersionId: "v", expectedCanvasItemVersion: 3 };
function task(status: Task["status"] = "READY"): Task {
  return { id: "task-1", projectId: "p", runId: null, stepKey: "reverse", kind: "TEXT_GENERATION", status,
    cancelRequested: status === "CANCELED", input: { sourceVersionId: "v", purpose: "IMAGE_REVERSE_PROMPT" },
    output: status === "SUCCEEDED" ? { prompt: "湖畔，晨光，柔和的色彩", sourceVersionId: "v", mock: false } : null,
    attemptNo: 1, nextActionAt: "2026-10-11T00:00:00Z", version: 1,
    createdAt: "2026-10-11T00:00:00Z", updatedAt: "2026-10-11T00:00:00Z" };
}
function show() {
  const client = createQueryClient();
  const close = vi.fn();
  const showCard = vi.fn().mockResolvedValue(true);
  const dialog = (open = true, sourceVersionId = "v") => <QueryClientProvider client={client}>
    <ImageReversePromptDialog key={sourceVersionId} projectId="p" artifactId="a" title="湖边" assetId="asset"
      request={{ ...request, sourceVersionId }} open={open} onClose={close} onShowCard={showCard} />
  </QueryClientProvider>;
  const view = render(dialog());
  return { client, close, showCard, ...view, reopen: (open: boolean, source = "v") => view.rerender(dialog(open, source)) };
}

describe("ImageReversePromptDialog", () => {
  it.each([
    ["IMAGE_PROMPT_INPUT_TOO_LARGE", "原图超过反推的 10 MiB 上限"],
    ["IMAGE_PROMPT_INPUT_UNSUPPORTED", "反推支持 PNG、JPEG 和 WebP 原图"],
    ["IMAGE_PROMPT_INPUT_UNAVAILABLE", "无法读取完整原图"],
    ["IMAGE_PROMPT_MODEL_REJECTED", "模型拒绝了图片请求"],
  ])("explains %s without submitting another model call", async (code, message) => {
    const submit = vi.fn();
    server.use(http.get(path, () => HttpResponse.json([{ ...task("FAILED"), errorCode: code }])),
      http.post(path, () => { submit(); return HttpResponse.json(task()); }));
    show();
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByText(/PNG\/JPEG\/WebP，最多 10 MiB/)).toBeVisible();
    expect(submit).not.toHaveBeenCalled();
  });
  beforeEach(() => server.use(
    http.get(path, () => HttpResponse.json([])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  ));

  it("restores a persisted text card and opens it only after refreshing the canvas", async () => {
    const user = userEvent.setup();
    let submissions = 0, reads = 0;
    server.use(http.get(path, () => HttpResponse.json([{ ...task("SUCCEEDED"), input: { schemaVersion: 5, sourceVersionId: "v" },
      output: { prompt: "已保存的提示词", sourceVersionId: "v", mock: false,
        artifactId: "text-artifact", artifactVersionId: "text-version", canvasItemId: "text-item" } }])),
      http.get("/api/v1/projects/p/canvas/items", () => { reads++; return HttpResponse.json({ projectId: "p", items: [{ id: "text-item" }], groups: [] }); }),
      http.post(path, () => { submissions++; return HttpResponse.json(task()); }));
    const { showCard, close } = show();
    await screen.findByText("已生成文字卡片，可在画布中编辑。");
    expect(screen.getByRole("textbox", { name: "反推提示词" })).toHaveAttribute("readonly");
    expect(showCard).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "查看文字卡片" }));
    await waitFor(() => expect(showCard).toHaveBeenCalledWith("text-item", expect.any(AbortSignal)));
    expect(close).toHaveBeenCalledOnce();
    expect(reads).toBe(1); expect(submissions).toBe(0);
  });

  it("retains a saved card result if refreshing the canvas fails", async () => {
    const user = userEvent.setup();
    server.use(http.get(path, () => HttpResponse.json([{ ...task("SUCCEEDED"),
      output: { prompt: "持久结果", mock: true, artifactId: "text-artifact", canvasItemId: "text-item" } }])),
      http.get("/api/v1/projects/p/canvas/items", () => new HttpResponse(null, { status: 503 })));
    const { showCard, close } = show();
    await user.click(await screen.findByRole("button", { name: "查看文字卡片" }));
    await screen.findByRole("alert");
    expect(screen.getByRole("textbox")).toHaveValue("持久结果");
    expect(showCard).not.toHaveBeenCalled(); expect(close).not.toHaveBeenCalled();
  });

  it("does not recreate a removed result card when opening history", async () => {
    const user = userEvent.setup(); let writes = 0;
    server.use(http.get(path, () => HttpResponse.json([{ ...task("SUCCEEDED"),
      output: { prompt: "原始反推文本", artifactId: "text-artifact", canvasItemId: "removed-card", mock: false } }])),
      http.get("/api/v1/projects/p/canvas/items", () => HttpResponse.json({ projectId: "p", items: [] })),
      http.post(path, () => { writes++; return HttpResponse.json(task()); }));
    const first = show();
    await user.click(await screen.findByRole("button", { name: "查看文字卡片" }));
    await screen.findByText("找不到这张文字卡片，可能已被移除。反推原文仍可复制。");
    expect(first.showCard).not.toHaveBeenCalled(); expect(first.close).not.toHaveBeenCalled();
    expect(screen.getByRole("textbox")).toHaveValue("原始反推文本");
    first.unmount();
    show();
    await screen.findByText("已生成文字卡片，可在画布中编辑。");
    expect(screen.getByRole("textbox")).toHaveValue("原始反推文本");
    expect(writes).toBe(0);
  });

  it.each(["dismiss", "reopen", "unmount"])("ignores a late view response after %s", async (mode) => {
    const user = userEvent.setup(); let reads = 0, release = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.get(path, () => HttpResponse.json([{ ...task("SUCCEEDED"),
      output: { prompt: "原始文本", artifactId: "text-artifact", canvasItemId: "text-item", mock: false } }])),
      http.get("/api/v1/projects/p/canvas/items", async () => { reads++; await gate; return HttpResponse.json({ items: [{ id: "text-item" }] }); }));
    const shown = show();
    await user.click(await screen.findByRole("button", { name: "查看文字卡片" }));
    await waitFor(() => expect(reads).toBe(1));
    if (mode === "unmount") shown.unmount();
    else {
      await user.click(screen.getByRole("button", { name: "关闭" }));
      shown.reopen(false);
      if (mode === "reopen") shown.reopen(true, "other-version");
    }
    await act(async () => { release(); });
    await waitFor(() => expect(shown.client.isMutating()).toBe(0));
    expect(shown.showCard).not.toHaveBeenCalled();
    expect(shown.close).toHaveBeenCalledTimes(mode === "unmount" ? 0 : 1);
    if (mode === "reopen") expect(screen.getByRole("dialog", { name: "提示词反推" })).toBeVisible();
  });

  it("requires an explicit start, pins the selected version and prevents duplicate clicks", async () => {
    const user = userEvent.setup();
    let body: unknown;
    let calls = 0;
    server.use(http.post(path, async ({ request: incoming }) => {
      calls++; body = await incoming.json();
      expect(incoming.headers.get("Idempotency-Key")).toBeTruthy();
      return HttpResponse.json(task());
    }));
    show();
    const start = await screen.findByRole("button", { name: "开始反推" });
    await waitFor(() => expect(start).toBeEnabled());
    expect(calls).toBe(0);
    await user.dblClick(start);
    await waitFor(() => expect(body).toEqual(request));
    expect(calls).toBe(1);
    expect(screen.getByRole("button", { name: "重新反推" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "取消" })).toBeEnabled();
  });

  it("restores completed results, allows editing and copies exactly the edited text", async () => {
    const user = userEvent.setup();
    server.use(http.get(path, () => HttpResponse.json([task("SUCCEEDED")])));
    show();
    const result = await screen.findByRole("textbox", { name: "反推提示词" });
    await user.clear(result);
    await user.type(result, "晨光下的湖畔");
    const copy = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
    await user.click(screen.getByRole("button", { name: "复制提示词" }));
    await screen.findByRole("button", { name: "已复制" });
    expect(copy).toHaveBeenCalledWith("晨光下的湖畔");
    await user.type(result, "，广角");
    expect(screen.getByRole("button", { name: "复制提示词" })).toBeVisible();
  });

  it("replays the same command key after an uncertain acceptance response", async () => {
    const user = userEvent.setup();
    const keys: (string | null)[] = [];
    const bodies: unknown[] = [];
    server.use(http.post(path, async ({ request: incoming }) => {
      keys.push(incoming.headers.get("Idempotency-Key")); bodies.push(await incoming.json());
      return keys.length === 1 ? new HttpResponse(null, { status: 502 }) : HttpResponse.json(task());
    }));
    show();
    const start = await screen.findByRole("button", { name: "开始反推" });
    await waitFor(() => expect(start).toBeEnabled());
    await user.click(start);
    await screen.findByText("尚未确认是否受理。重试会查询或重放同一请求。");
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBe(keys[1]); expect(bodies).toEqual([request, request]);
  });

  it("cancels a restored running task without dropping its record", async () => {
    const user = userEvent.setup();
    let current = task("RUNNING");
    server.use(http.get(path, () => HttpResponse.json([current])),
      http.post(`${path}/task-1/cancel`, () => { current = task("CANCELED"); return HttpResponse.json(current); }));
    show();
    await user.click(await screen.findByRole("button", { name: "取消" }));
    await screen.findByText("本次反推已取消。服务方可能仍在处理并计费。");
    expect(screen.queryByRole("button", { name: "取消" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重新反推" })).toBeEnabled();
  });

  it("retains text after clipboard failure and labels Mock results", async () => {
    const user = userEvent.setup();
    server.use(http.get(path, () => HttpResponse.json([{ ...task("SUCCEEDED"), output: { prompt: "演示提示词", mock: true } }])));
    show();
    await screen.findByText("Mock 演示，非真实图片理解结果。");
    vi.spyOn(navigator.clipboard, "writeText").mockRejectedValue(new Error("Denied"));
    await user.click(screen.getByRole("button", { name: "复制提示词" }));
    await screen.findByText("复制失败。可选择提示词后手动复制。");
    expect(screen.getByRole("textbox")).toHaveValue("演示提示词");
  });

  it("blocks new calls when task history is unavailable and allows a read-only retry", async () => {
    const user = userEvent.setup();
    server.use(http.get(path, () => new HttpResponse(null, { status: 401 })));
    show();
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "开始反推" })).toBeDisabled();
    server.use(http.get(path, () => HttpResponse.json([])));
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "开始反推" })).toBeEnabled());
  });

  it("keeps another source version distinct and lets the user stop its active task", async () => {
    server.use(http.get(path, () => HttpResponse.json([{ ...task("RUNNING"), input: { sourceVersionId: "old-version" } }])));
    show();
    await screen.findByText("此节点另一图片版本仍在反推，请等待完成或取消。");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "开始反推" })).toBeDisabled();
  });
});
