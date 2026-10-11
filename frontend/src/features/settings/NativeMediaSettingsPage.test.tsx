import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaSettings } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { nativePlatform, nativeProfiles } from "../../test/nativeProfiles";
import { server } from "../../test/server";
import { MediaSettingsPage } from "./MediaSettingsPage";

function mount(platform: MediaSettings["connections"][number]["platform"]) {
  const fixture: MediaSettings = { connections: [{ id: "native-connection", name: "Native", platform, origin: null,
    enabled: true, version: 0, connectionVersion: 1, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false, capabilities: [] }], defaults: [] };
  server.use(http.get("/api/v1/settings/media-connections", () => HttpResponse.json(fixture)));
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><MediaSettingsPage /></MemoryRouter></QueryClientProvider>);
  return fixture;
}

describe("native media settings", () => {
  beforeEach(() => server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", role: "ADMIN" })),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
    http.get("/api/v1/settings/media-profiles", () => HttpResponse.json({ profiles: nativeProfiles })),
  ));

  it.each(nativeProfiles)("publishes $adapterId $model $operation from the server catalog", async (profile) => {
    const fixture = mount(nativePlatform(profile.adapterId));
    const writes: unknown[] = [];
    server.use(http.post("/api/v1/settings/media-connections/native-connection/capabilities", async ({ request }) => {
      writes.push(await request.json()); return HttpResponse.json(fixture);
    }));
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    const dialog = screen.getByRole("dialog");
    await selectValue(within(dialog).getByRole("combobox", { name: "固定适配器" }), profile.adapterId);
    const operation = await within(dialog).findByRole("combobox", { name: "生成操作" });
    await selectValue(operation, profile.operation);
    await selectValue(within(dialog).getByRole("combobox", { name: "模型" }), profile.model);
    await user.type(within(dialog).getByRole("textbox", { name: "新能力名称" }), "原生能力");
    await user.click(within(dialog).getByRole("tab", { name: "默认参数" }));
    expect(within(dialog).queryByRole("combobox", { name: "默认分辨率" })).not.toBeInTheDocument();
    const defaults: Record<string, string | number | boolean> = {};
    if (profile.adapterId === "WAN_IMAGE") {
      const count = within(dialog).getByRole("spinbutton", { name: "Image count / sequential maximum" });
      await user.clear(count); await user.type(count, "4"); defaults.n = 4;
    }
    if (profile.adapterId === "ARK_SEEDREAM_IMAGE" && profile.operation !== "LAYER_DECOMPOSITION") {
      await user.clear(within(dialog).getByRole("textbox", { name: "Output size" }));
      await user.type(within(dialog).getByRole("textbox", { name: "Output size" }), "2K"); defaults.size = "2K";
    }
    if (profile.adapterId === "WAN_VIDEO") {
      await user.click(within(dialog).getByRole("tab", { name: "输入限制" }));
      expect(within(dialog).getByRole("spinbutton", { name: "最多参考视频数" })).toHaveAttribute("max", "5");
      expect(within(dialog).getByRole("spinbutton", { name: "最多参考音频数量" })).toHaveAttribute("max", "5");
    }
    await user.click(within(dialog).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(writes).toEqual([{ name: "原生能力", adapterId: profile.adapterId, settings: {
      model: profile.model, operation: profile.operation, ...(Object.keys(defaults).length ? { defaultParameters: { dynamicValues: defaults } } : {}),
    } }]));
  });

  it("preserves Seedream group defaults, image price tiers and idempotency after publication fails", async () => {
    const fixture = mount("ARK");
    const writes: { body: unknown; key: string | null }[] = [];
    server.use(http.post("/api/v1/settings/media-connections/native-connection/capabilities", async ({ request }) => {
      writes.push({ body: await request.json(), key: request.headers.get("Idempotency-Key") });
      return writes.length === 1 ? HttpResponse.json({ detail: "发布失败" }, { status: 503 }) : HttpResponse.json(fixture);
    }));
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    const dialog = screen.getByRole("dialog");
    await selectValue(within(dialog).getByRole("combobox", { name: "固定适配器" }), "ARK_SEEDREAM_IMAGE");
    await selectValue(await within(dialog).findByRole("combobox", { name: "模型" }), "doubao-seedream-5-0-260128");
    await user.type(within(dialog).getByRole("textbox", { name: "新能力名称" }), "完整组图");
    await user.click(within(dialog).getByRole("tab", { name: "默认参数" }));
    await selectValue(within(dialog).getByRole("combobox", { name: "Sequential images" }), "1");
    const count = within(dialog).getByRole("spinbutton", { name: "Maximum images" });
    await user.clear(count); await user.type(count, "6");
    await user.click(within(dialog).getByRole("tab", { name: "估算价格" }));
    await user.type(within(dialog).getByRole("spinbutton", { name: "4K 单位价格" }), "0.3");
    expect(within(dialog).getByRole("combobox", { name: "4K 计价单位" })).toHaveTextContent("每张图片");
    await user.click(within(dialog).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "发布能力" })).toBeEnabled());
    await user.click(within(dialog).getByRole("tab", { name: "默认参数" }));
    expect(within(dialog).getByRole("spinbutton", { name: "Maximum images" })).toHaveValue(6);
    await user.click(within(dialog).getByRole("button", { name: "发布能力" }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]?.body).toEqual({ name: "完整组图", adapterId: "ARK_SEEDREAM_IMAGE", settings: {
      model: "doubao-seedream-5-0-260128", operation: "GENERATE", defaultParameters: { dynamicValues: { sequential_image_generation: "auto", max_images: 6 } },
      pricingByResolution: { "4K": { amount: "0.3", currency: "CNY", unit: "IMAGE" } },
    } });
  });

  it("keeps the chosen workspace and credentials after a failed Wan connection creation", async () => {
    const fixture = mount("WAN");
    const writes: { body: unknown; key: string | null }[] = [];
    server.use(http.post("/api/v1/settings/media-connections", async ({ request }) => {
      writes.push({ body: await request.json(), key: request.headers.get("Idempotency-Key") });
      return writes.length === 1 ? HttpResponse.json({ detail: "临时失败" }, { status: 503 }) : HttpResponse.json(fixture);
    }));
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "添加连接" }));
    const dialog = screen.getByRole("dialog");
    await selectValue(within(dialog).getByRole("combobox", { name: "平台" }), "WAN");
    await user.type(within(dialog).getByRole("textbox", { name: "连接名称" }), "万相工作空间");
    const origin = "https://synthetic-workspace.cn-beijing.maas.aliyuncs.com";
    await user.type(within(dialog).getByRole("textbox", { name: /API Base URL/ }), origin);
    await user.type(within(dialog).getByLabelText("API Key"), "synthetic-wan-key");
    await user.click(within(dialog).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(writes).toHaveLength(1));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "添加连接" })).toBeEnabled());
    expect(within(dialog).getByRole("textbox", { name: /API Base URL/ })).toHaveValue(origin);
    expect(within(dialog).getByLabelText("API Key")).toHaveValue("synthetic-wan-key");
    await user.click(within(dialog).getByRole("button", { name: "添加连接" }));
    await waitFor(() => expect(writes).toHaveLength(2));
    expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]?.body).toEqual({ platform: "WAN", name: "万相工作空间", origin, apiKey: "synthetic-wan-key" });
  });

  it("blocks publication until catalog retry succeeds and keeps the entered name", async () => {
    server.use(http.get("/api/v1/settings/media-profiles", () => HttpResponse.json({ detail: "目录不可用" }, { status: 503 })));
    mount("WAN"); const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "发布能力" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByRole("textbox", { name: "新能力名称" }), "保留目录草稿");
    await within(dialog).findByRole("alert");
    expect(within(dialog).getByRole("button", { name: "发布能力" })).toBeDisabled();
    server.use(http.get("/api/v1/settings/media-profiles", () => HttpResponse.json({ profiles: nativeProfiles })));
    await user.click(within(dialog).getByRole("button", { name: "重试" }));
    await within(dialog).findByRole("combobox", { name: "生成操作" });
    expect(within(dialog).getByRole("textbox", { name: "新能力名称" })).toHaveValue("保留目录草稿");
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "发布能力" })).toBeEnabled());
  });
});
