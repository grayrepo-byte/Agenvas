import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
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
  const view = render(<QueryClientProvider client={client}>
    <ImageReversePromptDialog projectId="p" artifactId="a" title="湖边" assetId="asset" request={request} open onClose={close} />
  </QueryClientProvider>);
  return { client, close, ...view };
}

describe("ImageReversePromptDialog", () => {
  beforeEach(() => server.use(
    http.get(path, () => HttpResponse.json([])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  ));

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
