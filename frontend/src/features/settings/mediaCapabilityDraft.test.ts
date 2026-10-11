import { describe, expect, it } from "vitest";
import type { MediaCapability, RunningHubDefinition } from "../../shared/api/client";
import { AUTODL_ADAPTER, getAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { definition as comfyDefinition } from "./comfyWorkflowFixture";
import {
  applyAutoDlDraftDefinition, buildMediaCapabilitySettings, CapabilityDraftError,
  selectedAutoDlDraftTiers, selectAutoDlDraftPreset, selectAutoDlDraftTiers, type MediaCapabilityDraft,
} from "./mediaCapabilityDraft";

type Settings = MediaCapability["settings"];
const price = (amount: string): NonNullable<Settings["pricing"]> => ({ amount, currency: "CNY", unit: "SECOND" });
const definition: NonNullable<Settings["workflowDefinition"]> = {
  schemaVersion: 1, id: "synthetic_video_v1", label: "Synthetic video", minimumSeconds: 1, maximumSeconds: 20,
  promptLimit: 10000, mode: "TEXT", imageFields: [], audioFields: [], minimumImages: 0, minimumAudios: 0,
  resolutions: ["720p横(1280*720)", "2160p横(3840*2160)"], defaultResolution: "720p", supportsSeed: true,
};
const runningHub: RunningHubDefinition = {
  schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false,
  fields: [
    { key: "mode", label: "Mode", type: "BOOLEAN", nodeId: "1", fieldName: "mode", defaultValue: false, required: false, advanced: false },
    { key: "strength", label: "Strength", type: "NUMBER", nodeId: "2", fieldName: "strength", defaultValue: 0, required: false, advanced: false,
      enabledWhen: { field: "mode", value: true } },
  ],
  fixedBindings: [{ nodeId: "2", fieldName: "steps", value: 0 }],
  outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }],
};

