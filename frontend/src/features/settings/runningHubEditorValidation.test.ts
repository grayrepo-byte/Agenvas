import { describe, expect, it } from "vitest";
import type { RunningHubDefinition, RunningHubField } from "../../shared/api/client";
import { invalidRunningHubEditorField, invalidRunningHubEditorFixed, validRunningHubEditorValue } from "./runningHubEditorValidation";

const field: RunningHubField = {
  key: "value", label: "Value", type: "INTEGER", nodeId: "1", fieldName: "value", required: false, advanced: false,
};
function definition(fields: RunningHubField[]): RunningHubDefinition {
  return { schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123",
    usePersonalQueue: false, addMetadata: false, fields, outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }] };
}

describe("RunningHub editor validation", () => {
  it.each([
    { type: "STRING", value: "", valid: true },
    { type: "STRING", value: "x".repeat(20_001), valid: false },
    { type: "BOOLEAN", value: false, valid: true },
    { type: "BOOLEAN", value: "false", valid: false },
    { type: "INTEGER", value: 0, valid: true },
    { type: "INTEGER", value: 1.5, valid: false },
    { type: "INTEGER", value: Number.MAX_SAFE_INTEGER + 1, valid: false },
    { type: "NUMBER", value: 1.5, valid: true },
    { type: "NUMBER", value: Infinity, valid: false },
    { type: "NUMBER", value: NaN, valid: false },
    { type: "SELECT", value: 0, valid: true },
    { type: "SELECT", value: "0", valid: false },
    { type: "IMAGE", value: "image-version", valid: false },
  ] as const)("validates $type defaults without coercing $value", ({ type, value, valid }) => {
    expect(validRunningHubEditorValue({ ...field, type, options: [{ label: "Zero", value: 0 }] }, value)).toBe(valid);
  });

  it("applies published bounds to numeric selections and enforces the duration range", () => {
    const selection: RunningHubField = { ...field, type: "SELECT", minimum: 1, options: [{ label: "Zero", value: 0 }] };
    expect(validRunningHubEditorValue(selection, 0)).toBe(false);
    const duration: RunningHubField = { ...field, source: "DURATION_SECONDS", minimum: 5, maximum: 100 };
    expect([4, 5, 60, 61].map((value) => validRunningHubEditorValue(duration, value))).toEqual([false, true, true, false]);
  });

  it("returns the original index of an invalid selected field and ignores unchecked drafts", () => {
    const input = definition([{ ...field, key: "ignored", label: "" },
      { ...field, key: "selected", nodeId: "2", defaultValue: 3 }]);
    const drafts = { "ignored:default": "invalid", "selected:default": "invalid" };
    expect(invalidRunningHubEditorField(input, ["2"], ["selected"], drafts)).toBe(1);
    expect(invalidRunningHubEditorField(input, ["2"], [], drafts)).toBe(-1);
    expect(invalidRunningHubEditorField(input, ["1"], ["selected"], drafts)).toBe(-1);
    expect(invalidRunningHubEditorField(input, ["2"], ["selected"], { "selected:default": "" })).toBe(-1);
  });

  it("rejects duplicate mappings and conditions without a selected, unconditional parent", () => {
    const parent: RunningHubField = { ...field, key: "enabled", type: "BOOLEAN", defaultValue: false };
    const child: RunningHubField = { ...field, nodeId: "2", enabledWhen: { field: "enabled", value: false } };
    const input = definition([parent, child]);
    expect(invalidRunningHubEditorField(input, ["1", "2"], ["enabled", "value"], {})).toBe(-1);
    expect(invalidRunningHubEditorField(input, ["2"], ["value"], {})).toBe(1);
    expect(invalidRunningHubEditorField(input, ["1", "2"], ["enabled", "value"], { "value:condition": "0" })).toBe(1);
    expect(invalidRunningHubEditorField(input, ["1", "2"], ["enabled", "value"], { "value:condition": "" })).toBe(1);
    expect(invalidRunningHubEditorField(definition([parent, { ...child, nodeId: "1" }]), ["1"], ["enabled", "value"], {})).toBe(1);
  });

  it("checks fixed scalar drafts only on selected nodes without losing their original indices", () => {
    const input = { ...definition([]), fixedBindings: [
      { nodeId: "1", fieldName: "value", value: true }, { nodeId: "2", fieldName: "sampling.top_p", value: 0 },
    ] };
    expect(invalidRunningHubEditorFixed(input, ["2"], { "fixed:0:1:value": "invalid" })).toBe(-1);
    for (const raw of ["", "null", "{}", "[]"]) {
      expect(invalidRunningHubEditorFixed(input, ["2"], { "fixed:1:2:sampling.top_p": raw })).toBe(1);
    }
    expect(invalidRunningHubEditorFixed(input, ["2"], { "fixed:1:2:sampling.top_p": "false" })).toBe(-1);
  });
});
