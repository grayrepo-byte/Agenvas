import { describe, expect, it } from "vitest";
import type { MediaCapability, MediaTemplateImport } from "../../shared/api/client";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { templateApplicationError, templateDraftChanges, templateSeedPrompt } from "./templateApplication";

const capability: MediaCapability = {
  id: "image-cap", name: "Mock images", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "MOCK_IMAGE", kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceAudios: 0, maxReferenceImages: 4, supportedVideoInputModes: [], defaultVideoInputMode: null,
  supportsEndFrame: false, supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"],
  supportedImageQualities: [], supportsTransparentBackground: false, supportsImageMask: false,
  mappingSha256: "a".repeat(64), settings: {},
};
const fields: MediaDraftFields = { prompt: "old\uFFFC", mentions: [{ versionId: "old-image", role: "REFERENCE" }],
  capabilityId: "my-model", durationSeconds: 5, videoInputMode: "TEXT", parameters: { aspectRatio: "9:16", quality: "high" },
  mediaInputs: [{ versionId: "old-image", role: "REFERENCE", color: "#ffffff" }] };
function imported(count: number): MediaTemplateImport {
  return { templateId: "template", templateVersion: 1, targetKind: "IMAGE", prompt: "watercolor scene", images: Array.from({ length: count }, (_, index) => ({
    versionId: `new-${index}`, assetId: `asset-${index}`, title: "Synthetic image", contentType: "image/png",
    width: 100, height: 100, byteSize: 100, thumbnailUrl: "/synthetic.png",
  })) };
}
describe("template application", () => {
  it("saves repeated structured mentions as readable labels without original version identities", () => {
    const seeded = templateSeedPrompt({ ...fields, prompt: "Make \uFFFC red and keep \uFFFC sharp", mentions: [
      { versionId: "old-image", role: "REFERENCE" }, { versionId: "old-image", role: "REFERENCE" }] });
    expect(seeded).toBe("Make @Image 1 red and keep @Image 1 sharp");
    expect(seeded).not.toContain("old-image"); expect(seeded).not.toContain("\uFFFC");
    expect(templateSeedPrompt({ ...fields, prompt: "Plain prompt", mentions: [] })).toBe("Plain prompt");
    expect(templateSeedPrompt({ ...fields, mediaInputs: [{ versionId: "old-image", role: "START_FRAME", color: "#ffffff" }] })).toBe("old@Start Frame");
  });
  it("replaces plain prompt and clears old mentions without changing prompt-only references or model settings", () => {
    const changes = templateDraftChanges("IMAGE", fields, undefined, imported(0), { videoInputMode: null, imageSlots: [] }, ["#ffffff"]);
    expect(changes).toEqual({ prompt: "watercolor scene", mentions: [] });
    expect({ ...fields, ...changes }).toMatchObject({ mediaInputs: fields.mediaInputs, capabilityId: "my-model", durationSeconds: 5, parameters: fields.parameters });
  });
  it("requires an explicit supported video mode and refuses to truncate start/end inputs", () => {
    const video = { ...capability, kind: "VIDEO_GENERATION" as const, supportedVideoInputModes: ["TEXT", "START_END"] as MediaCapability["supportedVideoInputModes"], supportsEndFrame: true };
    expect(templateApplicationError("VIDEO", fields, video, 1, { videoInputMode: null, imageSlots: [] }, "prompt")).toBeTruthy();
    expect(templateApplicationError("VIDEO", fields, video, 1, { videoInputMode: "GENERAL_REFERENCE", imageSlots: [] }, "prompt")).toBeTruthy();
    expect(() => templateDraftChanges("VIDEO", fields, video, imported(3), { videoInputMode: "START_END", imageSlots: [] }, ["#ffffff"])).toThrow();
    const changes = templateDraftChanges("VIDEO", fields, video, imported(2), { videoInputMode: "START_END", imageSlots: [] }, ["#ffffff"]);
    expect(changes.mediaInputs?.map((input) => input.role)).toEqual(["START_FRAME", "END_FRAME"]);
    expect(changes).not.toHaveProperty("parameters");
    expect(templateApplicationError("VIDEO", fields, { ...video, maxReferenceImages: 0 }, 2,
      { videoInputMode: "START_END", imageSlots: [] }, "prompt")).toBeNull();
  });
  it("assigns imported images to explicit active RunningHub fields while retaining non-media parameters", () => {
    const dynamic = { ...capability, settings: { runningHub: { schemaVersion: 1 as const, protocolVersion: "V2" as const,
      targetId: "public-example", targetType: "WORKFLOW" as const, usePersonalQueue: false, addMetadata: false, outputs: [{ kind: "IMAGE" as const, primary: true, maxCount: 1 }], fields: [
        { key: "imageA", label: "Image A", type: "IMAGE" as const, required: true, advanced: false, nodeId: "1", fieldName: "image" },
        { key: "imageB", label: "Image B", type: "IMAGE" as const, required: false, advanced: false, nodeId: "2", fieldName: "image" },
        { key: "oldAudio", label: "Audio", type: "AUDIO" as const, required: false, advanced: false, nodeId: "3", fieldName: "audio" },
      ] } } };
    const state = { ...fields, parameters: { ...fields.parameters, dynamicValues: { imageA: "old-image", oldAudio: "old-audio", strength: 0.8 } } };
    expect(templateApplicationError("IMAGE", state, dynamic, 2, { videoInputMode: null, imageSlots: ["imageA", "imageA"] }, "prompt")).toBeTruthy();
    const changes = templateDraftChanges("IMAGE", state, dynamic, imported(2), { videoInputMode: null, imageSlots: ["imageB", "imageA"] }, ["#ffffff"]);
    expect(changes.parameters).toEqual({ aspectRatio: "9:16", quality: "high", dynamicValues: { strength: 0.8, imageB: "new-0", imageA: "new-1" } });
  });
});
