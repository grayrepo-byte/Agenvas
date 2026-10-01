import { QueryClientProvider } from "@tanstack/react-query";
import { act,fireEvent,render,screen,waitFor } from "@testing-library/react";
import { http,HttpResponse } from "msw";
import { describe,expect,it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ArtifactVersionList,CanvasItem } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
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

function setup(versions = [version("v2", 2), version("v1", 1)], selectedItem = item,
  historyResponse = () => HttpResponse.json({ items: versions })) {
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  server.use(http.get(`${base}/media-versions`, historyResponse), http.get("/api/v1/auth/csrf", () => HttpResponse.json({
    headerName: "X-XSRF-TOKEN", token: "test",
  })));
  render(<QueryClientProvider client={client}>
    <MediaVersionPicker projectId="project-1" item={selectedItem} />
  </QueryClientProvider>);
  return client;
}

describe("MediaVersionPicker", () => {
  it("numbers a derived node from v1 despite gaps in artifact audit numbers", async () => {
    let request: unknown;
    server.use(http.post(`${base}/select-media-version`, async ({ request: incoming }) => {
      request = await incoming.json();
      return HttpResponse.json(item);
    }));
    setup([version("derived-later", 12), version("derived-first", 8)], {
      ...item, selectedVersionId: "derived-first", selectedVersion: version("derived-first", 8),
    });
    await clickControl(await screen.findByRole("button", { name: "版本 v1" }));
    expect(await screen.findByRole("menuitem", { name: "v1 当前选用" })).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "v2 选用此版本" })).toBeVisible();
    expect(screen.queryByText("v8")).not.toBeInTheDocument();
    expect(screen.queryByText("v12")).not.toBeInTheDocument();
    await clickControl(screen.getByRole("menuitem", { name: "v2 选用此版本" }));
    await waitFor(() => expect(request).toEqual({ versionId: "derived-later", expectedVersion: 7 }));
  });

  it("hides the version button for a node with only one result", async () => {
    const client = setup([version("v2", 2)]);
    await act(async () => { client.setQueryData(["canvas-media-versions", "project-1", "item-1"],
      { items: [version("v2", 2)] }); });
    await waitFor(() => expect(client.getQueryData(["canvas-media-versions", "project-1", "item-1"]))
      .toEqual({ items: [version("v2", 2)] }));
    expect(screen.queryByRole("button", { name: /^版本/ })).not.toBeInTheDocument();
  });
  it("lists node versions and switches with the current node CAS", async () => {
    const client = setup();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    let request: unknown;
    server.use(http.post(`${base}/select-media-version`, async ({ request: incoming }) => {
      request = await incoming.json();
      return HttpResponse.json({ ...item, version: 8, selectedVersionId: "v1" });
    }));
    await clickControl(await screen.findByRole("button", { name: "版本 v2" }));
    expect(await screen.findByText("2 个版本")).toBeVisible();
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    await clickControl(screen.getByRole("menuitem", { name: "v1 选用此版本" }));
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
    await clickControl(await screen.findByRole("button", { name: "版本 v2" }));
    await clickControl(await screen.findByRole("menuitem", { name: "v1 选用此版本" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("节点已变化");
    expect(screen.getByRole("menuitem", { name: "v2 当前选用" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("button", { name: "刷新版本" })).toBeVisible();
  });

  it("retries a failed history read without changing the node", async () => {
    let attempts = 0;
    setup(undefined, undefined, () => ++attempts === 1
      ? HttpResponse.json({ detail: "读取失败" }, { status: 503 })
      : HttpResponse.json({ items: [version("v2", 2)] }));
    await clickControl(await screen.findByRole("button", { name: "重试读取" }));
    await waitFor(() => expect(attempts).toBe(2));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /^版本/ })).not.toBeInTheDocument();
  });

  it("closes with Escape and restores focus", async () => {
    setup();
    await clickControl(await screen.findByRole("button", { name: "版本 v2" }));
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "版本 v2" })).toHaveFocus());
  });

  it("shows the picker when a second node result arrives", async () => {
    const client = setup([version("v2", 2)]);
    await waitFor(() => expect(client.getQueryState(["canvas-media-versions", "project-1", "item-1"])?.status)
      .toBe("success"));
    expect(screen.queryByRole("button", { name: /^版本/ })).not.toBeInTheDocument();
    server.use(http.get(`${base}/media-versions`, () => HttpResponse.json({
      items: [version("v5", 5), version("v2", 2)],
    })));
    await act(async () => { await client.invalidateQueries({ queryKey: ["canvas-media-versions", "project-1"] }); });
    await clickControl(await screen.findByRole("button", { name: "版本 v1" }));
    expect(screen.getByRole("menuitem", { name: "v2 选用此版本" })).toBeVisible();
  });

  it("hides the picker when there are no archived results", async () => {
    const client = setup([]);
    await waitFor(() => expect(client.getQueryState(["canvas-media-versions", "project-1", "item-1"])?.status)
      .toBe("success"));
    expect(screen.queryByRole("button", { name: /^版本/ })).not.toBeInTheDocument();
  });
});
