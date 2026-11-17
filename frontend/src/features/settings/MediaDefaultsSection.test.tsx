import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaSettings } from "../../shared/api/client";
import { selectValue } from "../../test/controls";
import { imageFunctionsFixture } from "../../test/imageFunctionsFixture";
import { server } from "../../test/server";
import { MediaDefaultsSection } from "./MediaDefaultsSection";

function fixture(): MediaSettings {
  const result = imageFunctionsFixture();
  const connection = result.connections[1]!;
  const capability = connection.capabilities[0]!;
  connection.capabilities.push({ ...capability, id: "alternative", name: "备用图片模型" });
  result.defaults = [{ kind: "IMAGE_GENERATION", capabilityId: capability.id, version: 7 }];
  return result;
}

function mount(settings: MediaSettings) {
  const client = createQueryClient();
  const onApply = vi.fn();
  const onPublish = vi.fn();
  const view = render(
    <QueryClientProvider client={client}>
      <MediaDefaultsSection settings={settings} onApply={onApply} onPublish={onPublish} />
    </QueryClientProvider>,
  );
  return {
    onApply,
    onPublish,
    rerender: (next: MediaSettings) =>
      view.rerender(
        <QueryClientProvider client={client}>
          <MediaDefaultsSection settings={next} onApply={onApply} onPublish={onPublish} />
        </QueryClientProvider>,
      ),
  };
}

async function openImageDefault() {
  await userEvent
    .setup()
    .click(within(screen.getByRole("article", { name: "图片创作" })).getByRole("button", { name: "更换默认能力" }));
  return screen.getByRole("dialog");
}

describe("MediaDefaultsSection", () => {
  it("saves the chosen image capability with the default version and excludes unavailable choices", async () => {
    const settings = fixture();
    const cloud = settings.connections[1]!;
    cloud.capabilities.push({ ...cloud.capabilities[0]!, id: "disabled", name: "停用模型", enabled: false });
    settings.connections.push({
      ...cloud,
      id: "disabled-service",
      name: "停用服务",
      enabled: false,
      capabilities: [{ ...cloud.capabilities[0]!, id: "disabled-service-model", name: "不可用服务模型" }],
    });
    const writes: { kind: string | readonly string[]; body: unknown }[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-defaults/:kind", async ({ request, params }) => {
        writes.push({ kind: params.kind!, body: await request.json() });
        return HttpResponse.json(settings);
      }),
    );
    const { onApply } = mount(settings);
    const dialog = await openImageDefault();
    const control = within(dialog).getByRole("combobox", { name: "默认能力" });
    await userEvent.setup().click(control);
    const list = screen.getByRole("listbox");
    expect(
      within(list).queryByRole("option", { name: /本地图片处理|停用模型|不可用服务模型/ }),
    ).not.toBeInTheDocument();
    await userEvent.setup().click(within(list).getByRole("option", { name: "备用图片模型 · 合成图片服务" }));
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "保存默认能力" }));
    await waitFor(() =>
      expect(writes).toEqual([{ kind: "IMAGE_GENERATION", body: { expectedVersion: 7, capabilityId: "alternative" } }]),
    );
    await waitFor(() => expect(onApply).toHaveBeenCalledWith(settings));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("keeps the selected capability in the dialog after a failed save", async () => {
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/media-defaults/IMAGE_GENERATION", () =>
        HttpResponse.json(
          {
            title: "Unavailable",
            detail: "暂时无法保存",
            code: "SYNTHETIC_FAILURE",
            status: 503,
          },
          { status: 503, headers: { "Content-Type": "application/problem+json" } },
        ),
      ),
    );
    const { onApply } = mount(fixture());
    const dialog = await openImageDefault();
    await selectValue(within(dialog).getByRole("combobox", { name: "默认能力" }), "alternative");
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "保存默认能力" }));
    await waitFor(() => expect(within(dialog).getByRole("alert")).toHaveTextContent("暂时无法保存"));
    expect(within(dialog).getByRole("combobox", { name: "默认能力" })).toHaveValue("alternative");
    expect(within(dialog).getByRole("button", { name: "保存默认能力" })).toBeEnabled();
    expect(onApply).not.toHaveBeenCalled();
  });

  it("blocks a stale draft until an explicit reload and saves the refreshed version", async () => {
    const initial = fixture();
    const latest = structuredClone(initial);
    latest.defaults[0]!.version = 9;
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.get("/api/v1/settings/media-connections", () => HttpResponse.json(latest)),
      http.put("/api/v1/settings/media-defaults/IMAGE_GENERATION", async ({ request }) => {
        writes.push(await request.json());
        return HttpResponse.json(latest);
      }),
    );
    const { rerender } = mount(initial);
    const dialog = await openImageDefault();
    await selectValue(within(dialog).getByRole("combobox", { name: "默认能力" }), "alternative");
    rerender(latest);
    expect(within(dialog).getByText("配置已有新版本")).toBeInTheDocument();
    expect(within(dialog).getByRole("combobox", { name: "默认能力" })).toHaveValue("alternative");
    expect(within(dialog).getByRole("button", { name: "保存默认能力" })).toBeDisabled();
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "载入最新版本" }));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "保存默认能力" })).toBeEnabled());
    await selectValue(within(dialog).getByRole("combobox", { name: "默认能力" }), "alternative");
    await userEvent.setup().click(within(dialog).getByRole("button", { name: "保存默认能力" }));
    await waitFor(() => expect(writes).toEqual([{ expectedVersion: 9, capabilityId: "alternative" }]));
  });
});
