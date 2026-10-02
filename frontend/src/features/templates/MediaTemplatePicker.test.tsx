import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { MediaTemplate, MediaTemplateImport } from "../../shared/api/client";
import { changeControl, selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { MediaTemplateForm } from "./MediaTemplateForm";
import { MediaTemplatePicker, type TemplatePickerContext } from "./MediaTemplatePicker";

const template: MediaTemplate = { id: "my-template", targetKind: "IMAGE", scope: "PERSONAL", name: "Watercolor", prompt: "Watercolor style", images: [],
  version: 1, createdAt: "2026-10-02T00:00:00Z", updatedAt: "2026-10-02T00:00:00Z" };
const context: TemplatePickerContext = { projectId: "project", targetKind: "IMAGE", fields: { prompt: "draft", parameters: {}, durationSeconds: null,
  capabilityId: null, videoInputMode: null, mediaInputs: [], mentions: [] }, seedImages: [], onApply: vi.fn(), onClose: vi.fn(), onBusy: vi.fn() };
function mount(component: React.ReactNode) { render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{component}</QueryClientProvider>); }
function csrf() { server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" }))); }
afterEach(() => vi.restoreAllMocks());

describe("media templates", () => {
  it("filters wrong types, searches templates, separates mine and applies only after explicit use", async () => {
    const imported: MediaTemplateImport = { templateId: template.id, templateVersion: 1, targetKind: "IMAGE", prompt: template.prompt, images: [] };
    const apply = vi.fn(); const close = vi.fn(); const importing = vi.fn(); csrf();
    server.use(http.get("/api/v1/media-templates", ({ request }) => { expect(new URL(request.url).searchParams.get("targetKind")).toBe("IMAGE"); return HttpResponse.json({ items: [template,
      { ...template, id: "system", name: "Studio", scope: "SYSTEM" }, { ...template, id: "video", name: "Video only", targetKind: "VIDEO" }] }); }),
    http.post("/api/v1/projects/project/media-templates/my-template/import", async ({ request }) => { importing(await request.json()); return HttpResponse.json(imported); }));
    mount(<MediaTemplatePicker {...context} onApply={apply} onClose={close} />);
    const user = userEvent.setup();
    expect(await screen.findByRole("button", { name: /Watercolor/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Video only/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "我的模板" })); expect(screen.queryByRole("button", { name: /Studio/ })).not.toBeInTheDocument();
    await user.type(screen.getByRole("searchbox"), "color");
    await user.click(screen.getByRole("button", { name: /Watercolor/ })); expect(importing).not.toHaveBeenCalled();
    expect(screen.getByText(/只替换提示词/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "使用模板" }));
    await waitFor(() => expect(apply).toHaveBeenCalledWith(imported, { videoInputMode: null, imageSlots: [] })); expect(close).toHaveBeenCalledTimes(1);
  });
  it("retains draft context and reuse key after an import failure", async () => {
    const keys: string[] = []; let fail = true; const close = vi.fn(); csrf();
    server.use(http.get("/api/v1/media-templates", () => HttpResponse.json({ items: [template] })),
      http.post("/api/v1/projects/project/media-templates/my-template/import", async ({ request }) => {
        keys.push((await request.json() as { commandKey: string }).commandKey);
        if (fail) return HttpResponse.json({ title: "Synthetic failure", status: 503, code: "TEMPORARY" }, { status: 503, headers: { "Content-Type": "application/problem+json" } });
        return HttpResponse.json({ templateId: template.id, templateVersion: 1, targetKind: "IMAGE", prompt: template.prompt, images: [] });
      }));
    mount(<MediaTemplatePicker {...context} onClose={close} />); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /Watercolor/ })); await user.click(screen.getByRole("button", { name: "使用模板" }));
    await screen.findByRole("alert"); expect(close).not.toHaveBeenCalled(); fail = false;
    await user.click(screen.getByRole("button", { name: "使用模板" })); await waitFor(() => expect(close).toHaveBeenCalled());
    expect(keys).toHaveLength(2); expect(keys[0]).toBe(keys[1]);
  });
  it("retains form input on CAS conflict and refreshes only the version before retry", async () => {
    const saved = vi.fn(); const payloads: unknown[] = []; let conflict = true; csrf();
    server.use(http.patch("/api/v1/media-templates/my-template", async ({ request }) => {
      const payload = await request.json(); payloads.push(payload);
      if (conflict) return HttpResponse.json({ title: "Synthetic conflict", status: 409, code: "TEMPLATE_CONFLICT" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      return HttpResponse.json({ ...template, ...payload as object, version: 3 });
    }), http.get("/api/v1/media-templates", () => HttpResponse.json({ items: [{ ...template, version: 2, prompt: "remote edit" }] })));
    mount(<MediaTemplateForm template={template} scope="PERSONAL" targetKind="IMAGE" lockTargetKind onClose={vi.fn()} onSaved={saved} />);
    const user = userEvent.setup(); await user.clear(screen.getByLabelText("模板提示词")); await user.type(screen.getByLabelText("模板提示词"), "my retained edit");
    await user.click(screen.getByRole("button", { name: "保存模板" })); await screen.findByRole("alert");
    expect(screen.getByLabelText("模板提示词")).toHaveValue("my retained edit");
    expect(screen.getByRole("combobox", { name: "模板类型" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "刷新版本并保留表单" })); conflict = false;
    await waitFor(() => expect(screen.getByRole("button", { name: "保存模板" })).toBeEnabled()); await user.click(screen.getByRole("button", { name: "保存模板" }));
    await waitFor(() => expect(saved).toHaveBeenCalled()); expect(payloads[1]).toMatchObject({ expectedVersion: 2, prompt: "my retained edit", imageIds: [] });
  });
  it("copies ordered current project references for a new personal template and locks its type", async () => {
    const copies: string[] = []; const save = vi.fn(); csrf();
    server.use(http.post("/api/v1/media-templates/images/from-version", async ({ request }) => {
      const input = await request.json() as { versionId: string }; copies.push(input.versionId);
      return HttpResponse.json({ id: `copy-${input.versionId}`, width: 100, height: 100, byteSize: 100, contentType: "image/png", thumbnailUrl: "/synthetic.png", contentUrl: "/synthetic.png" });
    }), http.post("/api/v1/media-templates", async ({ request }) => { save(await request.json()); return HttpResponse.json(template); }));
    mount(<MediaTemplateForm scope="PERSONAL" targetKind="VIDEO" lockTargetKind seedPrompt="Camera motion" projectId="project"
      seedImages={[{ versionId: "first", title: "First", thumbnailUrl: "/first.png" }, { versionId: "second", title: "Second", thumbnailUrl: "/second.png" }]} onClose={vi.fn()} onSaved={vi.fn()} />);
    const user = userEvent.setup(); await user.type(screen.getByLabelText("模板名称"), "Motion");
    await user.click(screen.getByRole("button", { name: "前移图片 2" }));
    await user.click(screen.getByRole("button", { name: "保存模板" })); await waitFor(() => expect(save).toHaveBeenCalled());
    expect(copies).toEqual(["second", "first"]); expect(save).toHaveBeenCalledWith({ name: "Motion", targetKind: "VIDEO", prompt: "Camera motion", imageIds: ["copy-second", "copy-first"] });
  });
  it("requires explicit video mode before importing template images", async () => {
    const importing = vi.fn(); const imageTemplate = { ...template, targetKind: "VIDEO", images: [{ id: "image", contentType: "image/png", byteSize: 100, width: 100, height: 100, thumbnailUrl: "/synthetic.png", contentUrl: "/synthetic.png" }] };
    server.use(http.get("/api/v1/media-templates", () => HttpResponse.json({ items: [imageTemplate] })), http.post("/api/v1/projects/project/media-templates/my-template/import", () => { importing(); return HttpResponse.json({}); }));
    mount(<MediaTemplatePicker {...context} targetKind="VIDEO" fields={{ ...context.fields, videoInputMode: "TEXT" }} capability={{
      id: "mock", name: "Mock video", enabled: true, version: 0, capabilityVersion: 1, adapterId: "MOCK_VIDEO", kind: "VIDEO_GENERATION", minimumSeconds: 1, maximumSeconds: 10,
      maxReferenceAudios: 0, maxReferenceImages: 2, supportedVideoInputModes: ["TEXT", "START_END"], defaultVideoInputMode: "TEXT", supportsEndFrame: true,
      supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], supportsImageMask: false, supportsTransparentBackground: false, mappingSha256: "a".repeat(64), settings: {},
    }} />);
    const user = userEvent.setup(); await user.click(await screen.findByRole("button", { name: /Watercolor/ }));
    expect(screen.getByRole("button", { name: "使用模板" })).toBeDisabled(); expect(importing).not.toHaveBeenCalled();
    await selectValue(screen.getByRole("combobox", { name: "参考图片输入模式" }), "START_END");
    expect(screen.getByRole("button", { name: "使用模板" })).toBeEnabled();
    expect(within(screen.getByRole("dialog")).getByText(/移除原引用对应的画布连线/)).toBeInTheDocument();
  });
  it("cleans up only newly uploaded images on remove and cancel", async () => {
    const deletions: string[] = []; const close = vi.fn(); let counter = 0; csrf();
    const image = { id: "existing", contentType: "image/png", byteSize: 100, width: 100, height: 100, thumbnailUrl: "/synthetic.png", contentUrl: "/synthetic.png" };
    // jsdom File/FormData cannot be serialized by undici; intercept only the multipart boundary.
    const interceptedFetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === "/api/v1/media-templates/images") { expect(init?.body).toBeInstanceOf(FormData); return HttpResponse.json({ ...image, id: `upload-${++counter}` }); }
      return interceptedFetch(input, init);
    });
    server.use(http.delete("/api/v1/media-templates/images/:id", ({ params }) => { deletions.push(String(params.id)); return new HttpResponse(null, { status: 204 }); }));
    mount(<MediaTemplateForm template={{ ...template, images: [image] }} scope="PERSONAL" targetKind="IMAGE" onClose={close} onSaved={vi.fn()} />);
    const user = userEvent.setup();
    await changeControl(screen.getByLabelText("添加图片"), { target: { files: [new File(["synthetic"], "a.png", { type: "image/png" }), new File(["synthetic"], "b.png", { type: "image/png" })] } });
    await waitFor(() => { const alert = screen.queryByRole("alert"); if (alert) throw new Error(alert.textContent ?? "upload failure"); expect(counter).toBe(2); });
    expect(screen.getAllByRole("listitem")).toHaveLength(3);
    await user.click(screen.getByRole("button", { name: "移除图片 3" }));
    await waitFor(() => expect(screen.getAllByRole("listitem")).toHaveLength(2));
    await user.click(screen.getByRole("button", { name: "取消" })); await waitFor(() => expect(close).toHaveBeenCalled());
    expect(deletions).toEqual(["upload-2", "upload-1"]); expect(deletions).not.toContain("existing");
  });
});
