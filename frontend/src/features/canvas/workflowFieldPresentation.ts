import type { RunningHubField } from "../../shared/api/client";

/** Imported node names describe implementation; descriptions explain the user's parameter. */
export function workflowFieldLabel(field: RunningHubField): string {
  return field.description?.trim() || field.label;
}

/** Keep the saved name and exact mapping available without repeating them in the form. */
export function workflowFieldHint(field: RunningHubField): string {
  return `${field.label} · ${field.nodeId}.${field.fieldName}`;
}
