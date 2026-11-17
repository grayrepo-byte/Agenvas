import { describe, expect, it } from "vitest";
import type { MediaCapability } from "../../shared/api/client";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { catalogReferenceFields, catalogReferenceUnavailable } from "./catalogReferenceApplication";
const fields: MediaDraftFields = {
  prompt: "Keep character direction",
  capabilityId: "model",
  durationSeconds: null,
  styleId: "style",
  videoInputMode: null,
  parameters: { quality: "high" },
  mentions: [{ versionId: "character-v1", role: "REFERENCE" }],
  mediaInputs: [{ versionId: "character-v1", role: "REFERENCE", color: "pink" }],
};
const capability = { maxReferenceImages: 4, settings: {} } as MediaCapability;
describe("catalog reference application", () => {
  it("appends exactly one ordinary image while preserving prompt, parameters and character inputs", () => {
    const next = catalogReferenceFields({ fields, kind: "IMAGE", capability }, "pose-v1", "blue");
    expect(next).toEqual({
      ...fields,
      mediaInputs: [...fields.mediaInputs, { versionId: "pose-v1", role: "REFERENCE", color: "blue" }],
    });
    expect(fields.mediaInputs).toHaveLength(1);
  });
  it.each(["TEXT", "START_END"] as const)("rejects %s video input without changing mode or assigning frames", mode => {
    expect(() =>
      catalogReferenceFields(
        { fields: { ...fields, videoInputMode: mode }, kind: "VIDEO", capability },
        "pose-v1",
        "blue",
      ),
    ).toThrow();
  });
  it("reports a full model before importing", () => {
    expect(
      catalogReferenceUnavailable({ fields, kind: "IMAGE", capability: { ...capability, maxReferenceImages: 1 } }),
    ).toBeTruthy();
  });
  it("requires an explicitly selected empty image slot and preserves other dynamic parameters", () => {
    const workflow = {
      ...capability,
      settings: {
        comfyInputs: [
          { key: "subject", label: "Subject", type: "IMAGE", source: "PARAMETER", required: false },
          { key: "pose", label: "Pose", type: "IMAGE", source: "PARAMETER", required: false },
        ],
      },
    } as MediaCapability;
    const base = {
      ...fields,
      parameters: { ...fields.parameters, dynamicValues: { subject: "character-v1", seed: 42 } },
    };
    expect(catalogReferenceUnavailable({ fields: base, kind: "IMAGE", capability: workflow })).toBeTruthy();
    expect(
      catalogReferenceUnavailable({ fields: base, kind: "IMAGE", capability: workflow, slotKey: "subject" }),
    ).toBeTruthy();
    const next = catalogReferenceFields(
      { fields: base, kind: "IMAGE", capability: workflow, slotKey: "pose" },
      "pose-v1",
      "blue",
    );
    expect(next.parameters.dynamicValues).toEqual({ subject: "character-v1", seed: 42, pose: "pose-v1" });
    expect(next.prompt).toBe(fields.prompt);
    expect(next.mediaInputs).toHaveLength(2);
  });
});
