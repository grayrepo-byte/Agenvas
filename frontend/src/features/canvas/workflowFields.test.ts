import { describe, expect, it } from "vitest";
import type { CreatorInputDefinition } from "../../shared/creatorInputs";
import type { RunningHubField } from "../../shared/api/client";
import { evaluateWorkflowFields, workflowFieldValue, workflowUsedVersions, workflowValidationErrors } from "./workflowFields";

const field: RunningHubField = {
  key: "value", label: "参数", type: "INTEGER", nodeId: "1", fieldName: "value", required: true, advanced: false,
};

describe("published workflow field rules", () => {
  it("validates node-free provider fields using conditional defaults and typed duration choices", () => {
    const definition: CreatorInputDefinition = { fields: [
      { key: "prompt", label: "Prompt", type: "STRING", source: "PROMPT", required: true, advanced: false },
      { key: "duration", label: "Duration", type: "SELECT", source: "DURATION_SECONDS", required: true, advanced: false,
        defaultValue: 8, options: [{ label: "8 seconds", value: 8 }] },
      { key: "result_type", label: "Result type", type: "SELECT", required: true, advanced: false, defaultValue: "series",
        options: [{ label: "Series", value: "series" }, { label: "Single", value: "single" }] },
      { key: "n", label: "Image count", type: "INTEGER", minimum: 1, maximum: 9, required: true, advanced: false,
        enabledWhen: { field: "result_type", value: "single" } },
    ] };
    expect(workflowValidationErrors(definition, {}, "Synthetic prompt", null, [])).toEqual([]);
    expect(workflowValidationErrors(definition, { result_type: "single", n: 10 }, "Synthetic prompt", 4, [])).toHaveLength(2);
    expect(evaluateWorkflowFields(definition, {}, "Synthetic prompt", null).activeFields.map((item) => item.key))
      .toEqual(["prompt", "duration", "result_type"]);
  });

  it("keeps explicit false and zero while applying prompt and duration source defaults", () => {
    const definition = { fields: [
      { ...field, key: "sound", type: "BOOLEAN" as const, defaultValue: true },
      { ...field, key: "count", defaultValue: 4 },
      { ...field, key: "prompt", type: "STRING" as const, source: "PROMPT" as const, defaultValue: "published prompt" },
      { ...field, key: "seconds", source: "DURATION_SECONDS" as const, defaultValue: 5 },
    ] };
    const values = { sound: false, count: 0, prompt: "ignored parameter", seconds: 30 };
    expect(evaluateWorkflowFields(definition, values, "", null).effectiveValues).toEqual({
      sound: false, count: 0, prompt: "published prompt", seconds: 5,
    });
    expect(workflowFieldValue(definition.fields[2]!, values, "user prompt", 0)).toBe("user prompt");
    expect(workflowFieldValue(definition.fields[3]!, values, "", 0)).toBe(0);
    expect(values).toEqual({ sound: false, count: 0, prompt: "ignored parameter", seconds: 30 });
  });

  it("uses active published slots in order and keeps shared exact versions deduplicated", () => {
    const definition = { fields: [
      { ...field, key: "mode", type: "BOOLEAN" as const, defaultValue: false },
      { ...field, key: "first", type: "IMAGE" as const, enabledWhen: { field: "mode", value: false } },
      { ...field, key: "hidden", type: "AUDIO" as const, enabledWhen: { field: "mode", value: true } },
      { ...field, key: "second", type: "IMAGE" as const },
      { ...field, key: "orphan", type: "IMAGE" as const, enabledWhen: { field: "removed", value: false } },
    ] };
    const values = { first: "image-v1", second: "image-v1", hidden: "unavailable-audio" };
    const choices = [{ id: "image-v1", label: "图片", kind: "IMAGE", available: true }];
    expect(evaluateWorkflowFields(definition, values, "", null).activeFields.map((active) => active.key))
      .toEqual(["mode", "first", "second"]);
    expect([...workflowUsedVersions(definition, values, "", null)]).toEqual(["image-v1"]);
    expect(workflowValidationErrors(definition, values, "", null, choices)).toEqual([]);
  });

  it.each([
    { type: "INTEGER", value: 0, minimum: 0, maximum: 2, valid: true },
    { type: "INTEGER", value: 1.5, minimum: 0, maximum: 2, valid: false },
    { type: "NUMBER", value: 1.5, minimum: 0, maximum: 2, valid: true },
    { type: "NUMBER", value: -1, minimum: 0, maximum: 2, valid: false },
    { type: "NUMBER", value: 3, minimum: 0, maximum: 2, valid: false },
    { type: "NUMBER", value: NaN, valid: false },
    { type: "NUMBER", value: Infinity, valid: false },
    { type: "INTEGER", value: "1", valid: false },
  ] as const)("validates $type value $value without coercion", ({ type, value, valid, ...bounds }) => {
    const definition = { fields: [{ ...field, type, ...bounds }] };
    expect(workflowValidationErrors(definition, { value }, "", null, []).length).toBe(valid ? 0 : 1);
  });

  it("enforces duration limits together with narrower published bounds", () => {
    const definition = { fields: [{ ...field, source: "DURATION_SECONDS" as const, minimum: 5, maximum: 100 }] };
    expect(workflowValidationErrors(definition, {}, "", 4, [])).toHaveLength(1);
    expect(workflowValidationErrors(definition, {}, "", 5, [])).toEqual([]);
    expect(workflowValidationErrors(definition, {}, "", 60, [])).toEqual([]);
    expect(workflowValidationErrors(definition, {}, "", 61, [])).toHaveLength(1);
  });

  it("rejects missing, incorrectly typed, unavailable and removed published inputs", () => {
    const definition = { fields: [
      { ...field, key: "switch", type: "BOOLEAN" as const },
      { ...field, key: "selection", type: "SELECT" as const, options: [{ label: "零", value: 0 }] },
      { ...field, key: "text", type: "STRING" as const, maxLength: 3 },
      { ...field, key: "reference", type: "VIDEO" as const },
    ] };
    const choices = [{ id: "video-v1", label: "视频", kind: "VIDEO", available: false }];
    expect(workflowValidationErrors(definition,
      { switch: "false", selection: "0", text: "😀😀", reference: "video-v1", removed: true }, "", null, choices))
      .toHaveLength(5);
    expect(workflowValidationErrors({ fields: [{ ...field, type: "STRING" }] }, { value: "" }, "", null, []))
      .toEqual(["请填写“参数”"]);
    expect(workflowValidationErrors({ fields: [{ ...field, type: "STRING", required: false }] }, {}, "", null, []))
      .toEqual([]);
  });
});
