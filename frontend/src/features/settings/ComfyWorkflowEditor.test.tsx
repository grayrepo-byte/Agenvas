import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { useState } from "react";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { ComfyUiWorkflowDefinition, MediaCapability, RunningHubField } from "../../shared/api/client";
import { changeControl, selectValue } from "../../test/controls";
import { server } from "../../test/server";
import { ComfyWorkflowEditor } from "./ComfyWorkflowEditor";
import { definition, graph } from "./comfyWorkflowFixture";
import { comfyParameterKey, comfyWorkflowProblem } from "./comfyWorkflow";

const stepsParameter: RunningHubField = { key: "input_14_steps", label: "采样步数", type: "INTEGER", nodeId: "14", fieldName: "steps",
  source: "PARAMETER", defaultValue: 20, required: false, advanced: false };

function Harness({ initial }: { initial?: ComfyUiWorkflowDefinition }) {
  const [settings, setSettings] = useState<MediaCapability["settings"]>({ comfyWorkflow: initial });
  const [ready, setReady] = useState(false);
  const [queryClient] = useState(createQueryClient);
  return <QueryClientProvider client={queryClient}><form>
    <ComfyWorkflowEditor connectionId="comfy" adapterId="COMFY_VIDEO_V1" values={settings} onChange={setSettings} onReadyChange={setReady} />
    <button type="submit" disabled={!ready}>Publish fixture</button>
    <output data-testid="settings">{JSON.stringify(settings)}</output>
  </form></QueryClientProvider>;
}

