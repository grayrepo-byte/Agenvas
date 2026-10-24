import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import type { RunningHubDefinition } from "../../shared/api/client";
import { clickControl, selectValue } from "../../test/controls";
import { RunningHubDefinitionEditor } from "./RunningHubDefinitionEditor";
import { server } from "../../test/server";
import { selectRunningHubNodes } from "./runningHubSelection";

const initialDefinition: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
  fields: [
    { key: "mode", label: "模式", type: "BOOLEAN", nodeId: "1", fieldName: "mode", defaultValue: false, required: false, advanced: false },
    { key: "strength", label: "强度", type: "NUMBER", nodeId: "2", fieldName: "strength", defaultValue: 0, required: false, advanced: false,
      enabledWhen: { field: "mode", value: true } },
  ],
  fixedBindings: [{ nodeId: "2", fieldName: "text", value: "initial" }],
  outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
};

async function mount(selectNode = true, startingDefinition = initialDefinition) {
  function Editor() {
    const [definition, setDefinition] = useState(startingDefinition);
    const [nodes, setNodes] = useState<string[] | undefined>();
    const [fields, setFields] = useState<string[] | undefined>();
    return <>
      <RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={definition} selectedNodeIds={nodes} selectedFieldKeys={fields}
        onChange={(next, _kind, nodeIds, fieldKeys) => { setDefinition(next); setNodes(nodeIds); setFields(fieldKeys); }} />
      <output data-testid="definition">{JSON.stringify(definition)}</output>
      <output data-testid="published">{JSON.stringify(selectRunningHubNodes(definition, nodes, fields))}</output>
    </>;
  }
  render(<QueryClientProvider client={new QueryClient()}><Editor /></QueryClientProvider>);
  if (selectNode) {
    await showNodes("2");
    fireEvent.click(within(screen.getByRole("row", { name: "强度" })).getByRole("button", { name: "更多设置" }));
  }
}

async function showNodes(...nodeIds: string[]) {
  await clickControl(screen.getByRole("button", { name: "选择节点" }));
  for (const item of screen.getAllByRole("menuitemcheckbox")) {
    const selected = nodeIds.some((nodeId) => nodeId
      ? item.textContent === `节点 ${nodeId}` || item.textContent?.startsWith(`节点 ${nodeId} · `)
      : item.textContent === "待配置节点");
    if ((item.getAttribute("aria-checked") === "true") !== selected) await clickControl(item);
  }
  await userEvent.setup().keyboard("{Escape}");
}

function currentDefinition() {
  return JSON.parse(screen.getByTestId("definition").textContent ?? "") as RunningHubDefinition;
}

function publishedDefinition() {
  return JSON.parse(screen.getByTestId("published").textContent ?? "") as RunningHubDefinition;
}

