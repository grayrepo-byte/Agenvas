import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
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
      return HttpResponse.json({ items: [{ id: "entry", name: "旅馆", category: "SCENE", kind: "TEXT", textContent: { format: "PLAIN_TEXT", text: "海边酒店" }, source: {}, favorite: false, version: 4, trashedAt: trash ? "2026-10-01T00:00:00Z" : null, createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z", hasThumbnail: false }], nextCursor: null, total: 1, categoryCounts: { SCENE: 1 } });
    }),
    http.post("/api/v1/library/entries/entry/restore", async ({ request }) => {
      restored.push(await request.json()); return HttpResponse.json({});
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LibraryPage /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup();
  await user.click(await screen.findByRole("button", { name: "场景 (1)" }));
  expect(requests.some((url) => new URL(url).searchParams.get("category") === "SCENE")).toBe(true);
  await user.click(screen.getByRole("button", { name: "回收站" }));
  await user.click(await screen.findByRole("button", { name: "查看 旅馆" }));
  await user.click(screen.getByRole("button", { name: "恢复资产" }));
  expect(restored).toEqual([{ expectedVersion: 4 }]);
});
