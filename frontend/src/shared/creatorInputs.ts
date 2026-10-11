import type { CreatorInputField as DeclaredCreatorInputField } from "./api/client";

/** Creator controls carry input semantics; graph targets belong to workflow protocols only. */
export type CreatorInputField = DeclaredCreatorInputField;
export type CreatorInputDefinition = { fields: CreatorInputField[] };
export type CreatorInputValue = string | number | boolean;

/** Graph mappings participate in workflow switches; native input keys have no graph target. */
export function creatorInputMapping(field: CreatorInputField): string | undefined {
  return "nodeId" in field && "fieldName" in field ? `${field.nodeId}.${field.fieldName}` : undefined;
}
