import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent,render, screen,waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { http, HttpResponse } from "msw";
import { expect, it,vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { LibraryPage } from "./LibraryPage";

it("uses server classification/search and exposes recycle restore with the current version", async () => {
  const requests: string[] = []; const restored: unknown[] = [];
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/projects", () => HttpResponse.json({ items: [], nextCursor: null })),
    http.get("/api/v1/library/entries", ({ request }) => {
      requests.push(request.url); const trash = new URL(request.url).searchParams.get("trash") === "true";
      return HttpResponse.json({ items: [{ id: "entry", name: "旅馆", category: "SCENE", kind: "IMAGE", textContent: null, source: {}, favorite: false, version: 4, trashedAt: trash ? "2026-10-01T00:00:00Z" : null, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z", hasThumbnail: false }], nextCursor: null, total: 1, categoryCounts: { SCENE: 1 } });
    }),
    http.post("/api/v1/library/entries/entry/restore", async ({ request }) => {
      restored.push(await request.json()); return HttpResponse.json({});
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("radio", { name: "场景 (1)" }));
  expect(requests.some((url) => new URL(url).searchParams.get("category") === "SCENE")).toBe(true);
  expect(requests.every((url) => new URL(url).searchParams.get("mediaOnly") === "true")).toBe(true);
  await user.click(screen.getByRole("tab", { name: "回收站" }));
  await user.click(await screen.findByRole("button", { name: "查看 旅馆" }));
  await user.click(screen.getByRole("button", { name: "恢复资产" }));
  expect(restored).toEqual([{ expectedVersion: 4 }]);
});

it("removes the matching-asset count and keeps refresh with the filters", async () => {
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [], total: 0, nextCursor: null, categoryCounts: {} })),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  await screen.findByText("还没有资产");
  expect(screen.queryByText(/个匹配资产/)).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "刷新资产" }).closest(".library-filters")).toBeInTheDocument();
  expect(screen.getByRole("tab", { name: "全部资源" })).toBeInTheDocument();
});

it("shows audio with a placeholder and hides only text in both asset-page lists", async () => {
  const requests: URL[] = [];
  const base = { category: "OTHER", source: {}, favorite: false, version: 0, hasThumbnail: false, createdAt: "2026-10-04T00:00:00Z", updatedAt: "2026-10-04T00:00:00Z" };
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/library/entries", ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json({ items: [
        { ...base, id: "image", name: "图片结果", kind: "IMAGE" },
        { ...base, id: "video", name: "视频结果", kind: "VIDEO" },
        { ...base, id: "text", name: "文字结果", kind: "TEXT", textContent: { format: "PLAIN_TEXT", text: "文字" } },
        { ...base, id: "audio", name: "音频结果", kind: "AUDIO", hasThumbnail: true },
      ], total: 3, nextCursor: null, categoryCounts: { OTHER: 3 } });
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  for (const tab of ["我的资产", "回收站"]) {
    await user.click(await screen.findByRole("tab", { name: tab }));
    await screen.findByRole("button", { name: "查看 图片结果" });
    expect(screen.getByRole("button", { name: "查看 视频结果" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "查看 文字结果" })).not.toBeInTheDocument();
    const audio = screen.getByRole("button", { name: "查看 音频结果" });
    expect(audio.querySelector("img")).toBeNull();
    expect(audio.querySelector(".library-card-preview svg")).toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "媒体类型" }));
    expect(screen.queryByRole("option", { name: "文字" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "音频" }));
    await waitFor(() => expect(requests.some((request) => request.searchParams.get("kind") === "AUDIO" && request.searchParams.get("trash") === String(tab === "回收站"))).toBe(true));
    await user.click(screen.getByRole("combobox", { name: "媒体类型" }));
    await user.click(screen.getByRole("option", { name: "全部类型" }));
  }
  expect(requests.every((request) => request.searchParams.get("mediaOnly") === "true")).toBe(true);
});

it("accepts audio uploads and submits their media kind", async () => {
  const kinds: FormDataEntryValue[] = [];
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [], total: 0, nextCursor: null, categoryCounts: {} })),
    http.get("/api/v1/library/commands/audio-command", () => HttpResponse.json({ id: "audio-command", status: "SUCCEEDED", result: { entryId: "audio" } })),
  );
  // Inspect browser FormData before Node fetch serializes jsdom File objects, as in the canvas upload tests.
  const interceptedFetch = globalThis.fetch;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (input === "/api/v1/library/uploads") {
      expect(init?.body).toBeInstanceOf(FormData);
      const form = init?.body as FormData; const kind = form.get("kind"); if (kind) kinds.push(kind);
      expect(form.get("file")).toHaveProperty("name", "music.wav");
      return HttpResponse.json({ id: "audio-command", status: "SUCCEEDED", result: { entryId: "audio" } }, { status: 202 });
    }
    return interceptedFetch(input, init);
  });
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "上传资产" }));
  const file = screen.getByLabelText("资源文件");
  expect(file).toHaveAttribute("accept", expect.stringContaining("audio/wav"));
  await user.upload(file, new File(["synthetic audio"], "music.wav", { type: "audio/wav" }));
  const submit = screen.getByRole("button", { name: "上传并保存" });
  expect(submit).toBeEnabled();
  const form = submit.closest("form"); if (!form) throw new Error("Upload form is missing");
  // jsdom does not connect user-event's FileList to native required validation.
  fireEvent.submit(form);
  await waitFor(() => expect(kinds).toEqual(["AUDIO"]));
});