describe("media capability settings publication", () => {

  it.each(["KLING_IMAGE", "KLING_VIDEO", "WAN_IMAGE", "WAN_VIDEO", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO", "GOOGLE_OMNI_VIDEO"])(
    "publishes %s model, operation and defaults while excluding read-only profile metadata", (adapterId) => {
      const draft: MediaCapabilityDraft = {
        model: "synthetic-compiled-model", operation: "GENERATE",
        defaultParameters: { dynamicValues: { n: 2, resolution: "4k", watermark: false } },
        defaultDurationSeconds: 8, maxReferenceImages: 7, maxReferenceAudios: 2, maxReferenceVideos: 1,
        pricingByResolution: { "2160p": price("0"), "720p": price(" ") },
        klingInputs: [{ key: "first_frame", label: "First frame", type: "IMAGE", required: false, advanced: false }],
        nativeInputs: [{ key: "last_frame", label: "Last frame", type: "IMAGE", required: false, advanced: false }],
        nativeReusableSlots: ["first_frame", "last_frame"], workflowId: "obsolete-workflow", seed: 19,
      };
      const before = structuredClone(draft);
      expect(buildMediaCapabilitySettings(adapterId, draft)).toEqual({
        model: draft.model, operation: "GENERATE", defaultParameters: draft.defaultParameters,
        defaultDurationSeconds: 8, maxReferenceImages: 7, maxReferenceAudios: 2, maxReferenceVideos: 1,
        ...(adapterId !== "KLING_IMAGE" ? { pricingByResolution: { "2160p": price("0") } } : {}),
      });
      expect(draft).toEqual(before);
    });

  it("keeps empty compatible models and their quality default without publishing stale provider settings", () => {
    const draft: MediaCapabilityDraft = { model: "  ", workflowId: "previous-target", seed: 9,
      pricingByResolution: { "720p": price("1") }, runningHubSelectedNodeIds: ["1"] };
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", draft)).toEqual({ model: "", quality: "medium" });
    expect(buildMediaCapabilitySettings("GOOGLE_NANO_BANANA_2", draft)).toEqual({ model: "" });
    expect(buildMediaCapabilitySettings("ARK_SEEDANCE_2_I2V", draft)).toEqual({});
  });

  it("preserves explicit zero limits, false defaults and decimal strings while leaving the draft unchanged", () => {
    const draft: MediaCapabilityDraft = {
      model: "  compatible-image  ", quality: "low",
      defaultParameters: { transparentBackground: false, speechRate: 0 }, defaultDurationSeconds: 0,
      minimumSeconds: 0, maximumSeconds: 0, maxReferenceImages: 0, maxReferenceAudios: 0, maxReferenceVideos: 0,
      pricing: { amount: " 0.000000 ", currency: "USD", unit: "IMAGE" },
    };
    const before = structuredClone(draft);
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", draft)).toEqual({ ...draft, model: "compatible-image" });
    expect(draft).toEqual(before);
  });

  it.each([AUTODL_ADAPTER, "MINIMAX_H3", "KLING_VIDEO_3"])("filters unknown prices but retains a zero override for %s", (adapterId) => {
    const draft: MediaCapabilityDraft = {
      workflowDefinition: definition, videoResolutions: [], pricing: price(" \t "),
      pricingByResolution: { "720p": price(" 0.000000 "), "2160p": price(" \t ") },
    };
    const before = structuredClone(draft);
    const saved = buildMediaCapabilitySettings(adapterId, draft);
    expect(saved.pricing).toBeUndefined();
    expect(saved.pricingByResolution).toEqual({ "720p": price(" 0.000000 ") });
    expect(draft).toEqual(before);
  });

  it("publishes the complete imported AutoDL definition and keeps its ordered tiers and zero seed", () => {
    const draft: MediaCapabilityDraft = { workflowId: "stale-preset", workflowDefinition: definition,
      videoResolution: "2160p", videoResolutions: ["2160p", "720p"], seed: 0 };
    const before = structuredClone(draft);
    expect(buildMediaCapabilitySettings(AUTODL_ADAPTER, draft)).toEqual({ ...draft, workflowId: definition.id });
    expect(draft).toEqual(before);
  });

  it("distinguishes an unset administrator tier list, a legacy single tier and an explicit empty list", () => {
    const draft = { workflowDefinition: definition };
    expect(selectedAutoDlDraftTiers(draft)).toEqual(["720p", "2160p"]);
    expect(buildMediaCapabilitySettings(AUTODL_ADAPTER, draft).videoResolutions).toEqual(["720p", "2160p"]);
    expect(selectedAutoDlDraftTiers({ ...draft, videoResolution: "2160p" })).toEqual(["2160p"]);
    expect(buildMediaCapabilitySettings(AUTODL_ADAPTER, { ...draft, videoResolutions: [] }).videoResolutions).toEqual([]);
  });

  it("retains the published ComfyUI graph while excluding obsolete fixed-model settings", () => {
    expect(buildMediaCapabilitySettings("COMFY_VIDEO_V1", { comfyWorkflow: comfyDefinition,
      checkpoint: "old-checkpoint", diffusionModel: "old-model", quality: "high" }))
      .toEqual({ comfyWorkflow: comfyDefinition });
  });

  it("projects RunningHub selections without publishing editor choices or ordinary-model settings", () => {
    const draft: MediaCapabilityDraft = { runningHub, runningHubSelectedNodeIds: ["1", "2"],
      runningHubSelectedFieldKeys: ["mode"], pricing: price("0"), quality: "high", maxReferenceImages: 0 };
    const before = structuredClone(draft);
    expect(buildMediaCapabilitySettings("RUNNINGHUB_IMAGE", draft)).toEqual({
      runningHub: { ...runningHub, fields: [runningHub.fields[0]], fixedBindings: runningHub.fixedBindings }, pricing: price("0"),
    });
    expect(draft).toEqual(before);
  });

  it("rejects an exposed condition without its parent but does not validate an unselected candidate", () => {
    const draft = { runningHub, runningHubSelectedNodeIds: ["2"] };
    expect(() => buildMediaCapabilitySettings("RUNNINGHUB_IMAGE", draft)).toThrow(CapabilityDraftError);
    expect(buildMediaCapabilitySettings("RUNNINGHUB_IMAGE", { ...draft, runningHubSelectedFieldKeys: [] }).runningHub?.fields).toEqual([]);
  });
});

describe("AutoDL administrator draft changes", () => {
  const configured: Settings = {
    workflowId: definition.id, workflowDefinition: definition, videoResolution: "2160p", videoResolutions: ["720p", "2160p"],
    pricingByResolution: { "720p": price("0"), "2160p": price("1") }, seed: 19,
    minimumSeconds: 2, maximumSeconds: 10, defaultDurationSeconds: 3,
    maxReferenceImages: 2, maxReferenceAudios: 1, maxReferenceVideos: 3,
  };

  it("refreshes a definition by removing unavailable tiers and prices while retaining the remaining configuration", () => {
    const updated = { ...definition, resolutions: ["720p横(1280*720)"], supportsSeed: false };
    const before = structuredClone(configured);
    expect(applyAutoDlDraftDefinition(configured, updated)).toEqual({ ...configured,
      workflowDefinition: updated, videoResolution: "720p", videoResolutions: ["720p"], pricingByResolution: { "720p": price("0") }, seed: undefined });
    expect(configured).toEqual(before);
  });

  it("keeps target options during a manual ID edit and resets only the established fields for a new preview target", () => {
    const updated = { ...definition, id: "another_synthetic_target" };
    const edited = applyAutoDlDraftDefinition(configured, updated);
    expect(edited).toEqual({ ...configured, workflowId: updated.id,
      workflowDefinition: { ...updated, defaultResolution: "2160p" } });
    const replaced = applyAutoDlDraftDefinition(configured, updated, true);
    expect(replaced).toEqual({ ...configured, workflowId: updated.id, workflowDefinition: updated,
      videoResolution: "720p", pricingByResolution: undefined, seed: undefined,
      minimumSeconds: undefined, maximumSeconds: undefined, defaultDurationSeconds: undefined,
      maxReferenceImages: undefined, maxReferenceAudios: undefined });
    expect(replaced.maxReferenceVideos).toBe(3);
  });

  it("chooses a preset without keeping an imported definition or its previous target options", () => {
    const preset = getAutoDlWorkflow("minimax_h3_z0901")!;
    const next = selectAutoDlDraftPreset(configured, preset);
    expect(next.workflowId).toBe(preset.id);
    expect(next.workflowDefinition).toBeUndefined();
    expect(next.videoResolution).toBe("768p");
    expect(next.videoResolutions).toEqual(["480p", "768p", "1088p", "1440p"]);
    expect(next.pricingByResolution).toBeUndefined();
    expect(next.seed).toBeUndefined();
    expect(next.minimumSeconds).toBeUndefined();
    expect(next.maximumSeconds).toBeUndefined();
    expect(next.defaultDurationSeconds).toBeUndefined();
    expect(next.maxReferenceImages).toBeUndefined();
    expect(next.maxReferenceAudios).toBeUndefined();
    expect(next.maxReferenceVideos).toBe(3);
  });

  it("keeps provider tier order and repairs the default without dropping blank drafts for retained prices", () => {
    const draft = { ...configured, pricingByResolution: { "720p": price(""), "2160p": price("1") } };
    const before = structuredClone(draft);
    expect(selectAutoDlDraftTiers(draft, ["2160p", "720p", "unknown"], ["720p", "2160p"])).toEqual({ ...draft,
      workflowDefinition: { ...definition, defaultResolution: "2160p" } });
    expect(selectAutoDlDraftTiers(draft, ["720p"], ["720p", "2160p"])).toEqual({ ...draft,
      videoResolution: "720p", videoResolutions: ["720p"], workflowDefinition: definition, pricingByResolution: { "720p": price("") } });
    expect(selectAutoDlDraftTiers(draft, [], ["720p", "2160p"])).toBeNull();
    expect(draft).toEqual(before);
  });
});
