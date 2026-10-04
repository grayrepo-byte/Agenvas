import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { selectValue, clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { imageFunctionSettings, imageFunctionsFixture, imageWorkflowCapability } from "../../test/imageFunctionsFixture";
import { videoFunctionsFixture } from "../../test/videoFunctionsFixture";
import { MediaFunctionSettingsPage } from "./MediaFunctionSettingsPage";

describe("MediaFunctionSettingsPage", () => {
  beforeEach(() => server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "synthetic-admin", loginName: "synthetic-admin", roles: ["ADMIN"] })),
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(videoFunctionsFixture())),
    http.get("/api/v1/settings/media-functions", () => HttpResponse.json([
      { operation: "VIDEO_UPSCALE", capabilityId: null, version: 4 },
      { operation: "VIDEO_DEPTH_MAP", capabilityId: "depth-cap", version: 0 },
      { operation: "VIDEO_EXTRACT_AUDIO", capabilityId: "audio-cap", version: 0 },
    ])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  ));
  function mount() {
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><MediaFunctionSettingsPage /></MemoryRouter></QueryClientProvider>);
  }
  it("saves the compatible workflow with CAS independently of model defaults", async () => {
    let request: unknown;
    server.use(http.put("/api/v1/settings/media-functions/VIDEO_UPSCALE", async ({ request: incoming }) => {
      request = await incoming.json(); return HttpResponse.json([{ operation: "VIDEO_UPSCALE", capabilityId: "upscale-cap", version: 5 }]);
    }));
    mount();
    expect(await screen.findByRole("link", { name: "功能设置" })).toHaveAttribute("href", "/settings/functions");
    const row = await screen.findByRole("region", { name: "视频高清" });
    await selectValue(within(row).getByRole("combobox"), "upscale-cap");
    await clickControl(within(row).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(request).toEqual({ expectedVersion: 4, capabilityId: "upscale-cap" }));
    expect(await within(row).findByRole("status")).toHaveTextContent("功能设置已保存");
  });
  it("saves an image workflow independently of the video function with the same operation", async () => {
    const settings = imageFunctionsFixture();
    settings.connections.push({ ...videoFunctionsFixture().connections[1]!, capabilities: [imageWorkflowCapability()] });
    const functions = [...imageFunctionSettings(), { operation: "VIDEO_UPSCALE", capabilityId: "upscale-cap", version: 4 }];
    let request: unknown;
    server.use(http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/settings/media-functions", () => HttpResponse.json(functions)),
      http.put("/api/v1/settings/media-functions/IMAGE_UPSCALE", async ({ request: incoming }) => {
        request = await incoming.json(); return HttpResponse.json(functions.map((entry) => entry.operation === "IMAGE_UPSCALE"
          ? { ...entry, capabilityId: "image-upscale", version: 4 } : entry));
      }));
    mount();
    const image = await screen.findByRole("region", { name: "高清放大" });
    await selectValue(within(image).getByRole("combobox"), "image-upscale");
    await clickControl(within(image).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(request).toEqual({ expectedVersion: 3, capabilityId: "image-upscale" }));
    expect(await within(image).findByRole("status")).toHaveTextContent("功能设置已保存");
    expect(within(screen.getByRole("region", { name: "视频高清" })).getByRole("button", { name: "保存配置" })).toBeDisabled();
  });
  it("can disable an image function while retaining failed selections and excluding incompatible generation templates", async () => {
    const settings = imageFunctionsFixture();
    settings.connections[1]!.capabilities.push({ ...imageWorkflowCapability(), id: "wrong-template", name: "Unsupported generator", adapterId: "COMFY_IMAGE_V1", settings: {}, maxReferenceImages: 0 });
    let request: unknown;
    server.use(http.get("/api/v1/settings/media-connections", () => HttpResponse.json(settings)),
      http.get("/api/v1/settings/media-functions", () => HttpResponse.json(imageFunctionSettings())),
      http.put("/api/v1/settings/media-functions/IMAGE_SMART_EDIT", async ({ request: incoming }) => {
        request = await incoming.json(); return HttpResponse.json(imageFunctionSettings().map((entry) => entry.operation === "IMAGE_SMART_EDIT"
          ? { ...entry, capabilityId: null, version: 4 } : entry));
      }));
    mount();
    const row = await screen.findByRole("region", { name: "智能编辑" });
    await clickControl(within(row).getByRole("combobox"));
    expect(screen.getByRole("option", { name: /合成图片模型/ })).toBeVisible();
    expect(screen.queryByRole("option", { name: /Unsupported generator/ })).not.toBeInTheDocument();
    await clickControl(screen.getByRole("option", { name: "未配置 / 停用" }));
    await clickControl(within(row).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(request).toEqual({ expectedVersion: 3, capabilityId: null }));
  });
  it("preserves the selected method after a save failure", async () => {
    server.use(http.put("/api/v1/settings/media-functions/VIDEO_UPSCALE", () => HttpResponse.json({ title: "保存失败", detail: "保存失败" }, { status: 503, headers: { "Content-Type": "application/problem+json" } })));
    mount();
    const row = await screen.findByRole("region", { name: "视频高清" });
    await selectValue(within(row).getByRole("combobox"), "upscale-cap");
    await clickControl(within(row).getByRole("button", { name: "保存配置" }));
    expect(await within(row).findByText("保存失败")).toBeVisible();
    expect(within(row).getByRole("combobox")).toHaveTextContent("AI 超分");
    expect(within(row).getByRole("button", { name: "保存配置" })).toBeEnabled();
  });
});
