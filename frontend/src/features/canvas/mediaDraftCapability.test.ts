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
  maxReferenceAudios: 3, maxReferenceVideos: 3, supportsEndFrame: true, defaultVideoInputMode: "TEXT" };
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
  it.each((["IMAGE", "VIDEO", "AUDIO"] as const).flatMap((kind) =>
    (["IMAGE", "VIDEO", "AUDIO"] as const).map((inputKind) => ({ kind, inputKind }))))(
    "matches $inputKind references to published slots independently of $kind output", ({ kind, inputKind }) => {
      const input = { ...imageInput, role: inputKind === "AUDIO" ? "AUDIO_REFERENCE" as const
        : inputKind === "VIDEO" ? "VIDEO_REFERENCE" as const : "REFERENCE" as const };
      const next: MediaCapability = { ...dynamic, kind: kind === "AUDIO" ? "AUDIO_GENERATION"
        : kind === "VIDEO" ? "VIDEO_GENERATION" : "IMAGE_GENERATION", settings: { runningHub: { ...definition,
        fields: [{ ...definition.fields[0]!, type: inputKind }], outputs: [{ kind, primary: true, maxCount: 1 }] } } };
      const change = planMediaCapabilityChange({ kind, fields: { ...fields,
        mediaInputs: [input], prompt: `Use ${MENTION_MARKER}`, mentions: [input] },
      capabilityId: next.id, resolvedCapabilityId: next.id, next });
      expect(change.fields).toMatchObject({ parameters: { dynamicValues: { frame: input.versionId } },
        mediaInputs: [input], prompt: `Use ${MENTION_MARKER}`, mentions: [input] });
    });

  it("trims image references when switching ordinary image models to a smaller capacity", () => {
    const next = { ...image, maxReferenceImages: 1 };
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields,
      mediaInputs: [imageInput, { ...imageInput, versionId: "extra-image" }] },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: image, next });
    expect(change.fields.mediaInputs).toEqual([imageInput]);
  });
  it("prunes unsupported types and excess references when switching ordinary video models", () => {
    const inputs = [imageInput, { ...imageInput, versionId: "extra-image" }, audioInput,
      { ...imageInput, versionId: "video-version", role: "VIDEO_REFERENCE" as const }];
    const next = { ...video, maxReferenceImages: 1, maxReferenceAudios: 1, maxReferenceVideos: 0 };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, videoInputMode: "GENERAL_REFERENCE",
      mediaInputs: inputs, prompt: inputs.map(() => MENTION_MARKER).join("/"), mentions: inputs },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields.mediaInputs).toEqual([imageInput, audioInput]);
    expect(change.fields.prompt).toBe(`${MENTION_MARKER}//${MENTION_MARKER}/`);
  });

  it("removes only the end frame when switching to a first-frame-only video model", () => {
    const start = { ...imageInput, role: "START_FRAME" as const };
    const end = { ...imageInput, versionId: "end-version", role: "END_FRAME" as const };
    const next = { ...video, supportedVideoInputModes: ["START_END" as const], supportsEndFrame: false };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, videoInputMode: "START_END",
      mediaInputs: [start, end], prompt: `${MENTION_MARKER}/${MENTION_MARKER}`, mentions: [start, end] },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields.mediaInputs).toEqual([start]);
    expect(change.fields.prompt).toBe(`${MENTION_MARKER}/`);
  });

  it("selects text and clears references when switching to a text-only video model", () => {
    const next = { ...video, supportedVideoInputModes: ["TEXT" as const], defaultVideoInputMode: "TEXT" as const };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, videoInputMode: "GENERAL_REFERENCE",
      mediaInputs: [imageInput], prompt: `Use ${MENTION_MARKER}`, mentions: [imageInput] },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields).toMatchObject({ videoInputMode: "TEXT", mediaInputs: [], mentions: [], prompt: "Use " });
  });

  it("prunes unsupported references when switching ordinary audio models", () => {
    const previous = { ...image, kind: "AUDIO_GENERATION" as const, maxReferenceAudios: 3 };
    const next = { ...previous, maxReferenceImages: 0, maxReferenceAudios: 1 };
    const change = planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields,
      mediaInputs: [imageInput, audioInput, { ...audioInput, versionId: "extra-audio" }] },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous, next });
    expect(change.fields.mediaInputs).toEqual([audioInput]);
  });

  it.each(["IMAGE", "AUDIO"] as const)("keeps the first compatible %s modality when switching a mixed workflow to Seed Audio", (first) => {
    const next = { ...image, adapterId: "VOLC_SEED_AUDIO_1", kind: "AUDIO_GENERATION" as const,
      maxReferenceImages: 1, maxReferenceAudios: 3 };
    const inputs = first === "IMAGE" ? [imageInput, audioInput] : [audioInput, imageInput];
    const change = planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields, mediaInputs: inputs },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields.mediaInputs).toEqual([inputs[0]]);
  });

  it("reserves an audio reference slot for the selected speaker when switching from a workflow", () => {
    const next = { ...image, adapterId: "VOLC_SEED_AUDIO_1", kind: "AUDIO_GENERATION" as const,
      maxReferenceImages: 1, maxReferenceAudios: 2, settings: { defaultParameters: { speaker: "synthetic-voice" } } };
    const change = planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields,
      mediaInputs: [imageInput, audioInput, { ...audioInput, versionId: "extra-audio" }] },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields.mediaInputs).toEqual([audioInput]);
  });

  it("assigns existing GPT Image references to RunningHub image slots when switching models", () => {
    const next: MediaCapability = { ...image, id: "runninghub-edit", adapterId: "RUNNINGHUB_IMAGE",
      settings: { runningHub: { ...definition, outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }] } } };
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields,
      mediaInputs: [imageInput], prompt: `Edit ${MENTION_MARKER}`, mentions: [imageInput] },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: image, next });
    expect(change.fields).toMatchObject({ parameters: { dynamicValues: { frame: imageInput.versionId } },
      mediaInputs: [imageInput], prompt: `Edit ${MENTION_MARKER}`,
      mentions: [{ versionId: imageInput.versionId, role: "REFERENCE" }] });
  });

  it("matches media types and active slots in published order, pruning only excess references", () => {
    const second = { ...imageInput, versionId: "second-image" };
    const excess = { ...imageInput, versionId: "excess-image" };
    const videoInput = { ...imageInput, versionId: "video-version", role: "VIDEO_REFERENCE" as const };
    const next: MediaCapability = { ...dynamic, settings: { runningHub: { ...definition, fields: [
      { ...definition.fields[3]!, defaultValue: false },
      { ...definition.fields[0]!, key: "disabled", enabledWhen: { field: "sound", value: true } },
      { ...definition.fields[0]!, key: "audio", type: "AUDIO" },
      { ...definition.fields[0]!, key: "first" },
      { ...definition.fields[0]!, key: "video", type: "VIDEO" },
      { ...definition.fields[0]!, key: "second" },
    ] } } };
    const inputs = [imageInput, second, audioInput, videoInput, excess];
    const before: MediaDraftFields = { ...fields, mediaInputs: inputs,
      prompt: inputs.map(() => MENTION_MARKER).join("/"), mentions: inputs };
    const snapshot = structuredClone(before);
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: before,
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields.parameters?.dynamicValues).toEqual({ first: imageInput.versionId, second: second.versionId,
      audio: audioInput.versionId, video: videoInput.versionId });
    expect(change.fields.mediaInputs).toEqual(inputs.slice(0, -1));
    expect(change.fields.prompt).toBe(`${MENTION_MARKER}/${MENTION_MARKER}/${MENTION_MARKER}/${MENTION_MARKER}/`);
    expect(change.fields.mentions).toHaveLength(4);
    expect(before).toEqual(snapshot);
  });

  it("keeps compatible multi-slot assignments and reassigns references from disabled slots", () => {
    const next: MediaCapability = { ...dynamic, settings: { runningHub: { ...definition, fields: [
      ...definition.fields, { ...definition.fields[0]!, key: "shared" },
      { ...definition.fields[0]!, key: "inactive", enabledWhen: { field: "sound", value: true } },
      { ...definition.fields[0]!, key: "fallback" },
    ] } } };
    const second = { ...imageInput, versionId: "second-image" };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, mediaInputs: [imageInput, second],
      parameters: { dynamicValues: { frame: imageInput.versionId, shared: imageInput.versionId, inactive: second.versionId, sound: false } } },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: next, next });
    expect(change.fields.parameters?.dynamicValues).toEqual({ frame: imageInput.versionId,
      shared: imageInput.versionId, fallback: second.versionId, sound: false });
    expect(change.fields.mediaInputs).toEqual([imageInput, second]);
  });

  it("retains image references and mentions within the ordinary model capacity when leaving a workflow", () => {
    const inputs = [imageInput, { ...imageInput, versionId: "second-image" }, audioInput];
    const next = { ...image, maxReferenceImages: 1 };
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields, mediaInputs: inputs,
      parameters: { dynamicValues: { frame: imageInput.versionId } },
      prompt: inputs.map(() => MENTION_MARKER).join("/"), mentions: inputs },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields.mediaInputs).toEqual([imageInput]);
    expect(change.fields.prompt).toBe(`${MENTION_MARKER}//`);
    expect(change.fields.parameters).not.toHaveProperty("dynamicValues");
  });

  it("filters unsupported media types and capacity when leaving a workflow for ordinary audio", () => {
    const next: MediaCapability = { ...image, kind: "AUDIO_GENERATION", maxReferenceImages: 1, maxReferenceAudios: 1 };
    const videoInput = { ...imageInput, versionId: "video-version", role: "VIDEO_REFERENCE" as const };
    const change = planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields,
      mediaInputs: [videoInput, imageInput, audioInput, { ...audioInput, versionId: "extra-audio" }],
      parameters: { dynamicValues: { frame: imageInput.versionId } } },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields.mediaInputs).toEqual([imageInput]);
    expect(change.fields.parameters).toEqual({});
  });

  it.each(["GENERAL_REFERENCE", "START_END"] as const)("selects supported %s for an empty draft when switching from a text model", (mode) => {
    const next: MediaCapability = { ...video, id: "image-only-video", supportedVideoInputModes: [mode], defaultVideoInputMode: mode };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, videoInputMode: "TEXT" },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields).toMatchObject({ capabilityId: next.id, videoInputMode: mode, mediaInputs: [] });
  });

  it("keeps the implicit default selection and compatible image parameters without prompting", () => {
    const parameters = { aspectRatio: "16:9", resolution: "2K", quality: "high", transparentBackground: true, generationCount: 4 } as const;
    expect(planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields, parameters }, capabilityId: null,
      resolvedCapabilityId: image.id, previous: image, next: image }))
      .toEqual({ fields: { capabilityId: null, parameters } });
  });

  it("resets unsupported image parameters while planning the complete change", () => {
    const next = { ...image, supportedImageAspectRatios: ["AUTO"], supportedImageResolutions: ["1K"], supportsTransparentBackground: false } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: { ...fields,
      parameters: { aspectRatio: "16:9", resolution: "2K", transparentBackground: true } },
    capabilityId: next.id, resolvedCapabilityId: next.id, previous: image, next });
    expect(change.fields.parameters).toMatchObject({ aspectRatio: "AUTO", resolution: "1K", transparentBackground: false });
    expect(change.fields).not.toHaveProperty("mediaInputs");
  });

  it("preserves static audio settings when selecting another capability", () => {
    expect(planMediaCapabilityChange({ kind: "AUDIO", fields: { ...fields, parameters: { speechRate: 50 } },
      capabilityId: null, resolvedCapabilityId: "audio-default" }))
      .toEqual({ fields: { capabilityId: null } });
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
    expect(before).toEqual(snapshot);
  });

  it("retains mixed inputs in general reference mode while keeping an implicit default", () => {
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      videoInputMode: "START_END", mediaInputs: [{ ...imageInput, role: "START_FRAME" }, audioInput] },
    capabilityId: null, resolvedCapabilityId: video.id, next: { ...video, supportedVideoInputModes: ["GENERAL_REFERENCE"] } });
    expect(change.fields).toMatchObject({ capabilityId: null, videoInputMode: "GENERAL_REFERENCE", mediaInputs: [imageInput, audioInput] });
  });

  it.each(["768p", "1080p"] as const)("keeps published AutoDL tiers and resets unsupported %s tiers", (resolution) => {
    const next = { ...video, adapterId: AUTODL_ADAPTER, settings: {
      workflowId: "minimax_h3_image_audio_to_video_v2", videoResolution: "480p", videoResolutions: ["480p", "768p"],
    } } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { videoResolution: resolution } },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: video, next });
    expect(change.fields.parameters?.videoResolution).toBe(resolution === "768p" ? resolution : "480p");
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
    const remapped = { ...next, settings: { runningHub: { ...definition,
      fields: definition.fields.map((field) => ({ ...field, nodeId: "new-node" })),
    } } };
    expect(planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput] },
    capabilityId: remapped.id, resolvedCapabilityId: remapped.id, previous: dynamic, next: remapped }).fields)
      .toMatchObject({ parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput], videoInputMode: "GENERAL_REFERENCE" });
  });

  it("preserves Comfy slots and scalar values while retaining normal generation controls", () => {
    const comfy: MediaCapability = { ...image, id: "comfy", adapterId: "COMFY_IMAGE_V1", settings: { comfyInputs: [
      { ...definition.fields[0]!, key: "reference_0" }, definition.fields[1]!,
    ] } };
    const before = { ...fields, mediaInputs: [imageInput], parameters: {
      aspectRatio: "16:9" as const, generationCount: 2 as const, dynamicValues: { reference_0: imageInput.versionId, strength: 0.5 },
    } };
    const change = planMediaCapabilityChange({ kind: "IMAGE", fields: before,
      capabilityId: comfy.id, resolvedCapabilityId: comfy.id, previous: comfy, next: comfy });
    expect(change.fields.parameters).toMatchObject({ aspectRatio: "16:9", generationCount: 2,
      dynamicValues: { reference_0: imageInput.versionId, strength: 0.5 } });
    expect(change.fields.mediaInputs).toEqual([imageInput]);
  });

  it("clears dynamic parameters and retains compatible exact inputs when leaving RunningHub", () => {
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields,
      parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput],
      prompt: `Use ${MENTION_MARKER}`, mentions: [imageInput] },
    capabilityId: null, resolvedCapabilityId: video.id, previous: dynamic, next: video });
    expect(change.fields).toEqual({ capabilityId: null, parameters: { aspectRatio: "AUTO" }, mediaInputs: [imageInput],
      prompt: `Use ${MENTION_MARKER}`, mentions: [imageInput], videoInputMode: "GENERAL_REFERENCE" });
  });

  it.each(["GENERAL_REFERENCE", "START_END"] as const)("uses supported %s immediately when leaving RunningHub for an image-only model", (mode) => {
    const next: MediaCapability = { ...video, supportedVideoInputModes: [mode], defaultVideoInputMode: mode };
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { dynamicValues: { frame: imageInput.versionId } }, mediaInputs: [imageInput] },
      capabilityId: next.id, resolvedCapabilityId: next.id, previous: dynamic, next });
    expect(change.fields).toMatchObject({ videoInputMode: mode, mediaInputs: [
      { ...imageInput, role: mode === "START_END" ? "START_FRAME" : "REFERENCE" },
    ] });
  });

  it("keeps duration only when the next dynamic contract explicitly sources it", () => {
    const next = { ...dynamic, settings: { runningHub: { ...definition, fields: [...definition.fields,
      { key: "seconds", label: "Seconds", type: "INTEGER", nodeId: "5", fieldName: "duration", source: "DURATION_SECONDS", required: true, advanced: false },
    ] } } } satisfies MediaCapability;
    const change = planMediaCapabilityChange({ kind: "VIDEO", fields: { ...fields, parameters: { aspectRatio: "16:9" } },
      capabilityId: next.id, resolvedCapabilityId: next.id, next });
    expect(change.fields.durationSeconds).toBe(5);
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
