import { QueryClientProvider } from "@tanstack/react-query";
import { act,fireEvent,render,screen,waitFor,within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http,HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { describe,expect,it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaCapability,MediaConnection,MediaSettings } from "../../shared/api/client";
import { clickControl,selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { definition as comfyDefinition, graph as comfyGraph } from "./comfyWorkflowFixture";
import { MediaSettingsPage } from "./MediaSettingsPage";

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
        minimumSeconds: 0, maximumSeconds: 0, maxReferenceAudios: 0, maxReferenceVideos: 0, maxReferenceImages: 1,
        supportedVideoInputModes: [], defaultVideoInputMode: null,
        supportsEndFrame: false,
        supportedImageAspectRatios: ["AUTO", "1:1"], supportedImageResolutions: ["1K", "2K", "4K"],
        supportedImageQualities: ["low", "medium", "high"], supportsTransparentBackground: true,
        supportsImageMask: true,
        mappingSha256: "a".repeat(64), settings: { quality: "high" },
        ...capabilityChanges,
      } as MediaCapability],
    }],
  };
}

describe("MediaSettingsPage", () => {
  it("shows platform-specific address guidance without submitting the connection", async () => {
    const requests: string[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: mockDefault })),
      http.post("/api/v1/settings/media-connections", () => { requests.push("create"); return HttpResponse.json(settingsFixture()); }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    const dialog = screen.getByRole("dialog");
    const platforms = [
      ["COMFYUI", "代理路径和其中的 Key 直接填入地址", "ComfyUI 地址"],
      ["OPENAI", "系统不会自动补 /v1", "API Base URL（留空使用官方地址）"],
      ["GOOGLE", "服务商要求 /v1beta 时需显式填写", "API Base URL（留空使用官方地址）"],
      ["RUNNINGHUB", "不要添加 /v1、/openapi/v2", "RunningHub API 地址"],
      ["ARK", "已包含 /api/v3", "固定 API 地址"],
      ["VOLCENGINE", "固定 TTS 地址", "固定 API 地址"],
      ["AUTODL", "分组为 ComfyUI 的 Token", "固定 API 地址"],
    ] as const;
    for (const [platform, hint, label] of platforms) {
      await selectValue(within(dialog).getByRole("combobox", { name: "平台" }), platform);
      expect(within(dialog).getByRole("textbox", { name: label })).toBeInTheDocument();
      const help = within(dialog).getByRole("button", { name: "地址填写说明" });
      await user.hover(help);
      expect(await screen.findByRole("tooltip")).toHaveTextContent(hint);
      await user.keyboard("{Escape}");
      await waitFor(() => expect(screen.queryByRole("tooltip")).not.toBeInTheDocument());
    }
    const help = within(dialog).getByRole("button", { name: "地址填写说明" });
    await user.pointer([{ keys: "[TouchA>]", target: help }, { keys: "[/TouchA]", target: help }]);
    expect(await screen.findByRole("tooltip")).toHaveTextContent("AutoDL");
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("tooltip")).not.toBeInTheDocument());
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(requests).toEqual([]);
  });

  it("submits a complete remote ComfyUI URL without a separate API key", async () => {
    const posted: unknown[] = [];
    const endpoint = "https://comfy.example.com/proxy/synthetic-key";
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: mockDefault })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections", async ({ request }) => {
        posted.push(await request.json());
        return HttpResponse.json(settingsFixture({ platform: "COMFYUI", origin: "https://comfy.example.com/[configured-path]", capabilities: [] }));
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: "连接名称" }), "Remote Comfy");
    await selectValue(within(dialog).getByRole("combobox", { name: "平台" }), "COMFYUI");
    await user.type(within(dialog).getByRole("textbox", { name: "ComfyUI 地址" }), endpoint);
    expect(within(dialog).queryByLabelText("API Key")).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(posted).toEqual([{ name: "Remote Comfy", platform: "COMFYUI", origin: endpoint, apiKey: null }]));
  });

  it("preserves the saved ComfyUI endpoint when editing its redacted address", async () => {
    const posted: unknown[] = [];
    const origin = "https://comfy.example.com/[configured-path]";
    const fixture = settingsFixture({ platform: "COMFYUI", origin, capabilities: [] });
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1", async ({ request }) => {
        posted.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑连接" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("textbox", { name: "ComfyUI 地址" })).toHaveValue(origin);
    await user.click(within(dialog).getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(posted).toEqual([{ expectedVersion: 1, name: "OpenAI", enabled: true, origin, apiKey: null }]));
  });

  it("lets administrators configure a RunningHub HTTPS origin outside the official domains", async () => {
    const posted: unknown[] = [];
    const fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub", origin: "https://custom-api.example.com", capabilities: [] });
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: mockDefault })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections", async ({ request }) => {
        posted.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: "连接名称" }), "RunningHub");
    await selectValue(within(dialog).getByRole("combobox", { name: "平台" }), "RUNNINGHUB");
    await user.type(within(dialog).getByRole("textbox", { name: "RunningHub API 地址" }), "https://custom-api.example.com");
    await user.type(within(dialog).getByLabelText("API Key"), "fixture-key");
    await user.click(within(dialog).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(posted).toEqual([{ name: "RunningHub", platform: "RUNNINGHUB", origin: "https://custom-api.example.com", apiKey: "fixture-key" }]));
  });
  it("imports a RunningHub app, previews its fields and publishes on explicit submit without a review option", async () => {
    const fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub", origin: "https://www.runninghub.ai", capabilities: [] });
    const published: unknown[] = [];
    const definition = { schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "style", label: "创作风格", type: "SELECT", nodeId: "1", fieldName: "style", required: true, advanced: false,
        defaultValue: "photo", options: [{ label: "写实", value: "photo" }, { label: "插画", value: "illustration" }] },
        { key: "strength", label: "强度", type: "NUMBER", nodeId: "2", fieldName: "strength", required: false, advanced: false, defaultValue: 1 }],
      fixedBindings: [{ nodeId: "2", fieldName: "seed", value: 8 }],
      outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }] };
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections/openai-1/runninghub/preview", async ({ request }) => {
        expect(await request.json()).toEqual({ targetType: "AI_APP", targetId: "123", kind: "IMAGE_GENERATION" });
        return HttpResponse.json({ definition, warnings: ["确认字段后发布"] });
      }),
      http.post("/api/v1/settings/media-connections/openai-1/capabilities", async ({ request }) => {
        published.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    const dialog = screen.getByRole("dialog");
    expect(dialog).toHaveClass("runninghub-capability-dialog");
    await user.type(within(dialog).getByRole("textbox", { name: "新能力名称" }), "背景应用");
    await selectValue(within(dialog).getByRole("combobox", { name: "目标类型" }), "AI_APP");
    await user.type(within(dialog).getByRole("textbox", { name: "真实目标 ID" }), "123");
    await user.click(within(dialog).getByRole("button", { name: "自动发现参数" }));
    const importedField = await within(dialog).findByRole("row", { name: "创作风格" });
    expect(importedField).toBeVisible();
    expect(within(dialog).getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 创作风格 · 节点 2 · 强度");
    expect(within(importedField).getByRole("textbox", { name: "节点字段" })).toHaveValue("style");
    expect(within(importedField).getByRole("textbox", { name: "默认值" })).toHaveValue('"photo"');
    await clickControl(within(dialog).getByRole("button", { name: "选择节点" }));
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 1 · 创作风格" })).toHaveAttribute("aria-checked", "true");
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 强度" }));
    await user.keyboard("{Escape}");
    await user.click(within(dialog).getByText("创作者表单预览 · 离线"));
    expect(await within(dialog).findByRole("combobox", { name: "创作风格 *" })).toHaveValue("0");
    expect(within(dialog).queryByRole("spinbutton", { name: "强度" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "创作风格 *" }));
    expect(screen.getByRole("option", { name: "写实" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(within(dialog).queryByRole("checkbox", { name: "已核对开放字段、素材格式与输出映射" })).not.toBeInTheDocument();
    expect(published).toEqual([]);
    await user.click(within(dialog).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(published).toEqual([{ name: "背景应用", adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { ...definition, fields: [definition.fields[0]], fixedBindings: [] } } }]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it.each(["AI_APP", "WORKFLOW"] as const)("excludes unchecked RunningHub %s nodes from the saved capability and reopening", async (targetType) => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType, targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [
        { key: "prompt", label: "提示词", type: "STRING", nodeId: "1", fieldName: "text", required: false, advanced: false },
        { key: "strength", label: "强度", type: "NUMBER", nodeId: "2", fieldName: "strength", defaultValue: 1, required: false, advanced: false },
      ],
      fixedBindings: [{ nodeId: "2", fieldName: "seed", value: 8 }],
      outputs: [{ nodeId: "2", kind: "IMAGE", primary: true, maxCount: 1 }],
      nodeOptions: [{ nodeId: "2", label: "输出图片" }],
      importSource: targetType === "WORKFLOW" ? { "2": { inputs: { strength: 1, seed: 8 } } }
        : [{ nodeId: "2", fieldName: "strength", fieldValue: 1 }],
    };
    const savedDefinition = { ...definition, fields: [definition.fields[0]!], fixedBindings: [] };
    let fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
      adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: definition },
    });
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
          adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: savedDefinition }, version: 5, capabilityVersion: 3,
        });
        return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    await clickControl(within(dialog).getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 强度" }));
    await user.keyboard("{Escape}");
    expect(within(dialog).queryByRole("row", { name: "强度" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByText("创作者表单预览 · 离线"));
    expect(within(dialog).queryByRole("spinbutton", { name: "强度" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(writes).toEqual([{ expectedVersion: 4, name: "Portrait", enabled: true,
      adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: savedDefinition } }]));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    const reopened = screen.getByRole("dialog");
    expect(within(reopened).getByRole("row", { name: "提示词" })).toBeVisible();
    expect(within(reopened).queryByRole("row", { name: "强度" })).not.toBeInTheDocument();
    expect(within(reopened).getByRole("combobox", { name: "输出节点" })).toHaveTextContent("节点 2 · 输出图片");
  });

  it("clears all RunningHub mappings without validating unchecked drafts and retains the selection across failed saves", async () => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "strength", label: "强度", type: "NUMBER", nodeId: "2", fieldName: "strength",
        defaultValue: 1, required: false, advanced: false }],
      fixedBindings: [{ nodeId: "2", fieldName: "seed", value: 8 }],
      outputs: [{ nodeId: "2", kind: "IMAGE", primary: true, maxCount: 1 }],
    };
    const fixture = settingsFixture({ platform: "RUNNINGHUB" }, { adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: definition } });
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        return writes.length === 1 ? HttpResponse.json({ detail: "Unavailable", code: "SYNTHETIC_FAILURE", status: 503 }, {
          status: 503, headers: { "Content-Type": "application/problem+json" },
        }) : HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    const input = within(within(dialog).getByRole("row", { name: "强度" })).getByRole("textbox", { name: "默认值" });
    fireEvent.change(input, { target: { value: "invalid" } }); fireEvent.blur(input);
    const fixed = within(dialog).getByRole("textbox", { name: "固定值（JSON 标量）" });
    fireEvent.change(fixed, { target: { value: "invalid" } }); fireEvent.blur(fixed);
    async function choose(action: string) {
      await clickControl(within(dialog).getByRole("button", { name: "选择节点" }));
      await clickControl(screen.getByRole("menuitem", { name: action }));
      await user.keyboard("{Escape}");
    }
    await choose("清空选择");
    expect(input).toBeDisabled(); expect(fixed).toBeDisabled();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "真实目标 ID" }), { target: { value: "321" } });
    const save = within(dialog).getByRole("button", { name: "保存能力" });
    await user.click(save);
    expect(await within(dialog).findByText("Unavailable")).toBeInTheDocument();
    expect(writes).toEqual([{ expectedVersion: 4, name: "Portrait", enabled: true, adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { ...definition, targetId: "321", fields: [], fixedBindings: [] } } }]);
    await choose("全选节点");
    expect(input).toHaveValue("invalid"); expect(input).toBeEnabled();
    expect(fixed).toHaveValue("invalid"); expect(fixed).toBeEnabled();
    await choose("清空选择");
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toHaveLength(2);
    expect(writes[1]).toEqual(writes[0]);
  });

  it("requires repairing a RunningHub visibility condition when its parent node is unchecked", async () => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [
        { key: "mode", label: "模式", type: "BOOLEAN", nodeId: "1", fieldName: "mode", defaultValue: true, required: false, advanced: false },
        { key: "strength", label: "强度", type: "NUMBER", nodeId: "2", fieldName: "strength", defaultValue: 1,
          required: false, advanced: false, enabledWhen: { field: "mode", value: true } },
      ], outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
    };
    const fixture = settingsFixture({ platform: "RUNNINGHUB" }, { adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: definition } });
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    await clickControl(within(dialog).getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 1 · 模式" }));
    await user.keyboard("{Escape}");
    await user.click(within(dialog).getByRole("button", { name: "保存能力" }));
    expect(await within(dialog).findByText("“强度”的显示条件引用了未勾选节点的字段，请重新勾选该节点或修改显示条件。")).toBeInTheDocument();
    expect(writes).toEqual([]);
    await user.click(within(within(dialog).getByRole("row", { name: "强度" })).getByRole("button", { name: "更多设置" }));
    await selectValue(within(dialog).getByRole("combobox", { name: "显示条件" }), "");
    await user.click(within(dialog).getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toEqual({ expectedVersion: 4, name: "Portrait", enabled: true, adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { ...definition, fields: [{ ...definition.fields[1], enabledWhen: null }] } } });
  });

  it("validates and saves an edited workflow on explicit submit, preserving failed drafts until an explicit retry", async () => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [], outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
    };
    const fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
      adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: definition },
    });
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        return writes.length === 1
          ? HttpResponse.json({ title: "Unavailable", detail: "Unavailable", code: "SYNTHETIC_FAILURE", status: 503 }, {
            status: 503, headers: { "Content-Type": "application/problem+json" },
          })
          : HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    const name = within(dialog).getByRole("textbox", { name: "能力名称" });
    await user.clear(name); await user.type(name, "Updated workflow");
    const target = within(dialog).getByRole("textbox", { name: "真实目标 ID" });
    await user.clear(target); await user.type(target, "invalid");
    expect(within(dialog).queryByRole("checkbox", { name: "已核对开放字段、素材格式与输出映射" })).not.toBeInTheDocument();
    const save = within(dialog).getByRole("button", { name: "保存能力" });
    await user.click(save);
    expect(target).toBeInvalid();
    expect(writes).toEqual([]);

    await user.clear(target); await user.type(target, "321");
    expect(writes).toEqual([]);
    await user.click(save);
    expect(await within(dialog).findByText("Unavailable")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBe(dialog);
    expect(name).toHaveValue("Updated workflow");
    expect(target).toHaveValue("321");
    expect(writes).toEqual([{ expectedVersion: 4, name: "Updated workflow", enabled: true,
      adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: { ...definition, targetId: "321" } } }]);

    expect(writes).toHaveLength(1);
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toHaveLength(2);
    expect(writes[1]).toEqual(writes[0]);
  });

  it.each(["AI_APP", "WORKFLOW"] as const)("edits saved RunningHub %s mappings and reopens the saved values without rediscovery", async (targetType) => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType, targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [
        { key: "prompt", label: "画面提示", nodeId: "10", fieldName: "text", type: "STRING", source: "PROMPT",
          defaultValue: "合成提示", required: false, advanced: false },
        { key: "seconds", label: "视频时长", nodeId: "20", fieldName: "seconds", type: "INTEGER", source: "DURATION_SECONDS",
          defaultValue: 4, required: false, advanced: false },
        { key: "video", label: "来源视频", nodeId: "20", fieldName: "video", type: "VIDEO", source: "PARAMETER",
          resourceFormat: "FILE_NAME", required: true, advanced: false },
      ],
      fixedBindings: [{ nodeId: "30", fieldName: "enabled", value: false, encoding: "NATIVE" }],
      outputs: [{ nodeId: "99", kind: "VIDEO", primary: true, maxCount: 2 },
        { nodeId: "100", kind: "AUDIO", primary: false, maxCount: 1 }],
      nodeOptions: [{ nodeId: "99", label: "原视频输出" }, { nodeId: "101", label: "新视频输出" }],
      importSource: targetType === "WORKFLOW" ? { "99": { class_type: "SaveVideo", inputs: { video: ["20", 0] } } }
        : [{ nodeId: "10", fieldName: "text", fieldType: "STRING", fieldValue: "合成提示" }],
    };
    const savedDefinition = { ...definition, fields: definition.fields.map((field) => field.key === "prompt"
      ? { ...field, defaultValue: "修改后的合成提示" } : field),
      outputs: definition.outputs.map((output) => output.primary ? { ...output, nodeId: "101" } : output) };
    let fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
      adapterId: "RUNNINGHUB_VIDEO", kind: "VIDEO_GENERATION", settings: { runningHub: definition },
    });
    const writes: unknown[] = [];
    const discoveryRequests: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections/openai-1/runninghub/preview", async ({ request }) => {
        discoveryRequests.push(await request.json());
        return HttpResponse.json({ definition, warnings: [] });
      }),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
          adapterId: "RUNNINGHUB_VIDEO", kind: "VIDEO_GENERATION", version: 5, capabilityVersion: 3,
          settings: { runningHub: savedDefinition },
        });
        return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("combobox", { name: "目标类型" })).toHaveValue(targetType);
    expect(within(dialog).getByRole("textbox", { name: "真实目标 ID" })).toHaveValue("123");
    expect(within(dialog).getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 10 · 画面提示 · 节点 20 · 视频时长 · 节点 30");
    const prompt = within(dialog).getByRole("row", { name: "画面提示" });
    expect(prompt).toBeVisible();
    expect(within(prompt).getByRole("textbox", { name: "节点 ID" })).toHaveValue("10");
    expect(within(prompt).getByRole("textbox", { name: "节点字段" })).toHaveValue("text");
    expect(within(prompt).getByRole("combobox", { name: "输入来源" })).toHaveValue("PROMPT");
    const promptDefault = within(prompt).getByRole("textbox", { name: "默认值" });
    expect(promptDefault).toHaveValue("合成提示");
    const duration = within(dialog).getByRole("row", { name: "视频时长" });
    expect(within(duration).getByRole("combobox", { name: "输入来源" })).toHaveValue("DURATION_SECONDS");
    expect(within(duration).getByRole("textbox", { name: "默认值" })).toHaveValue("4");
    const video = within(dialog).getByRole("row", { name: "来源视频" });
    expect(within(video).getByRole("combobox", { name: "上传后的引用格式" })).toHaveValue("FILE_NAME");
    expect(within(dialog).getByRole("textbox", { name: "固定节点 ID" })).toHaveValue("30");
    expect(within(dialog).getByRole("textbox", { name: "固定字段" })).toHaveValue("enabled");
    expect(within(dialog).getByRole("textbox", { name: "固定值（JSON 标量）" })).toHaveValue("false");
    const outputs = within(dialog).getByRole("table", { name: "输出映射" });
    expect(within(outputs).getAllByRole("combobox", { name: "输出节点" }).map((input) => (input as HTMLInputElement).value)).toEqual(["99", "100"]);
    expect(within(outputs).getAllByRole("combobox", { name: "媒体类型" }).map((input) => (input as HTMLInputElement).value)).toEqual(["VIDEO", "AUDIO"]);
    expect(within(outputs).getAllByRole("spinbutton", { name: "最多结果数" }).map((input) => (input as HTMLInputElement).value)).toEqual(["2", "1"]);
    expect(discoveryRequests).toEqual([]);

    await user.clear(promptDefault); await user.type(promptDefault, "修改后的合成提示");
    await selectValue(within(outputs).getAllByRole("combobox", { name: "输出节点" })[0]!, "101");
    await user.click(within(dialog).getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toEqual([{ expectedVersion: 4, name: "Portrait", enabled: true,
      adapterId: "RUNNINGHUB_VIDEO", settings: { runningHub: savedDefinition } }]);
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    const reopened = screen.getByRole("dialog");
    await user.click(within(reopened).getByText("导入 / 已保存的 JSON"));
    expect(JSON.parse((within(reopened).getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" }) as HTMLTextAreaElement).value)).toEqual(definition.importSource);
    expect(within(reopened).getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 10 · 画面提示 · 节点 20 · 视频时长 · 节点 30");
    expect(within(within(reopened).getByRole("row", { name: "画面提示" })).getByRole("textbox", { name: "默认值" })).toHaveValue("修改后的合成提示");
    expect(within(reopened).getByRole("textbox", { name: "固定值（JSON 标量）" })).toHaveValue("false");
    expect(within(reopened).getAllByRole("combobox", { name: "输出节点" })[0]).toHaveTextContent("节点 101 · 新视频输出");
    await clickControl(within(reopened).getAllByRole("combobox", { name: "输出节点" })[0]!);
    expect(screen.getByRole("option", { name: "节点 99 · 原视频输出" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(discoveryRequests).toEqual([]);
  });

  it.each(["VIDEO", "AUDIO"] as const)("saves a changed %s primary output and preserves the workflow fields", async (kind) => {
    const definition: NonNullable<MediaCapability["settings"]["runningHub"]> = {
      schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
      fields: [{ key: "prompt", label: "提示词", nodeId: "1", fieldName: "text", type: "STRING", source: "PROMPT", required: false, advanced: false }],
      outputs: [{ nodeId: "8", kind: "IMAGE", primary: true, maxCount: 1 }],
    };
    let fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
      adapterId: "RUNNINGHUB_IMAGE", settings: { runningHub: definition,
        pricing: { amount: "0.125", currency: "CNY", unit: "IMAGE" } },
    });
    const updatedDefinition = { ...definition, outputs: [{ ...definition.outputs[0]!, kind }] };
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json());
        fixture = settingsFixture({ platform: "RUNNINGHUB", name: "RunningHub" }, {
          adapterId: `RUNNINGHUB_${kind}`, kind: `${kind}_GENERATION`, version: 5,
          capabilityVersion: 3, settings: { runningHub: updatedDefinition },
        });
        fixture.defaults[0]!.capabilityId = null;
        return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    const outputs = within(dialog).getByRole("table", { name: "输出映射" });
    const target = within(dialog).getByRole("textbox", { name: "真实目标 ID" });
    const save = within(dialog).getByRole("button", { name: "保存能力" });
    await user.clear(target);
    await user.click(save);
    expect(writes).toEqual([]);
    await selectValue(within(dialog).getByRole("combobox", { name: "主输出类型" }), `RUNNINGHUB_${kind}`);
    expect(within(outputs).getByRole("combobox", { name: "媒体类型" })).toHaveValue(kind);
    await user.type(target, "123");
    await selectValue(within(outputs).getByRole("combobox", { name: "媒体类型" }), "IMAGE");
    await selectValue(within(outputs).getByRole("combobox", { name: "媒体类型" }), kind);
    expect(within(dialog).getByRole("combobox", { name: "主输出类型" })).toHaveValue(`RUNNINGHUB_${kind}`);
    expect(within(dialog).getByRole("spinbutton", { name: "单位价格" })).toHaveValue(null);
    expect(writes).toEqual([]);
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toEqual([{ expectedVersion: 4, name: "Portrait", enabled: true,
      adapterId: `RUNNINGHUB_${kind}`, settings: { runningHub: updatedDefinition } }]);
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    expect(within(screen.getByRole("table", { name: "输出映射" })).getByRole("combobox", { name: "媒体类型" })).toHaveValue(kind);
  });

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
    await user.click(await screen.findByRole("button", { name: "编辑连接" }));
    let dialog = screen.getByRole("dialog");
    const connectionName = within(dialog).getByRole("textbox", { name: "连接名称" });
    await user.clear(connectionName); await user.type(connectionName, "Connection draft");
    await user.type(within(dialog).getByLabelText("替换 API Key（留空则不修改）"), "new-key-draft");
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    dialog = screen.getByRole("dialog");
    const name = within(dialog).getByRole("textbox", { name: "能力名称" });
    await user.clear(name); await user.type(name, "Capability draft");
    settings = settingsFixture({ version: 2, name: "Remote connection" }, {
      version: 5, name: "Remote capability", settings: { quality: "low" },
    });
    await act(() => queryClient.invalidateQueries({ queryKey: ["settings", "media"] }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "保存能力" })).toBeDisabled());
    expect(name).toHaveValue("Capability draft");
    await user.click(within(dialog).getByRole("button", { name: "载入最新版本" }));
    expect(name).toHaveValue("Remote capability");
    expect(within(dialog).getByRole("combobox", { name: "GPT Image 2 质量" })).toHaveValue("low");
    await user.click(within(dialog).getByRole("button", { name: "取消" }));
    await user.click(screen.getByRole("button", { name: "编辑连接" }));
    dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("textbox", { name: "连接名称" })).toHaveValue("Connection draft");
    expect(within(dialog).getByLabelText("替换 API Key（留空则不修改）")).toHaveValue("new-key-draft");
    expect(within(dialog).getByRole("button", { name: "保存连接" })).toBeDisabled();
    await user.click(within(dialog).getByRole("button", { name: "保存连接" }));
    expect(writes).toEqual([]);
    await user.click(within(dialog).getByRole("button", { name: "载入最新版本" }));
    expect(within(dialog).getByRole("textbox", { name: "连接名称" })).toHaveValue("Remote connection");
    expect(within(dialog).getByLabelText("替换 API Key（留空则不修改）")).toHaveValue("");
    await user.click(within(dialog).getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(writes).toEqual([{
      expectedVersion: 2, name: "Remote connection", enabled: true, origin: null, apiKey: null,
    }]));
  });

  it("advances the saved capability baseline without exposing concurrency controls", async () => {
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
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByText("编辑能力参数"));
    const name = screen.getByRole("textbox", { name: "能力名称" });
    await user.clear(name);
    await user.type(name, "Updated portrait");
    await user.click(screen.getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    const savedName = screen.getByRole("textbox", { name: "能力名称" });
    expect(savedName).toHaveValue("Updated portrait");
    await user.clear(savedName);
    await user.type(savedName, "Next unsaved name");
    expect(savedName).toHaveValue("Next unsaved name");
    expect(writes).toEqual([{ expectedVersion: 4, name: "Updated portrait", enabled: true,
      adapterId: "OPENAI_GPT_IMAGE_2", settings: { model: "", quality: "high" } }]);
    expect(screen.queryByText(/全局并发/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "载入最新版本" })).not.toBeInTheDocument();
  });

  it("sets the first configured default and edits a capability in a tabbed dialog", async () => {
    let defaultSelection: unknown;
    const settings: MediaSettings = {
      defaults: [
        { kind: "IMAGE_GENERATION", capabilityId: null, version: 2 },
        { kind: "VIDEO_GENERATION", capabilityId: null, version: 0 },
      ],
      connections: [{
        id: "openai-1", name: "OpenAI", platform: "OPENAI", enabled: true,
        version: 1, connectionVersion: 1, origin: null, keyMask: "••••7890",
        connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
        capabilities: [{
          id: "portrait", name: "Portrait", enabled: true, version: 4, capabilityVersion: 2,
          adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION",
          minimumSeconds: 0, maximumSeconds: 0, maxReferenceAudios: 0, maxReferenceVideos: 0, maxReferenceImages: 1,
          supportedVideoInputModes: [], defaultVideoInputMode: null,
          supportsEndFrame: false,
          supportedImageAspectRatios: ["AUTO", "1:1"], supportedImageResolutions: ["1K", "2K", "4K"],
          supportedImageQualities: ["low", "medium", "high"], supportsTransparentBackground: true,
          supportsImageMask: true,
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
    );
    mount();
    const user = userEvent.setup();
    const heading = await screen.findByText("Portrait");
    const row = heading.closest("tr");
    if (!row) throw new Error("Missing capability row");
    expect(within(row).queryByText(/全局并发/)).not.toBeInTheDocument();
    await user.click(within(row).getByRole("button", { name: "设为默认" }));
    await waitFor(() => expect(defaultSelection).toEqual({ expectedVersion: 2, capabilityId: "portrait" }));
    await waitFor(() => expect(within(row).getByRole("button", { name: "设为默认" })).toBeDisabled());
    await user.click(within(row).getByText("编辑能力参数"));
    expect(within(screen.getByRole("dialog")).getByRole("combobox", { name: "GPT Image 2 质量" })).toHaveValue("high");
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
    await user.click(screen.getByRole("button", { name: "添加连接" }));
    await user.type(screen.getByRole("textbox", { name: "连接名称" }), "OpenAI main");
    await selectValue(screen.getByRole("combobox", { name: "平台" }), "OPENAI");
    const key = screen.getByLabelText("API Key") as HTMLInputElement;
    await user.type(key, "provider-secret-7890");
    await user.type(screen.getByRole("textbox", { name: "API Base URL（留空使用官方地址）" }),
      "https://gateway.example.com/proxy/v1");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "添加连接" }));
    expect(screen.getByRole("button", { name: "正在保存…" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    release?.();
    expect(await screen.findByText(/密钥 ••••7890 · 已配置/)).toBeInTheDocument();
    expect(screen.queryByText(/未实测/)).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
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
    await user.click(await screen.findByRole("button", { name: "编辑连接" }));
    await user.type(screen.getByRole("textbox", { name: "API Base URL（留空使用官方地址）" }),
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
    await user.click(await screen.findByRole("button", { name: "编辑连接" }));
    const name = screen.getByRole("textbox", { name: "连接名称" });
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

  it("publishes an imported ComfyUI graph on explicit submit with valid mappings, defaults and pricing", async () => {
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
    server.use(http.post("/api/v1/settings/media-connections/comfy-1/comfyui/preview", () => HttpResponse.json(comfyGraph)));
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "Wan video");
    await selectValue(screen.getByRole("combobox", { name: "主输出类型" }), "COMFY_VIDEO_V1");
    await user.upload(screen.getByLabelText("选择 API JSON 文件"), new File([JSON.stringify(comfyGraph)], "workflow.json", { type: "application/json" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "解析工作流" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "解析工作流" }));
    await screen.findByRole("button", { name: /#11.*CustomTextEncoder/ });
    await selectValue(screen.getByRole("combobox", { name: "text 的参数来源" }), "PROMPT");
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "seconds 的参数来源" }), "DURATION_SECONDS");
    await selectValue(screen.getByRole("combobox", { name: "结果节点" }), "99");
    await selectValue(screen.getByRole("combobox", { name: "结果字段" }), "videos");
    const publish = within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" });
    expect(publish).toBeEnabled();
    expect(screen.queryByLabelText("基础宽度（像素）")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("基础高度（像素）")).not.toBeInTheDocument();
    expect(submitted).toBeUndefined();
    const minimum = screen.getByLabelText("工作流最短时长（秒）");
    await user.clear(minimum); await user.type(minimum, "3");
    expect(publish).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.queryByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" })).not.toBeInTheDocument();
    await user.type(screen.getByRole("spinbutton", { name: "默认视频时长（秒）" }), "4");
    expect(publish).toBeEnabled();
    await user.type(screen.getByRole("spinbutton", { name: "单位价格" }), "1.25");
    expect(publish).toBeEnabled();
    expect(submitted).toBeUndefined();
    await user.click(publish);
    await waitFor(() => expect(submitted).toEqual({ name: "Wan video", adapterId: "COMFY_VIDEO_V1",
      settings: { comfyWorkflow: { ...comfyDefinition, minimumSeconds: 3 }, defaultDurationSeconds: 4,
        pricing: { amount: "1.25", currency: "CNY", unit: "SECOND" } } }));
  });

  it("saves an edited ComfyUI workflow without repeated confirmation while blocking invalid mappings", async () => {
    const fixture = settingsFixture({ platform: "COMFYUI", name: "ComfyUI", origin: "http://127.0.0.1:8188" }, {
      adapterId: "COMFY_VIDEO_V1", kind: "VIDEO_GENERATION", settings: { comfyWorkflow: comfyDefinition },
    });
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        writes.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑能力参数" }));
    const dialog = screen.getByRole("dialog");
    const save = within(dialog).getByRole("button", { name: "保存能力" });
    expect(save).toBeEnabled();
    await user.click(within(dialog).getByRole("button", { name: /#14.*CustomGenerator/ }));
    const steps = within(dialog).getByLabelText("steps 的固定值");
    await user.clear(steps); await user.type(steps, "30");
    expect(save).toBeEnabled();
    const seconds = within(dialog).getByRole("combobox", { name: "seconds 的参数来源" });
    await selectValue(seconds, "FIXED");
    expect(save).toBeDisabled();
    expect(writes).toEqual([]);
    await selectValue(seconds, "DURATION_SECONDS");
    expect(save).toBeEnabled();
    await user.click(within(dialog).getByRole("button", { name: "检查发布配置" }));
    expect(within(dialog).queryByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" })).not.toBeInTheDocument();
    await user.type(within(dialog).getByRole("spinbutton", { name: "单位价格" }), "1.25");
    expect(save).toBeEnabled();
    expect(writes).toEqual([]);
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(writes).toEqual([{ expectedVersion: 4, name: "Portrait", enabled: true, adapterId: "COMFY_VIDEO_V1",
      settings: { comfyWorkflow: { ...comfyDefinition, graph: { ...comfyGraph,
        "14": { ...comfyGraph["14"]!, inputs: { ...comfyGraph["14"]!.inputs, steps: 30 } },
      } }, pricing: { amount: "1.25", currency: "CNY", unit: "SECOND" } },
    }]);
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
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "Portrait image");
    expect(screen.getByRole("combobox", { name: "固定适配器" })).toHaveValue("OPENAI_GPT_IMAGE_2");
    await selectValue(screen.getByRole("combobox", { name: "GPT Image 2 质量" }), "high");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Portrait image",
      adapterId: "OPENAI_GPT_IMAGE_2", settings: { model: "", quality: "high" } }));
  });

  it("saves a Google API address without clearing the existing credential", async () => {
    const settings = settingsFixture({ id: "google-1", name: "Google", platform: "GOOGLE",
      origin: "https://gateway.example.com" }, { adapterId: "GOOGLE_NANO_BANANA_2", settings: { model: "custom-image" } });
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/google-1", async ({ request }) => {
        submitted = await request.json();
        return HttpResponse.json({ ...settings, connections: settings.connections.map((connection) => ({
          ...connection, origin: "https://new-gateway.example.com/proxy/v1beta/",
        })) });
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "编辑连接" }));
    const region = screen.getByRole("dialog");
    const origin = within(region).getByRole("textbox", { name: "API Base URL（留空使用官方地址）" });
    expect(origin).toHaveValue("https://gateway.example.com");
    const help = within(region).getByRole("note", { name: "Nano Banana 接口配置说明" });
    expect(help).toHaveTextContent("当前接口格式：Gemini v1（默认）");
    expect(help).toHaveTextContent("https://grsai.dakka.com.cn/v1beta");
    expect(help).toHaveTextContent("nano-banana-2-lite");
    expect(help).toHaveTextContent("/v1/draw/nano-banana");
    expect(origin).toHaveAttribute("aria-describedby", help.id);
    await user.clear(origin);
    await user.type(origin, "https://new-gateway.example.com/proxy/v1beta/");
    expect(help).toHaveTextContent("当前接口格式：Gemini v1beta（兼容）");
    await user.click(within(region).getByRole("button", { name: "保存连接" }));
    await waitFor(() => expect(submitted).toEqual({ expectedVersion: 1, name: "Google", enabled: true,
      origin: "https://new-gateway.example.com/proxy/v1beta/", apiKey: null }));
    expect(await screen.findByText("Gemini v1beta（兼容）")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "编辑能力参数" }));
    expect(within(screen.getByRole("dialog")).getByText(/与模型名分开配置/)).toBeInTheDocument();
  });

  it("persists defaults, price and reference limits while preserving the configured model", async () => {
    const settings = settingsFixture({}, { settings: { model: "gateway-image", quality: "high",
      defaultParameters: { aspectRatio: "16:9", resolution: "2K", generationCount: 2 },
      maxReferenceImages: 3, pricing: { amount: "0.125", currency: "USD", unit: "IMAGE" } } });
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async ({ request }) => {
        submitted = await request.json(); return HttpResponse.json(settings);
      }),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByText("编辑能力参数"));
    const dialog = screen.getByRole("dialog");
    await user.click(screen.getByRole("tab", { name: "估算价格" }));
    expect(within(dialog).getByRole("spinbutton", { name: "单位价格" })).toHaveValue(0.125);
    await user.click(screen.getByRole("tab", { name: "默认参数" }));
    expect(within(dialog).getByRole("combobox", { name: "默认分辨率" })).toHaveValue("2K");
    await selectValue(within(dialog).getByRole("combobox", { name: "默认分辨率" }), "4K");
    await user.click(within(dialog).getByRole("button", { name: "保存能力" }));
    await waitFor(() => expect(submitted).toMatchObject({ settings: { model: "gateway-image", quality: "high",
      defaultParameters: { aspectRatio: "16:9", resolution: "4K", generationCount: 2 },
      maxReferenceImages: 3, pricing: { amount: "0.125", currency: "USD", unit: "IMAGE" } } }));
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
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "Nano Banana 2");
    expect(screen.getByRole("combobox", { name: "固定适配器" }))
      .toHaveValue("GOOGLE_NANO_BANANA_2");
    await user.click(screen.getByRole("combobox", { name: "固定适配器" }));
    expect(screen.getByRole("option", { name: "Nano Banana 2 · Google Gemini" })).toHaveAttribute("data-value", "GOOGLE_NANO_BANANA_2");
    await user.keyboard("{Escape}");
    expect(screen.getByText("Google Gemini", { selector: "strong" })).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Nano Banana 2",
      adapterId: "GOOGLE_NANO_BANANA_2", settings: { model: "" } }));
  });

  it("keeps forms out of the tables and preserves a draft when Escape closes the dialog", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settingsFixture())),
    );
    mount(); const user = userEvent.setup();
    const edit = await screen.findByRole("button", { name: "编辑能力参数" });
    expect(screen.getByRole("table", { name: "媒体连接" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "已发布能力" })).toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    await user.click(edit);
    const name = screen.getByRole("textbox", { name: "能力名称" });
    await user.clear(name); await user.type(name, "Unsaved portrait");
    await user.click(screen.getByRole("tab", { name: "默认参数" }));
    expect(screen.queryByRole("textbox", { name: "能力名称" })).not.toBeInTheDocument();
    const resolution = screen.getByRole("combobox", { name: "默认分辨率" });
    await user.click(resolution);
    expect(screen.getByRole("listbox").closest("[role=dialog]")).toBeNull();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(edit).toHaveFocus();
    await user.click(edit);
    expect(screen.getByRole("textbox", { name: "能力名称" })).toHaveValue("Unsaved portrait");
    const saveButton = screen.getByRole("button", { name: "保存能力" });
    saveButton.focus(); await user.keyboard("{Tab}");
    expect(screen.getByRole("button", { name: "关闭窗口" })).toHaveFocus();
    await user.keyboard("{Shift>}{Tab}{/Shift}");
    expect(saveButton).toHaveFocus();
    const modelTab = screen.getByRole("tab", { name: "模型配置" });
    await user.click(modelTab); await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "默认参数" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "默认参数" })).toHaveFocus();
  });

  it("requires importing and reviewing a ComfyUI workflow before publishing", async () => {
    const settings = settingsFixture({ platform: "COMFYUI", capabilities: [] });
    // The fixture adds its capability after connection overrides; remove it explicitly.
    settings.connections[0]!.capabilities = [];
    let writes = 0;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.post("/api/v1/settings/media-connections/openai-1/capabilities", () => { writes += 1; return HttpResponse.json(settings); }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "Incomplete model");
    expect(screen.getByRole("button", { name: "2. 节点与参数映射" })).toBeDisabled();
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" })).toBeDisabled();
    expect(screen.getByLabelText("API JSON 内容")).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "Checkpoint 文件名" })).not.toBeInTheDocument();
    expect(writes).toBe(0);
  });

  it("creates a Google connection with the configured API address", async () => {
    let submitted: unknown;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json({ connections: [], defaults: mockDefault })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/media-connections", async ({ request }) => {
        submitted = await request.json(); return HttpResponse.json({ connections: [], defaults: mockDefault });
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    await selectValue(screen.getByRole("combobox", { name: "平台" }), "GOOGLE");
    expect(screen.getByRole("note", { name: "Nano Banana 接口配置说明" }))
      .toHaveTextContent("当前接口格式：Gemini v1（默认）");
    await user.type(screen.getByRole("textbox", { name: "连接名称" }), "Google gateway");
    await user.type(screen.getByRole("textbox", { name: "API Base URL（留空使用官方地址）" }), "https://gemini.example.com");
    await user.type(screen.getByLabelText("API Key"), "mock-key");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(submitted).toEqual({ name: "Google gateway", platform: "GOOGLE", origin: "https://gemini.example.com", apiKey: "mock-key" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("creates an AutoDL token connection and publishes a versioned H3 workflow", async () => {
    let config: MediaSettings = { connections: [], defaults: [] };
    const connectionWrites: unknown[] = [];
    const capabilityWrites: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(config)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "fake" })),
      http.post("/api/v1/settings/media-connections", async ({ request }) => {
        connectionWrites.push(await request.json());
        config = settingsFixture({ platform: "AUTODL", name: "AutoDL", origin: null });
        config.connections[0]!.capabilities = [];
        return HttpResponse.json(config);
      }),
      http.post("/api/v1/settings/media-connections/openai-1/capabilities", async ({ request }) => {
        capabilityWrites.push(await request.json()); return HttpResponse.json(config);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    await user.type(screen.getByRole("textbox", { name: "连接名称" }), "AutoDL");
    await selectValue(screen.getByRole("combobox", { name: "平台" }), "AUTODL");
    expect(screen.getByText(/分组为 ComfyUI 的 Token/, { selector: "p.ui-muted" })).toBeInTheDocument();
    await user.type(screen.getByLabelText("API Key"), "fake-autodl-key");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(connectionWrites).toEqual([{ name: "AutoDL", platform: "AUTODL", origin: null, apiKey: "fake-autodl-key" }]));
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "H3 mixed");
    expect(screen.getByRole("combobox", { name: "AutoDL 工作流" })).toHaveValue("minimax_h3_z0903");
    const resolutions = screen.getByRole("group", { name: "可选视频分辨率" });
    await user.click(within(resolutions).getByRole("button", { name: "1088p" }));
    await user.click(within(resolutions).getByRole("button", { name: "1440p" }));
    await selectValue(screen.getByRole("combobox", { name: "默认分辨率" }), "480p");
    await user.type(screen.getByRole("spinbutton", { name: "随机种子（留空使用工作流默认）" }), "123");
    await user.click(screen.getByRole("tab", { name: "估算价格" }));
    await user.type(screen.getByRole("spinbutton", { name: "480p 单位价格" }), "0.1");
    await user.type(screen.getByRole("spinbutton", { name: "768p 单位价格" }), "0.3");
    await user.click(screen.getByRole("tab", { name: "输入限制" }));
    expect(screen.getByRole("spinbutton", { name: "最多参考图数量" })).toHaveAttribute("max", "6");
    expect(screen.getByRole("spinbutton", { name: "最多参考音频数量" })).toHaveAttribute("min", "1");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(capabilityWrites).toEqual([{ name: "H3 mixed", adapterId: "AUTODL_COMFY_VIDEO",
      settings: { workflowId: "minimax_h3_z0903", videoResolution: "480p", videoResolutions: ["480p", "768p"], pricingByResolution: { "480p": { amount: "0.1", currency: "CNY", unit: "SECOND" }, "768p": { amount: "0.3", currency: "CNY", unit: "SECOND" } }, seed: 123 } }]));
    expect(window.localStorage.getItem("mediaApiKey")).toBeNull();
  });

  it("publishes an imported AutoDL target with its complete local definition and tier price", async () => {
    const fixture = settingsFixture({ platform: "AUTODL", name: "AutoDL", origin: null, capabilities: [] });
    const definition: NonNullable<MediaCapability["settings"]["workflowDefinition"]> = {
      schemaVersion: 1, id: "future_video_v1", label: "Future video", minimumSeconds: 1, maximumSeconds: 20,
      promptLimit: 10000, mode: "TEXT", imageFields: [], audioFields: [], minimumImages: 0, minimumAudios: 0,
      resolutions: ["720p横(1280*720)", "720p竖(720*1280)"], defaultResolution: "720p", supportsSeed: false,
    };
    const published: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "fake" })),
      http.get("/api/v1/settings/autodl-workflows", () => HttpResponse.json({ items: [{ id: definition.id, label: definition.label }] })),
      http.post("/api/v1/settings/autodl-workflows/preview", () => HttpResponse.json(definition)),
      http.post("/api/v1/settings/media-connections/openai-1/capabilities", async ({ request }) => {
        published.push(await request.json()); return HttpResponse.json(fixture);
      }),
    );
    mount(); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    await user.type(screen.getByRole("textbox", { name: "新能力名称" }), "Future video");
    await user.click(screen.getByRole("button", { name: "刷新官方工作流目录" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "刷新官方工作流目录" })).toBeEnabled());
    await selectValue(screen.getByRole("combobox", { name: "AutoDL 工作流" }), definition.id);
    expect(await screen.findByRole("textbox", { name: "工作流 ID" })).toHaveValue(definition.id);
    expect(published).toEqual([]);
    await user.click(screen.getByRole("tab", { name: "估算价格" }));
    await user.type(screen.getByRole("spinbutton", { name: "720p 单位价格" }), "0.2");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(published).toEqual([{ name: "Future video", adapterId: "AUTODL_COMFY_VIDEO", settings: {
      workflowId: definition.id, workflowDefinition: definition, videoResolution: "720p", videoResolutions: ["720p"],
      pricingByResolution: { "720p": { amount: "0.2", currency: "CNY", unit: "SECOND" } },
    } }]));
  });

});


