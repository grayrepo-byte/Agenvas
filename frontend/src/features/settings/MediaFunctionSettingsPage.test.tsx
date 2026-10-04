import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { selectValue, clickControl } from "../../test/controls";
import { server } from "../../test/server";
import { videoFunctionsFixture } from "../../test/videoFunctionsFixture";
import { MediaFunctionSettingsPage } from "./MediaFunctionSettingsPage";

describe("MediaFunctionSettingsPage", () => {
  beforeEach(() => server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "synthetic-admin", loginName: "synthetic-admin", roles: ["ADMIN"] })),
    http.get("/api/v1/settings/media-connections", () => HttpResponse.json(videoFunctionsFixture())),
    http.get("/api/v1/settings/media-functions", () => HttpResponse.json([
      { operation: "UPSCALE", capabilityId: null, version: 4 },
      { operation: "DEPTH_MAP", capabilityId: "depth-cap", version: 0 },
      { operation: "EXTRACT_AUDIO", capabilityId: "audio-cap", version: 0 },
    ])),
    http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
  ));
  function mount() {
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><MediaFunctionSettingsPage /></MemoryRouter></QueryClientProvider>);
  }
  it("saves the compatible workflow with CAS independently of model defaults", async () => {
    let request: unknown;
    server.use(http.put("/api/v1/settings/media-functions/UPSCALE", async ({ request: incoming }) => {
      request = await incoming.json(); return HttpResponse.json([{ operation: "UPSCALE", capabilityId: "upscale-cap", version: 5 }]);
    }));
    mount();
    const row = await screen.findByRole("region", { name: "视频高清" });
    await selectValue(within(row).getByRole("combobox"), "upscale-cap");
    await clickControl(within(row).getByRole("button", { name: "保存配置" }));
    await waitFor(() => expect(request).toEqual({ expectedVersion: 4, capabilityId: "upscale-cap" }));
    expect(await within(row).findByRole("status")).toHaveTextContent("功能设置已保存");
  });
  it("preserves the selected method after a save failure", async () => {
    server.use(http.put("/api/v1/settings/media-functions/UPSCALE", () => HttpResponse.json({ title: "保存失败", detail: "保存失败" }, { status: 503, headers: { "Content-Type": "application/problem+json" } })));
    mount();
    const row = await screen.findByRole("region", { name: "视频高清" });
    await selectValue(within(row).getByRole("combobox"), "upscale-cap");
    await clickControl(within(row).getByRole("button", { name: "保存配置" }));
    expect(await within(row).findByText("保存失败")).toBeVisible();
    expect(within(row).getByRole("combobox")).toHaveTextContent("AI 超分");
    expect(within(row).getByRole("button", { name: "保存配置" })).toBeEnabled();
  });
});
