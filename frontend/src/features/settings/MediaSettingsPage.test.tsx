import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { MediaSettingsPage } from "./MediaSettingsPage";
import type { MediaCapability, MediaConnection, MediaSettings } from "../../shared/api/client";

const mockDefault = [
  { kind: "IMAGE_GENERATION", capabilityId: "mock-image", version: 0 },
  { kind: "VIDEO_GENERATION", capabilityId: "mock-video", version: 0 },
];

function mount() {
  const queryClient = createQueryClient();
  render(<QueryClientProvider client={queryClient}><MemoryRouter>
    <MediaSettingsPage />
  </MemoryRouter></QueryClientProvider>);
  return queryClient;
}

function settingsFixture(connectionChanges: Partial<MediaConnection> = {}, capabilityChanges: Partial<MediaCapability> = {}): MediaSettings {
  return {
    defaults: [
      { kind: "IMAGE_GENERATION", capabilityId: "portrait", version: 0 },
      { kind: "VIDEO_GENERATION", capabilityId: "mock-video", version: 0 },
    ],
    connections: [{
      id: "openai-1", name: "OpenAI", platform: "OPENAI", enabled: true,
      version: 1, connectionVersion: 1, origin: null, keyMask: "••••7890",
      connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
      ...connectionChanges,
      capabilities: [{
        id: "portrait", name: "Portrait", enabled: true, version: 4, capabilityVersion: 2,
        adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION",
        minimumSeconds: 0, maximumSeconds: 0, maxConcurrent: 2,
        mappingSha256: "a".repeat(64), settings: { quality: "high" },
        ...capabilityChanges,
      }],
    }],
  };
}

