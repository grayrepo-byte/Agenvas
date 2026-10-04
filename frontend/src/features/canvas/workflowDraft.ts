import type { MediaCapability, RunningHubDefinition, RunningHubField } from "../../shared/api/client";
import type { MediaDraftFields } from "./mediaDraftCapability";
import { runningHubFieldValue, type RunningHubValue } from "./RunningHubForm";

const WORKFLOW_MEDIA_TYPES = new Set<RunningHubField["type"]>(["IMAGE", "VIDEO", "AUDIO"]);

/** Keep published ordering and apply the same effective values used for submission. */
export function activeWorkflowMediaFields(definition: Pick<RunningHubDefinition, "fields">, values: Record<string, RunningHubValue>,
  prompt: string, durationSeconds: number | null | undefined): RunningHubField[] {
  const effective = Object.fromEntries(definition.fields.map((field) =>
    [field.key, runningHubFieldValue(field, values, prompt, durationSeconds)]));
  return definition.fields.filter((field) => WORKFLOW_MEDIA_TYPES.has(field.type)
    && (!field.enabledWhen || effective[field.enabledWhen.field] === field.enabledWhen.value));
}

/** The public Comfy summary contains declared inputs only; the graph stays on the server. */
export function workflowDefinition(capability?: MediaCapability): Pick<RunningHubDefinition, "fields"> | undefined {
  if (capability?.settings.runningHub) return capability.settings.runningHub;
  const fields = capability?.settings.comfyInputs;
  if (!fields) return undefined;
  return { fields };
}

/** Existing positional Comfy drafts retain their slot assignments until the first explicit edit. */
export function workflowDraftValues(capability: MediaCapability | undefined, fields: MediaDraftFields): Record<string, RunningHubValue> {
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
