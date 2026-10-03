import { describe, expect, it } from "vitest";
import type { MediaCapability, RunningHubDefinition } from "../../shared/api/client";
import { AUTODL_ADAPTER } from "../../shared/autodlWorkflows";
import { inputsForVideoMode, planMediaCapabilityChange, type MediaDraftFields } from "./mediaDraftCapability";
import { MENTION_MARKER } from "./mediaPrompt";

const image: MediaCapability = {
  id: "image-model", name: "Image", enabled: true, version: 0, capabilityVersion: 1,
  adapterId: "OPENAI_GPT_IMAGE_2", kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceImages: 4, maxReferenceAudios: 0, maxReferenceVideos: 0, supportedVideoInputModes: [], defaultVideoInputMode: null,
  supportsEndFrame: false, supportedImageAspectRatios: ["AUTO", "16:9"],
  supportedImageResolutions: ["1K", "2K"], supportedImageQualities: ["medium", "high"],
  supportsTransparentBackground: true, supportsImageMask: true, mappingSha256: "a".repeat(64), settings: {},
};
const video: MediaCapability = { ...image, id: "video-model", kind: "VIDEO_GENERATION",
  adapterId: "ARK_SEEDANCE_2_I2V", supportedVideoInputModes: ["TEXT", "GENERAL_REFERENCE", "START_END"],
  defaultVideoInputMode: "TEXT" };
const imageInput = { versionId: "image-version", role: "REFERENCE", color: "#F15CAF" } as const;
const audioInput = { versionId: "audio-version", role: "AUDIO_REFERENCE", color: "#67C7F3" } as const;
const fields: MediaDraftFields = { prompt: "Create a scene", parameters: {}, durationSeconds: 5,
  mediaInputs: [], mentions: [], capabilityId: null, videoInputMode: null };
const definition: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "AI_APP", targetId: "123",
  usePersonalQueue: false, addMetadata: false,
  fields: [
    { key: "frame", label: "Frame", type: "IMAGE", nodeId: "1", fieldName: "image", required: true, advanced: false },
    { key: "strength", label: "Strength", type: "NUMBER", nodeId: "2", fieldName: "strength", minimum: 0, maximum: 1, required: false, advanced: false },
    { key: "mode", label: "Mode", type: "SELECT", nodeId: "3", fieldName: "mode", options: [{ label: "A", value: "a" }, { label: "B", value: "b" }], required: false, advanced: false },
    { key: "sound", label: "Sound", type: "BOOLEAN", nodeId: "4", fieldName: "sound", required: false, advanced: false },
  ], outputs: [{ kind: "VIDEO", primary: true, maxCount: 1 }],
};
const dynamic: MediaCapability = { ...video, id: "runninghub", adapterId: "RUNNINGHUB_VIDEO",
  settings: { runningHub: definition } };

