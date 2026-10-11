import type { MediaCapability } from "../../shared/api/client";
import type { CreatorInputDefinition, CreatorInputField } from "../../shared/creatorInputs";
import type { MediaDraftFields } from "./mediaDraftCapability";
import { evaluateWorkflowFields, isWorkflowMediaField, type WorkflowValue } from "./workflowFields";

/** Keep published ordering and apply the same effective values used for submission. */
export function activeWorkflowMediaFields(definition: CreatorInputDefinition, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined): CreatorInputField[] {
  return evaluateWorkflowFields(definition, values, prompt, durationSeconds).activeFields.filter(isWorkflowMediaField);
}

/** The public Comfy summary contains declared inputs only; the graph stays on the server. */
export function workflowDefinition(capability?: MediaCapability): CreatorInputDefinition | undefined {
  if (capability?.settings.runningHub) return capability.settings.runningHub;
  if (capability?.settings.klingInputs) return { fields: capability.settings.klingInputs };
  if (capability?.settings.nativeInputs) return { fields: capability.settings.nativeInputs };
  const fields = capability?.settings.comfyInputs;
  if (!fields) return undefined;
  return { fields };
}

/** Existing positional Comfy drafts retain their slot assignments until the first explicit edit. */
export function workflowDraftValues(capability: MediaCapability | undefined, fields: MediaDraftFields): Record<string, WorkflowValue> {
  const values = { ...capability?.settings.defaultParameters?.dynamicValues, ...fields.parameters.dynamicValues };
  const slots = capability?.settings.comfyInputs?.filter((field) => field.type === "IMAGE") ?? [];
  if (slots.length && fields.parameters.dynamicValues === undefined) {
    for (const [index, slot] of slots.entries()) {
      const input = fields.mediaInputs[index];
      if (input) values[slot.key] = input.versionId;
    }
  }
  return values;
}
