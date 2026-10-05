import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { CreateMediaStyleRequest, MediaStyle, UpdateMediaStyleRequest } from "../../shared/api/client";
import { server } from "../../test/server";
import { MediaStyleSettingsSection } from "./MediaStyleSettingsSection";
import { SystemSettingsPage } from "./SystemSettingsPage";

const endpoint = "/api/v1/settings/media-styles";
const preset: MediaStyle = { id: "style-photo", name: "写实摄影", category: "摄影", enabled: true, version: 1,
  thumbnailUrl: "/api/v1/media-styles/style-photo/thumbnail?v=1", builtIn: true, promptSuffix: "Natural photographic light." };

function renderSettings() {
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><MediaStyleSettingsSection enabled /></MemoryRouter></QueryClientProvider>);
}

describe("MediaStyleSettingsSection", () => {
  beforeEach(() => {
    const NativeUrl = URL;
    vi.stubGlobal("URL", class extends NativeUrl {
      static createObjectURL() { return "blob:style-preview"; }
      static revokeObjectURL() { /* No browser blob storage in jsdom. */ }
    });
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "token" })));
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

  it("supports the styles deep link and loads this category on demand", async () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get(endpoint, () => { reads(); return HttpResponse.json([preset]); }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/settings/general?tab=styles"]}><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole("tab", { name: "风格" })).toHaveAttribute("aria-selected", "true");
    await screen.findByRole("article", { name: "写实摄影" });
    expect(reads).toHaveBeenCalledOnce();
    expect(screen.getByRole("img", { name: "写实摄影效果预览" })).toHaveAttribute("src", preset.thumbnailUrl);
  });

  it("retains a newly saved style when preview upload fails and retries without duplicate create", async () => {
    const creates = vi.fn(); const uploads = vi.fn(); let uploadFail = true;
    server.use(http.get(endpoint, () => HttpResponse.json([])),
      http.post(endpoint, async ({ request }) => {
        const input = await request.json() as CreateMediaStyleRequest;
        creates(input);
        return HttpResponse.json({ ...input, id: "custom-style", version: 0, builtIn: false, thumbnailUrl: null }, { status: 201 });
      }));
    // Inspect browser FormData before Node fetch attempts to serialize jsdom File objects.
    const fetch = globalThis.fetch;
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
      if (input === `${endpoint}/custom-style/thumbnail`) {
        expect(new Headers(init?.headers).has("Content-Type")).toBe(false);
        expect(init?.body).toBeInstanceOf(FormData);
        const form = init?.body as FormData;
        expect(form.get("file")).toHaveProperty("name", "preview.png");
        uploads(form.get("expectedVersion"));
        return uploadFail ? HttpResponse.json({ status: 503, code: "UPLOAD_FAILED" }, { status: 503 })
          : HttpResponse.json({ id: "custom-style", name: "自定义水彩", category: "绘画", promptSuffix: "Soft watercolor washes.",
            enabled: true, version: 1, builtIn: false, thumbnailUrl: "/api/v1/media-styles/custom-style/thumbnail?v=1" });
      }
      return fetch(input, init);
    });
    renderSettings();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加风格" }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("风格名称"), "自定义水彩");
    await user.type(within(modal).getByLabelText("分类"), "绘画");
    await user.type(within(modal).getByLabelText("风格提示词"), "Soft watercolor washes.");
    await user.upload(within(modal).getByLabelText("效果配图"), new File(["synthetic-preview"], "preview.png", { type: "image/png" }));
    await user.click(within(modal).getByRole("button", { name: "保存风格" }));
    expect(await within(modal).findByRole("alert")).toHaveTextContent("风格资料已保存");
    expect(creates).toHaveBeenCalledOnce();
    uploadFail = false;
    await user.click(within(modal).getByRole("button", { name: "保存风格" }));
    await waitFor(() => expect(uploads).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(creates).toHaveBeenCalledOnce(); expect(uploads).toHaveBeenCalledTimes(2);
    expect(uploads).toHaveBeenLastCalledWith("0");
    expect(screen.getByRole("img", { name: "自定义水彩效果预览" })).toHaveAttribute("src", "/api/v1/media-styles/custom-style/thumbnail?v=1");
  });

  it("refreshes a style CAS conflict while retaining local edits", async () => {
    let stored = preset; const writes = vi.fn(); let conflict = true;
    server.use(http.get(endpoint, () => HttpResponse.json([stored])),
      http.put(`${endpoint}/${preset.id}`, async ({ request }) => {
        const input = await request.json() as UpdateMediaStyleRequest; writes(input);
        if (conflict) { stored = { ...preset, name: "远端摄影", version: 2 }; return HttpResponse.json({ status: 409, code: "STYLE_CONFLICT" }, { status: 409 }); }
        stored = { ...stored, ...input, version: 3 }; return HttpResponse.json(stored);
      }));
    renderSettings(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑风格" }));
    const modal = screen.getByRole("dialog"); const name = within(modal).getByLabelText("风格名称");
    await user.clear(name); await user.type(name, "本地摄影");
    await user.click(within(modal).getByRole("button", { name: "保存风格" }));
    await within(modal).findByText("风格已被其他操作修改");
    expect(name).toHaveValue("本地摄影");
    expect(within(modal).getByRole("button", { name: "保存风格" })).toBeDisabled();
    await user.click(within(modal).getByRole("button", { name: "刷新版本并保留输入" }));
    await waitFor(() => expect(within(modal).getByRole("button", { name: "保存风格" })).toBeEnabled());
    conflict = false;
    await user.click(within(modal).getByRole("button", { name: "保存风格" }));
    await waitFor(() => expect(writes).toHaveBeenLastCalledWith(expect.objectContaining({ name: "本地摄影", expectedVersion: 2 })));
  });

  it("disables styles through versioned settings", async () => {
    const write = vi.fn();
    server.use(http.get(endpoint, () => HttpResponse.json([preset])), http.put(`${endpoint}/${preset.id}`, async ({ request }) => {
      write(await request.json()); return HttpResponse.json({ ...preset, enabled: false, version: 2 });
    }));
    renderSettings();
    await userEvent.setup().click(await screen.findByRole("button", { name: "停用" }));
    await screen.findByText("已停用");
    expect(write).toHaveBeenCalledWith(expect.objectContaining({ enabled: false, expectedVersion: 1 }));
  });
});
