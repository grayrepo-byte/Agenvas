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
import { comfyParameterKey, comfyReferenceCount, comfyWorkflowProblem, mapComfyInput } from "./comfyWorkflow";

const stepsParameter: RunningHubField = { key: "input_14_steps", label: "采样步数", type: "INTEGER", nodeId: "14", fieldName: "steps",
  source: "PARAMETER", defaultValue: 20, required: false, advanced: false };
const dimensionGraph = { ...graph, "14": { ...graph["14"]!, inputs: {
  ...graph["14"]!.inputs, width: 640, height: 360, secondaryWidth: 1280,
} } };
const dimensionDefinition: ComfyUiWorkflowDefinition = { ...definition, graph: dimensionGraph, width: 1536, height: 768 };

function currentWorkflow(): ComfyUiWorkflowDefinition {
  return (JSON.parse(screen.getByTestId("settings").textContent ?? "{}") as MediaCapability["settings"]).comfyWorkflow!;
}

function Harness({ initial, adapterId = "COMFY_VIDEO_V1" }: { initial?: ComfyUiWorkflowDefinition; adapterId?: string }) {
  const [settings, setSettings] = useState<MediaCapability["settings"]>({ comfyWorkflow: initial });
  const [ready, setReady] = useState(false);
  const [queryClient] = useState(createQueryClient);
  return <QueryClientProvider client={queryClient}><form>
    <ComfyWorkflowEditor connectionId="comfy" adapterId={adapterId} values={settings} onChange={setSettings} onReadyChange={setReady} />
    <button type="submit" disabled={!ready}>Publish fixture</button>
    <output data-testid="settings">{JSON.stringify(settings)}</output>
  </form></QueryClientProvider>;
}

