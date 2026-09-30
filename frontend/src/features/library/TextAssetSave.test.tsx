import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { http, HttpResponse } from "msw";
import { expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { TextCanvasEditor } from "../canvas/TextCanvasEditor";
import type { VersionedArtifact } from "../canvas/versionedArtifact";

const text: VersionedArtifact = { id: "a", projectId: "p", kind: "TEXT", title: "说明", version: 1,
  resourceDefaultVersionId: "v1", createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-01T00:00:00Z",
  resourceDefaultVersion: { id: "v1", versionNo: 1, schemaVersion: 1, content: { format: "PLAIN_TEXT", text: "旧正文" }, createdByKind: "USER", createdAt: "2026-10-01T00:00:00Z", inputReferences: [] } };

it("saves unsaved text before reading the library snapshot", async () => {
  const events: string[] = [];
  server.use(
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/artifacts/a/revisions", async ({ request }) => {
      expect(await request.json()).toEqual({ expectedVersion: 1, title: "说明", content: { format: "PLAIN_TEXT", text: "新正文" } });
      events.push("text-saved"); return HttpResponse.json({ ...text, version: 2, resourceDefaultVersionId: "v2", resourceDefaultVersion: { ...text.resourceDefaultVersion, id: "v2", versionNo: 2, content: { format: "PLAIN_TEXT", text: "新正文" } } });
    }),
    http.get("/api/v1/projects/p/canvas-items/i/library-saves", () => {
      events.push("snapshot-read"); return HttpResponse.json({ title: "说明", kind: "TEXT", versionId: "v2", expectedSelectionEpoch: 0, expectedArtifactVersion: 2, versionNo: 2, textContent: { format: "PLAIN_TEXT", text: "新正文" }, assetId: null });
    }),
  );
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><TextCanvasEditor artifact={text} canvasItemId="i" locked={false} onDone={() => {}} /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup(); await user.clear(screen.getByRole("textbox", { name: "内容" })); await user.type(screen.getByRole("textbox", { name: "内容" }), "新正文");
  await user.click(screen.getByRole("button", { name: "保存为资产" }));
  await waitFor(() => expect(events).toEqual(["text-saved", "snapshot-read"]));
  expect(await screen.findByRole("dialog", { name: "保存为资产" })).toHaveTextContent("新正文");
});

it("retains unsaved text and does not save an older asset if the text CAS conflicts", async () => {
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/artifacts/a/revisions", () => HttpResponse.json({ code: "VERSION_CONFLICT", title: "冲突", detail: "正文已变化" }, { status: 409, headers: { "Content-Type": "application/problem+json" } })));
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><TextCanvasEditor artifact={text} canvasItemId="i" locked={false} onDone={() => {}} /></MemoryRouter></QueryClientProvider>);
  const user = userEvent.setup(); await user.type(screen.getByRole("textbox", { name: "内容" }), "本地修改");
  await user.click(screen.getByRole("button", { name: "保存为资产" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("内容有冲突");
  expect(screen.getByRole("textbox", { name: "内容" })).toHaveValue("旧正文本地修改");
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
});
