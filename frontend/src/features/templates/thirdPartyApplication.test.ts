import { describe, expect, it } from "vitest";
import type { MediaCapability, ThirdPartyPromptEntry, MediaTemplateImport } from "../../shared/api/client";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { thirdPartyApplicationError, thirdPartyReferences, thirdPartyVideoMode } from "./thirdPartyApplication";
import { templateDraftChanges } from "./templateApplication";

const capability: MediaCapability = { id: "video", name: "Synthetic", kind: "VIDEO_GENERATION", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "MOCK_VIDEO", minimumSeconds: 0, maximumSeconds: 20, maxReferenceImages: 2, maxReferenceVideos: 1, maxReferenceAudios: 1,
  supportedVideoInputModes: ["TEXT", "START_END", "GENERAL_REFERENCE"], defaultVideoInputMode: "TEXT", supportsEndFrame: true,
  supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], supportsImageMask: false,
  supportsTransparentBackground: false, mappingSha256: "a".repeat(64), settings: {} };
const fields: MediaDraftFields = { capabilityId: "chosen-model", prompt: "Draft", mediaInputs: [], mentions: [], parameters: { quality: "high" },
  durationSeconds: 5, videoInputMode: "TEXT" };
const entry: ThirdPartyPromptEntry = { id: "native:v", sourceId: "native", targetKind: "VIDEO", image: null, version: 1,
  cachedAt: "2026-10-08T00:00:00Z", updatedAt: "2026-10-08T00:00:00Z", video: { id: "native:v", sourceId: "native", title: "Motion",
    prompt: "Orbit camera", description: "", coverUrl: "https://example.com/preview.jpg", tags: [], author: "", sourceUrl: "", createdAt: "",
    videoMode: "image_to_video", videoModel: "source-model", imageGeneration: null,
    references: [{ kind: "IMAGE", role: "START_FRAME", url: "https://example.com/start.png" }] } };
describe("third-party template application", () => {
  it("uses only explicit inputs and restores the required start-frame mode", () => {
    expect(thirdPartyReferences(entry)).toEqual(entry.video?.references);
    expect(thirdPartyReferences(entry)).not.toContainEqual(expect.objectContaining({ url: entry.video?.coverUrl }));
    expect(thirdPartyVideoMode(entry)).toBe("START_END");
    expect(thirdPartyApplicationError(entry, fields, capability, [])).toBeNull();
    expect(thirdPartyApplicationError(entry, fields, { ...capability, supportedVideoInputModes: ["TEXT"] }, [])).toBeTruthy();
  });
  it("keeps mixed media roles and rejects capacity limits before importing", () => {
    const mixed: ThirdPartyPromptEntry = { ...entry, video: { ...entry.video!, videoMode: "omni_reference", references: [
      { kind: "IMAGE", role: "REFERENCE", url: "https://example.com/image.png" },
      { kind: "VIDEO", role: "VIDEO_REFERENCE", url: "https://example.com/video.mp4" },
      { kind: "AUDIO", role: "AUDIO_REFERENCE", url: "https://example.com/audio.wav" },
    ] } };
    expect(thirdPartyVideoMode(mixed)).toBe("GENERAL_REFERENCE");
    expect(thirdPartyApplicationError(mixed, fields, capability, [])).toBeNull();
    expect(thirdPartyApplicationError(mixed, fields, { ...capability, maxReferenceVideos: 0 }, [])).toBeTruthy();
    const imported: MediaTemplateImport = { templateId: "template", templateVersion: 1, targetKind: "VIDEO", prompt: "Orbit camera", images: [],
      videoInputMode: "GENERAL_REFERENCE", references: mixed.video!.references.map((ref, index) => ({ ...ref, versionId: `v${index}`,
        assetId: `a${index}`, title: "Reference", thumbnailUrl: "" })) };
    const changes = templateDraftChanges("VIDEO", fields, capability, imported, { videoInputMode: "GENERAL_REFERENCE", imageSlots: [] }, ["#fff"]);
    expect(changes.mediaInputs?.map((ref) => ref.role)).toEqual(["REFERENCE", "VIDEO_REFERENCE", "AUDIO_REFERENCE"]);
    expect(changes.videoInputMode).toBe("GENERAL_REFERENCE");
    expect({ ...fields, ...changes }).toMatchObject({ capabilityId: "chosen-model", durationSeconds: 5, parameters: fields.parameters });
  });
  it("requires an existing image for upstream edit templates whose inputs were not published", () => {
    const image: ThirdPartyPromptEntry = { ...entry, targetKind: "IMAGE", video: null, image: { id: entry.id, sourceId: entry.sourceId,
      title: "Edit", prompt: "Keep identity", description: "", coverUrl: "", author: "", sourceUrl: "", createdAt: "", tags: [],
      imageMode: "edit", imageModel: "image", referenceImageUrls: [] } };
    expect(thirdPartyApplicationError(image, fields, undefined, [])).toBeTruthy();
    expect(thirdPartyApplicationError(image, { ...fields, mediaInputs: [{ versionId: "input", role: "REFERENCE", color: "#fff" }] }, undefined, [])).toBeNull();
  });
  it("does not silently discard a text-to-image stage or existing text-to-video references", () => {
    expect(thirdPartyApplicationError({ ...entry, video: { ...entry.video!, videoMode: "text_to_image_to_video", imageGeneration: {
      prompt: "Create character", imageModel: "image", referenceImageUrls: [] } } }, fields, capability, [])).toBeTruthy();
    const text: ThirdPartyPromptEntry = { ...entry, video: { ...entry.video!, videoMode: "text_to_video", references: [] } };
    expect(thirdPartyApplicationError(text, { ...fields, mediaInputs: [{ versionId: "input", role: "REFERENCE", color: "#fff" }] }, capability, [])).toBeTruthy();
  });
});
