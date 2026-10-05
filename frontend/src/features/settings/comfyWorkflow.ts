import type { ComfyUiWorkflowDefinition, RunningHubField } from "../../shared/api/client";
import { t } from "../../shared/i18n";
// Required v1 metadata is unused when no dimensions are mapped into the graph.
export const COMFY_WORKFLOW_DEFAULT_SIDE = 1024;
export const COMFY_WORKFLOW_LIMITS = {
  jsonBytes: 256 * 1024, nodes: 256, bindings: 128, references: 14,
  minimumSide: 8, maximumSide: 4096, fps: 120, frameMultiple: 64, seconds: 30,
  parameters: 64, fieldKeyLength: 64, fieldLabelLength: 160, fieldMaxLength: 20000, options: 100,
} as const;
export function isComfyAdapter(adapterId: string) { return adapterId === "COMFY_IMAGE_V1" || adapterId === "COMFY_VIDEO_V1"; }

export function comfyReferenceCount(definition: ComfyUiWorkflowDefinition) {
  return Math.max(0, ...definition.bindings.filter((binding) => binding.source === "REFERENCE_IMAGE").map((binding) => (binding.referenceIndex ?? 0) + 1));
}

/** Preserve a published key even when the administrator edits the display name. */
export function comfyParameterKey(nodeId: string, inputName: string, fields: RunningHubField[]) {
  const base = `input_${nodeId}_${inputName.replace(/[^A-Za-z0-9_]/g, "_")}`.slice(0, COMFY_WORKFLOW_LIMITS.fieldKeyLength);
  let key = base;
  let ordinal = 1;
  while (fields.some((field) => field.key === key)) {
    const suffix = `_${ordinal++}`;
    key = `${base.slice(0, COMFY_WORKFLOW_LIMITS.fieldKeyLength - suffix.length)}${suffix}`;
  }
  return key;
}

export function comfyParameterTypes(value: unknown): RunningHubField["type"][] {
  return typeof value === "string" ? ["STRING", "SELECT"]
    : typeof value === "boolean" ? ["BOOLEAN", "SELECT"]
      : typeof value === "number" ? [...(Number.isInteger(value) ? ["INTEGER" as const] : []), "NUMBER", "SELECT"] : [];
}

function validParameter(field: RunningHubField, literal: unknown) {
  const limits = COMFY_WORKFLOW_LIMITS;
  const sameScalar = (value: unknown) => typeof value === typeof literal
    && (typeof value !== "number" || Number.isFinite(value));
  if (!comfyParameterTypes(literal).includes(field.type) || (field.source ?? "PARAMETER") !== "PARAMETER"
      || field.encoding === "STRING" || field.resourceFormat
      || !/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(field.key) || !field.label.trim()
      || field.key === "prompt" || field.key === "durationSeconds" || field.key.startsWith("reference_")
      || field.label.length > limits.fieldLabelLength) return false;
  if (field.minimum != null && !Number.isFinite(field.minimum) || field.maximum != null && !Number.isFinite(field.maximum)
      || field.minimum != null && field.maximum != null && field.minimum > field.maximum
      || field.maxLength != null && (!Number.isInteger(field.maxLength) || field.maxLength < 1 || field.maxLength > limits.fieldMaxLength)) return false;
  const validValue = (value: unknown) => sameScalar(value)
    && (field.type !== "INTEGER" || typeof value === "number" && Number.isInteger(value))
    && (typeof value !== "number" || (field.minimum == null || value >= field.minimum) && (field.maximum == null || value <= field.maximum))
    && (typeof value !== "string" || value.length <= (field.maxLength ?? limits.fieldMaxLength));
  if (field.type === "SELECT" && (!field.options?.length || field.options.length > limits.options
      || field.options.some((option) => !option.label.trim() || !validValue(option.value))
      || new Set(field.options.map((option) => JSON.stringify(option.value))).size !== field.options.length)) return false;
  const defaultValue = field.defaultValue ?? literal;
  return validValue(defaultValue)
    && (field.type !== "SELECT" || field.options?.some((option) => option.value === defaultValue));
}

/** Mirrors the publish invariants for immediate feedback; the server remains authoritative. */
export function comfyWorkflowProblem(definition: ComfyUiWorkflowDefinition | undefined, video: boolean): string | null {
  if (!definition) return t("settings.comfy.importRequired");
  if (!definition.graph[definition.output.nodeId]) return t("settings.comfy.outputRequired");
  const bounded = (value: number, min: number, max: number) => Number.isInteger(value) && value >= min && value <= max;
  const limits = COMFY_WORKFLOW_LIMITS;
  if (!bounded(definition.width, limits.minimumSide, limits.maximumSide) || !bounded(definition.height, limits.minimumSide, limits.maximumSide)
    || !bounded(definition.fps, 1, limits.fps) || !bounded(definition.frameMultiple, 1, limits.frameMultiple)
    || !bounded(definition.frameOffset, 0, definition.frameMultiple - 1)
    || !bounded(definition.minimumSeconds, video ? 1 : 0, video ? limits.seconds : 0)
    || !bounded(definition.maximumSeconds, definition.minimumSeconds, video ? limits.seconds : 0)) return t("settings.comfy.boundsRequired");
  const sources = new Set(definition.bindings.map((binding) => binding.source));
  if (sources.has("WIDTH") !== sources.has("HEIGHT")) return t("settings.comfy.dimensionsRequired");
  if (video && (!sources.has("DURATION_SECONDS") && !sources.has("FRAME_COUNT") || sources.has("FRAME_COUNT") && !sources.has("FPS"))) return t("settings.comfy.durationRequired");
  const indices = new Set(definition.bindings.filter((binding) => binding.source === "REFERENCE_IMAGE").map((binding) => binding.referenceIndex));
  if ([...indices].some((index) => index == null || index < 0 || index >= limits.references) || indices.size !== comfyReferenceCount(definition)) return t("settings.comfy.referencesRequired");
  const ancestors = new Set<string>();
  function visit(id: string) {
    if (ancestors.has(id)) return;
    ancestors.add(id);
    for (const input of Object.values(definition?.graph[id]?.inputs ?? {})) {
      if (Array.isArray(input) && input.length === 2 && typeof input[0] === "string" && typeof input[1] === "number") visit(input[0]);
    }
  }
  visit(definition.output.nodeId);
  const parameters = definition.parameters ?? [];
  const targets = [...definition.bindings.map((binding) => `${binding.nodeId}:${binding.inputName}`),
    ...parameters.map((field) => `${field.nodeId}:${field.fieldName}`)];
  if (new Set(targets).size !== targets.length || new Set(parameters.map((field) => field.key)).size !== parameters.length) return t("settings.comfy.duplicateMapping");
  if (parameters.length > limits.parameters || parameters.some((field) => !validParameter(field, definition.graph[field.nodeId]?.inputs[field.fieldName])
      || field.enabledWhen && !parameters.some((parent) => parent.key === field.enabledWhen?.field && parent.key !== field.key && !parent.enabledWhen))) return t("settings.comfy.parameterInvalid");
  if (definition.bindings.some((binding) => !ancestors.has(binding.nodeId))
      || parameters.some((field) => !ancestors.has(field.nodeId))) return t("settings.comfy.ancestryRequired");
  return null;
}
