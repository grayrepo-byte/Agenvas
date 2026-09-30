import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ArtifactVersionList, CanvasItem } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaVersionPicker } from "./MediaVersionPicker";

const base = "/api/v1/projects/project-1/canvas/items/item-1";
const version = (id: string, versionNo: number): ArtifactVersionList["items"][number] => ({ id, versionNo, schemaVersion: 1,
  content: { sourceType: "UPLOAD", assetId: `asset-${id}` }, inputReferences: [], createdByKind: "TASK",
  runId: null, createdAt: "2026-09-30T00:00:00Z" });
const item: CanvasItem = { id: "item-1", subjectType: "ARTIFACT", subjectId: "image-1",
  title: "Concept", x: 0, y: 0, width: 280, height: 240, zIndex: 0, locked: false,
  selectedVersionId: "v2", selectedVersion: version("v2", 2), version: 7,
  artifact: null, agent: null };

function setup() {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  server.use(http.get(`${base}/media-versions`, () => HttpResponse.json({
    items: [version("v2", 2), version("v1", 1)],
  })), http.get("/api/v1/auth/csrf", () => HttpResponse.json({
    headerName: "X-XSRF-TOKEN", token: "test",
  })));
  render(<QueryClientProvider client={client}>
    <MediaVersionPicker projectId="project-1" item={item} />
  </QueryClientProvider>);
  return client;
}

describe("MediaVersionPicker", () => {
  it("lists node versions and switches with the current node CAS", async () => {
    const client = setup();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    let request: unknown;
    server.use(http.post(`${base}/select-media-version`, async ({ request: incoming }) => {
      request = await incoming.json();
      return HttpResponse.json({ ...item, version: 8, selectedVersionId: "v1" });
    }));
    fireEvent.click(screen.getByRole("button", { name: "版本 v2" }));
    expect(await screen.findByText("2 个版本")).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    fireEvent.click(screen.getByRole("menuitem", { name: "v1 选用此版本" }));
    await waitFor(() => expect(request).toEqual({ versionId: "v1", expectedVersion: 7 }));
    await waitFor(() => expect(screen.queryByRole("menu")).not.toBeInTheDocument());
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["canvas", "project-1"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["media-draft", "project-1", "item-1"] });
  });

  it("keeps current selection and offers refresh after a concurrent change", async () => {
    setup();
    server.use(http.post(`${base}/select-media-version`, () => HttpResponse.json({
      code: "VERSION_CONFLICT", title: "版本冲突", detail: "节点已变化，请刷新后重试。",
    }, { status: 409, headers: { "content-type": "application/problem+json" } })));
    fireEvent.click(screen.getByRole("button", { name: "版本 v2" }));
    fireEvent.click(await screen.findByRole("menuitem", { name: "v1 选用此版本" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("节点已变化");
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("button", { name: "刷新版本" })).toBeVisible();
  });

  it("retries a failed history read without changing the node", async () => {
    setup();
    let attempts = 0;
    server.use(http.get(`${base}/media-versions`, () => ++attempts === 1
      ? HttpResponse.json({ detail: "读取失败" }, { status: 503 })
      : HttpResponse.json({ items: [version("v2", 2)] })));
    fireEvent.click(screen.getByRole("button", { name: "版本 v2" }));
    fireEvent.click(await screen.findByRole("button", { name: "重试读取" }));
    expect(await screen.findByText("1 个版本")).toBeVisible();
  });

  it("shows an empty history and closes with Escape", async () => {
    setup();
    server.use(http.get(`${base}/media-versions`, () => HttpResponse.json({ items: [] })));
    fireEvent.click(screen.getByRole("button", { name: "版本 v2" }));
    expect(await screen.findByText("还没有已归档的结果。")).toBeVisible();
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "版本 v2" })).toHaveFocus();
  });
});
