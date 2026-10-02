import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, within } from "@testing-library/react";
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
  fixedBindings: [{ nodeId: "3", fieldName: "text", value: "initial" }],
  outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
};

function mount() {
  function Editor() {
    const [definition, setDefinition] = useState(initialDefinition);
    return <>
      <RunningHubDefinitionEditor connectionId="connection" adapterId="RUNNINGHUB_IMAGE" value={definition} onChange={setDefinition} />
      <output data-testid="definition">{JSON.stringify(definition)}</output>
    </>;
  }
  render(<QueryClientProvider client={new QueryClient()}><Editor /></QueryClientProvider>);
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
  const scope = label === "默认值" ? within(screen.getByRole("group", { name: "强度" })) : screen;
  return scope.getByRole("textbox", { name: label });
}

describe("RunningHubDefinitionEditor scalar inputs", () => {
  it.each(scalarInputs)("keeps the draft on invalid $label and clears errors after a typed scalar is committed", async ({ label, error, getValue }) => {
    mount();
    const review = screen.getByRole("checkbox", { name: "已核对开放字段、素材格式与输出映射" });
    const originalValue = getValue(currentDefinition());
    await clickControl(review);
    expect(review).toBeChecked();

    for (const raw of ["invalid", "null", "{}", "[]"]) {
      const input = inputFor(label);
      fireEvent.change(input, { target: { value: raw } });
      fireEvent.blur(input);
      expect(getValue(currentDefinition())).toEqual(originalValue);
      expect(input).toHaveProperty("validationMessage", error);
      expect(screen.getByRole("alert")).toHaveTextContent(error);
      expect(review).not.toBeChecked();
    }

    for (const value of [1.25, false, "文字🎨"]) {
      const input = inputFor(label);
      fireEvent.change(input, { target: { value: JSON.stringify(value) } });
      fireEvent.blur(input);
      expect(getValue(currentDefinition())).toBe(value);
      expect(inputFor(label)).toHaveProperty("validationMessage", "");
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    }
    await clickControl(review);
    expect(review).toBeChecked();
  });

  it("clears an optional default while empty condition and fixed values retain their previous draft", () => {
    mount();
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
