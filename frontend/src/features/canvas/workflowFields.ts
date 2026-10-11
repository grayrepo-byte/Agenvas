import type { CreatorInputDefinition, CreatorInputField } from "../../shared/creatorInputs";
import { t } from "../../shared/i18n";
import { workflowFieldLabel } from "./workflowFieldPresentation";

export type WorkflowValue = string | number | boolean;
export type WorkflowChoice = { id: string; label: string; kind: string; available: boolean };
type WorkflowDefinition = CreatorInputDefinition;
const MIN_DURATION_SECONDS = 1;
const MAX_DURATION_SECONDS = 60;
export const WORKFLOW_DEFAULT_MAX_LENGTH = 20_000;

export function isWorkflowMediaField(field: CreatorInputField): boolean {
  return field.type === "IMAGE" || field.type === "VIDEO" || field.type === "AUDIO";
}

/** Prompt's empty string uses its published default; zero and false remain explicit values. */
export function workflowFieldValue(field: CreatorInputField, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined): WorkflowValue | undefined {
  if (field.source === "PROMPT") return prompt || (field.defaultValue ?? undefined);
  if (field.source === "DURATION_SECONDS") return durationSeconds ?? field.defaultValue ?? undefined;
  return values[field.key] ?? field.defaultValue ?? undefined;
}

/** Presentation, validation and slot assignment use one set of effective conditional fields. */
export function evaluateWorkflowFields(definition: WorkflowDefinition, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined) {
  const effectiveValues: Record<string, WorkflowValue | undefined> = Object.fromEntries(definition.fields.map((field) =>
    [field.key, workflowFieldValue(field, values, prompt, durationSeconds)]));
  const activeFields = definition.fields.filter((field) => !field.enabledWhen
    || effectiveValues[field.enabledWhen.field] === field.enabledWhen.value);
  return { effectiveValues, activeFields };
}

/** Duration bounds apply even when a published field omits explicit numeric constraints. */
export function workflowNumericBounds(field: CreatorInputField) {
  if (field.source === "DURATION_SECONDS") return {
    minimum: Math.max(MIN_DURATION_SECONDS, field.minimum ?? MIN_DURATION_SECONDS),
    maximum: Math.min(MAX_DURATION_SECONDS, field.maximum ?? MAX_DURATION_SECONDS),
  };
  return { minimum: field.minimum ?? undefined, maximum: field.maximum ?? undefined };
}

function validWorkflowFieldValue(field: CreatorInputField, value: WorkflowValue, choices: WorkflowChoice[]): boolean {
  switch (field.type) {
    case "STRING":
      return typeof value === "string" && value.length <= (field.maxLength ?? WORKFLOW_DEFAULT_MAX_LENGTH);
    case "BOOLEAN":
      return typeof value === "boolean";
    case "NUMBER":
    case "INTEGER": {
      if (typeof value !== "number" || !Number.isFinite(value)) return false;
      if (field.type === "INTEGER" && !Number.isInteger(value)) return false;
      const { minimum, maximum } = workflowNumericBounds(field);
      return (minimum === undefined || value >= minimum) && (maximum === undefined || value <= maximum);
    }
    case "SELECT":
      return field.options?.some((option) => option.value === value) ?? false;
    case "IMAGE":
    case "AUDIO":
    case "VIDEO":
      return choices.some((choice) => choice.id === value && choice.kind === field.type && choice.available);
  }
}

export function workflowValidationErrors(definition: WorkflowDefinition, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined, choices: WorkflowChoice[]) {
  const { effectiveValues, activeFields } = evaluateWorkflowFields(definition, values, prompt, durationSeconds);
  const errors: string[] = [];
  for (const field of activeFields) {
    const value = effectiveValues[field.key];
    if (value === undefined || value === "") {
      if (field.required) errors.push(t("media.runningHub.requiredField", { "0": workflowFieldLabel(field) }));
      continue;
    }
    if (!validWorkflowFieldValue(field, value, choices)) {
      errors.push(t("media.runningHub.invalidValue", { "0": workflowFieldLabel(field) }));
    }
  }
  const publishedKeys = new Set(definition.fields.map((field) => field.key));
  if (Object.keys(values).some((key) => !publishedKeys.has(key))) errors.push(t("media.runningHub.fieldsChanged"));
  return errors;
}

/** Reused exact versions occupy several slots but appear once in the draft's reference list. */
export function workflowUsedVersions(definition: WorkflowDefinition, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined) {
  const { effectiveValues, activeFields } = evaluateWorkflowFields(definition, values, prompt, durationSeconds);
  return new Set(activeFields.filter(isWorkflowMediaField)
    .map((field) => effectiveValues[field.key]).filter((value) => typeof value === "string"));
}
