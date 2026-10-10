import type { MediaCapability, RunningHubDefinition, RunningHubField } from "../../shared/api/client";
import type { MediaDraftFields } from "./mediaDraftCapability";
import { evaluateWorkflowFields, isWorkflowMediaField, type WorkflowValue } from "./workflowFields";

/** Keep published ordering and apply the same effective values used for submission. */
export function activeWorkflowMediaFields(definition: Pick<RunningHubDefinition, "fields">, values: Record<string, WorkflowValue>,
  prompt: string, durationSeconds: number | null | undefined): RunningHubField[] {
  return evaluateWorkflowFields(definition, values, prompt, durationSeconds).activeFields.filter(isWorkflowMediaField);
}

/** The public Comfy summary contains declared inputs only; the graph stays on the server. */
export function workflowDefinition(capability?: MediaCapability): Pick<RunningHubDefinition, "fields"> | undefined {
  if (capability?.settings.runningHub) return capability.settings.runningHub;
  const fields = capability?.settings.comfyInputs;
  if (!fields) return undefined;
  return { fields };
}

/** Existing positional Comfy drafts retain their slot assignments until the first explicit edit. */
export function workflowDraftValues(capability: MediaCapability | undefined, fields: MediaDraftFields): Record<string, WorkflowValue> {
  const values = { ...fields.parameters.dynamicValues };
  const slots = capability?.settings.comfyInputs?.filter((field) => field.type === "IMAGE") ?? [];
  if (slots.length && fields.parameters.dynamicValues === undefined) {
    for (const [index, slot] of slots.entries()) {
      const input = fields.mediaInputs[index];
      if (input) values[slot.key] = input.versionId;
    }
  }
  return values;
}
