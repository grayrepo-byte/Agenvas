import type { MediaCapability, RunningHubDefinition } from "../../shared/api/client";
import type { MediaDraftFields } from "./mediaDraftCapability";
import type { RunningHubValue } from "./RunningHubForm";

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
