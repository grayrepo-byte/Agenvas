import { describe, expect, it } from "vitest";
import type { Artifact, MediaCapability, MediaSettings } from "../../shared/api/client";
import { connectedMediaModel } from "./connectedMediaModel";

const image: MediaCapability = { id: "image", name: "Synthetic model", enabled: true, version: 0,
  capabilityVersion: 1, adapterId: "MOCK_IMAGE", kind: "IMAGE_GENERATION", minimumSeconds: 0, maximumSeconds: 0,
  maxReferenceImages: 1, maxReferenceVideos: 0, maxReferenceAudios: 0, supportedVideoInputModes: [],
  defaultVideoInputMode: null, supportsEndFrame: false, supportedImageAspectRatios: ["AUTO"],
  supportedImageResolutions: ["1K"], supportedImageQualities: [], supportsTransparentBackground: false,
  supportsImageMask: false, mappingSha256: "0".repeat(64), settings: {} };
function settings(capabilities: MediaCapability[], defaultId = capabilities[0]?.id): MediaSettings {
  return { defaults: defaultId ? [{ kind: capabilities[0]!.kind, capabilityId: defaultId, version: 0 }] : [],
    connections: [{ id: "mock", name: "Synthetic provider", platform: "MOCK", enabled: true, version: 0,
      connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED",
      realGenerationTested: false, capabilities }] };
}

describe("connected media model selection", () => {
  it("keeps a compatible configured default even when it is not first", () => {
    expect(connectedMediaModel(settings([image, { ...image, id: "default" }], "default"), "IMAGE", "IMAGE"))
      .toMatchObject({ capabilityId: "default" });
  });

  it("skips text-only, disabled and wrong-output models in published order", () => {
    const candidates = [{ ...image, maxReferenceImages: 0 }, { ...image, id: "disabled", enabled: false },
      { ...image, id: "other-kind", kind: "VIDEO_GENERATION" as const }, { ...image, id: "supported" },
      { ...image, id: "later" }];
    expect(connectedMediaModel(settings(candidates), "IMAGE", "IMAGE")).toMatchObject({ capabilityId: "supported" });
  });

  it("does not select disabled connections or local processing capabilities", () => {
    const available = settings([image]);
    const disabled = { ...available.connections[0]!, id: "disabled", enabled: false };
    const local = { ...available.connections[0]!, id: "local", platform: "LOCAL" as const };
    expect(connectedMediaModel({ ...available, connections: [disabled, local] }, "IMAGE", "IMAGE")).toBeNull();
  });

  it.each(["GENERAL_REFERENCE", "START_END"] as const)("prepares %s for an image-to-video connection", (mode) => {
    const model: MediaCapability = { ...image, kind: "VIDEO_GENERATION", supportedVideoInputModes: ["TEXT", mode] };
    expect(connectedMediaModel(settings([model]), "VIDEO", "IMAGE"))
      .toMatchObject({ capabilityId: image.id, videoInputMode: mode });
  });

  it.each(["VIDEO", "AUDIO"] as const)("requires general-reference mode for a %s video input", (source) => {
    const model: MediaCapability = { ...image, kind: "VIDEO_GENERATION", maxReferenceVideos: 1, maxReferenceAudios: 1,
      supportedVideoInputModes: ["START_END"] };
    expect(connectedMediaModel(settings([model]), "VIDEO", source)).toBeNull();
    expect(connectedMediaModel(settings([{ ...model, supportedVideoInputModes: ["GENERAL_REFERENCE"] }]), "VIDEO", source))
      .toMatchObject({ videoInputMode: "GENERAL_REFERENCE" });
  });

  it.each([{ modes: [] }, { modes: ["TEXT"] }] as const)("rejects video models without a supported image mode: $modes", ({ modes }) => {
    const model: MediaCapability = { ...image, kind: "VIDEO_GENERATION", supportedVideoInputModes: [...modes] };
    expect(connectedMediaModel(settings([model]), "VIDEO", "IMAGE")).toBeNull();
  });

  it.each(["VIDEO", "AUDIO", "TEXT"] as const)("does not mistake an ordinary image model for a %s input model", (source) => {
    expect(connectedMediaModel(settings([image]), "IMAGE", source)).toBeNull();
  });

  it("supports audio references while accounting for the default speaker", () => {
    const model: MediaCapability = { ...image, kind: "AUDIO_GENERATION", maxReferenceAudios: 1,
      settings: { defaultParameters: { speaker: "synthetic-speaker" } } };
    expect(connectedMediaModel(settings([model]), "AUDIO", "IMAGE")).toBeNull();
    expect(connectedMediaModel(settings([model]), "AUDIO", "AUDIO")).toBeNull();
    expect(connectedMediaModel(settings([{ ...model, maxReferenceAudios: 2 }]), "AUDIO", "AUDIO"))
      .toMatchObject({ capabilityId: image.id });
  });

  it.each(["comfyInputs", "nativeInputs", "klingInputs"] as const)("uses the actual %s slots instead of aggregate limits", (key) => {
    const model: MediaCapability = { ...image, settings: { [key]: [
      { key: "prompt", label: "Prompt", type: "STRING", source: "PROMPT", required: false, advanced: false },
    ] } };
    expect(connectedMediaModel(settings([model]), "IMAGE", "IMAGE")).toBeNull();
    const mediaSlot = { key: "reference", label: "Reference", type: "IMAGE" as const, required: false, advanced: false };
    expect(connectedMediaModel(settings([{ ...model, settings: { [key]: [mediaSlot] } }]), "IMAGE", "IMAGE"))
      .toMatchObject({ capabilityId: image.id });
  });

  it("checks RunningHub slot type and conditions using the defaults saved on the new draft", () => {
    const model: MediaCapability = { ...image, settings: { runningHub: { schemaVersion: 1, protocolVersion: "V2",
      targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false, fields: [
        { key: "mode", label: "Mode", type: "SELECT", nodeId: "1", fieldName: "mode", defaultValue: "text",
          options: [{ label: "Text", value: "text" }, { label: "Image", value: "image" }], required: false, advanced: false },
        { key: "reference", label: "Reference", type: "IMAGE", nodeId: "2", fieldName: "image", required: false,
          advanced: false, enabledWhen: { field: "mode", value: "image" } },
      ], outputs: [{ kind: "IMAGE", primary: true, maxCount: 1 }] } } };
    expect(connectedMediaModel(settings([model]), "IMAGE", "IMAGE")).toBeNull();
    const active = { ...model, settings: { ...model.settings, defaultParameters: { dynamicValues: { mode: "image" } } } };
    expect(connectedMediaModel(settings([active]), "IMAGE", "IMAGE"))
      .toMatchObject({ capabilityId: image.id, parameters: { dynamicValues: { mode: "image" } } });
    expect(connectedMediaModel(settings([active]), "IMAGE", "AUDIO")).toBeNull();
  });

  it.each(["IMAGE", "VIDEO", "AUDIO", "TEXT"] as Artifact["kind"][])("does not create a text target from %s", (source) => {
    expect(connectedMediaModel(settings([image]), "TEXT", source)).toBeNull();
  });

  it("returns no candidate when settings are empty", () => {
    expect(connectedMediaModel({ connections: [], defaults: [] }, "IMAGE", "IMAGE")).toBeNull();
  });
});
