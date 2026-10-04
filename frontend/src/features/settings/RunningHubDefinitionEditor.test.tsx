import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { RunningHubDefinition } from "../../shared/api/client";
import { clickControl } from "../../test/controls";
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

async function mount(selectNode = true) {
  function Editor() {
    const [definition, setDefinition] = useState(initialDefinition);
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
    const selected = nodeIds.some((nodeId) => item.textContent === `节点 ${nodeId}`);
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


describe("RunningHubDefinitionEditor mapping table", () => {
  it("selects multiple nodes in one open menu and shows their editable and fixed parameters together", async () => {
    await mount(false);
    await clickControl(screen.getByRole("button", { name: "选择节点" }));
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 1" }));
    expect(screen.getByRole("menu")).toBeInTheDocument();
    await clickControl(screen.getByRole("menuitemcheckbox", { name: "节点 2" }));
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 1" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 2" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "固定值（JSON 标量）" })).toBeVisible();
    expect(currentDefinition()).toEqual(initialDefinition);
    await userEvent.setup().keyboard("{Escape}");
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 节点 2");
  });

  it("shows parameters only after choosing a node and retains scalar drafts across node switches", async () => {
    await mount(false);
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
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    await clickControl(screen.getByRole("menuitem", { name: "清空选择" }));
    expect(screen.queryByRole("table", { name: "参数绑定" })).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: "固定值（JSON 标量）" })).not.toBeInTheDocument();
    expect(screen.getAllByRole("menuitemcheckbox")).toHaveLength(2);
    for (const item of screen.getAllByRole("menuitemcheckbox")) expect(item).toHaveAttribute("aria-checked", "false");
    expect(currentDefinition()).toEqual(initialDefinition);
  });

  it("requires a destination node when adding editable and fixed parameters with multiple nodes selected", async () => {
    await mount(false);
    await showNodes("1", "2");
    await clickControl(screen.getByRole("button", { name: "手动添加字段" }));
    expect(currentDefinition().fields).toHaveLength(2);
    await clickControl(screen.getByRole("menuitem", { name: "节点 1" }));
    expect(currentDefinition().fields.at(-1)?.nodeId).toBe("1");
    expect(screen.getByRole("row", { name: "强度" })).toBeVisible();
    await clickControl(screen.getByRole("button", { name: "添加固定映射" }));
    expect(currentDefinition().fixedBindings).toHaveLength(1);
    await clickControl(screen.getByRole("menuitem", { name: "节点 2" }));
    expect(currentDefinition().fixedBindings?.at(-1)).toMatchObject({ nodeId: "2", fieldName: "", value: "" });
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
  });

  it("supports keyboard selection and returns focus to the picker after Escape", async () => {
    await mount(false);
    const picker = screen.getByRole("button", { name: "选择节点" });
    picker.focus();
    await userEvent.setup().keyboard("{Enter}{End} ");
    expect(screen.getByRole("menuitemcheckbox", { name: "节点 2" })).toHaveAttribute("aria-checked", "true");
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
    expect(screen.getByRole("button", { name: "选择节点" })).toHaveTextContent("节点 1 · 节点 2");
    expect(screen.getByRole("row", { name: "模式" })).toBeVisible();
    expect(screen.getByRole("textbox", { name: "条件值（JSON 标量）" })).toBe(input);
    expect(currentDefinition().fields[1]?.enabledWhen?.value).toBe(true);
  });
});