describe("RunningHubDefinitionEditor intelligent discovery", () => {
  const discovered: RunningHubDefinition = { ...initialDefinition, fixedBindings: [], fields: [
    { key: "prompt", required: false, advanced: false, label: "画面提示", type: "STRING", source: "PROMPT", nodeId: "10", fieldName: "text", defaultValue: "scene" },
    { key: "seed", required: false, advanced: false, label: "随机种子", type: "INTEGER", source: "PARAMETER", nodeId: "10", fieldName: "seed", defaultValue: 42 },
    { key: "image", required: false, advanced: false, label: "参考图", type: "IMAGE", source: "PARAMETER", nodeId: "20", fieldName: "image" },
    { key: "steps", required: false, advanced: false, label: "采样步数", type: "INTEGER", source: "PARAMETER", nodeId: "30", fieldName: "steps", defaultValue: 20 },
  ] };
  const creationInputs: RunningHubDefinition = { ...initialDefinition, fixedBindings: [], fields: [
    discovered.fields[0]!, discovered.fields[2]!,
    { key: "duration", label: "片段时长", type: "NUMBER", source: "PARAMETER", nodeId: "30", fieldName: "duration", defaultValue: 5.5,
      minimum: 1, maximum: 60, required: false, advanced: false },
    { key: "ratio", label: "画幅", type: "SELECT", source: "PARAMETER", nodeId: "40", fieldName: "aspect_ratio", defaultValue: "16:9",
      options: [{ label: "横屏", value: "16:9" }, { label: "竖屏", value: "9:16" }], required: false, advanced: false },
    { key: "width", label: "图片宽度", type: "INTEGER", nodeId: "40", fieldName: "width", defaultValue: 1024,
      minimum: 64, maximum: 4096, required: false, advanced: false },
    { key: "height", label: "图片高度", type: "INTEGER", nodeId: "40", fieldName: "height", defaultValue: 576,
      minimum: 64, maximum: 4096, required: false, advanced: false },
    { key: "sound", label: "保留声音", type: "BOOLEAN", source: "PARAMETER", nodeId: "50", fieldName: "sound", defaultValue: false,
      required: false, advanced: false },
  ] };
  function discovery(definition = discovered, recommendedFieldKeys = ["prompt", "image"]) {
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", () => HttpResponse.json({ definition, warnings: [], recommendedFieldKeys })));
  }
  it("exposes only suggested inputs, including individual fields within a selected node", async () => {
    discovery();
    await mount(false, { ...initialDefinition, fields: [], fixedBindings: [] });
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    const prompt = await screen.findByRole("row", { name: "画面提示" });
    expect(within(prompt).getByRole("combobox", { name: "输入来源" })).toHaveValue("PROMPT");
    const seed = screen.getByRole("row", { name: "随机种子" });
    expect(within(seed).getByRole("checkbox", { name: "暴露参数 · 随机种子" })).not.toBeChecked();
    expect(within(seed).getByRole("textbox", { name: "默认值" })).toBeDisabled();
    expect(screen.queryByRole("row", { name: "采样步数" })).not.toBeInTheDocument();
    expect(publishedDefinition().fields.map((field) => field.key)).toEqual(["prompt", "image"]);
    await showNodes("10", "20", "30");
    const steps = screen.getByRole("row", { name: "采样步数" });
    await clickControl(within(steps).getByRole("checkbox", { name: "暴露参数 · 采样步数" }));
    expect(publishedDefinition().fields.map((field) => field.key)).toEqual(["prompt", "image", "steps"]);
    expect(currentDefinition().fields).toEqual(discovered.fields);
  });
  it("exposes suggested creation parameters with their imported types, defaults and options", async () => {
    discovery(creationInputs, creationInputs.fields.map((field) => field.key));
    await mount(false, { ...initialDefinition, fields: [], fixedBindings: [] });
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await screen.findByRole("row", { name: "片段时长" });
    for (const field of creationInputs.fields) {
      const row = screen.getByRole("row", { name: field.label });
      expect(within(row).getByRole("checkbox", { name: `暴露参数 · ${field.label}` })).toBeChecked();
      expect(within(row).getByRole("combobox", { name: "类型" })).toHaveValue(field.type);
    }
    expect(publishedDefinition().fields).toEqual(creationInputs.fields);
    await clickControl(screen.getByText("创作者表单预览 · 离线"));
    const preview = screen.getByLabelText("能力专属参数");
    expect(within(preview).getByRole("spinbutton", { name: "片段时长" })).toHaveValue(5.5);
    expect(within(preview).getByRole("combobox", { name: "画幅" })).toHaveValue("0");
    expect(within(preview).getByRole("spinbutton", { name: "图片宽度" })).toHaveValue(1024);
    expect(within(preview).getByRole("spinbutton", { name: "图片高度" })).toHaveValue(576);
    expect(within(preview).getByRole("combobox", { name: "保留声音" })).toHaveValue("false");
  });
  it("preserves deselected creation parameters and their edited types, defaults and constraints on rediscovery", async () => {
    discovery(creationInputs, creationInputs.fields.map((field) => field.key));
    await mount(false, creationInputs);
    const duration = screen.getByRole("row", { name: "片段时长" });
    await selectValue(within(duration).getByRole("combobox", { name: "类型" }), "INTEGER");
    const defaultValue = within(duration).getByRole("textbox", { name: "默认值" });
    fireEvent.change(defaultValue, { target: { value: "7" } }); fireEvent.blur(defaultValue);
    await clickControl(within(duration).getByRole("button", { name: "更多设置" }));
    const constraints = screen.getByRole("row", { name: "更多设置 · 片段时长" });
    fireEvent.change(within(constraints).getByRole("spinbutton", { name: "最小值" }), { target: { value: "3" } });
    fireEvent.change(within(constraints).getByRole("spinbutton", { name: "最大值" }), { target: { value: "20" } });
    await clickControl(within(screen.getByRole("row", { name: "画幅" })).getByRole("checkbox", { name: "暴露参数 · 画幅" }));
    await showNodes("10", "20", "30", "40");
    const configured = currentDefinition();
    expect(configured.fields.find((field) => field.key === "duration")).toMatchObject({ type: "INTEGER", defaultValue: 7, minimum: 3, maximum: 20 });
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "自动发现参数" })).toBeEnabled());
    expect(currentDefinition().fields).toEqual(configured.fields);
    expect(publishedDefinition().fields.map((field) => field.key)).toEqual(["prompt", "image", "duration", "width", "height"]);
    expect(within(screen.getByRole("row", { name: "画幅" })).getByRole("checkbox", { name: "暴露参数 · 画幅" })).not.toBeChecked();
    expect(screen.queryByRole("row", { name: "保留声音" })).not.toBeInTheDocument();
  });
  it("keeps manual deselection, scalar drafts and renamed exposed keys when rediscovering the same target", async () => {
    discovery();
    await mount(false, discovered);
    const prompt = screen.getByRole("row", { name: "画面提示" });
    await clickControl(within(prompt).getByRole("checkbox", { name: "暴露参数 · 画面提示" }));
    const seed = screen.getByRole("row", { name: "随机种子" });
    const input = within(seed).getByRole("textbox", { name: "默认值" });
    fireEvent.change(input, { target: { value: "invalid" } }); fireEvent.blur(input);
    await clickControl(within(seed).getByRole("checkbox", { name: "暴露参数 · 随机种子" }));
    expect(input).toBeDisabled();
    expect((input as HTMLInputElement).checkValidity()).toBe(true);
    await clickControl(within(seed).getByRole("checkbox", { name: "暴露参数 · 随机种子" }));
    expect(input).toHaveValue("invalid");
    expect(input).toHaveProperty("validity.valid", false);
    fireEvent.change(input, { target: { value: "100" } }); fireEvent.blur(input);
    fireEvent.change(within(seed).getByRole("textbox", { name: "稳定字段键" }), { target: { value: "customSeed" } });
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "自动发现参数" })).toBeEnabled());
    expect(publishedDefinition().fields.map((field) => field.key)).toEqual(["customSeed", "image", "steps"]);
    expect(currentDefinition().fields[1]?.defaultValue).toBe(100);
    expect(within(screen.getByRole("row", { name: "画面提示" })).getByRole("checkbox", { name: "暴露参数 · 画面提示" })).not.toBeChecked();
  });
  it("applies new media suggestions after key collision renaming without reselecting an existing node", async () => {
    const existing: RunningHubDefinition = { ...initialDefinition, fixedBindings: [], fields: [{ ...initialDefinition.fields[0]!, key: "input1" }] };
    const imported: RunningHubDefinition = { ...existing, fields: [
      { ...discovered.fields[2]!, key: "input1" },
      { ...existing.fields[0]!, key: "input2" },
    ] };
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", () => HttpResponse.json({ definition: imported, warnings: [], recommendedFieldKeys: ["input1"] })));
    await mount(false, existing);
    await showNodes();
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await screen.findByRole("row", { name: "参考图" });
    expect(screen.queryByRole("row", { name: "模式" })).not.toBeInTheDocument();
    expect(publishedDefinition().fields).toEqual([{ ...discovered.fields[2], key: "input2" }]);
    expect(currentDefinition().fields[0]).toEqual(existing.fields[0]);
  });
  it("preserves an existing canvas prompt instead of exposing a newly inferred second prompt", async () => {
    const existing: RunningHubDefinition = { ...initialDefinition, fixedBindings: [], fields: [
      { ...discovered.fields[0]!, key: "savedPrompt", nodeId: "99" },
    ] };
    discovery();
    await mount(false, existing);
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await screen.findByRole("row", { name: "参考图" });
    expect(publishedDefinition().fields.map((field) => field.key)).toEqual(["savedPrompt", "image"]);
    expect(currentDefinition().fields.filter((field) => field.source === "PROMPT")).toHaveLength(1);
    expect(currentDefinition().fields.find((field) => field.key === "prompt")?.source).toBe("PARAMETER");
  });
});

