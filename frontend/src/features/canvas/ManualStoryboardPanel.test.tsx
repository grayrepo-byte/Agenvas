import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { ManualStoryboardPanel } from "./ManualStoryboardPanel";

function item(id: string, kind: "SCENE" | "CHARACTER", versionId: string): CanvasItem {
  const now = "2026-09-24T00:00:00Z";
  return { id: `${id}-card`, subjectType: "ARTIFACT", subjectId: id,
    x: 0, y: 0, width: 280, height: 220, zIndex: 0, groupId: null,
    locked: false, version: 0, agent: null,
    artifact: { id, projectId: "project-1", kind, title: id,
      currentVersionId: versionId, version: 0, createdAt: now, updatedAt: now,
      currentVersion: { id: versionId, versionNo: 1, schemaVersion: 1,
        content: kind === "SCENE" ? { name: id, location: "Room", timeOfDay: "Day",
          lighting: "Soft", style: "Realistic", referenceVersionIds: [] }
          : { name: id, description: "Lead", appearance: "Blue", referenceVersionIds: [] },
        inputReferences: [], createdByKind: "USER", runId: null, createdAt: now } },
  };
}

function renderPanel(items: CanvasItem[] = []) {
  const onSaved = vi.fn();
  const onSaveError = vi.fn();
  render(<QueryClientProvider client={createQueryClient()}>
    <ManualStoryboardPanel projectId="project-1" items={items}
      onSaveStart={vi.fn()} onSaved={onSaved} onSaveError={onSaveError} />
  </QueryClientProvider>);
  return { onSaved, onSaveError };
}