describe("ComfyWorkflowEditor", () => {
  beforeEach(() => server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" }))));
  it("maps video and audio files independently and reviews each media count before publishing", async () => {
    const mixed = { ...definition, graph: { ...graph, "14": { ...graph["14"]!, inputs: {
      ...graph["14"]!.inputs, video_file: "reference.mp4", audio_file: "reference.wav",
    } } } };
    render(<Harness initial={mixed} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "video_file 的参数来源" }), "REFERENCE_VIDEO");
    await selectValue(screen.getByRole("combobox", { name: "audio_file 的参数来源" }), "REFERENCE_AUDIO");
    expect(currentWorkflow().bindings).toEqual(expect.arrayContaining([
      { nodeId: "14", inputName: "video_file", source: "REFERENCE_VIDEO", referenceIndex: 0 },
      { nodeId: "14", inputName: "audio_file", source: "REFERENCE_AUDIO", referenceIndex: 0 },
    ]));
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    await changeControl(screen.getByLabelText("video_file 的素材序号"), { target: { value: "2" } });
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await changeControl(screen.getByLabelText("video_file 的素材序号"), { target: { value: "1" } });
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.getByText(/需要 0 张图片、1 段视频、1 段音频/)).toBeVisible();
    expect(screen.getByRole("group", { name: "画布输入映射" })).toHaveTextContent("参考视频 #1");
    expect(screen.getByRole("group", { name: "画布输入映射" })).toHaveTextContent("参考音频 #1");
  });

  it("validates per-kind contiguous numbers and the combined fourteen-slot bound", () => {
    const mixedGraph = { ...graph, "14": { ...graph["14"]!, inputs: { ...graph["14"]!.inputs,
      ...Object.fromEntries(Array.from({ length: 15 }, (_, index) => [`media_${index}`, "file"])) } } };
    let mapped: ComfyUiWorkflowDefinition = { ...definition, graph: mixedGraph };
    mapped = mapComfyInput(mapped, "14", "media_0", "REFERENCE_VIDEO")!;
    mapped = mapComfyInput(mapped, "14", "media_1", "REFERENCE_AUDIO")!;
    expect(comfyReferenceCount(mapped, "REFERENCE_VIDEO")).toBe(1);
    expect(comfyReferenceCount(mapped, "REFERENCE_AUDIO")).toBe(1);
    expect(comfyWorkflowProblem(mapped, true)).toBeNull();
    const tooMany: ComfyUiWorkflowDefinition = { ...definition, graph: mixedGraph, bindings: [...definition.bindings,
      ...Array.from({ length: 14 }, (_, index) => ({ nodeId: "14", inputName: `media_${index}`, source: "REFERENCE_IMAGE" as const, referenceIndex: index })),
      { nodeId: "14", inputName: "media_14", source: "REFERENCE_AUDIO", referenceIndex: 0 },
    ] };
    expect(comfyWorkflowProblem(tooMany, true)).toContain("合计最多 14");
  });

  it("searches node names, IDs and types without changing the inspected node or its fixed values", async () => {
    render(<Harness initial={definition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await changeControl(screen.getByLabelText("steps 的固定值"), { target: { value: "32" } });
    const search = screen.getByRole("searchbox", { name: "搜索节点名称、类型或编号" });
    await user.type(search, "99");
    expect(screen.queryByRole("button", { name: /#14.*CustomGenerator/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /#99/ })).toBeInTheDocument();
    expect(screen.getByLabelText("steps 的固定值")).toHaveValue(32);
    await user.clear(search); await user.type(search, "no-matching-node");
    expect(screen.getByText("没有匹配的节点")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    await user.clear(search); await user.type(search, "CustomGenerator");
    expect(screen.getByRole("button", { name: /#14.*CustomGenerator/ })).toHaveAttribute("aria-pressed", "true");
    expect(currentWorkflow().graph["14"]?.inputs.steps).toBe(32);
  });

  it("keeps technical parameter keys collapsed and reviews output separately from defaults and pricing", async () => {
    render(<Harness initial={{ ...definition, parameters: [stepsParameter] }} />); const user = userEvent.setup();
    const row = within(screen.getByRole("table", { name: "扩展参数" })).getByRole("row", { name: "#14.steps" });
    expect(within(row).getByText("input_14_steps")).not.toBeVisible();
    await user.click(within(row).getByText("稳定字段键"));
    expect(within(row).getByText("input_14_steps")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.getByRole("group", { name: "画布输入映射" })).toHaveTextContent("#14.steps");
    expect(screen.getByRole("group", { name: "结果与运行范围" })).toHaveTextContent("#99");
    expect(screen.getByRole("group", { name: "默认参数" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "估算价格" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
  });
  it("imports API JSON, preserves custom nodes and constants, and enables publishing after valid mapping", async () => {
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
    expect(screen.queryByLabelText("基础宽度（像素）")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("基础高度（像素）")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.queryByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    await user.click(screen.getByRole("button", { name: "2. 节点与参数映射" }));
    const steps = screen.getByLabelText("steps 的固定值");
    await user.clear(steps); await user.type(steps, "30");
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
  });

  it.each(["COMFY_IMAGE_V1", "COMFY_VIDEO_V1"])("keeps a valid %s workflow ready without base-size controls or repeated confirmation", async (adapterId) => {
    const initial = adapterId === "COMFY_IMAGE_V1" ? { ...definition,
      bindings: definition.bindings.filter((binding) => binding.source !== "DURATION_SECONDS"),
      output: { ...definition.output, field: "images" as const }, minimumSeconds: 0, maximumSeconds: 0,
    } : definition;
    render(<Harness initial={initial} adapterId={adapterId} />); const user = userEvent.setup();
    const publish = screen.getByRole("button", { name: "Publish fixture" });
    expect(publish).toBeEnabled();
    expect(screen.queryByLabelText("基础宽度（像素）")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("基础高度（像素）")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "检查发布配置" }));
    expect(screen.queryByRole("checkbox", { name: "已核对节点映射、结果节点与估算价格" })).not.toBeInTheDocument();
    await user.type(screen.getByRole("spinbutton", { name: "单位价格" }), "1.25");
    expect(publish).toBeEnabled();
    if (adapterId === "COMFY_VIDEO_V1") {
      await user.type(screen.getByRole("spinbutton", { name: "默认视频时长（秒）" }), "4");
      expect(publish).toBeEnabled();
    } else {
      await selectValue(screen.getByRole("combobox", { name: "默认生成数量" }), "2");
      expect(publish).toBeEnabled();
    }
  });

  it("blocks invalid mappings and enables publishing again when the mapping is corrected", async () => {
    render(<Harness initial={definition} />); const user = userEvent.setup();
    const publish = screen.getByRole("button", { name: "Publish fixture" });
    expect(publish).toBeEnabled();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    const seconds = screen.getByRole("combobox", { name: "seconds 的参数来源" });
    await selectValue(seconds, "FIXED");
    expect(publish).toBeDisabled();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeDisabled();
    await selectValue(seconds, "DURATION_SECONDS");
    expect(publish).toBeEnabled();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeEnabled();
  });

  it("derives first width and height mappings from graph literals and preserves their shared base values", async () => {
    render(<Harness initial={dimensionDefinition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "width 的参数来源" }), "WIDTH");
    expect(currentWorkflow().width).toBe(640);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await selectValue(screen.getByRole("combobox", { name: "height 的参数来源" }), "HEIGHT");
    expect(currentWorkflow().height).toBe(360);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    await selectValue(screen.getByRole("combobox", { name: "secondaryWidth 的参数来源" }), "WIDTH");
    expect(currentWorkflow().width).toBe(640);
    expect(currentWorkflow().graph).toEqual(dimensionGraph);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
  });

  it("preserves published base dimensions until the last binding is removed and remapped", async () => {
    const initial: ComfyUiWorkflowDefinition = { ...dimensionDefinition, bindings: [...definition.bindings,
      { nodeId: "14", inputName: "width", source: "WIDTH" }, { nodeId: "14", inputName: "height", source: "HEIGHT" },
    ] };
    render(<Harness initial={initial} />); const user = userEvent.setup();
    expect(currentWorkflow()).toEqual(initial);
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await changeControl(screen.getByLabelText("steps 的固定值"), { target: { value: "30" } });
    expect(currentWorkflow()).toMatchObject({ width: 1536, height: 768 });
    await selectValue(screen.getByRole("combobox", { name: "width 的参数来源" }), "FIXED");
    await changeControl(screen.getByLabelText("width 的固定值"), { target: { value: "800" } });
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await selectValue(screen.getByRole("combobox", { name: "width 的参数来源" }), "WIDTH");
    expect(currentWorkflow()).toMatchObject({ width: 800, height: 768 });
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
  });

  it("replaces an invalid extended parameter with a dimension mapping and retains the graph and unrelated parameters", async () => {
    const widthParameter: RunningHubField = { ...stepsParameter, key: "published_width", fieldName: "width", defaultValue: 777 };
    const initial = { ...dimensionDefinition, parameters: [widthParameter, stepsParameter] };
    const before = structuredClone(initial);
    render(<Harness initial={initial} />); const user = userEvent.setup();
    const publish = screen.getByRole("button", { name: "Publish fixture" });
    expect(publish).toBeEnabled();
    const row = within(screen.getByRole("table", { name: "扩展参数" })).getByRole("row", { name: "#14.width" });
    const input = within(row).getByRole("textbox", { name: "默认值" });
    await user.click(input); await changeControl(input, { target: { value: "[640]" } }); await user.tab();
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(publish).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "width 的参数来源" }), "WIDTH");
    expect(currentWorkflow()).toMatchObject({ width: 640, height: 768, parameters: [stepsParameter], graph: dimensionGraph });
    expect(screen.queryByRole("row", { name: "#14.width" })).not.toBeInTheDocument();
    expect(publish).toBeDisabled(); // The height mapping is still required.
    await selectValue(screen.getByRole("combobox", { name: "height 的参数来源" }), "HEIGHT");
    expect(publish).toBeEnabled();
    expect(currentWorkflow()).toEqual({ ...initial, width: 640, height: 360, parameters: [stepsParameter],
      bindings: [...initial.bindings, { nodeId: "14", inputName: "width", source: "WIDTH" },
        { nodeId: "14", inputName: "height", source: "HEIGHT" }] });
    expect(initial).toEqual(before);
  });

  it.each([7, 640.5])("blocks publishing when a mapped graph width of %s is invalid", async (width) => {
    const initial = { ...dimensionDefinition, graph: { ...dimensionGraph, "14": { ...dimensionGraph["14"],
      inputs: { ...dimensionGraph["14"].inputs, width },
    } } };
    render(<Harness initial={initial} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "width 的参数来源" }), "WIDTH");
    await selectValue(screen.getByRole("combobox", { name: "height 的参数来源" }), "HEIGHT");
    expect(currentWorkflow().width).toBe(width);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeDisabled();
  });

  it("restores publishing after invalid size mappings are removed while preserving fixed graph literals", async () => {
    const initial = { ...dimensionDefinition, graph: { ...dimensionGraph, "14": { ...dimensionGraph["14"],
      inputs: { ...dimensionGraph["14"].inputs, width: 7 },
    } } };
    render(<Harness initial={initial} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    const width = screen.getByRole("combobox", { name: "width 的参数来源" });
    const height = screen.getByRole("combobox", { name: "height 的参数来源" });
    await selectValue(width, "WIDTH");
    await selectValue(height, "HEIGHT");
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await selectValue(width, "FIXED");
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await selectValue(height, "FIXED");
    expect(currentWorkflow().graph).toEqual(initial.graph);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeEnabled();
  });

  it("keeps the current graph when replacement import fails and shows the failure", async () => {
    server.use(http.post("/api/v1/settings/media-connections/comfy/comfyui/preview", () => HttpResponse.json({ title: "Invalid workflow", detail: "API format required", code: "COMFYUI_WORKFLOW_INVALID" }, { status: 422, headers: { "Content-Type": "application/problem+json" } })));
    render(<Harness initial={definition} />); const user = userEvent.setup();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
    await user.click(screen.getByRole("button", { name: "1. 导入工作流" }));
    expect(JSON.parse((screen.getByLabelText("API JSON 内容") as HTMLTextAreaElement).value)).toEqual(graph);
    await user.clear(screen.getByLabelText("API JSON 内容"));
    await user.type(screen.getByLabelText("API JSON 内容"), "invalid");
    await user.click(screen.getByRole("button", { name: "替换工作流并重新映射" }));
    expect(await screen.findByText("API format required")).toBeInTheDocument();
    expect(JSON.parse(screen.getByTestId("settings").textContent ?? "{}").comfyWorkflow).toEqual(definition);
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
  });

  it("restores the saved graph as editable JSON and includes later fixed-value edits without losing a JSON draft", async () => {
    render(<Harness initial={definition} />); const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await changeControl(screen.getByLabelText("steps 的固定值"), { target: { value: "30" } });
    await user.click(screen.getByRole("button", { name: "1. 导入工作流" }));
    const source = screen.getByLabelText("API JSON 内容") as HTMLTextAreaElement;
    expect(JSON.parse(source.value)).toEqual(currentWorkflow().graph);
    expect(JSON.parse(source.value)["14"].inputs.steps).toBe(30);
    await changeControl(source, { target: { value: "unfinished draft" } });
    await user.click(screen.getByRole("button", { name: "2. 节点与参数映射" }));
    await user.click(screen.getByRole("button", { name: "1. 导入工作流" }));
    expect(screen.getByLabelText("API JSON 内容")).toHaveValue("unfinished draft");
    expect(currentWorkflow().graph["14"]?.inputs.steps).toBe(30);
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
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: /#14.*CustomGenerator/ }));
    await selectValue(screen.getByRole("combobox", { name: "steps 的参数来源" }), "FIXED");
    expect(screen.queryByRole("table", { name: "扩展参数" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "检查发布配置" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Publish fixture" })).toBeEnabled();
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
