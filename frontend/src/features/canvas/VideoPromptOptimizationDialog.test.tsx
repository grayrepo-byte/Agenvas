import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { Task } from "../../shared/api/client";
import { server } from "../../test/server";
import { VideoPromptOptimizationDialog } from "./VideoPromptOptimizationDialog";

const path = "/api/v1/projects/p/canvas-items/i/video-prompt-optimizations";
const prompt = "湖边晨光";
const now = "2026-10-24T00:00:00Z";
function task(status: Task["status"] = "READY"): Task {
  return { id: "t", projectId: "p", runId: null, stepKey: "optimize", kind: "TEXT_GENERATION", status, cancelRequested: false,
    input: { prompt, purpose: "VIDEO_PROMPT_OPTIMIZATION" }, output: status === "SUCCEEDED" ? { prompt: "晨光下的湖边，镜头缓缓推进。", mock: false } : null,
    attemptNo: 1, version: 1, nextActionAt: now, createdAt: now, updatedAt: now };
}
function show(apply = vi.fn(() => true)) {
  const client = createQueryClient();
  const close = vi.fn();
  render(<QueryClientProvider client={client}><VideoPromptOptimizationDialog projectId="p" canvasItemId="i" prompt={prompt}
    maxLength={20000} onApply={apply} onClose={close} /></QueryClientProvider>);
  return { client, apply, close };
}
beforeEach(() => server.use(
  http.get(path, () => HttpResponse.json([])),
  http.get("/api/v1/skills", () => HttpResponse.json({ items: [], nextCursor: null, total: 0 })),
  http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
));
describe("VideoPromptOptimizationDialog", () => {
  it("requires an explicit start and pins the chosen published Skill version", async () => {
    let body: unknown;
    let calls = 0;
    server.use(http.get("/api/v1/skills", () => HttpResponse.json({ items: [{ id: "s", title: "运镜方法", description: "镜头运动", currentVersionId: "v2", trashed: false }], total: 1, nextCursor: null })),
      http.get("/api/v1/skills/s/versions", () => HttpResponse.json([{ id: "v2", versionNumber: 2 }, { id: "v1", versionNumber: 1 }])),
      http.post(path, async ({ request }) => { calls++; body = await request.json(); return HttpResponse.json(task()); }));
    show();
    const start = await screen.findByRole("button", { name: "开始优化" });
    await waitFor(() => expect(start).toBeEnabled());
    expect(calls).toBe(0);
    await screen.findByRole("option", { name: "运镜方法", hidden: true });
    fireEvent.change(screen.getByRole("option", { name: "运镜方法", hidden: true }).closest("select")!, { target: { value: "s" } });
    await screen.findByRole("option", { name: "版本 1", hidden: true });
    fireEvent.change(screen.getByRole("option", { name: "版本 1", hidden: true }).closest("select")!, { target: { value: "v1" } });
    await userEvent.dblClick(start);
    await waitFor(() => expect(body).toEqual({ prompt, maxLength: 20000, skill: { skillId: "s", skillVersionId: "v1" } }));
    expect(calls).toBe(1);
    expect(screen.getByRole("button", { name: "重新优化" })).toBeDisabled();
  });
  it("retains a selected publication when changing category and combines search with the category", async () => {
    let body: unknown;
    const requests: URL[] = [];
    server.use(http.get("/api/v1/skills", ({ request }) => {
      const url = new URL(request.url); requests.push(url);
      const items = url.searchParams.has("category") ? [] : [{ id: "s", title: "H3 提示词", description: "提示词方法", builtin: true, category: "PROMPT", currentVersionId: "v2", trashed: false }];
      return HttpResponse.json({ items, total: items.length, nextCursor: null });
    }), http.get("/api/v1/skills/s/versions", () => HttpResponse.json([{ id: "v2", versionNumber: 2 }, { id: "v1", versionNumber: 1 }])),
    http.post(path, async ({ request }) => { body = await request.json(); return HttpResponse.json(task()); }));
    show(); const user = userEvent.setup();
    await user.click(await screen.findByRole("combobox", { name: "创作 Skill（可选）" }));
    await user.click(await screen.findByRole("option", { name: "H3 提示词" }));
    await waitFor(() => expect(screen.getByRole("combobox", { name: "发布版本" })).toBeEnabled());
    await user.click(screen.getByRole("combobox", { name: "发布版本" }));
    await user.click(await screen.findByRole("option", { name: "版本 1" }));
    await user.click(screen.getByRole("combobox", { name: "Skill 分类" }));
    await user.click(await screen.findByRole("option", { name: "知识讲解" }));
    await user.type(screen.getByRole("searchbox", { name: "搜索 Skill" }), "paper");
    await waitFor(() => expect(requests.at(-1)?.searchParams.get("query")).toBe("paper"));
    expect(requests.at(-1)?.searchParams.get("category")).toBe("EXPLAINER");
    expect(screen.getByRole("combobox", { name: "发布版本" })).toHaveTextContent("版本 1");
    await user.click(screen.getByRole("button", { name: "开始优化" }));
    await waitFor(() => expect(body).toEqual({ prompt, maxLength: 20000, skill: { skillId: "s", skillVersionId: "v1" } }));
  });
  it("replays the original key and selection when acceptance is uncertain", async () => {
    const keys: (string | null)[] = [];
    const bodies: unknown[] = [];
    server.use(http.post(path, async ({ request }) => {
      keys.push(request.headers.get("Idempotency-Key")); bodies.push(await request.json());
      return keys.length === 1 ? new HttpResponse(null, { status: 502 }) : HttpResponse.json(task());
    }));
    show();
    const start = await screen.findByRole("button", { name: "开始优化" });
    await waitFor(() => expect(start).toBeEnabled());
    await userEvent.click(start);
    await screen.findByText(/尚未确认任务是否受理/);
    await userEvent.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBe(keys[1]); expect(bodies[0]).toEqual(bodies[1]);
  });
  it("restores results without another call, permits editing and preserves conflicts", async () => {
    server.use(http.get(path, () => HttpResponse.json([task("SUCCEEDED")])));
    const apply = vi.fn(() => false);
    show(apply);
    const result = await screen.findByRole("textbox", { name: "优化结果" });
    fireEvent.change(result, { target: { value: "我的修改" } });
    await userEvent.click(screen.getByRole("button", { name: "应用到草稿" }));
    expect(apply).toHaveBeenCalledWith("我的修改");
    expect(await screen.findByText(/草稿已变化、引用标记不完整/)).toBeVisible();
    expect(result).toHaveValue("我的修改");
  });
  it("restores an active task and allows cancellation", async () => {
    let current = task("RUNNING");
    server.use(http.get(path, () => HttpResponse.json([current])), http.post(`${path}/t/cancel`, () => {
      current = task("CANCELED"); return HttpResponse.json(current);
    }));
    show();
    await userEvent.click(await screen.findByRole("button", { name: "取消" }));
    expect(await screen.findByText("提示词优化已取消，原草稿已保留。")).toBeVisible();
  });
  it("keeps the read failure visible and blocks submissions until recovery", async () => {
    server.use(http.get(path, () => new HttpResponse(null, { status: 401 })));
    show();
    expect(await screen.findByRole("button", { name: "开始优化" })).toBeDisabled();
    await screen.findByRole("button", { name: "重试读取" });
  });
});