describe("RunningHubDefinitionEditor node field names", () => {
  it("accepts dotted open and fixed bindings without changing local parameter keys", async () => {
    await mount();
    const open = within(screen.getByRole("row", { name: "强度" })).getByRole("textbox", { name: "节点字段" });
    const fixed = screen.getByRole("textbox", { name: "固定字段" });
    fireEvent.change(open, { target: { value: "sampling_mode.top_p" } });
    fireEvent.change(fixed, { target: { value: "sampling_mode.seed" } });
    expect(open).toHaveProperty("validity.valid", true);
    expect(fixed).toHaveProperty("validity.valid", true);
    expect(currentDefinition().fields[1]).toMatchObject({ key: "strength", fieldName: "sampling_mode.top_p" });
    expect(currentDefinition().fixedBindings?.[0]).toMatchObject({ fieldName: "sampling_mode.seed" });
    for (const value of [".top_p", "sampling_mode/top_p", "sampling mode.top_p", "a".repeat(81)]) {
      fireEvent.change(open, { target: { value } });
      fireEvent.change(fixed, { target: { value } });
      expect(open).toHaveProperty("validity.valid", false);
      expect(fixed).toHaveProperty("validity.valid", false);
    }
  });
});

describe("RunningHubDefinitionEditor saved import JSON", () => {
  it("reopens and re-parses saved JSON without replacing configured fields, fixed bindings or outputs", async () => {
    const source = {
      "1": { class_type: "Mode", inputs: { mode: false } },
      "2": { class_type: "Sampler", inputs: { strength: 0.75, text: "source default" } },
      "3": { class_type: "Seed", inputs: { seed: 8 } },
      "99": { class_type: "SaveImage", inputs: { images: ["2", 0] } },
    };
    const stored: RunningHubDefinition = { ...initialDefinition, importSource: source,
      outputs: [{ nodeId: "99", kind: "IMAGE", primary: true, maxCount: 2 }] };
    const imported: RunningHubDefinition = { ...stored,
      fields: [...initialDefinition.fields.map((field) => ({ ...field, label: "导入名称", defaultValue: field.nodeId === "2" ? 0.75 : false })),
        { key: "text", label: "固定字段候选", type: "STRING", nodeId: "2", fieldName: "text", required: false, advanced: false },
        { key: "mode", label: "种子候选", type: "INTEGER", nodeId: "3", fieldName: "seed", required: false, advanced: false }],
      fixedBindings: [], outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
      nodeOptions: [{ nodeId: "99", label: "SaveImage" }],
    };
    const requests: unknown[] = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", async ({ request }) => {
        requests.push(await request.json()); return HttpResponse.json({ definition: imported, warnings: [], recommendedFieldKeys: [] });
      }));
    await mount(false, stored);
    expect(requests).toEqual([]);
    fireEvent.click(screen.getByText("导入 / 已保存的 JSON"));
    const input = screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" });
    expect(JSON.parse((input as HTMLTextAreaElement).value)).toEqual(source);
    await clickControl(screen.getByRole("button", { name: "导入 JSON 字段" }));
    await waitFor(() => expect(currentDefinition().fields).toHaveLength(3));
    expect(requests).toEqual([{ targetType: "WORKFLOW", targetId: "123", kind: "IMAGE_GENERATION", source }]);
    expect(currentDefinition().fields.slice(0, 2)).toEqual(stored.fields);
    expect(currentDefinition().fields[2]).toMatchObject({ key: "input1", nodeId: "3", fieldName: "seed" });
    expect(currentDefinition().fixedBindings).toEqual(stored.fixedBindings);
    expect(currentDefinition().outputs).toEqual(stored.outputs);
    expect(currentDefinition().importSource).toEqual(source);
  });

  it("keeps the saved JSON and mappings when re-parsing fails", async () => {
    const stored: RunningHubDefinition = { ...initialDefinition, importSource: { "1": { inputs: { mode: false } } } };
    await mount(false, stored);
    fireEvent.click(screen.getByText("导入 / 已保存的 JSON"));
    fireEvent.change(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" }), { target: { value: "invalid" } });
    await clickControl(screen.getByRole("button", { name: "导入 JSON 字段" }));
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(currentDefinition()).toEqual(stored);
    expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue("invalid");
  });
});

