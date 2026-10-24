import type { RunningHubDefinition, RunningHubField } from "../../shared/api/client";

/** Pagination removes controls from the DOM; validate the complete selected draft before submission. */
export function invalidRunningHubEditorField(definition: RunningHubDefinition, nodeIds: readonly string[], fieldKeys: readonly string[], rawDrafts: Readonly<Record<string, string>>): number {
  const keys = new Set<string>();
  const bindings = new Set<string>();
  const sources = new Set<string>();
  const selected = definition.fields.filter((field) => nodeIds.includes(field.nodeId) && fieldKeys.includes(field.key));
  for (const field of selected) {
    const source = field.source ?? "PARAMETER";
    const binding = `${field.nodeId}:${field.fieldName}`;
    let invalid = !field.label.trim() || field.label.length > 160 || !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(field.key)
      || keys.has(field.key) || bindings.has(binding) || !/^[0-9]{1,32}$/.test(field.nodeId)
      || !/^[A-Za-z_][A-Za-z0-9_.]{0,79}$/.test(field.fieldName)
      || (field.description?.length ?? 0) > 1000 || (field.group?.length ?? 0) > 80
      || field.minimum != null && field.maximum != null && field.minimum > field.maximum
      || field.maxLength != null && (!Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > 20000)
      || source !== "PARAMETER" && sources.has(source)
      || source === "PROMPT" && field.type !== "STRING" || source === "DURATION_SECONDS" && field.type !== "INTEGER";
    keys.add(field.key); bindings.add(binding); if (source !== "PARAMETER") sources.add(source);
    const options = field.options ?? [];
    if (field.type === "SELECT" && !options.length || options.length > 100
      || new Set(options.map((option) => `${typeof option.value}:${String(option.value)}`)).size !== options.length) invalid = true;
    let defaultValue: unknown = field.defaultValue;
    const defaultDraft = rawDrafts[`${field.key}:default`];
    if (defaultDraft !== undefined) {
      try { defaultValue = defaultDraft ? JSON.parse(defaultDraft) as unknown : null; }
      catch { invalid = true; }
    }
    if (defaultValue != null && !validRunningHubEditorValue(field, defaultValue)) invalid = true;
    if (field.enabledWhen) {
      const parent = selected.find((candidate) => candidate.key === field.enabledWhen!.field);
      let condition: unknown = field.enabledWhen.value;
      const conditionDraft = rawDrafts[`${field.key}:condition`];
      if (conditionDraft !== undefined) { try { condition = JSON.parse(conditionDraft) as unknown; } catch { invalid = true; } }
      if (!parent || parent === field || parent.enabledWhen || ["IMAGE", "AUDIO", "VIDEO"].includes(parent.type) || !validRunningHubEditorValue(parent, condition)) invalid = true;
    }
    if (invalid) return definition.fields.indexOf(field);
  }
  return -1;
}

export function validRunningHubEditorValue(field: RunningHubField, value: unknown): boolean {
  const valid = field.type === "STRING" ? typeof value === "string" && value.length <= (field.maxLength ?? 20000)
    : field.type === "BOOLEAN" ? typeof value === "boolean"
    : field.type === "SELECT" ? !!field.options?.some((option) => option.value === value)
    : field.type === "INTEGER" ? typeof value === "number" && Number.isSafeInteger(value)
    : field.type === "NUMBER" ? typeof value === "number" && Number.isFinite(value) : false;
  return valid && (typeof value !== "number" || (field.minimum == null || value >= field.minimum)
    && (field.maximum == null || value <= field.maximum)
    && (field.source !== "DURATION_SECONDS" || value >= 1 && value <= 60));
}

export function invalidRunningHubEditorFixed(definition: RunningHubDefinition, nodeIds: readonly string[], rawDrafts: Readonly<Record<string, string>>): number {
  return (definition.fixedBindings ?? []).findIndex((binding, index) => {
    if (!nodeIds.includes(binding.nodeId)) return false;
    if (!/^[0-9]{1,32}$/.test(binding.nodeId) || !/^[A-Za-z_][A-Za-z0-9_.]{0,79}$/.test(binding.fieldName)) return true;
    let value: unknown = binding.value;
    const raw = rawDrafts[`fixed:${index}:${binding.nodeId}:${binding.fieldName}`];
    if (raw !== undefined) { try { value = JSON.parse(raw) as unknown; } catch { return true; } }
    return !["string", "number", "boolean"].includes(typeof value) || typeof value === "string" && value.length > 20000;
  });
}
