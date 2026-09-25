import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { MediaSettingsPage } from "./MediaSettingsPage";

const mockDefault = [
  { kind: "IMAGE_GENERATION", capabilityId: "mock-image", version: 0 },
  { kind: "VIDEO_GENERATION", capabilityId: "mock-video", version: 0 },
];

function mount() {
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter>
    <MediaSettingsPage />
  </MemoryRouter></QueryClientProvider>);
}

describe("MediaSettingsPage", () => {
  it("keeps a new key in memory and shows the masked, untested connection", async () => {
    const posted: unknown[] = [];
    let release: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { release = resolve; });
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: mockDefault })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections", async ({ request }) => {
        posted.push(await request.json());
        await pending;
        return HttpResponse.json({ defaults: mockDefault, connections: [{
          id: "conn-1", name: "OpenAI main", platform: "OPENAI", enabled: true,
          version: 0, connectionVersion: 1, origin: null, keyMask: "••••7890",
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
          capabilities: [],
        }] });
      }),
    );
    mount();
    const user = userEvent.setup();
    expect(await screen.findByText("尚无媒体连接")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "连接名称" }), "OpenAI main");
    await user.selectOptions(screen.getByRole("combobox", { name: "平台" }), "OPENAI");
    const key = screen.getByLabelText("API Key") as HTMLInputElement;
    await user.type(key, "provider-secret-7890");
    await user.click(screen.getByRole("button", { name: "添加连接" }));
    expect(screen.getByRole("button", { name: "正在保存…" })).toBeDisabled();
    release?.();
    expect(await screen.findByText(/密钥 ••••7890 · 已配置、未实测/)).toBeInTheDocument();
    expect(key).toHaveValue("");
    expect(document.body).not.toHaveTextContent("provider-secret-7890");
    expect(window.localStorage.getItem("mediaApiKey")).toBeNull();
    expect(posted).toEqual([{ name: "OpenAI main", platform: "OPENAI", origin: null,
      apiKey: "provider-secret-7890" }]);
  });

  it("keeps a draft after a version conflict and reloads the server version", async () => {
    let reads = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => {
        reads += 1;
        return HttpResponse.json({ defaults: mockDefault, connections: [{
          id: "conn-2", name: "Local Comfy", platform: "COMFYUI", enabled: true,
          version: reads === 1 ? 0 : 1, connectionVersion: 1,
          origin: "http://127.0.0.1:8188", keyMask: null,
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
          capabilities: [],
        }] });
      }),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/conn-2", () => HttpResponse.json({
        title: "冲突", detail: "版本过期", code: "MEDIA_CAPABILITY_CONFLICT", status: 409,
      }, { status: 409 })),
    );
    mount();
    const user = userEvent.setup();
    const name = (await screen.findAllByRole("textbox", { name: "连接名称" }))[0];
    if (!name) throw new Error("Missing saved connection editor");
    await user.clear(name);
    await user.type(name, "My draft");
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("已刷新版本");
    expect(name).toHaveValue("My draft");
    await waitFor(() => expect(reads).toBeGreaterThan(1));
  });

  it("publishes fixed ComfyUI video model filenames from the settings form", async () => {
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        defaults: mockDefault, connections: [{
          id: "comfy-1", name: "ComfyUI", platform: "COMFYUI", enabled: true,
          version: 0, connectionVersion: 1, origin: "http://127.0.0.1:8188",
          keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
          capabilities: [],
        }],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections/comfy-1/capabilities", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ defaults: mockDefault, connections: [] });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "新能力名称" }), "Wan video");
    await user.selectOptions(screen.getByRole("combobox", { name: "固定适配器" }), "COMFY_VIDEO_V1");
    await user.type(screen.getByRole("textbox", { name: "视频扩散模型文件名" }), "wan.safetensors");
    await user.type(screen.getByRole("textbox", { name: "文本编码器文件名" }), "text.safetensors");
    await user.type(screen.getByRole("textbox", { name: "VAE 文件名" }), "vae.safetensors");
    await user.type(screen.getByRole("textbox", { name: "CLIP Vision 文件名" }), "vision.safetensors");
    await user.click(screen.getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Wan video", adapterId: "COMFY_VIDEO_V1",
      settings: { diffusionModel: "wan.safetensors", textEncoder: "text.safetensors",
        vae: "vae.safetensors", clipVision: "vision.safetensors" } }));
  });
});