describe("ComfyWorkflowEditor", () => {
  beforeEach(() => server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" }))));
  it("imports API JSON, preserves custom nodes and constants, maps inputs and requires review", async () => {
    const requests: unknown[] = [];
    server.use(http.post("/api/v1/settings/media-connections/comfy/comfyui/preview", async ({ request }) => {
      requests.push(await request.json()); return HttpResponse.json(graph);
    }));
    render(<Harness />); const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await user.upload(screen.getByLabelText("选择 API JSON 文件"), new File([JSON.stringify(graph)], "workflow.json", { type: "application/json" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "解析工作流" })).toBeEnabled());
    await user.click(screen.getByRole("button", { name: "解析工作流" }));
    await screen.findByRole("button", { name: /#11.*CustomTextEncoder/ });
    expect(requests).toEqual([{ workflowJson: JSON.stringify(graph) }]);
    expect(screen.getByLabelText("model 的固定值")).toHaveValue("custom-model.gguf");
    await selectValue(screen.getByRole("combobox", { name: "text 的参数来源" }), "PROMPT");
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    expect(screen.queryByRole("combobox", { name: "conditioning 的参数来源" })).not.toBeInTheDocument();
    await selectValue(screen.getByRole("combobox", { name: "seconds 的参数来源" }), "DURATION_SECONDS");
    await selectValue(screen.getByRole("combobox", { name: "结果节点" }), "99");
    await selectValue(screen.getByRole("combobox", { name: "结果字段" }), "videos");
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" }));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    await user.click(screen.getByRole("button", { name: "2. 节点与参数映射" }));
    const steps = screen.getByLabelText("steps 的固定值");
    await user.clear(steps); await user.type(steps, "30");
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
  });

  it("keeps the current graph when replacement import fails and shows the failure", async () => {
    server.use(http.post("/api/v1/settings/media-connections/comfy/comfyui/preview", () => HttpResponse.json({ title: "Invalid workflow", detail: "API format required", code: "COMFYUI_WORKFLOW_INVALID" }, { status: 422, headers: { "Content-Type": "application/problem+json" } })));
    render(<Harness initial={definition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "1. 导入工作流" }));
    await user.type(screen.getByLabelText("API JSON 内容"), "invalid");
    await user.click(screen.getByRole("button", { name: "替换工作流并重新映射" }));
    expect(await screen.findByText("API format required")).toBeInTheDocument();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
  });

  it("accepts promptless workflows but rejects disconnected mappings, incomplete dimensions and noncontiguous images", () => {
    expect(comfyWorkflowProblem(undefined, true)).toContain("导入");
    expect(comfyWorkflowProblem({ ...definition, bindings: definition.bindings.filter((binding) => binding.source !== "PROMPT") }, true)).toBeNull();
    expect(comfyWorkflowProblem({ ...definition, bindings: [], output: { nodeId: "99", field: "images" }, minimumSeconds: 0, maximumSeconds: 0 }, false)).toBeNull();
    expect(comfyWorkflowProblem({ ...definition, bindings: [...definition.bindings, { nodeId: "14", inputName: "steps", source: "WIDTH" }] }, true)).toContain("同时映射");
    expect(comfyWorkflowProblem({ ...definition, graph: { ...graph, "88": { class_type: "Other", inputs: { text: "unused" } } },
      bindings: [...definition.bindings, { nodeId: "88", inputName: "text", source: "NEGATIVE_PROMPT" }] }, true)).toContain("连接");
    expect(comfyWorkflowProblem({ ...definition, bindings: [...definition.bindings, { nodeId: "11", inputName: "model", source: "REFERENCE_IMAGE", referenceIndex: 1 }] }, true)).toContain("连续");
  });

  it("exposes a scalar input in the extended table and retains typed defaults and limits", async () => {
    render(<Harness initial={definition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "steps 的参数来源" }), "PARAMETER");
    const row = within(screen.getByRole("table", { name: "扩展参数" })).getByRole("row", { name: "#14.steps" });
    const label = within(row).getByRole("textbox", { name: "显示名称" });
    await user.clear(label); await user.type(label, "采样步数");
    const defaultValue = within(row).getByRole("textbox", { name: "默认值" });
    await user.clear(defaultValue); await user.type(defaultValue, "24"); await user.tab();
    const minimum = within(row).getByRole("spinbutton", { name: "最小值" });
    await user.type(minimum, "1");
    const maximum = within(row).getByRole("spinbutton", { name: "最大值" });
    await user.type(maximum, "50");
    await user.click(within(row).getByRole("checkbox", { name: "必填" }));
    const workflow = JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow;
    expect(workflow.parameters).toEqual([{ ...stepsParameter, label: "采样步数", defaultValue: 24, minimum: 1, maximum: 50, required: true }]);
    expect(workflow.graph).toEqual(graph);
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    await user.click(screen.getByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" }));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
  });

  it("keeps invalid scalar edits local and removes exposure when remapping the target", async () => {
    render(<Harness initial={{ ...definition, parameters: [stepsParameter] }} />); const user = userEvent.setup();
    const row = within(screen.getByRole("table", { name: "扩展参数" })).getByRole("row", { name: "#14.steps" });
    const input = within(row).getByRole("textbox", { name: "默认值" });
    await user.click(input); await changeControl(input, { target: { value: "[1]" } }); await user.tab();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow.parameters[0].defaultValue).toBe(20);
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "steps 的参数来源" }), "FIXED");
    expect(screen.queryByRole("table", { name: "扩展参数" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeEnabled();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow.parameters).toEqual([]);
  });

  it("edits numeric enum options without changing their scalar type", async () => {
    render(<Harness initial={{ ...definition, parameters: [stepsParameter] }} />); const user = userEvent.setup();
    const row = within(screen.getByRole("table", { name: "扩展参数" })).getByRole("row", { name: "#14.steps" });
    await selectValue(within(row).getByRole("combobox", { name: "类型" }), "SELECT");
    const options = within(row).getByRole("textbox", { name: "枚举选项（JSON 数组）" });
    await user.click(options); await changeControl(options, { target: { value: '[20,30]' } }); await user.tab();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow.parameters[0].options)
      .toEqual([{ label: "20", value: 20 }, { label: "30", value: 30 }]);
    const updatedOptions = within(row).getByRole("textbox", { name: "枚举选项（JSON 数组）" });
    await user.click(updatedOptions); await changeControl(updatedOptions, { target: { value: '["20"]' } }); await user.tab();
    expect(updatedOptions).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeDisabled();
  });

  it("validates scalar exposure type, defaults, targets, ancestry, and stable keys", () => {
    expect(comfyWorkflowProblem({ ...definition, parameters: [stepsParameter] }, true)).toBeNull();
    expect(comfyWorkflowProblem({ ...definition, parameters: [{ ...stepsParameter, type: "STRING" }] }, true)).toContain("类型");
    expect(comfyWorkflowProblem({ ...definition, parameters: [{ ...stepsParameter, defaultValue: 2.5 }] }, true)).toContain("类型");
    expect(comfyWorkflowProblem({ ...definition, parameters: [{ ...stepsParameter, minimum: 50 }] }, true)).toContain("范围");
    expect(comfyWorkflowProblem({ ...definition, parameters: [{ ...stepsParameter, fieldName: "seconds" }] }, true)).toContain("重复");
    expect(comfyWorkflowProblem({ ...definition, parameters: [stepsParameter, { ...stepsParameter, fieldName: "model", nodeId: "11" }] }, true)).toContain("重复");
    expect(comfyWorkflowProblem({ ...definition, graph: { ...graph, "88": { class_type: "Other", inputs: { steps: 20 } } },
      parameters: [{ ...stepsParameter, nodeId: "88" }] }, true)).toContain("连接");
    expect(comfyWorkflowProblem({ ...definition, parameters: [{ ...stepsParameter, type: "SELECT", options: [{ label: "20", value: "20" }] }] }, true)).toContain("类型");
    const key = comfyParameterKey("14", "a".repeat(160), []);
    expect(key).toMatch(/^[A-Za-z][A-Za-z0-9_]{0,63}$/);
    expect(comfyParameterKey("14", "a".repeat(160), [{ ...stepsParameter, key }])).not.toBe(key);
  });
});