describe("RunningHubDefinitionEditor field capacity", () => {
  function importedFields(count: number): RunningHubDefinition["fields"] {
    return Array.from({ length: count }, (_, index) => ({
      key: `imported${index}`, label: `导入字段 ${index}`, type: "STRING", nodeId: "3", fieldName: `input${index}`,
      required: false, advanced: false,
    }));
  }

  function preview(definition: RunningHubDefinition) {
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", () => HttpResponse.json({
        definition, warnings: [], recommendedFieldKeys: [],
      })));
  }

  it("accepts automatic discovery when merged candidates reach 1024 fields", async () => {
    const onChange = vi.fn();
    preview({ ...initialDefinition, fields: importedFields(1022), fixedBindings: [] });
    render(<QueryClientProvider client={new QueryClient()}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={initialDefinition} onChange={onChange} /></QueryClientProvider>);
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await waitFor(() => expect(onChange).toHaveBeenCalledOnce());
    const next = onChange.mock.calls[0]?.[0] as RunningHubDefinition;
    expect(next.fields).toHaveLength(1024);
    expect(next.fields.slice(0, 2)).toEqual(initialDefinition.fields);
    expect(next.fixedBindings).toEqual(initialDefinition.fixedBindings);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("preserves mappings and JSON drafts when re-parsing would merge 1025 fields", async () => {
    const stored = { ...initialDefinition, importSource: { saved: true } };
    const onChange = vi.fn();
    preview({ ...initialDefinition, fields: importedFields(1023), fixedBindings: [] });
    render(<QueryClientProvider client={new QueryClient()}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={stored} onChange={onChange} /></QueryClientProvider>);
    fireEvent.click(screen.getByText("导入 / 已保存的 JSON"));
    const source = screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" });
    fireEvent.change(source, { target: { value: '{"draft":true}' } });
    await clickControl(screen.getByRole("button", { name: "导入 JSON 字段" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("合并后开放字段超过 1024 个，原配置已保留。");
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("row", { name: "模式" })).toBeInTheDocument();
    expect(source).toHaveValue('{"draft":true}');
  });
});

const scalarInputs = [
  { label: "默认值", error: "默认值需要有效数字或 JSON 标量。", getValue: (definition: RunningHubDefinition) => definition.fields[1]?.defaultValue },
  { label: "条件值（JSON 标量）", error: "显示条件需要有效的 JSON 标量。", getValue: (definition: RunningHubDefinition) => definition.fields[1]?.enabledWhen?.value },
  { label: "固定值（JSON 标量）", error: "固定值需要有效的 JSON 文字、数字或布尔值。", getValue: (definition: RunningHubDefinition) => definition.fixedBindings?.[0]?.value },
];

describe("RunningHubDefinitionEditor output node selection", () => {
  it("selects output-only imported nodes and keeps choices independent of parameter visibility", async () => {
    const definition: RunningHubDefinition = {
      ...initialDefinition,
      nodeOptions: [{ nodeId: "20", label: "保存图片" }, { nodeId: "3", label: "预览图片" }],
      outputs: [{ kind: "IMAGE", primary: true, maxCount: 2 }, { nodeId: "99", kind: "AUDIO", primary: false, maxCount: 1 }],
    };
    await mount(false, definition);
    await showNodes();
    const table = screen.getByRole("table", { name: "输出映射" });
    expect(within(table).queryByRole("textbox")).not.toBeInTheDocument();
    const [primary, extra] = within(table).getAllByRole("combobox", { name: "输出节点" });
    expect(extra).toHaveTextContent("节点 99");
    await clickControl(primary!);
    expect(screen.getAllByRole("option").map((item) => item.textContent)).toEqual([
      "按媒体类型匹配（不限节点）", "节点 1 · 模式", "节点 2 · 强度", "节点 3 · 预览图片", "节点 20 · 保存图片", "节点 99",
    ]);
    await clickControl(screen.getByRole("option", { name: "节点 20 · 保存图片" }));
    expect(currentDefinition().outputs).toEqual([{ ...definition.outputs[0], nodeId: "20" }, definition.outputs[1]]);
    expect(currentDefinition().nodeOptions).toEqual(definition.nodeOptions);
    await selectValue(primary!, "");
    expect(currentDefinition().outputs[0]?.nodeId).toBeNull();
    expect(currentDefinition().outputs[1]).toEqual(definition.outputs[1]);
  });

  it("adds extra mappings with a node picker and changes only the chosen row", async () => {
    await mount(false);
    await clickControl(screen.getByRole("button", { name: "添加额外输出映射" }));
    const pickers = within(screen.getByRole("table", { name: "输出映射" })).getAllByRole("combobox", { name: "输出节点" });
    await selectValue(pickers[1]!, "2");
    expect(currentDefinition().outputs[1]).toEqual({ nodeId: "2", kind: "IMAGE", primary: false, maxCount: 1 });
    expect(currentDefinition().outputs[0]).toEqual(initialDefinition.outputs[0]);
    expect(currentDefinition().fields).toEqual(initialDefinition.fields);
    await clickControl(screen.getByRole("button", { name: "移除额外输出" }));
    expect(currentDefinition().outputs).toEqual(initialDefinition.outputs);
  });

  it("retains legacy saved output IDs as choices after changing to another node", async () => {
    const definition: RunningHubDefinition = { ...initialDefinition, outputs: [{ nodeId: "99", kind: "IMAGE", primary: true, maxCount: 1 }] };
    await mount(false, definition);
    const picker = screen.getByRole("combobox", { name: "输出节点" });
    await selectValue(picker, "2");
    await selectValue(picker, "99");
    expect(currentDefinition()).toEqual(definition);
  });
});

function inputFor(label: string) {
  const scope = label === "默认值" ? within(screen.getByRole("row", { name: "强度" })) : screen;
  return scope.getByRole("textbox", { name: label });
}

describe("RunningHubDefinitionEditor scalar inputs", () => {
  it.each(scalarInputs)("keeps the draft on invalid $label and clears errors after a typed scalar is committed", async ({ label, error, getValue }) => {
    await mount();
    const originalValue = getValue(currentDefinition());

    for (const raw of ["invalid", "null", "{}", "[]"]) {
      const input = inputFor(label);
      fireEvent.change(input, { target: { value: raw } });
      fireEvent.blur(input);
      expect(getValue(currentDefinition())).toEqual(originalValue);
      expect(input).toHaveProperty("validationMessage", error);
      expect(screen.getByRole("alert")).toHaveTextContent(error);
    }

    for (const value of [1.25, false, "文字🎨"]) {
      const input = inputFor(label);
      fireEvent.change(input, { target: { value: JSON.stringify(value) } });
      fireEvent.blur(input);
      expect(getValue(currentDefinition())).toBe(value);
      expect(inputFor(label)).toHaveProperty("validationMessage", "");
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    }
  });

  it("clears an optional default while empty condition and fixed values retain their previous draft", async () => {
    await mount();
    const defaultInput = inputFor("默认值");
    fireEvent.change(defaultInput, { target: { value: "" } });
    fireEvent.blur(defaultInput);
    expect(currentDefinition().fields[1]?.defaultValue).toBeNull();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    for (const { label, error, getValue } of scalarInputs.slice(1)) {
      const originalValue = getValue(currentDefinition());
      const input = inputFor(label);
      fireEvent.change(input, { target: { value: "" } });
      fireEvent.blur(input);
      expect(getValue(currentDefinition())).toEqual(originalValue);
      expect(input).toHaveProperty("validationMessage", error);
    }
  });
});

describe("RunningHubDefinitionEditor node labels", () => {
  it("shows one workflow node title across multiple fields and preserves separators within a name", async () => {
    const definition: RunningHubDefinition = {
      ...initialDefinition,
      fields: [
        { key: "loraName", label: "LoRA · Loader · lora_name", type: "STRING", nodeId: "115", fieldName: "lora_name", required: false, advanced: false },
        { key: "loraStrength", label: "LoRA · Loader · strength", type: "NUMBER", nodeId: "115", fieldName: "strength", required: false, advanced: false },
        { key: "sampler", label: "采样器 · 自定义名称", type: "STRING", nodeId: "116", fieldName: "method", required: false, advanced: false },
      ],
      fixedBindings: [],
    };
    await mount(false, definition);
    await showNodes();
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    expect(screen.getAllByRole("menuitemcheckbox")).toHaveLength(2);
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 115 · LoRA · Loader" }));
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 116 · 采样器 · 自定义名称" }));
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 115 · LoRA · Loader · 节点 116 · 采样器 · 自定义名称");
    expect(screen.getByRole("row", { name: "LoRA · Loader · lora_name" })).toBeVisible();
    expect(screen.getByRole("row", { name: "LoRA · Loader · strength" })).toBeVisible();
    expect(currentDefinition()).toEqual(definition);
  });

  it("keeps AI application input labels intact when their names end with the field name", async () => {
    const definition: RunningHubDefinition = {
      ...initialDefinition, targetType: "AI_APP",
      fields: [{ key: "prompt", label: "输入 · prompt", type: "STRING", nodeId: "8", fieldName: "prompt", required: false, advanced: false }],
      fixedBindings: [],
    };
    await mount(false, definition);
    await showNodes("8");
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 8 · 输入 · prompt");
    expect(currentDefinition()).toEqual(definition);
  });

  it("uses the first useful field name and retains ID-only and unassigned fallbacks", async () => {
    const definition: RunningHubDefinition = {
      ...initialDefinition,
      fields: [
        { key: "unassigned", label: "未配置输入", type: "STRING", nodeId: "", fieldName: "text", required: false, advanced: false },
        { key: "blank", label: "  ", type: "STRING", nodeId: "3", fieldName: "text", required: false, advanced: false },
        { key: "generated", label: "节点 4 · scale", type: "NUMBER", nodeId: "4", fieldName: "scale", required: false, advanced: false },
        { key: "english", label: "Node 6 · seed", type: "INTEGER", nodeId: "6", fieldName: "seed", required: false, advanced: false },
        { key: "firstBlank", label: "", type: "STRING", nodeId: "7", fieldName: "text", required: false, advanced: false },
        { key: "named", label: "命名节点 · strength", type: "NUMBER", nodeId: "7", fieldName: "strength", required: false, advanced: false },
        { key: "emptyTitle", label: " · scale", type: "NUMBER", nodeId: "8", fieldName: "scale", required: false, advanced: false },
      ],
      fixedBindings: [{ nodeId: "5", fieldName: "seed", value: 1 }],
    };
    await mount(false, definition);
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    expect(screen.getAllByRole("menuitemcheckbox").map((item) => item.textContent)).toEqual([
      "待配置节点", "节点 3", "节点 4", "节点 5", "节点 6", "节点 7 · 命名节点", "节点 8",
    ]);
    await clickControl(screen.getByRole("menuitem", { name: "全选节点" }));
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("待配置节点 · 节点 3 · 节点 4 · 节点 5 · 节点 6 · 节点 7 · 命名节点 · 节点 8");
    expect(currentDefinition()).toEqual(definition);
  });

  it("updates the picker and selected summary when an existing saved field label is edited", async () => {
    await mount(false);
    await showNodes("2");
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 2 · 强度");
    fireEvent.change(within(screen.getByRole("row", { name: "强度" })).getByRole("textbox", { name: "显示名称" }),
      { target: { value: "采样强度" } });
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 2 · 采样强度");
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 采样强度" })).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByRole("menuitemcheckbox", { name: "节点 2 · 强度" })).not.toBeInTheDocument();
    expect(currentDefinition().fields[1]).toEqual({ ...initialDefinition.fields[1], label: "采样强度" });
    await userEvent.setup().keyboard("{Escape}");
  });
});


describe("RunningHubDefinitionEditor mapping table", () => {
  it("shows saved nodes and their parameter mappings immediately when opening the editor", async () => {
    await mount(false);
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 模式 · 节点 2 · 强度");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    const strength = screen.getByRole("row", { name: "强度" });
    expect(within(strength).getByRole("textbox", { name: "节点 ID" })).toHaveValue("2");
    expect(within(strength).getByRole("textbox", { name: "节点字段" })).toHaveValue("strength");
    expect(within(strength).getByRole("textbox", { name: "默认值" })).toHaveValue("0");
    expect(screen.getByRole("textbox", { name: "固定值（JSON 标量）" })).toHaveValue('"initial"');
    expect(currentDefinition()).toEqual(initialDefinition);
  });

  it("selects multiple nodes in one open menu and shows their editable and fixed parameters together", async () => {
    await mount(false);
    await showNodes();
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 1 · 模式" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 强度" }));
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 1 · 模式" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 强度" })).toHaveAttribute("aria-checked", "true");
    // Modal menu isolates the background until it closes, while both selections stay active.
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "固定值（JSON 标量）" })).toBeVisible();
    expect(currentDefinition()).toEqual(initialDefinition);
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 模式 · 节点 2 · 强度");
  });

  it("retains scalar drafts across clearing the selection and switching nodes", async () => {
    await mount(false);
    await showNodes();
    expect(screen.queryByRole("table", { name: "参数绑定" })).not.toBeInTheDocument();
    await showNodes("1");
    expect(screen.getByRole("row", { name: "模式" })).toBeInTheDocument();
    expect(screen.queryByRole("row", { name: "强度" })).not.toBeInTheDocument();
    await showNodes("2");
    expect(screen.queryByRole("row", { name: "模式" })).not.toBeInTheDocument();
    const input = inputFor("默认值");
    fireEvent.change(input, { target: { value: "invalid" } });
    fireEvent.blur(input);
    await showNodes("1");
    await showNodes("1", "2");
    expect(inputFor("默认值")).toBe(input);
    expect(input).toHaveValue("invalid");
    // checkValidity() dispatches invalid and moves focus; inspect draft validity without submitting.
    expect(input).toHaveProperty("validity.valid", false);
    expect(currentDefinition().fields[1]?.defaultValue).toBe(0);
    await showNodes("2");
    fireEvent.click(screen.getByRole("button", { name: "手动添加字段" }));
    expect(currentDefinition().fields.at(-1)?.nodeId).toBe("2");
  });

  it("selects all nodes and clears the view without changing parameter values", async () => {
    await mount(false);
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitem", { name: "全选节点" }));
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitem", { name: "清空选择" }));
    expect(screen.getAllByRole("menuitemcheckbox")).toHaveLength(2);
    for (const item of screen.getAllByRole("menuitemcheckbox")) expect(item).toHaveAttribute("aria-checked", "false");
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.queryByRole("table", { name: "参数绑定" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "固定值（JSON 标量）" })).not.toBeInTheDocument();
    expect(currentDefinition()).toEqual(initialDefinition);
  });

  it("requires a destination node when adding editable and fixed parameters with multiple nodes selected", async () => {
    await mount(false);
    await showNodes("1", "2");
    await clickControl(screen.getByRole("button", { name: "手动添加字段" }));
    expect(currentDefinition().fields).toHaveLength(2);
    await clickControl(screen.getByRole("menuitem", { name: "节点 1 · 模式" }));
    expect(currentDefinition().fields.at(-1)?.nodeId).toBe("1");
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "添加固定映射" }));
    expect(currentDefinition().fixedBindings).toHaveLength(1);
    await clickControl(screen.getByRole("menuitem", { name: "节点 2 · 强度" }));
    expect(currentDefinition().fixedBindings?.at(-1)).toMatchObject({ nodeId: "2", fieldName: "", value: "" });
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
  });

  it("supports keyboard selection and returns focus to the picker after Escape", async () => {
    await mount(false);
    await showNodes();
    const picker = screen.getByRole("button", { name: "选择节点" });
    picker.focus();
    await userEvent.setup().keyboard("{Enter}{End} ");
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 2 · 强度" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menu")).toBeInTheDocument();
    await userEvent.setup().keyboard("{Escape}");
    expect(picker).toHaveFocus();
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
  });

  it("edits each row independently and preserves other mappings when one is removed", async () => {
    await mount();
    const table = screen.getByRole("table", { name: "参数绑定" });
    const strength = within(table).getByRole("row", { name: "强度" });
    fireEvent.change(within(strength).getByRole("textbox", { name: "节点 ID" }), { target: { value: "42" } });
    fireEvent.change(within(strength).getByRole("textbox", { name: "节点字段" }), { target: { value: "scale" } });
    fireEvent.change(within(strength).getByRole("textbox", { name: "显示名称" }), { target: { value: "采样强度" } });
    expect(currentDefinition().fields[1]).toMatchObject({ nodeId: "42", fieldName: "scale", label: "采样强度", defaultValue: 0 });
    expect(currentDefinition().fields[0]).toEqual(initialDefinition.fields[0]);
    fireEvent.click(within(strength).getByRole("button", { name: "移除此候选字段 · 采样强度" }));
    expect(currentDefinition().fields).toEqual([initialDefinition.fields[0]]);
    expect(currentDefinition().outputs).toEqual(initialDefinition.outputs);
    expect(currentDefinition().fixedBindings).toEqual(initialDefinition.fixedBindings);
  });

  it("expands hidden settings when form validation targets an invalid condition", async () => {
    await mount();
    const input = screen.getByRole("textbox", { name: "条件值（JSON 标量）" });
    fireEvent.change(input, { target: { value: "invalid" } });
    fireEvent.blur(input);
    const row = screen.getByRole("row", { name: "强度" });
    fireEvent.click(within(row).getByRole("button", { name: "更多设置" }));
    await showNodes("1", "2");
    expect(screen.queryByRole("textbox", { name: "条件值（JSON 标量）" })).not.toBeInTheDocument();
    fireEvent.invalid(input);
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 模式 · 节点 2 · 强度");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "条件值（JSON 标量）" })).toBe(input);
    expect(currentDefinition().fields[1]?.enabledWhen?.value).toBe(true);
  });

  it("excludes unchecked scalar controls from validation and restores their invalid drafts on reselect", async () => {
    await mount();
    const input = inputFor("默认值");
    fireEvent.change(input, { target: { value: "invalid" } });
    fireEvent.blur(input);
    const fixed = inputFor("固定值（JSON 标量）");
    fireEvent.change(fixed, { target: { value: "invalid fixed value" } });
    fireEvent.blur(fixed);
    await showNodes("1");
    expect(input).toBeDisabled();
    expect(fixed).toBeDisabled();
    expect((input as HTMLInputElement).checkValidity()).toBe(true);
    expect((fixed as HTMLInputElement).checkValidity()).toBe(true);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await showNodes("1", "2");
    expect(inputFor("默认值")).toBe(input);
    expect(input).toHaveValue("invalid");
    expect(input).toBeEnabled();
    expect(input).toHaveProperty("validity.valid", false);
    expect(inputFor("固定值（JSON 标量）")).toBe(fixed);
    expect(fixed).toHaveValue("invalid fixed value");
    expect(fixed).toHaveProperty("validity.valid", false);
  });
});