describe("media capability changes", () => {
  it.each(["GENERAL_REFERENCE", "START_END"] as const)("selects supported %s for an empty draft when switching from a text model", (mode) => {
    const next: MediaCapability = { ...video, id: "image-only-video", supportedVideoInputModes: [mode], defaultVideoInputMode: mode };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, videoInputMode: "TEXT" },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields).toMatchObject({ capabilityId: next.id, videoInputMode: mode, mediaInputs: [] });
    expect(change.confirmation).toBeNull();
  });

  it("keeps the implicit default selection and compatible image parameters without prompting", () => {
    const parameters = { aspectRatio: "16:9", resolution: "2K", quality: "high", transparentBackground: true, generationCount: 4 } as const;
    expect(planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields, parameters }, capabilityId: null,
      resolvedCapabilityId: image.id, previous: image, next: image }))
      .toEqual({ fields: { capabilityId: null, parameters }, confirmation: null });
  });

  it("requests confirmation before resetting unsupported image parameters", () => {
    const next = { ...image, supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"], supportsTransparentBackground: false } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields,
      parameters: { aspectRatio: "16:9", resolution: "2K", transparentBackground: true } },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: image, next });
    expect(change.confirmation).toContain("不受支持的图片参数");
    expect(change.fields.parameters).toMatchObject({ aspectRatio: "AUTO", resolution: "1K", transparentBackground: false });
    expect(change.fields).not.toHaveProperty("mediaInputs");
  });

  it("preserves static audio settings when selecting another capability", () => {
    expect(planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields, parameters: { speechRate: 50 } },
      capabilityId: null, resolvedCapabilityId: "audio-default" }))
      .toEqual({ fields: { capabilityId: null }, confirmation: null });
  });

  it("changes mixed references to frames and prunes only removed versions without mutating the draft", () => {
    const third = { ...imageInput, versionId: "third-image" };
    const second = { ...imageInput, versionId: "second-image" };
    const before: MediaDraftFields = { ...fields, videoInputMode: "GENERAL_REFERENCE",
      parameters: { videoResolution: "768p" }, mediaInputs: [audioInput, imageInput, second, third],
      prompt: `${MENTION_MARKER}/${MENTION_MARKER}/${MENTION_MARKER}/${MENTION_MARKER}`,
      mentions: [audioInput, imageInput, second, third] };
    const snapshot = structuredClone(before);
    const next = { ...video, supportedVideoInputModes: ["START_END"] } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: before,
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields).toMatchObject({ videoInputMode: "START_END",
      mediaInputs: [{ ...imageInput, role: "START_FRAME" }, { ...second, role: "END_FRAME" }],
      prompt: `/${MENTION_MARKER}/${MENTION_MARKER}/`, mentions: [
        { versionId: imageInput.versionId, role: "START_FRAME" }, { versionId: second.versionId, role: "END_FRAME" }],
      parameters: { aspectRatio: "AUTO" } });
    expect(change.fields.parameters).not.toHaveProperty("videoResolution");
    expect(change.confirmation).toContain("重置不支持的分辨率");
    expect(before).toEqual(snapshot);
  });

  it("retains mixed inputs in general reference mode while keeping an implicit default", () => {
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      videoInputMode: "START_END", mediaInputs: [{ ...imageInput, role: "START_FRAME" }, audioInput] },
    capabilityId: null, resolvedCapabilityId: video.id, next: { ...video, supportedVideoInputModes: ["GENERAL_REFERENCE"] } });
    expect(change.confirmation).toBeNull();
    expect(change.fields).toMatchObject({ capabilityId: null, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [imageInput, audioInput] });
  });

  it.each(["768p", "1080p"] as const)("keeps published AutoDL tiers and resets unsupported %s tiers", (resolution) => {
    const next = { ...video, adapterId: AUTODL_ADAPTER, settings: {
      workflowId: "minimax_h3_image_audio_to_video_v2", videoResolution: "480p", videoResolutions: ["480p", "768p"],
    } } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { videoResolution: resolution } },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields.parameters?.videoResolution).toBe(resolution === "768p" ? resolution : "480p");
    expect(change.confirmation === null).toBe(resolution === "768p");
  });

  it("retains RunningHub values only when field mappings, ranges and options remain compatible", () => {
    const next: MediaCapability = { ...dynamic, id: "runninghub-next", settings: { runningHub: { ...definition,
      fields: definition.fields.map((field) => field.key === "strength" ? { ...field, maximum: 0.25 }
        : field.key === "mode" ? { ...field, options: [{ label: "A", value: "a" }] } : field),
    } } };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      parameters: { dynamicValues: { frame: imageInput.versionId, strength: 0.5, mode: "b", sound: false } },
      mediaInputs: [imageInput, audioInput], prompt: `${MENTION_MARKER}+${MENTION_MARKER}`, mentions: [imageInput, audioInput] },
    capabilityId: null, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields).toMatchObject({ capabilityId: next.id, durationSeconds: null,
      parameters: { dynamicValues: { frame: imageInput.versionId, sound: false } }, mediaInputs: [imageInput],
      videoInputMode: "GENERAL_REFERENCE", prompt: `${MENTION_MARKER}+` });
    expect(change.confirmation).toContain("strength、mode");
    const remapped = { ...next, settings: { runningHub: { ...definition,
      fields: definition.fields.map((field) => ({ ...field, nodeId: "new-node" })),
    } } };
    expect(planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput] },
    capabilityId: remapped.id, resolvedCapabilityId: remapped.id, previous: dynamic, next: remapped }).fields)
      .toMatchObject({ parameters: { dynamicValues: {} }, mediaInputs: [], videoInputMode: "TEXT" });
  });

  it("clears dynamic parameters and exact inputs when leaving RunningHub", () => {
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput],
      prompt: `Use ${MENTION_MARKER}`, mentions: [imageInput] },
    capabilityId: null, resolvedCapabilityId: video.id, previous: dynamic, next: video });
    expect(change.fields).toEqual({ capabilityId: video.id, parameters: {}, mediaInputs: [],
      prompt: "Use ", mentions: [], durationSeconds: null, videoInputMode: "TEXT" });
    expect(change.confirmation).toContain("frame");
  });

  it.each(["GENERAL_REFERENCE", "START_END"] as const)("uses supported %s immediately when leaving RunningHub for an image-only model", (mode) => {
    const next: MediaCapability = { ...video, supportedVideoInputModes: [mode], defaultVideoInputMode: mode };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput] },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields).toMatchObject({ videoInputMode: mode, mediaInputs: [] });
  });

  it("keeps duration only when the next dynamic contract explicitly sources it", () => {
    const next = { ...dynamic, settings: { runningHub: { ...definition, fields: [...definition.fields,
      { key: "seconds", label: "Seconds", type: "INTEGER", nodeId: "5", fieldName: "duration", source: "DURATION_SECONDS", required: true, advanced: false },
    ] } } } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { aspectRatio: "16:9" } },
      capabilityId: next.id, resolvedCapabilityId: next.id, next });
    expect(change.fields.durationSeconds).toBe(5);
    expect(change.confirmation).toContain("不兼容参数");
  });
  it("keeps video roles in general reference mode and removes them from first/last slots", () => {
    const videoInput = { versionId: "video-version", role: "VIDEO_REFERENCE", color: "#67C7F3" } as const;
    const before = { ...fields, videoInputMode: "GENERAL_REFERENCE" as const, mediaInputs: [videoInput, imageInput, audioInput] };
    const general = planMediaCapabilityChange({ kind: "VIDEO", fields: before, capabilityId: video.id,
      resolvedCapabilityId: video.id, next: video });
    expect(general.fields.mediaInputs).toEqual(before.mediaInputs);
    const frames = inputsForVideoMode(before.mediaInputs, "START_END");
    expect(frames).toEqual([{ ...imageInput, role: "START_FRAME" }]);
  });

});
