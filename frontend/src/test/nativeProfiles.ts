import type { CreatorInputField, MediaCapability, MediaConnection, NativeMediaProfile } from "../shared/api/client";

export function nativeField(key: string, label: string, type: CreatorInputField["type"], patch: Partial<CreatorInputField> = {}): CreatorInputField {
  return { key, label, type, required: false, advanced: false, source: "PARAMETER", ...patch };
}
const prompt = nativeField("prompt", "Prompt", "STRING", { required: true, source: "PROMPT", maxLength: 20000 });
const media = (key: string, type: "IMAGE" | "VIDEO" | "AUDIO" = "IMAGE", required = false) => nativeField(key, key, type, { required });
const choice = (key: string, label: string, options: (string | number)[], defaultValue: string | number, patch: Partial<CreatorInputField> = {}) =>
  nativeField(key, label, "SELECT", { defaultValue, options: options.map((value) => ({ label: String(value), value })), ...patch });
const seedream = (model: string, operation: NativeMediaProfile["operation"]): NativeMediaProfile => ({
  adapterId: "ARK_SEEDREAM_IMAGE", model, operation, kind: "IMAGE_GENERATION", inputLimits: { maxReferenceImages: operation === "LAYER_DECOMPOSITION" ? 1 : model.includes("260128") ? 14 : 10, maxReferenceVideos: 0, maxReferenceAudios: 0 },
  inputs: [...(operation === "LAYER_DECOMPOSITION" ? [] : [prompt]), media("image1", "IMAGE", operation === "LAYER_DECOMPOSITION"),
    nativeField("size", "Output size", "STRING", { defaultValue: operation === "LAYER_DECOMPOSITION" ? "auto" : "2K", maxLength: 32,
      options: (model.includes("260128") ? ["2K", "3K", "4K"] : ["1K", "1.5K", "2K"]).map((value) => ({ label: value, value })) }),
    choice("output_format", "Output format", ["jpeg", "png"], "jpeg"),
    ...(model.includes("260128") ? [choice("sequential_image_generation", "Sequential images", ["disabled", "auto"], "disabled"),
      nativeField("max_images", "Maximum images", "INTEGER", { defaultValue: 15, minimum: 1, maximum: 15, enabledWhen: { field: "sequential_image_generation", value: "auto" } })]
      : operation === "LAYER_DECOMPOSITION" ? [] : [choice("background", "Background", ["opaque", "transparent"], "opaque")]),
    ...(operation === "INTERACTIVE_EDIT" ? [nativeField("edit_regions", "Edit regions", "STRING", { advanced: true, maxLength: 16000 })] : [])],
});

