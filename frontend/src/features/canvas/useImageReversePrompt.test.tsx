import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { useImageReversePrompt } from "./useImageReversePrompt";

const path = "/api/v1/projects/p/artifacts/a/image-prompts";
function task(status: Task["status"] = "READY"): Task {
  return { id: "task-1", projectId: "p", runId: null, stepKey: "reverse", kind: "TEXT_GENERATION", status,
    cancelRequested: status === "CANCELED", input: { sourceVersionId: "v", purpose: "IMAGE_REVERSE_PROMPT" },
    output: status === "SUCCEEDED" ? { prompt: "Synthetic prompt", sourceVersionId: "v", mock: true,
      artifactId: "text-artifact", artifactVersionId: "text-version", canvasItemId: "text-item" } : null,
    attemptNo: 1, nextActionAt: "2026-11-07T00:00:00Z", version: 1,
    createdAt: "2026-11-07T00:00:00Z", updatedAt: "2026-11-07T00:00:00Z" };
}
function Action({ enabled }: { enabled: boolean }) {
  const action = useImageReversePrompt({ projectId: "p", artifactId: "a", canvasItemId: "i",
    request: { canvasItemId: "i", sourceVersionId: "v", expectedCanvasItemVersion: 3 }, enabled });
  return <>{action.controls}{action.feedback}</>;
}
function show() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } });
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })));
  const view = (enabled: boolean) => <QueryClientProvider client={client}><Action enabled={enabled} /></QueryClientProvider>;
  const rendered = render(view(true));
  return { client, ...rendered, hideToolbar: () => rendered.rerender(view(false)) };
}

describe("image reverse prompt action", () => {
  it("submits once on direct double click and keeps tracking when the toolbar hides", async () => {
    const user = userEvent.setup(); let writes = 0, release = () => {};
    const gate = new Promise<void>((resolve) => { release = resolve; });
    server.use(http.get(path, () => HttpResponse.json([])), http.post(path, async () => {
      writes++; await gate; return HttpResponse.json(task());
    }));
    const shown = show();
    const start = screen.getByRole("button", { name: "提示词反推" });
    await waitFor(() => expect(start).toBeEnabled());
    expect(writes).toBe(0);
    await user.dblClick(start);
    shown.hideToolbar();
    await act(async () => release());
    await screen.findByRole("button", { name: "取消" });
    expect(writes).toBe(1);
    expect(screen.getByRole("button", { name: "排队中" })).toBeDisabled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("refreshes the canvas and connections for saved results without recreating or previewing text", async () => {
    let writes = 0;
    server.use(http.get(path, () => HttpResponse.json([task("SUCCEEDED")])),
      http.post(path, () => { writes++; return HttpResponse.json(task()); }));
    const shown = show();
    shown.client.setQueryData(["canvas", "p"], { items: [] });
    shown.client.setQueryData(["canvas-connections", "p"], { items: [] });
    await waitFor(() => expect(shown.client.getQueryState(["canvas-connections", "p"])?.isInvalidated).toBe(true));
    expect(shown.client.getQueryState(["canvas", "p"])?.isInvalidated).toBe(true);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(writes).toBe(0);
  });

  it("restores an active task from another image version and cancels it without starting new work", async () => {
    const user = userEvent.setup(); let current = { ...task("RUNNING"), input: { sourceVersionId: "old-v" } }, writes = 0;
    server.use(http.get(path, () => HttpResponse.json([current])),
      http.post(path, () => { writes++; return HttpResponse.json(task()); }),
      http.post(`${path}/task-1/cancel`, () => {
        current = { ...current, status: "CANCELED", cancelRequested: true, version: 2 }; return HttpResponse.json(current);
      }));
    const shown = show();
    await screen.findByText("此节点另一图片版本仍在反推，请等待完成或取消。");
    expect(screen.getByRole("button", { name: "正在反推提示词…" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "取消" }));
    await waitFor(() => expect(shown.client.getQueryData<Task[]>(["image-prompt-tasks", "p", "i"])?.[0]?.status).toBe("CANCELED"));
    expect(screen.getByRole("button", { name: "提示词反推" })).toBeEnabled();
    expect(writes).toBe(0);
  });

  it("blocks submission while history is unavailable and retries only the read", async () => {
    const user = userEvent.setup(); let writes = 0;
    server.use(http.get(path, () => new HttpResponse(null, { status: 401 })),
      http.post(path, () => { writes++; return HttpResponse.json(task()); }));
    show();
    await screen.findByRole("alert");
    expect(screen.getByRole("button", { name: "提示词反推" })).toBeDisabled();
    server.use(http.get(path, () => HttpResponse.json([])));
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "提示词反推" })).toBeEnabled());
    expect(writes).toBe(0);
  });

  it.each([
    ["IMAGE_PROMPT_INPUT_TOO_LARGE", "原图超过反推的 10 MiB 上限"],
    ["IMAGE_PROMPT_INPUT_UNSUPPORTED", "反推支持 PNG、JPEG 和 WebP 原图"],
    ["IMAGE_PROMPT_INPUT_UNAVAILABLE", "无法读取完整原图"],
    ["IMAGE_PROMPT_MODEL_REJECTED", "模型拒绝了图片请求"],
  ])("shows safe task failure %s inline", async (errorCode, message) => {
    server.use(http.get(path, () => HttpResponse.json([{ ...task("FAILED"), errorCode }])));
    show();
    expect(await screen.findByRole("alert")).toHaveTextContent(message);
    expect(screen.getByRole("button", { name: "提示词反推" })).toBeEnabled();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
