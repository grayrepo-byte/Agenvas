import type { CreatorInputField } from "../../shared/creatorInputs";

/** Imported node names describe implementation; descriptions explain the user's parameter. */
export function workflowFieldLabel(field: CreatorInputField): string {
  return "nodeId" in field ? field.description?.trim() || field.label : field.label;
}

/** Keep the saved name and exact mapping available without repeating them in the form. */
export function workflowFieldHint(field: CreatorInputField): string {
  return "nodeId" in field && "fieldName" in field
    ? `${field.label} · ${field.nodeId}.${field.fieldName}` : field.description?.trim() || field.label;
}
