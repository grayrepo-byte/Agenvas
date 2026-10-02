import { useState } from "react";
import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { MediaCapability } from "../../shared/api/client";
import { AUTODL_WORKFLOW_LABEL_KEYS, getAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { setLocale, SUPPORTED_LOCALES, t, translate } from "../../shared/i18n";
import { server } from "../../test/server";
import { selectValue } from "../../test/controls";
import { AutoDlWorkflowFields } from "./AutoDlWorkflowFields";

type Settings = MediaCapability["settings"];
const definition: NonNullable<Settings["workflowDefinition"]> = {
  schemaVersion: 1, id: "future_video_v1", label: "Future video", minimumSeconds: 1, maximumSeconds: 20,
  promptLimit: 10000, mode: "TEXT", imageFields: [], audioFields: [], minimumImages: 0, minimumAudios: 0,
  resolutions: ["720p横(1280*720)", "720p竖(720*1280)", "2160p横(3840*2160)"], defaultResolution: "720p", supportsSeed: false,
};
function mount(initial: Settings = { workflowId: "minimax_h3_z0901", videoResolution: "480p" }) {
  let current = initial;
  function Harness() {
    const [values, setValues] = useState(initial);
    return <AutoDlWorkflowFields values={values} onChange={(next) => { current = next; setValues(next); }} />;
  }
  server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "fake" })));
  render(<QueryClientProvider client={createQueryClient()}><Harness /></QueryClientProvider>);
  return () => current;
}

describe("AutoDL workflow discovery", () => {
  it("localizes preset labels without putting message keys into submitted protocol metadata", async () => {
    const current = mount();
    const id = "minimax_h3_z0901";
    for (const locale of SUPPORTED_LOCALES) {
      act(() => { setLocale(locale); });
      expect(screen.getByRole("combobox", { name: t("settings.autoDl.workflow") }))
        .toHaveTextContent(translate(locale, AUTODL_WORKFLOW_LABEL_KEYS[id]));
    }
    await selectValue(screen.getByRole("combobox", { name: t("settings.autoDl.workflow") }), "custom");
    expect(current().workflowDefinition).toEqual({ ...getAutoDlWorkflow(id), defaultResolution: "480p", schemaVersion: 1 });
    expect(current().workflowDefinition).not.toHaveProperty("labelKey");
    expect(current().workflowDefinition?.label).toBe("H3文生视频（高质量创意直出）");
  });

  it("refreshes new candidates and imports all their resolution tiers before publication", async () => {
    server.use(
      http.get("/api/v1/settings/autodl-workflows", () => HttpResponse.json({ items: [{ id: definition.id, label: definition.label }, { id: "wan2.2animate-v4-motion_retargeting", label: "Video reference" }] })),
      http.post("/api/v1/settings/autodl-workflows/preview", async ({ request }) => {
        expect(await request.json()).toEqual({ workflowId: definition.id });
        return HttpResponse.json(definition);
      }),
    );
    const current = mount(); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "刷新官方工作流目录" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "刷新官方工作流目录" })).toBeEnabled());
    await selectValue(screen.getByRole("combobox", { name: "AutoDL 工作流" }), definition.id);
    expect(await screen.findByRole("textbox", { name: "工作流 ID" })).toHaveValue(definition.id);
    expect(within(screen.getByRole("group", { name: "可选视频分辨率" })).getByRole("button", { name: "2160p" })).toBeInTheDocument();
    expect(current().workflowDefinition).toEqual(definition);
    expect(current().videoResolutions).toEqual(["720p", "2160p"]);
    expect(current().videoResolution).toBe("720p");
  });
  it("keeps the reviewed definition and prices when official discovery fails", async () => {
    server.use(http.post("/api/v1/settings/autodl-workflows/preview", () => HttpResponse.json({ status: 422, code: "AUTODL_WORKFLOW_UNSUPPORTED", detail: "工作流协议不支持" }, { status: 422, headers: { "Content-Type": "application/problem+json" } })));
    const initial: Settings = { workflowId: definition.id, workflowDefinition: definition, videoResolution: "720p", videoResolutions: ["720p"], pricingByResolution: { "720p": { amount: "1", currency: "CNY", unit: "VIDEO" } } };
    const current = mount(initial); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "读取官方输入定义" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("工作流协议不支持");
    expect(current()).toEqual(initial);
  });
  it("imports offline metadata using its identity without a fixed catalog option", async () => {
    const source = { data: { uuid: definition.id, name: definition.label, input_rules: {} } };
    server.use(http.post("/api/v1/settings/autodl-workflows/preview", async ({ request }) => {
      expect(await request.json()).toEqual({ workflowId: definition.id, source });
      return HttpResponse.json(definition);
    }));
    const current = mount(); const user = userEvent.setup();
    await user.click(screen.getByText("离线导入官方详情"));
    await user.click(screen.getByRole("button", { name: "导入官方详情 JSON" }));
    expect(current().workflowDefinition).toBeUndefined();
    await user.click(screen.getByRole("textbox", { name: "官方工作流详情 JSON（可选）" }));
    await user.paste(JSON.stringify(source));
    await user.click(screen.getByRole("button", { name: "导入官方详情 JSON" }));
    await waitFor(() => expect(current().workflowDefinition?.id).toBe(definition.id));
  });
  it("allows manual same-protocol targets and exact enums without editing source code", async () => {
    const current = mount(); const user = userEvent.setup();
    await selectValue(screen.getByRole("combobox", { name: "AutoDL 工作流" }), "custom");
    const id = screen.getByRole("textbox", { name: "工作流 ID" });
    await user.clear(id); await user.type(id, "manual_video_v1");
    const enums = screen.getByRole("textbox", { name: "供应商精确分辨率枚举（每行一个）" });
    await user.clear(enums); await user.type(enums, "720p横(1280*720){Enter}720p竖(720*1280){Enter}");
    await user.tab();
    expect(current().workflowId).toBe("manual_video_v1");
    expect(current().videoResolution).toBe("720p");
    expect(current().workflowDefinition?.resolutions).toEqual(["720p横(1280*720)", "720p竖(720*1280)"]);
  });
  it("refreshes the same target without dropping its published tiers and price overrides", async () => {
    server.use(http.post("/api/v1/settings/autodl-workflows/preview", () => HttpResponse.json(definition)));
    const initial: Settings = { workflowId: definition.id, workflowDefinition: definition, videoResolution: "720p", videoResolutions: ["720p"], pricingByResolution: { "720p": { amount: "0.2", currency: "CNY", unit: "SECOND" } } };
    const current = mount(initial); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "读取官方输入定义" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "读取官方输入定义" })).toBeEnabled());
    expect(current().pricingByResolution).toEqual(initial.pricingByResolution);
    expect(current().videoResolutions).toEqual(["720p"]);
  });
});
