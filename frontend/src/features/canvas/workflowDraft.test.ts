import { describe, expect, it } from "vitest";
import type { MediaCapability, RunningHubField } from "../../shared/api/client";
import type { MediaDraftFields } from "./mediaDraftCapability";
import { activeWorkflowMediaFields, workflowDefinition, workflowDraftValues } from "./workflowDraft";

const slots: RunningHubField[] = [0, 1, 2].map((index) => ({ key: `reference_${index}`,
  label: `Image ${index + 1}`, type: "IMAGE", nodeId: String(index + 1), fieldName: "image", required: true, advanced: false }));
const capability: MediaCapability = {
  id: "comfy", name: "Workflow", enabled: true, adapterId: "COMFY_IMAGE_V1", kind: "IMAGE_GENERATION",
  version: 0, capabilityVersion: 1, minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceImages: 3, maxReferenceAudios: 0, maxReferenceVideos: 0,
  supportedVideoInputModes: [], defaultVideoInputMode: null, supportsEndFrame: false,
  supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"], supportedImageQualities: [],
  supportsTransparentBackground: false, supportsImageMask: false, mappingSha256: "a".repeat(64), settings: { comfyInputs: slots },
};
const fields: MediaDraftFields = { prompt: "", parameters: {}, durationSeconds: null, capabilityId: "comfy",
  videoInputMode: null, mediaInputs: [{ versionId: "old-image", role: "REFERENCE", color: "#F15CAF" }], mentions: [] };

describe("workflow draft presentation", () => {
  it("preserves legacy positional assignments without guessing missing named slots", () => {
    expect(workflowDraftValues(capability, fields)).toEqual({ reference_0: "old-image" });
    expect(workflowDraftValues(capability, { ...fields, parameters: { dynamicValues: { reference_2: "old-image" } } }))
      .toEqual({ reference_2: "old-image" });
    expect(workflowDraftValues(capability, { ...fields, parameters: { dynamicValues: {} } })).toEqual({});
    expect(fields.parameters.dynamicValues).toBeUndefined();
  });

  it("presents the exact published slots and supports a workflow without a prompt", () => {
    expect(workflowDefinition(capability)?.fields).toEqual(slots);
    expect(workflowDefinition(capability)?.fields.some((field) => field.source === "PROMPT")).toBe(false);
  });

  it("uses effective defaults, prompt and duration values to filter media slots without changing published ordering", () => {
    const definition = { fields: [
      { key: "mode", label: "Mode", type: "INTEGER" as const, nodeId: "4", fieldName: "mode", defaultValue: 0, required: false, advanced: false },
      { key: "prompt", label: "Prompt", type: "STRING" as const, nodeId: "5", fieldName: "text", source: "PROMPT" as const, defaultValue: "default", required: false, advanced: false },
      { key: "seconds", label: "Duration", type: "INTEGER" as const, nodeId: "6", fieldName: "seconds", source: "DURATION_SECONDS" as const, defaultValue: 5, required: false, advanced: false },
      { ...slots[0]!, enabledWhen: { field: "mode", value: 0 } },
      { ...slots[1]!, enabledWhen: { field: "prompt", value: "ready" } },
      { ...slots[2]!, type: "AUDIO" as const, enabledWhen: { field: "seconds", value: 10 } },
    ] };
    expect(activeWorkflowMediaFields(definition, {}, "", null).map((field) => field.key)).toEqual(["reference_0"]);
    expect(activeWorkflowMediaFields(definition, {}, "ready", 10).map((field) => field.key)).toEqual(["reference_0", "reference_1", "reference_2"]);
    expect(activeWorkflowMediaFields(definition, { mode: 2 }, "ready", 10).map((field) => field.key)).toEqual(["reference_1", "reference_2"]);
  });
});