describe("RunningHubDefinitionEditor workbench session", () => {
  it("groups mappings by node and places injected identity and pricing fields in their numbered sections", async () => {
    render(<QueryClientProvider client={new QueryClient()}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={initialDefinition} onChange={() => undefined} identityFields={<span>Identity fields</span>} pricingFields={<span>Pricing fields</span>} />
    </QueryClientProvider>);
    const navigation = screen.getByRole("navigation", { name: "能力配置步骤" });
    const links = within(navigation).getAllByRole("link");
    expect(links.map((link) => link.textContent)).toEqual(["01目标与来源", "02输入与参数", "03输出与运行", "04预览与费用"]);
    for (const link of links) expect(document.getElementById(link.getAttribute("href")!.slice(1))).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "目标与来源" })).getByText("Identity fields")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "预览与费用" })).getByText("Pricing fields")).toBeInTheDocument();
    const mappings = screen.getByRole("table", { name: "参数绑定" });
    expect(within(mappings).getByRole("heading", { name: "节点 1 · 模式" })).toBeInTheDocument();
    expect(within(mappings).getByRole("heading", { name: "节点 2 · 强度" })).toBeInTheDocument();
    expect(within(mappings).queryByRole("columnheader")).not.toBeInTheDocument();
    const summary = within(screen.getByRole("region", { name: "预览与费用" }));
    expect(summary.getByText("2 个节点")).toBeInTheDocument();
    expect(summary.getByText("2 个开放参数")).toBeInTheDocument();
    expect(summary.getByText("1 个固定参数")).toBeInTheDocument();
    expect(links[0]).toHaveAttribute("aria-current", "step");
    const scroll = vi.fn();
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    const outputsSection = screen.getByRole("region", { name: "输出与运行" });
    outputsSection.scrollIntoView = scroll;
    await clickControl(links[2]!);
    expect(links[2]).toHaveAttribute("aria-current", "step");
    expect(links[0]).not.toHaveAttribute("aria-current");
    expect(scroll).toHaveBeenCalled();
    vi.unstubAllGlobals();
  });


  it("scrolls section navigation inside the dialog body without moving the dialog ancestors", async () => {
    vi.stubGlobal("matchMedia", () => ({ matches: false }));
    const view = render(<QueryClientProvider client={new QueryClient()}><div className="ui-dialog-body">
      <RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={initialDefinition} onChange={() => undefined} />
    </div></QueryClientProvider>);
    const body = view.container.querySelector<HTMLElement>(".ui-dialog-body")!;
    const bodyScroll = vi.fn();
    body.scrollTo = bodyScroll;
    const section = screen.getByRole("region", { name: "输出与运行" });
    const ancestorScroll = vi.fn();
    section.scrollIntoView = ancestorScroll;
    await clickControl(within(screen.getByRole("navigation", { name: "能力配置步骤" })).getByRole("link", { name: "输出与运行" }));
    expect(bodyScroll).toHaveBeenCalledWith({ top: -16, behavior: "smooth" });
    expect(ancestorScroll).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("ignores a discovery response after an explicit reload replaces its editing session", async () => {
    let finish: (() => void) | undefined;
    const waiting = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", async () => {
        await waiting;
        return HttpResponse.json({ definition: initialDefinition, warnings: [], recommendedFieldKeys: ["mode", "strength"] });
      }));
    const queryClient = new QueryClient();
    const onChange = vi.fn();
    const onBusyChange = vi.fn();
    const view = render(<QueryClientProvider client={queryClient}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={initialDefinition} onChange={onChange} onBusyChange={onBusyChange} resetKey={0} /></QueryClientProvider>);
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await waitFor(() => expect(onBusyChange).toHaveBeenLastCalledWith(true));
    view.rerender(<QueryClientProvider client={queryClient}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={{ ...initialDefinition, targetId: "987" }} onChange={onChange} onBusyChange={onBusyChange} resetKey={1} /></QueryClientProvider>);
    expect(onBusyChange).toHaveBeenLastCalledWith(false);
    finish?.();
    await waitFor(() => expect(queryClient.getMutationCache().getAll()[0]?.state.status).toBe("success"));
    expect(screen.getByRole("textbox", { name: "真实目标 ID" })).toHaveValue("987");
    expect(onChange).not.toHaveBeenCalled();
  });
  it("reports discovery as busy, protects contract controls and releases the parent after discovery", async () => {
    let finish: (() => void) | undefined;
    const waiting = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", async () => {
        await waiting;
        return HttpResponse.json({ definition: initialDefinition, warnings: [], recommendedFieldKeys: ["mode", "strength"] });
      }));
    function Editor() {
      const [definition, setDefinition] = useState(initialDefinition);
      const [busy, setBusy] = useState(false);
      return <><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={definition}
        onChange={setDefinition} onBusyChange={setBusy} identityFields={<input aria-label="Capability name" defaultValue="Draft name" />} /><output data-testid="busy">{String(busy)}</output></>;
    }
    render(<QueryClientProvider client={new QueryClient()}><Editor /></QueryClientProvider>);
    await clickControl(screen.getByRole("button", { name: "自动发现参数" }));
    await waitFor(() => expect(screen.getByTestId("busy")).toHaveTextContent("true"));
    expect(within(screen.getByRole("row", { name: "强度" })).getByRole("textbox", { name: "显示名称" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "选择节点" })).toBeDisabled();
    expect(screen.getByRole("combobox", { name: "输出节点" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Capability name" })).toBeEnabled();
    finish?.();
    await waitFor(() => expect(screen.getByTestId("busy")).toHaveTextContent("false"));
    expect(screen.getByRole("button", { name: "选择节点" })).toBeEnabled();
  });

  it("restores unparsed JSON after closing and reopening while an explicit reload accepts the latest saved source", async () => {
    function EditingSession() {
      const [open, setOpen] = useState(true);
      const [sourceDraft, setSourceDraft] = useState<string>();
      const [definition, setDefinition] = useState<RunningHubDefinition>({ ...initialDefinition, importSource: { saved: true } });
      const [resetKey, setResetKey] = useState(0);
      return <>
        <button type="button" onClick={() => setOpen((current) => !current)}>{open ? "Close editor" : "Reopen editor"}</button>
        <button type="button" onClick={() => {
          setSourceDraft(undefined); setDefinition({ ...initialDefinition, importSource: { latest: true } }); setResetKey((current) => current + 1);
        }}>Accept latest source</button>
        {open ? <RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={definition} onChange={setDefinition}
          sourceDraft={sourceDraft} onSourceDraftChange={setSourceDraft} resetKey={resetKey} /> : null}
      </>;
    }
    render(<QueryClientProvider client={new QueryClient()}><EditingSession /></QueryClientProvider>);
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    fireEvent.change(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" }), { target: { value: "unfinished JSON draft" } });
    await clickControl(screen.getByRole("button", { name: "Close editor" }));
    await clickControl(screen.getByRole("button", { name: "Reopen editor" }));
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue("unfinished JSON draft");
    await clickControl(screen.getByRole("button", { name: "Accept latest source" }));
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue(JSON.stringify({ latest: true }, null, 2));
  });

  it("reports source drafts only for user input and keeps import normalization local to the editor", async () => {
    const normalized = { "1": { inputs: { mode: false } } };
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "synthetic" })),
      http.post("/api/v1/settings/media-connections/connection/runninghub/preview", () => HttpResponse.json({
        definition: { ...initialDefinition, importSource: normalized }, warnings: [], recommendedFieldKeys: ["mode", "strength"],
      })));
    const sourceChanged = vi.fn();
    render(<QueryClientProvider client={new QueryClient()}><RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE"
      value={initialDefinition} onChange={() => undefined} sourceDraft="" onSourceDraftChange={sourceChanged} /></QueryClientProvider>);
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    expect(sourceChanged).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" }), { target: { value: '{"user":"input"}' } });
    expect(sourceChanged).toHaveBeenCalledExactlyOnceWith('{"user":"input"}');
    await clickControl(screen.getByRole("button", { name: "导入 JSON 字段" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue(JSON.stringify(normalized, null, 2)));
    expect(sourceChanged).toHaveBeenCalledExactlyOnceWith('{"user":"input"}');
  });

  it("resets unparsed JSON, scalar validation and selections only when an explicit reload changes the reset key", async () => {
    const starting = { ...initialDefinition, importSource: { original: true } };
    const latest: RunningHubDefinition = { ...initialDefinition, targetId: "987", importSource: { latest: true },
      fields: [{ ...initialDefinition.fields[0]!, nodeId: "44", label: "最新参数" }], fixedBindings: [] };
    const queryClient = new QueryClient();
    const onChange = vi.fn();
    const view = render(<QueryClientProvider client={queryClient}><RunningHubDefinitionEditor connectionId="connection"
      adapterId="RUNNINGHUB_IMAGE" value={starting} onChange={onChange} resetKey={0} /></QueryClientProvider>);
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    fireEvent.change(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" }), { target: { value: "unfinished JSON draft" } });
    const scalar = within(screen.getByRole("row", { name: "强度" })).getByRole("textbox", { name: "默认值" });
    fireEvent.change(scalar, { target: { value: "invalid scalar" } }); fireEvent.blur(scalar);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    view.rerender(<QueryClientProvider client={queryClient}><RunningHubDefinitionEditor connectionId="connection"
      adapterId="RUNNINGHUB_IMAGE" value={latest} onChange={onChange} resetKey={0} /></QueryClientProvider>);
    expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue("unfinished JSON draft");
    view.rerender(<QueryClientProvider client={queryClient}><RunningHubDefinitionEditor connectionId="connection"
      adapterId="RUNNINGHUB_IMAGE" value={latest} onChange={onChange} resetKey={1} /></QueryClientProvider>);
    await clickControl(screen.getByText("导入 / 已保存的 JSON"));
    expect(screen.getByRole("textbox", { name: "nodeInfoList 或 ComfyUI API-format JSON" })).toHaveValue(JSON.stringify(latest.importSource, null, 2));
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 44 · 最新参数");
    expect(screen.getByRole("textbox", { name: "真实目标 ID" })).toHaveValue("987");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });
});