/** Synthetic catalog fixtures verify UI contracts, not successful calls to a real provider. */
export const nativeProfiles: NativeMediaProfile[] = [
  ...["wan2.7-image", "wan2.7-image-pro"].map((model): NativeMediaProfile => ({ adapterId: "WAN_IMAGE", model, operation: "GENERATE", kind: "IMAGE_GENERATION", inputLimits: { maxReferenceImages: 9, maxReferenceVideos: 0, maxReferenceAudios: 0 }, inputs: [prompt, media("image_1"),
    nativeField("n", "Image count / sequential maximum", "INTEGER", { defaultValue: 1, minimum: 1, maximum: 12 }), nativeField("enable_sequential", "Sequential images", "BOOLEAN", { defaultValue: false })] })),
  ...["wan3.0-video", "wan3.0-video-prime"].map((model): NativeMediaProfile => ({ adapterId: "WAN_VIDEO", model, operation: "GENERATE", kind: "VIDEO_GENERATION", inputLimits: { maxReferenceImages: 10, maxReferenceVideos: 5, maxReferenceAudios: 5 }, inputs: [{ ...prompt, required: false },
    nativeField("duration", "Duration (seconds)", "INTEGER", { source: "DURATION_SECONDS", defaultValue: 5, minimum: 2, maximum: 30, enabledWhen: { field: "smart_duration", value: false } }), nativeField("smart_duration", "Automatic duration", "BOOLEAN", { defaultValue: false }),
    choice("resolution", "Resolution", ["480P", "720P", "1080P"], "1080P"), media("first_frame"), media("last_frame"), media("image_1"),
    ...Array.from({ length: 5 }, (_, i) => media(`video_${i + 1}`, "VIDEO")), ...Array.from({ length: 5 }, (_, i) => media(`audio_${i + 1}`, "AUDIO"))] })),
  ...["doubao-seedream-5-0-pro-260628", "doubao-seedream-5-0-flash-260915", "doubao-seedream-5-0-260128"].flatMap((model) =>
    (model.includes("260128") ? ["GENERATE" as const] : ["GENERATE", "LAYER_DECOMPOSITION", "INTERACTIVE_EDIT"] as const).map((operation) => seedream(model, operation))),
  ...["veo-3.1-generate-preview", "veo-3.1-fast-generate-preview", "veo-3.1-lite-generate-preview"].flatMap((model) =>
    (model.includes("lite") ? ["GENERATE" as const] : ["GENERATE", "EXTEND"] as const).map((operation): NativeMediaProfile => ({ adapterId: "GOOGLE_VEO", model, operation, kind: "VIDEO_GENERATION", inputLimits: { maxReferenceImages: operation === "EXTEND" ? 0 : 5, maxReferenceVideos: operation === "EXTEND" ? 1 : 0, maxReferenceAudios: 0 }, inputs: [prompt,
      choice("durationSeconds", "Duration (seconds)", [4, 6, 8], 8, { source: "DURATION_SECONDS" }), choice("resolution", "Resolution", operation === "EXTEND" ? ["720p"] : ["720p", "1080p", "4k"], "720p"),
      choice("personGeneration", "People generation", ["allow_all", "allow_adult"], "allow_all"), ...(operation === "EXTEND" ? [media("base_video", "VIDEO", true)] : [media("first_frame"), media("last_frame"), media("image_1")])] }))),
  ...(["GENERATE", "EDIT", "EXTEND"] as const).map((operation): NativeMediaProfile => ({ adapterId: "GOOGLE_OMNI_VIDEO", model: "gemini-omni-1.1-flash", operation, kind: "VIDEO_GENERATION", inputLimits: { maxReferenceImages: 6, maxReferenceVideos: 4, maxReferenceAudios: 0 }, inputs: [prompt,
    choice("resolution", "Resolution", ["360p", "720p", "1080p", "4k"], "720p"), nativeField("duration", "Native duration", "STRING", { advanced: true, maxLength: 32 }),
    ...(operation === "GENERATE" ? [choice("task", "Generation task", ["auto", "text_to_video", "image_to_video", "reference_to_video"], "auto"), media("first_frame"), media("last_frame")] : [media("base_video", "VIDEO", true)]), media("image_1"), media("video_1", "VIDEO")] })),
];

export function nativeCapability(profile: NativeMediaProfile): MediaCapability {
  return { id: "native-capability", name: "原生媒体", enabled: true, version: 0, capabilityVersion: 1,
    adapterId: profile.adapterId, kind: profile.kind, minimumSeconds: 0, maximumSeconds: 30,
    maxReferenceImages: profile.inputLimits.maxReferenceImages, maxReferenceVideos: profile.inputLimits.maxReferenceVideos,
    maxReferenceAudios: profile.inputLimits.maxReferenceAudios, supportedVideoInputModes: profile.kind === "VIDEO_GENERATION" ? ["GENERAL_REFERENCE"] : [],
    defaultVideoInputMode: profile.kind === "VIDEO_GENERATION" ? "GENERAL_REFERENCE" : null, supportsEndFrame: false,
    supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [], supportsTransparentBackground: false, supportsImageMask: false,
    mappingSha256: "b".repeat(64), settings: { model: profile.model, operation: profile.operation, nativeInputs: [...profile.inputs] } };
}
export function nativePlatform(adapterId: string): MediaConnection["platform"] {
  return adapterId.startsWith("WAN_") ? "WAN" : adapterId === "ARK_SEEDREAM_IMAGE" ? "ARK" : "GOOGLE";
}
