import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import type { ThirdPartyPromptEntry } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaTemplatePicker, type TemplatePickerContext } from "./MediaTemplatePicker";
import { ThirdPartyPromptCatalog } from "./ThirdPartyPromptCatalog";

const entry: ThirdPartyPromptEntry = { id: "native:1", sourceId: "native", targetKind: "IMAGE", video: null, version: 2,
  cachedAt: "2026-10-08T00:00:00Z", updatedAt: "2026-10-08T00:00:00Z", image: { id: "native:1", sourceId: "native", title: "Cached portrait",
    prompt: "Synthetic portrait", description: "Synthetic description", coverUrl: "https://example.com/cover.png", referenceImageUrls: [], tags: ["portrait"],
    author: "Artist", sourceUrl: "https://example.com/original", createdAt: "", imageMode: "generate", imageModel: "source-model" } };
const context: TemplatePickerContext = { projectId: "project", targetKind: "IMAGE", fields: { prompt: "draft", parameters: {}, durationSeconds: null,
  capabilityId: null, videoInputMode: null, mediaInputs: [], mentions: [] }, seedImages: [], onApply: vi.fn(), onClose: vi.fn(), onBusy: vi.fn() };
function mount(props = context) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MediaTemplatePicker {...props} /></QueryClientProvider>);
}
function catalogue() {
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "fixture" })),
    http.get("/api/v1/media-templates", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/media-templates/third-party/sources", () => HttpResponse.json({ items: [] })),
    http.get("/api/v1/media-templates/third-party", () => HttpResponse.json({ items: [entry], total: 1, offset: 0, limit: 50 })));
}
describe("third-party prompt catalogue", () => {
  it("shows a generated video with manual playback and blocks importing unpublished references", async () => {
    catalogue();
    const video: ThirdPartyPromptEntry = { ...entry, targetKind: "VIDEO", image: null, video: {
      id: entry.id, sourceId: entry.sourceId, title: "Synthetic reference clip", prompt: "Animate @Image1", description: "", coverUrl: "https://example.com/poster.jpg",
      tags: [], author: "Fixture author", sourceUrl: "https://example.com/original", createdAt: "", videoMode: "image_to_video", videoModel: "source-model",
      references: [], imageGeneration: null, previewVideoUrl: "https://example.com/output.mp4", missingReferences: [{ kind: "IMAGE", label: "IMAGE 1" }],
    } };
    server.use(http.get("/api/v1/media-templates/third-party", () => HttpResponse.json({ items: [video], total: 1, offset: 0, limit: 50 })));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const apply = vi.fn(); const imported = vi.fn();
    server.use(http.post("/api/v1/projects/project/media-templates/third-party/import", () => { imported(); return HttpResponse.json({}); }));
    render(<QueryClientProvider client={client}><ThirdPartyPromptCatalog kind="VIDEO" context={{ ...context, targetKind: "VIDEO", onApply: apply }} /></QueryClientProvider>);
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: "Synthetic reference clip" }));
    const preview = screen.getByLabelText("生成效果视频");
    expect(preview).toHaveAttribute("src", "https://example.com/output.mp4");
    expect(preview).toHaveAttribute("controls"); expect(preview).toHaveAttribute("preload", "none"); expect(preview).not.toHaveAttribute("autoplay");
    expect(screen.getByText(/来源未提供所需参考素材/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "使用模板" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "使用模板" })); expect(imported).not.toHaveBeenCalled(); expect(apply).not.toHaveBeenCalled();
  });
  it("previews cached attribution and applies through the current editor without generating", async () => {
    catalogue(); const apply = vi.fn(); const close = vi.fn(); const imported = { templateId: "id", templateVersion: 2, targetKind: "IMAGE",
      prompt: "Synthetic portrait", images: [], references: [], videoInputMode: null };
    server.use(http.post("/api/v1/projects/project/media-templates/third-party/import", () => HttpResponse.json(imported)));
    mount({ ...context, onApply: apply, onClose: close }); const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "第三方模板" }));
    await user.click(await screen.findByRole("button", { name: "Cached portrait" }));
    expect(screen.getByRole("link", { name: "查看原始来源" })).toHaveAttribute("href", "https://example.com/original");
    expect(apply).not.toHaveBeenCalled(); await user.click(screen.getByRole("button", { name: "使用模板" }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith(imported, { videoInputMode: null, imageSlots: [] })); expect(close).toHaveBeenCalled();
  });
  it("retains the selected template and command key after a lost import response", async () => {
    catalogue(); const calls: Record<string, unknown>[] = []; let failures = 0;
    server.use(http.post("/api/v1/projects/project/media-templates/third-party/import", async ({ request }) => {
      calls.push(await request.json() as Record<string, unknown>); failures++;
      return failures === 1 ? HttpResponse.json({ detail: "Synthetic failure" }, { status: 503, headers: { "Content-Type": "application/problem+json" } }) : HttpResponse.json({
        templateId: "id", templateVersion: 2, targetKind: "IMAGE", prompt: "Synthetic portrait", images: [] });
    }));
    const apply = vi.fn(); mount({ ...context, onApply: apply }); const user = userEvent.setup();
    await user.click(screen.getByRole("tab", { name: "第三方模板" })); await user.click(await screen.findByRole("button", { name: "Cached portrait" }));
    await user.click(screen.getByRole("button", { name: "使用模板" })); await screen.findByText("Synthetic failure");
    expect(apply).not.toHaveBeenCalled(); await user.click(screen.getByRole("button", { name: "使用模板" }));
    await waitFor(() => expect(apply).toHaveBeenCalled()); expect(calls[0]).toEqual(calls[1]); expect(calls[0]).toMatchObject({ promptId: entry.id, expectedVersion: 2 });
  });
});
