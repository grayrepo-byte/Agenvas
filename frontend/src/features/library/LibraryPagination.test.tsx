import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { LibraryEntry, ResourceResult } from "../../shared/api/client";
import { server } from "../../test/server";
import { LibraryBrowser } from "./LibraryBrowser";
import { ResourceBrowser } from "./ResourceBrowser";
import { ProjectResourceBrowser } from "../canvas/ProjectResourceBrowser";
import { projectCacheCallbacks } from "../canvas/projectCache";

const now = "2026-11-05T00:00:00Z";
function entry(id: string, name: string): LibraryEntry {
  return { id, name, category: "OTHER", kind: "AUDIO", textContent: null, source: { origin: "UPLOAD" },
    contentType: "audio/wav", byteSize: 128, width: null, height: null, durationMs: 1_000,
    favorite: false, version: 1, trashedAt: null, createdAt: now, updatedAt: now, hasThumbnail: false };
}
function resource(id: string, title: string): ResourceResult {
  return { versionId: id, artifactId: `artifact-${id}`, projectId: "project-1", projectName: "合成项目",
    title, kind: "AUDIO", versionNo: 1, content: { sourceType: "UPLOAD", assetId: `asset-${id}` }, createdAt: now };
}
const scenarios = [
  { name: "all resources", path: "/api/v1/resources", refresh: "刷新资源", more: "加载更多资源",
    render: () => <ResourceBrowser />, page: (id: string, name: string, cursor: string | null) =>
      ({ items: [resource(id, name)], nextCursor: cursor }) },
  { name: "project resources after an event", path: "/api/v1/projects/project-1/resources", refresh: null,
    more: "加载更多资源", render: () => <ProjectResourceBrowser projectId="project-1" position={() => ({ x: 80, y: 80 })}
      zIndex={1} onPlaced={vi.fn()} />, page: (id: string, name: string, cursor: string | null) =>
      ({ items: [resource(id, name)], nextCursor: cursor }) },
  ...(["page", "picker"] as const).map((presentation) => ({ name: `library ${presentation}`,
    path: "/api/v1/library/entries", refresh: "刷新资产", more: "加载更多资产",
    render: () => <LibraryBrowser presentation={presentation} onPick={vi.fn()} />,
    page: (id: string, name: string, cursor: string | null) =>
      ({ items: [entry(id, name)], nextCursor: cursor, total: 3, categoryCounts: { OTHER: 3 } }) })),
];

it.each(scenarios)("keeps the refreshed pages and cursor when load more is attempted in $name", async (scenario) => {
  const cursors: (string | null)[] = [];
  let firstReads = 0;
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  server.use(http.get(scenario.path, async ({ request }) => {
    const cursor = new URL(request.url).searchParams.get("cursor"); cursors.push(cursor);
    if (!cursor) {
      if (++firstReads === 1) return HttpResponse.json(scenario.page("old-first", "旧首项", "old-2"));
      await pending; return HttpResponse.json(scenario.page("new-first", "新首项", "new-2"));
    }
    if (cursor === "old-2") return HttpResponse.json(scenario.page("old-second", "旧次项", "old-3"));
    if (cursor === "new-2") return HttpResponse.json(scenario.page("new-second", "新次项", "new-3"));
    return HttpResponse.json(scenario.page("third", cursor === "old-3" ? "过期末项" : "新末项", null));
  }));
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter>{scenario.render()}</MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await screen.findByText("旧首项");
  await user.click(screen.getByRole("button", { name: scenario.more }));
  await screen.findByText("旧次项");
  if (scenario.refresh) await user.click(screen.getByRole("button", { name: scenario.refresh }));
  else act(() => projectCacheCallbacks(client, "project-1").onChange({ eventId: "event-1", projectId: "project-1",
    seq: 1, type: "artifact.version.created", schemaVersion: 1, aggregateId: "artifact-new", aggregateVersion: 1,
    occurredAt: now, payload: {} }));
  await waitFor(() => expect(firstReads).toBe(2));
  try {
    const more = screen.getByRole("button", { name: scenario.more });
    expect(more).toBeDisabled();
    await user.click(more);
    expect(cursors).not.toContain("old-3");
  } finally {
    release();
    await waitFor(() => expect(client.isFetching()).toBe(0));
  }
  expect(await screen.findByText("新首项")).toBeVisible();
  expect(screen.getByText("新次项")).toBeVisible();
  expect(screen.queryByText("旧首项")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: scenario.more }));
  expect(await screen.findByText("新末项")).toBeVisible();
  expect(screen.queryByText("过期末项")).not.toBeInTheDocument();
  expect(cursors).toEqual([null, "old-2", null, "new-2", "new-3"]);
});

it("retains a committed favorite and uses its new CAS version after background refresh", async () => {
  let saved = { ...entry("first", "甲"), version: 0 };
  let refreshStarted = false;
  let release!: () => void;
  const pending = new Promise<void>((resolve) => { release = resolve; });
  const writes: { expectedVersion: number; favorite: boolean }[] = [];
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
    http.get("/api/v1/library/entries", async ({ request }) => {
      const cursor = new URL(request.url).searchParams.get("cursor");
      if (cursor) return HttpResponse.json({ items: [entry(cursor, cursor === "second" ? "乙" : "丙")],
        nextCursor: cursor === "second" ? "third" : null, total: 3, categoryCounts: { OTHER: 3 } });
      if (writes.length) { refreshStarted = true; await pending; }
      return HttpResponse.json({ items: [saved], nextCursor: "second", total: 3, categoryCounts: { OTHER: 3 } });
    }),
    http.patch("/api/v1/library/entries/first", async ({ request }) => {
      const input = await request.json() as { expectedVersion: number; favorite: boolean }; writes.push(input);
      if (input.expectedVersion !== saved.version) return HttpResponse.json({ title: "Synthetic CAS conflict",
        detail: "Synthetic CAS conflict", status: 409, code: "VERSION_CONFLICT" },
      { status: 409, headers: { "Content-Type": "application/problem+json" } });
      saved = { ...saved, favorite: input.favorite, version: saved.version + 1 };
      return HttpResponse.json(saved);
    }),
  );
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter><LibraryBrowser presentation="page" onPick={vi.fn()} /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await screen.findByRole("button", { name: "收藏 甲" });
  await user.click(screen.getByRole("button", { name: "加载更多资产" }));
  await screen.findByRole("button", { name: "查看 乙" });
  await user.click(screen.getByRole("button", { name: "收藏 甲" }));
  await waitFor(() => expect(refreshStarted).toBe(true));
  try { await user.click(screen.getByRole("button", { name: "加载更多资产" })); }
  finally { release(); await waitFor(() => expect(client.isFetching()).toBe(0)); }
  const unfavorite = await screen.findByRole("button", { name: "取消收藏 甲" });
  await waitFor(() => expect(unfavorite).toBeEnabled());
  await user.click(unfavorite);
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes).toMatchObject([{ expectedVersion: 0, favorite: true }, { expectedVersion: 1, favorite: false }]);
  expect(await screen.findByRole("button", { name: "收藏 甲" })).toBeEnabled();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
