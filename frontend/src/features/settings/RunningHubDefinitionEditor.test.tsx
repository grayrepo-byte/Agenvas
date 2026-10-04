import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { RunningHubDefinition } from "../../shared/api/client";
import { clickControl, selectValue } from "../../test/controls";
import { RunningHubDefinitionEditor } from "./RunningHubDefinitionEditor";

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
    return <>
      <RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={definition} onChange={setDefinition} />
      <output data-testid="definition">{JSON.stringify(definition)}</output>
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
    await showNodes("1");
    expect(screen.queryByRole("textbox", { name: "条件值（JSON 标量）" })).not.toBeInTheDocument();
    fireEvent.invalid(input);
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 模式 · 节点 2 · 强度");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "条件值（JSON 标量）" })).toBe(input);
    expect(currentDefinition().fields[1]?.enabledWhen?.value).toBe(true);
  });
});
