import type { RunningHubDefinition, RunningHubField } from "../../shared/api/client";

const FIELD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const NODE_ID = /^[0-9]{1,32}$/;
const NODE_FIELD_NAME = /^[A-Za-z_][A-Za-z0-9_.]{0,79}$/;
const MAX_TEXT_LENGTH = 20_000;

/** Pagination removes controls from the DOM; validate the complete selected draft before submission. */
export function invalidRunningHubEditorField(
  definition: RunningHubDefinition,
  nodeIds: readonly string[],
  fieldKeys: readonly string[],
  rawDrafts: Readonly<Record<string, string>>,
): number {
  const keys = new Set<string>();
  const bindings = new Set<string>();
  const sources = new Set<string>();
  const selected = definition.fields
    .map((field, index) => ({ field, index }))
    .filter(({ field }) => nodeIds.includes(field.nodeId) && fieldKeys.includes(field.key));
  for (const { field, index } of selected) {
    const source = field.source ?? "PARAMETER";
    const binding = `${field.nodeId}:${field.fieldName}`;
    const invalidIdentity =
      !field.label.trim() ||
      field.label.length > 160 ||
      !FIELD_KEY.test(field.key) ||
      keys.has(field.key) ||
      bindings.has(binding) ||
      !NODE_ID.test(field.nodeId) ||
      !NODE_FIELD_NAME.test(field.fieldName);
    const invalidLimits =
      (field.description?.length ?? 0) > 1000 ||
      (field.group?.length ?? 0) > 80 ||
      (field.minimum != null && field.maximum != null && field.minimum > field.maximum) ||
      (field.maxLength != null &&
        (!Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > MAX_TEXT_LENGTH));
    const invalidSource =
      (source !== "PARAMETER" && sources.has(source)) ||
      (source === "PROMPT" && field.type !== "STRING") ||
      (source === "DURATION_SECONDS" && field.type !== "INTEGER");
    if (invalidIdentity || invalidLimits || invalidSource) return index;
    keys.add(field.key);
    bindings.add(binding);
    if (source !== "PARAMETER") sources.add(source);

    const options = field.options ?? [];
    if (
      (field.type === "SELECT" && !options.length) ||
      options.length > 100 ||
      new Set(options.map(option => `${typeof option.value}:${String(option.value)}`)).size !== options.length
    )
      return index;

    let defaultValue: unknown = field.defaultValue;
    const defaultDraft = rawDrafts[`${field.key}:default`];
    if (defaultDraft !== undefined) {
      // Empty defaults explicitly clear an optional value; conditions cannot be empty.
      try {
        defaultValue = defaultDraft ? (JSON.parse(defaultDraft) as unknown) : null;
      } catch {
        return index;
      }
    }
    if (defaultValue != null && !validRunningHubEditorValue(field, defaultValue)) return index;

    if (field.enabledWhen) {
      const parent = selected.find(({ field: candidate }) => candidate.key === field.enabledWhen!.field)?.field;
      let condition: unknown = field.enabledWhen.value;
      const conditionDraft = rawDrafts[`${field.key}:condition`];
      if (conditionDraft !== undefined) {
        try {
          condition = JSON.parse(conditionDraft) as unknown;
        } catch {
          return index;
        }
      }
      if (!parent || parent === field || parent.enabledWhen || !validRunningHubEditorValue(parent, condition))
        return index;
    }
  }
  return -1;
}

export function validRunningHubEditorValue(field: RunningHubField, value: unknown): boolean {
  switch (field.type) {
    case "STRING":
      return typeof value === "string" && value.length <= (field.maxLength ?? MAX_TEXT_LENGTH);
    case "BOOLEAN":
      return typeof value === "boolean";
    case "SELECT":
      if (!field.options?.some(option => option.value === value)) return false;
      break;
    case "INTEGER":
      if (typeof value !== "number" || !Number.isSafeInteger(value)) return false;
      break;
    case "NUMBER":
      if (typeof value !== "number" || !Number.isFinite(value)) return false;
      break;
    default:
      // Media fields use project-scoped version assignments, never scalar defaults.
      return false;
  }
  return (
    typeof value !== "number" ||
    ((field.minimum == null || value >= field.minimum) &&
      (field.maximum == null || value <= field.maximum) &&
      (field.source !== "DURATION_SECONDS" || (value >= 1 && value <= 60)))
  );
}

export function invalidRunningHubEditorFixed(
  definition: RunningHubDefinition,
  nodeIds: readonly string[],
  rawDrafts: Readonly<Record<string, string>>,
): number {
  return (definition.fixedBindings ?? []).findIndex((binding, index) => {
    if (!nodeIds.includes(binding.nodeId)) return false;
    if (!NODE_ID.test(binding.nodeId) || !NODE_FIELD_NAME.test(binding.fieldName)) return true;
    let value: unknown = binding.value;
    const raw = rawDrafts[`fixed:${index}:${binding.nodeId}:${binding.fieldName}`];
    if (raw !== undefined) {
      try {
        value = JSON.parse(raw) as unknown;
      } catch {
        return true;
      }
    }
    return (
      !["string", "number", "boolean"].includes(typeof value) ||
      (typeof value === "string" && value.length > MAX_TEXT_LENGTH)
    );
  });
}
