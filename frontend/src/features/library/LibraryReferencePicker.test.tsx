import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ReferenceLibraryRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { LibraryReferencePicker } from "./LibraryReferencePicker";

it.each([undefined, "fixed_slot"])("adds a library reference atomically without placing a canvas card with slot %s", async (slotKey) => {
  const requests: unknown[] = []; const applied = vi.fn();
  server.use(
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "entry", name: "旅馆", category: "SCENE", kind: "IMAGE", version: 2, source: {}, favorite: false, createdAt: "2026-10-01T00:00:00Z", hasThumbnail: false }], total: 1, categoryCounts: { SCENE: 1 }, nextCursor: null })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/canvas-items/i/library-references", async ({ request }) => { requests.push(await request.json()); return HttpResponse.json({ id: "cmd", status: "ACCEPTED", result: null }, { status: 202 }); }),
    http.get("/api/v1/library/commands/cmd", () => HttpResponse.json({ id: "cmd", status: "SUCCEEDED", result: { versionId: "reference", draftVersion: 6 } })),
    http.get("/api/v1/projects/p/canvas-items/i/media-draft", () => HttpResponse.json({ version: 6, mediaInputs: [{ versionId: "reference" }] })),
  );
  render(<QueryClientProvider client={createQueryClient()}><LibraryReferencePicker projectId="p" itemId="i" kinds={["IMAGE"]} slotKey={slotKey}
    draft={{ expectedVersion: 5, prompt: "酒店", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [] }}
    plan={() => ({ role: "REFERENCE", color: "#67C7F3", videoInputMode: null })} onApplied={applied} /></QueryClientProvider>);
  const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "用作参考：旅馆" }));
  await waitFor(() => expect(applied).toHaveBeenCalledOnce());
  expect(requests).toEqual([expect.objectContaining({ entryId: "entry", expectedVersion: 2, role: "REFERENCE", draft: expect.objectContaining({ expectedVersion: 5, prompt: "酒店" }) })]);
  if (slotKey) expect(requests[0]).toHaveProperty("slotKey", slotKey);
  else expect(requests[0]).not.toHaveProperty("slotKey");
  expect(applied).toHaveBeenCalledWith(expect.any(Object), expect.objectContaining({ expectedVersion: 5, prompt: "酒店" }), slotKey);
});

it.each(["IMAGE", "VIDEO", "AUDIO"] as const)("selects the workflow slot after picking a %s library entry", async (kind) => {
  const requests: unknown[] = []; const applied = vi.fn();
  const slotKeys = { IMAGE: "image_slot", VIDEO: "video_slot", AUDIO: "audio_slot" };
  const roles = { IMAGE: "REFERENCE", VIDEO: "VIDEO_REFERENCE", AUDIO: "AUDIO_REFERENCE" } as const;
  const draft: ReferenceLibraryRequest["draft"] = { expectedVersion: 5, prompt: "混合素材", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [] };
  const saved = { version: 6, mediaInputs: [], parameters: { dynamicValues: { [slotKeys[kind]]: "reference" } } };
  server.use(
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "entry", name: "素材", category: "OTHER", kind, version: 2, source: {}, favorite: false, createdAt: "2026-10-01T00:00:00Z", hasThumbnail: false }], total: 1, categoryCounts: { OTHER: 1 }, nextCursor: null })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/canvas-items/i/library-references", async ({ request }) => { requests.push(await request.json()); return HttpResponse.json({ id: "cmd", status: "ACCEPTED", result: null }, { status: 202 }); }),
    http.get("/api/v1/library/commands/cmd", () => HttpResponse.json({ id: "cmd", status: "SUCCEEDED", result: { versionId: "reference", draftVersion: 6 } })),
    http.get("/api/v1/projects/p/canvas-items/i/media-draft", () => HttpResponse.json(saved)),
  );
  render(<QueryClientProvider client={createQueryClient()}><LibraryReferencePicker projectId="p" itemId="i" kinds={["IMAGE", "VIDEO", "AUDIO"]} slotKey="fixed_slot"
    draft={draft} plan={(entry) => entry.kind === "TEXT" ? null : ({ role: roles[entry.kind], color: "#67C7F3", videoInputMode: null, slotKey: slotKeys[entry.kind] })} onApplied={applied} /></QueryClientProvider>);
  await userEvent.setup().click(await screen.findByRole("button", { name: "用作参考：素材" }));
  await waitFor(() => expect(applied).toHaveBeenCalledOnce());
  expect(requests).toEqual([expect.objectContaining({ entryId: "entry", expectedVersion: 2, role: roles[kind], slotKey: slotKeys[kind], draft, commandKey: expect.any(String) })]);
  expect(applied).toHaveBeenCalledWith(saved, draft, slotKeys[kind]);
});

it("preserves local input when another client changes the draft after reference completion", async () => {
  const applied = vi.fn();
  server.use(
    http.get("/api/v1/library/entries", () => HttpResponse.json({ items: [{ id: "entry", name: "旅馆", category: "SCENE", kind: "IMAGE", version: 2, source: {}, favorite: false, createdAt: "2026-10-01T00:00:00Z", hasThumbnail: false }], total: 1, categoryCounts: { SCENE: 1 } })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.post("/api/v1/projects/p/canvas-items/i/library-references", () => HttpResponse.json({ id: "cmd", status: "ACCEPTED" }, { status: 202 })),
    http.get("/api/v1/library/commands/cmd", () => HttpResponse.json({ id: "cmd", status: "SUCCEEDED", result: { draftVersion: 6 } })),
    http.get("/api/v1/projects/p/canvas-items/i/media-draft", () => HttpResponse.json({ version: 7, prompt: "另一客户端的新提示词", mediaInputs: [] })),
  );
  render(<QueryClientProvider client={createQueryClient()}><LibraryReferencePicker projectId="p" itemId="i" kinds={["IMAGE"]}
    draft={{ expectedVersion: 5, prompt: "本地输入", parameters: {}, videoInputMode: null, mediaInputs: [], mentions: [] }}
    plan={() => ({ role: "REFERENCE", color: "#67C7F3", videoInputMode: null })} onApplied={applied} /></QueryClientProvider>);
  await userEvent.setup().click(await screen.findByRole("button", { name: "用作参考：旅馆" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("草稿已被其他操作修改");
  expect(applied).not.toHaveBeenCalled();
});