describe("capability deletion", () => {
  function serve(settings: MediaSettings) {
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
    );
  }

  it("cancels without deleting and confirms with the displayed version", async () => {
    const settings = settingsFixture();
    serve(settings);
    const versions: string[] = [];
    server.use(http.delete("/api/v1/settings/media-connections/openai-1/capabilities/portrait", ({ request }) => {
      versions.push(new URL(request.url).searchParams.get("expectedVersion")!);
      return HttpResponse.json({ ...settings, connections: [{ ...settings.connections[0], capabilities: [] }],
        defaults: settings.defaults.map((item) => item.capabilityId === "portrait" ? { ...item, capabilityId: null, version: 1 } : item) });
    }));
    const user = userEvent.setup();
    const client = mount();
    client.setQueryData(["media-functions"], []);
    await user.click(await screen.findByRole("button", { name: "删除能力" }));
    expect(screen.getByRole("dialog")).toHaveTextContent("历史结果和已受理任务保留");
    expect(versions).toEqual([]);
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "取消" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(versions).toEqual([]);
    await user.click(screen.getByRole("button", { name: "删除能力" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(screen.queryByText("Portrait")).not.toBeInTheDocument());
    expect(versions).toEqual(["4"]);
    expect(client.getQueryState(["media-functions"])?.isInvalidated).toBe(true);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("prevents repeated deletion and closing while the request is pending", async () => {
    serve(settingsFixture());
    let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    let attempts = 0;
    server.use(http.delete("/api/v1/settings/media-connections/openai-1/capabilities/portrait", async () => {
      attempts += 1;
      await pending;
      return HttpResponse.json({ title: "删除失败", detail: "请重试", code: "INTERNAL_ERROR" },
        { status: 500, headers: { "Content-Type": "application/problem+json" } });
    }));
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole("button", { name: "删除能力" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(attempts).toBe(1));
    expect(screen.getByRole("button", { name: "正在删除…" })).toBeDisabled();
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "取消" })).toBeDisabled();
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    finish?.();
    await screen.findByText("请重试");
    expect(screen.getByRole("button", { name: "确认删除" })).toBeEnabled();
    expect(attempts).toBe(1);
  });

  it("allows deleting a disabled capability on a disabled connection", async () => {
    serve(settingsFixture({ enabled: false }, { enabled: false }));
    mount();
    expect(await screen.findByRole("button", { name: "删除能力" })).toBeEnabled();
  });

  it("retains the confirmation and capability when deletion fails, then allows retry", async () => {
    const settings = settingsFixture(); serve(settings);
    let attempts = 0;
    server.use(http.delete("/api/v1/settings/media-connections/openai-1/capabilities/portrait", () => {
      attempts += 1;
      return HttpResponse.json({ title: "删除失败", detail: "请重试", code: "INTERNAL_ERROR" }, { status: 500, headers: { "Content-Type": "application/problem+json" } });
    }));
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole("button", { name: "删除能力" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    expect(await within(screen.getByRole("dialog")).findByText(/请重试/)).toBeInTheDocument();
    expect(screen.getByText("Portrait")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(attempts).toBe(2));
  });

  it("requires loading the new version after a concurrent edit", async () => {
    let settings = settingsFixture(); serve(settings);
    const versions: string[] = [];
    server.use(http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.delete("/api/v1/settings/media-connections/openai-1/capabilities/portrait", ({ request }) => {
        versions.push(new URL(request.url).searchParams.get("expectedVersion")!);
        settings = settingsFixture({}, { version: 5 });
        return HttpResponse.json({ title: "配置冲突", detail: "能力已修改", code: "MEDIA_CAPABILITY_CONFLICT" }, { status: 409 });
      }));
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole("button", { name: "删除能力" }));
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "确认删除" })).toBeDisabled());
    await user.click(screen.getByRole("button", { name: /载入最新/ }));
    expect(screen.getByRole("button", { name: "确认删除" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() => expect(versions).toEqual(["4", "5"]));
  });

  it("does not offer deletion for built-in local capabilities", async () => {
    serve(settingsFixture({ platform: "LOCAL" }, { adapterId: "LOCAL_IMAGE_PROCESSOR" }));
    mount(); await screen.findByText("Portrait");
    expect(screen.queryByRole("button", { name: "删除能力" })).not.toBeInTheDocument();
  });
});