describe("MediaSettingsPage", () => {
  it("preserves connection and capability drafts after background updates until each explicit reload", async () => {
    let settings = settingsFixture();
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(settings);
      }),
    );
    const queryClient = mount();
    const user = userEvent.setup();
    const connection = await screen.findByRole("region", { name: "OpenAI" });
    const connectionName = within(connection).getByRole("textbox", { name: "连接名称" });
    await user.clear(connectionName);
    await user.type(connectionName, "Connection draft");
    const replacementKey = within(connection).getByLabelText("替换 API Key（留空则不修改）");
    await user.type(replacementKey, "new-key-draft");
    await user.click(within(connection).getByText("编辑能力参数"));
    const name = within(connection).getByRole("textbox", { name: "能力名称" });
    const limit = within(connection).getByRole("spinbutton", { name: "全局并发上限" });
    await user.clear(name);
    await user.type(name, "Capability draft");
    await user.clear(limit);
    await user.type(limit, "3");
    settings = settingsFixture({ version: 2, name: "Remote connection" }, {
      version: 5, name: "Remote capability", maxConcurrent: 6, settings: { quality: "low" },
    });
    await act(() => queryClient.invalidateQueries({ queryKey: ["settings", "media"] }));
    await waitFor(() => expect(within(connection).getByRole("button", { name: "保存连接" })).toBeDisabled());
    expect(connectionName).toHaveValue("Connection draft");
    expect(replacementKey).toHaveValue("new-key-draft");
    expect(name).toHaveValue("Capability draft");
    expect(limit).toHaveValue(3);
    expect(within(connection).getByRole("button", { name: "保存能力" })).toBeDisabled();
    expect(within(connection).getByRole("button", { name: "保存并发上限" })).toBeDisabled();
    await user.click(within(connection).getByRole("button", { name: "保存连接" }));
    expect(writes).toEqual([]);
    const row = name.closest("li");
    if (!row) throw new Error("Missing capability row");
    await user.click(within(row).getByRole("button", { name: "载入最新版本" }));
    expect(name).toHaveValue("Remote capability");
    expect(limit).toHaveValue(6);
    expect(within(row).getByRole("combobox", { name: "GPT Image 2 质量" })).toHaveValue("low");
    expect(connectionName).toHaveValue("Connection draft");
    await user.click(within(connection).getByRole("button", { name: "载入最新版本" }));
    expect(connectionName).toHaveValue("Remote connection");
    expect(replacementKey).toHaveValue("");
    await user.click(within(connection).getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(writes).toEqual([{
      expectedVersion: 2, name: "Remote connection", enabled: true, origin: null, apiKey: null,
    }]));
  });

  it("advances only the saved capability baseline and preserves the other form's draft", async () => {
    let settings = settingsFixture();
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        settings = settingsFixture({}, { version: 5, name: "Updated portrait" });
        return HttpResponse.json(settings);
      }),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait/concurrency", async ({ request }) => {
        writes.push(await request.json());
        settings = settingsFixture({}, { version: 6, name: "Updated portrait", maxConcurrent: 3 });
        return HttpResponse.json(settings);
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByText("编辑能力参数"));
    const name = screen.getByRole("textbox", { name: "能力名称" });
    const limit = screen.getByRole("spinbutton", { name: "全局并发上限" });
    await user.clear(name);
    await user.type(name, "Updated portrait");
    await user.clear(limit);
    await user.type(limit, "3");
    await user.click(screen.getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "保存能力" })).toBeEnabled());
    expect(limit).toHaveValue(3);
    await user.clear(name);
    await user.type(name, "Next unsaved name");
    await user.click(screen.getByRole("button", { name: "保存并发上限" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "保存并发上限" })).toBeEnabled());
    expect(name).toHaveValue("Next unsaved name");
    expect(writes).toEqual([
      { expectedVersion: 4, name: "Updated portrait", enabled: true, adapterId: "OPENAI_GPT_IMAGE_2", settings: { model: "", quality: "high" } },
      { expectedVersion: 5, maxConcurrent: 3 },
    ]);
    expect(screen.queryByRole("button", { name: "载入最新版本" })).not.toBeInTheDocument();
  });

  it("keeps default actions visible and edits a capability inside its expandable row", async () => {
    let concurrency: unknown;
    let defaultSelection: unknown;
    const settings: MediaSettings = {
      defaults: [
        { kind: "IMAGE_GENERATION", capabilityId: "mock-image", version: 2 },
        { kind: "VIDEO_GENERATION", capabilityId: "mock-video", version: 0 },
      ],
      connections: [{
        id: "openai-1", name: "OpenAI", platform: "OPENAI", enabled: true,
        version: 1, connectionVersion: 1, origin: null, keyMask: "••••7890",
        connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
        capabilities: [{
          id: "portrait", name: "Portrait", enabled: true, version: 4, capabilityVersion: 2,
          adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION",
          minimumSeconds: 0, maximumSeconds: 0, maxConcurrent: 2,
          mappingSha256: "a".repeat(64), settings: { quality: "high" },
        }],
      }],
    };
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-defaults/IMAGE_GENERATION", async ({ request }) => {
        defaultSelection = await request.json();
        return HttpResponse.json({ ...settings, defaults: [
          { kind: "IMAGE_GENERATION", capabilityId: "portrait", version: 3 },
          settings.defaults[1],
        ] });
      }),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait/concurrency", async ({ request }) => {
        concurrency = await request.json();
        return HttpResponse.json(settings);
      }),
    );
    mount();
    const user = userEvent.setup();
    const heading = await screen.findByRole("heading", { name: "Portrait" });
    const row = heading.closest("li");
    if (!row) throw new Error("Missing capability row");
    expect(within(row).getByRole("spinbutton", { name: "全局并发上限" })).not.toBeVisible();
    await user.click(within(row).getByRole("button", { name: "设为默认" }));
    await waitFor(() => expect(defaultSelection).toEqual({ expectedVersion: 2, capabilityId: "portrait" }));
    await waitFor(() => expect(within(row).getByRole("button", { name: "设为默认" })).toBeDisabled());
    await user.click(within(row).getByText("编辑能力参数"));
    expect(within(row).getByRole("combobox", { name: "GPT Image 2 质量" })).toHaveValue("high");
    const limit = within(row).getByRole("spinbutton", { name: "全局并发上限" });
    await user.clear(limit);
    await user.type(limit, "3");
    await user.click(within(row).getByRole("button", { name: "保存并发上限" }));
    await waitFor(() => expect(concurrency).toEqual({ expectedVersion: 4, maxConcurrent: 3 }));
  });

  it("offers a retry when media settings fail to load", async () => {
    let reads = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => {
        reads += 1;
        return reads === 1
          ? HttpResponse.json({ detail: "Unavailable", status: 503 }, { status: 503 })
          : HttpResponse.json({ connections: [], defaults: mockDefault });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "重新读取" }));
    expect(await screen.findByText("尚无媒体连接")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

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
    await user.type(screen.getByRole("textbox", { name: "API Base URL（留空使用官方地址）" }),
      "https://gateway.example.com/proxy/v1");
    await user.click(screen.getByRole("button", { name: "添加连接" }));
    expect(screen.getByRole("button", { name: "正在保存…" })).toBeDisabled();
    release?.();
    expect(await screen.findByText(/密钥 ••••7890 · 已配置、未实测/)).toBeInTheDocument();
    expect(key).toHaveValue("");
    expect(document.body).not.toHaveTextContent("provider-secret-7890");
    expect(window.localStorage.getItem("mediaApiKey")).toBeNull();
    expect(posted).toEqual([{ name: "OpenAI main", platform: "OPENAI",
      origin: "https://gateway.example.com/proxy/v1",
      apiKey: "provider-secret-7890" }]);
  });

  it("saves an updated OpenAI base URL on the versioned connection", async () => {
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        defaults: mockDefault, connections: [{
          id: "openai-1", name: "OpenAI", platform: "OPENAI", enabled: true,
          version: 2, connectionVersion: 1, origin: null, keyMask: "••••7890",
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [],
        }],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ defaults: mockDefault, connections: [] });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "API Base URL（留空使用官方地址）" }),
      "https://gateway.example.com/v1");
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(submitted).toMatchObject({ expectedVersion: 2,
      origin: "https://gateway.example.com/v1", apiKey: null }));
  });

  it("keeps a draft after a version conflict and reloads the server version", async () => {
    let reads = 0;
    const writes: unknown[] = [];
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
      http.put("/api/v1/settings/media-connections/conn-2", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json({
          title: "冲突", detail: "版本过期", code: "MEDIA_CAPABILITY_CONFLICT", status: 409,
        }, { status: 409 });
      }),
    );
    mount();
    const user = userEvent.setup();
    const name = (await screen.findAllByRole("textbox", { name: "连接名称" }))[0];
    if (!name) throw new Error("Missing saved connection editor");
    await user.clear(name);
    await user.type(name, "My draft");
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("当前草稿已保留");
    expect(name).toHaveValue("My draft");
    await waitFor(() => expect(reads).toBeGreaterThan(1));
    await waitFor(() => expect(screen.getByRole("button", { name: "保存连接" })).toBeDisabled());
    expect(writes).toHaveLength(1);
    expect(writes[0]).toMatchObject({ expectedVersion: 0, name: "My draft" });
    await user.click(screen.getByRole("button", { name: "载入最新版本" }));
    expect(name).toHaveValue("Local Comfy");
    await user.click(screen.getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[1]).toMatchObject({ expectedVersion: 1, name: "Local Comfy" });
  });

  it("keeps the capability CAS baseline after a concurrency conflict until explicit reload", async () => {
    let settings = settingsFixture();
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait/concurrency", async ({ request }) => {
        writes.push(await request.json());
        if (writes.length === 1) {
          settings = settingsFixture({}, { version: 5, name: "Remote portrait", maxConcurrent: 6 });
          return HttpResponse.json({ title: "冲突", detail: "版本过期", code: "MEDIA_CAPABILITY_CONFLICT", status: 409 }, {
            status: 409, headers: { "Content-Type": "application/problem+json" },
          });
        }
        settings = settingsFixture({}, { version: 6, name: "Remote portrait", maxConcurrent: 6 });
        return HttpResponse.json(settings);
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByText("编辑能力参数"));
    const name = screen.getByRole("textbox", { name: "能力名称" });
    const limit = screen.getByRole("spinbutton", { name: "全局并发上限" });
    await user.clear(name);
    await user.type(name, "Unsaved portrait");
    await user.clear(limit);
    await user.type(limit, "3");
    await user.click(screen.getByRole("button", { name: "保存并发上限" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("版本过期");
    await waitFor(() => expect(screen.getByRole("button", { name: "保存并发上限" })).toBeDisabled());
    expect(writes).toEqual([{ expectedVersion: 4, maxConcurrent: 3 }]);
    expect(name).toHaveValue("Unsaved portrait");
    expect(limit).toHaveValue(3);
    expect(screen.getByRole("button", { name: "保存能力" })).toBeDisabled();
    await user.click(await screen.findByRole("button", { name: "载入最新版本" }));
    expect(name).toHaveValue("Remote portrait");
    expect(limit).toHaveValue(6);
    await user.click(screen.getByRole("button", { name: "保存并发上限" }));
    await waitFor(() => expect(writes).toEqual([
      { expectedVersion: 4, maxConcurrent: 3 }, { expectedVersion: 5, maxConcurrent: 6 },
    ]));
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

  it("publishes only the fixed GPT Image 2 mapping with an allowed quality", async () => {
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        defaults: mockDefault, connections: [{
          id: "openai-1", name: "OpenAI", platform: "OPENAI", enabled: true,
          version: 0, connectionVersion: 1, origin: null, keyMask: "••••7890",
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [],
        }],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections/openai-1/capabilities", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ defaults: mockDefault, connections: [] });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "新能力名称" }), "Portrait image");
    expect(screen.getByRole("combobox", { name: "固定适配器" })).toHaveValue("OPENAI_GPT_IMAGE_2");
    await user.selectOptions(screen.getByRole("combobox", { name: "GPT Image 2 质量" }), "high");
    await user.click(screen.getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Portrait image",
      adapterId: "OPENAI_GPT_IMAGE_2", settings: { model: "", quality: "high" } }));
  });

  it("publishes the fixed Nano Banana 2 image capability", async () => {
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({
        defaults: mockDefault, connections: [{
          id: "google-1", name: "Google Gemini", platform: "GOOGLE", enabled: true,
          version: 0, connectionVersion: 1, origin: null, keyMask: "••••7890",
          connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [],
        }],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections/google-1/capabilities", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ defaults: mockDefault, connections: [] });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "新能力名称" }), "Nano Banana 2");
    expect(screen.getByRole("combobox", { name: "固定适配器" }))
      .toHaveValue("GOOGLE_NANO_BANANA_2");
    await user.click(screen.getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Nano Banana 2",
      adapterId: "GOOGLE_NANO_BANANA_2", settings: { model: "" } }));
  });
});