describe("manual storyboard creation", () => {
  it("retries an uncertain creation response with the exact same server idempotency key", async () => {
    const keys: string[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts", ({ request }) => {
        keys.push(request.headers.get("Idempotency-Key") ?? "");
        return keys.length === 1 ? HttpResponse.error()
          : HttpResponse.json({ id: "scene-id" }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", () =>
        HttpResponse.json({ items: [item("scene-id", "SCENE", "scene-v1")] })),
    );
    const { onSaved } = renderPanel();
    const user = userEvent.setup();
    const panel = screen.getByRole("region", { name: "手工分镜" });
    await user.type(within(panel).getByLabelText("标题"), "Studio");
    await user.type(within(panel).getByLabelText("地点"), "Room");
    await user.type(within(panel).getByLabelText("时间"), "Day");
    await user.type(within(panel).getByLabelText("光线"), "Soft");
    await user.type(within(panel).getByLabelText("风格"), "Realistic");
    await user.click(within(panel).getByRole("button", { name: "添加场景到画布" }));
    expect(await within(panel).findByText(/创建或放置未完成/)).toBeInTheDocument();
    await user.click(within(panel).getByRole("button", { name: "添加场景到画布" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBeTruthy();
    expect(keys[0]).toBe(keys[1]);
  });

  it("creates a character description with an empty optional image-reference list", async () => {
    let creations = 0;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        creations++;
        expect(await request.json()).toEqual({ kind: "CHARACTER", title: "Hero",
          content: { name: "Hero", description: "Lead", appearance: "Blue coat",
            referenceVersionIds: [] } });
        return HttpResponse.json({ id: "hero-id" }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", () =>
        HttpResponse.json({ items: [item("hero-id", "CHARACTER", "hero-v1")] })),
    );
    const { onSaved } = renderPanel();
    const user = userEvent.setup();
    const panel = screen.getByRole("region", { name: "手工分镜" });
    await user.selectOptions(within(panel).getByLabelText("类型"), "CHARACTER");
    await user.type(within(panel).getByLabelText("标题"), "Hero");
    await user.type(within(panel).getByLabelText("描述"), "Lead");
    await user.type(within(panel).getByLabelText("外观"), "Blue coat");
    await user.click(within(panel).getByRole("button", { name: "添加角色到画布" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(creations).toBe(1);
  });

  it("reuses a confirmed scene Artifact when placement fails and is retried", async () => {
    let creations = 0;
    let placements = 0;
    const itemIds: string[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        creations++;
        expect(await request.json()).toEqual({ kind: "SCENE", title: "Studio",
          content: { name: "Studio", location: "Room", timeOfDay: "Day",
            lighting: "Soft", style: "Realistic", referenceVersionIds: [] } });
        return HttpResponse.json({ id: "scene-id" }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", async ({ request }) => {
        placements++;
        const body = await request.json() as { commands: Array<{
          itemId: string; artifactId: string; type: string }> };
        expect(body.commands[0]).toMatchObject({ type: "PLACE_ARTIFACT", artifactId: "scene-id" });
        itemIds.push(body.commands[0]!.itemId);
        return placements === 1
          ? HttpResponse.json({ title: "暂时失败", detail: "画布放置失败。",
            code: "CANVAS_SAVE_FAILED", retryable: true },
          { status: 503, headers: { "Content-Type": "application/problem+json" } })
          : HttpResponse.json({ items: [item("scene-id", "SCENE", "scene-v1")] });
      }),
    );
    const { onSaved, onSaveError } = renderPanel();
    const user = userEvent.setup();
    const panel = screen.getByRole("region", { name: "手工分镜" });
    await user.type(within(panel).getByLabelText("标题"), "Studio");
    await user.type(within(panel).getByLabelText("地点"), "Room");
    await user.type(within(panel).getByLabelText("时间"), "Day");
    await user.type(within(panel).getByLabelText("光线"), "Soft");
    await user.type(within(panel).getByLabelText("风格"), "Realistic");
    await user.click(within(panel).getByRole("button", { name: "添加场景到画布" }));
    expect(await within(panel).findByText(/产物已创建；保持表单内容重试/))
      .toBeInTheDocument();
    expect(onSaveError).toHaveBeenCalledTimes(1);
    await user.click(within(panel).getByRole("button", { name: "添加场景到画布" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(creations).toBe(1);
    expect(placements).toBe(2);
    expect(itemIds[0]).toBe(itemIds[1]);
    expect(within(panel).getByLabelText("标题")).toHaveValue("");
  });

  it("creates a shot pinned to chosen scene and character versions without model calls", async () => {
    let revisions = 0;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({
        headerName: "X-XSRF-TOKEN", token: "test-token",
      })),
      http.post("/api/v1/projects/:projectId/artifacts", async ({ request }) => {
        revisions++;
        expect(await request.json()).toEqual({ kind: "SHOT", title: "Opening",
          content: { order: 1, durationMs: 3000, description: "Arrival",
            camera: "Wide", action: "Walk", characterVersionIds: ["hero-v1"],
            sceneVersionId: "studio-v1" } });
        return HttpResponse.json({ id: "shot-id" }, { status: 201 });
      }),
      http.post("/api/v1/projects/:projectId/canvas/commands", () =>
        HttpResponse.json({ items: [] })),
    );
    const { onSaved } = renderPanel([
      item("studio", "SCENE", "studio-v1"), item("hero", "CHARACTER", "hero-v1"),
    ]);
    const user = userEvent.setup();
    const panel = screen.getByRole("region", { name: "手工分镜" });
    await user.selectOptions(within(panel).getByLabelText("类型"), "SHOT");
    await user.type(within(panel).getByLabelText("标题"), "Opening");
    await user.type(within(panel).getByLabelText("描述"), "Arrival");
    await user.type(within(panel).getByLabelText("镜头语言"), "Wide");
    await user.type(within(panel).getByLabelText("动作"), "Walk");
    await user.selectOptions(within(panel).getByLabelText("场景精确版本"), "studio-v1");
    await user.click(within(panel).getByRole("checkbox", { name: /hero/ }));
    await user.click(within(panel).getByRole("button", { name: "添加镜头到画布" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1));
    expect(revisions).toBe(1);
    expect(within(panel).getByLabelText("顺序（1–6）")).toHaveValue(2);
  });

  it("requires a visible scene before offering manual shot creation", async () => {
    renderPanel();
    await userEvent.setup().selectOptions(screen.getByLabelText("类型"), "SHOT");
    expect(screen.getByRole("button", { name: "添加镜头到画布" })).toBeDisabled();
    expect(screen.getByText("请先创建至少一张场景卡片。")).toBeInTheDocument();
  });
});
