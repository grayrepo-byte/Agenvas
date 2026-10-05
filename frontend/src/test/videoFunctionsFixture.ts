import type { MediaCapability, MediaSettings } from "../shared/api/client";

export function videoFunctionCapability(changes: Partial<MediaCapability> = {}): MediaCapability {
  return { id: "depth-cap", name: "本地深度", enabled: true, version: 0, capabilityVersion: 1,
    adapterId: "LOCAL_VIDEO_PROCESSOR", kind: "VIDEO_GENERATION", minimumSeconds: 0, maximumSeconds: 30,
    maxReferenceImages: 0, maxReferenceAudios: 0, maxReferenceVideos: 0,
    supportedVideoInputModes: ["GENERAL_REFERENCE"], defaultVideoInputMode: "GENERAL_REFERENCE", supportsEndFrame: false,
    supportedImageAspectRatios: [], supportedImageResolutions: [], supportedImageQualities: [],
    supportsTransparentBackground: false, supportsImageMask: false, mappingSha256: "a".repeat(64), settings: {}, ...changes };
}

export function videoFunctionsFixture(): MediaSettings {
  return { defaults: [], connections: [{ id: "local", name: "本地处理", platform: "LOCAL", enabled: true,
    version: 0, connectionVersion: 1, origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability(), videoFunctionCapability({ id: "audio-cap", name: "本地音轨", adapterId: "LOCAL_VIDEO_AUDIO_EXTRACTOR", kind: "AUDIO_GENERATION" })] },
  { id: "rh", name: "合成工作流", platform: "RUNNINGHUB", enabled: true, version: 0, connectionVersion: 1,
    origin: null, keyMask: null, connectivityStatus: "NOT_CHECKED", realGenerationTested: false,
    capabilities: [videoFunctionCapability({ id: "upscale-cap", name: "AI 超分", adapterId: "RUNNINGHUB_VIDEO", settings: {
      pricing: { amount: "0.25", currency: "USD", unit: "VIDEO" },
      runningHub: { schemaVersion: 1, protocolVersion: "V2", targetType: "WORKFLOW", targetId: "123", usePersonalQueue: false, addMetadata: false, fields: [
        { key: "video", label: "来源视频", type: "VIDEO", required: true, advanced: false, nodeId: "1", fieldName: "video" },
        { key: "scale", label: "放大倍数", type: "SELECT", required: true, advanced: false, nodeId: "2", fieldName: "scale", defaultValue: 2,
          options: [{ label: "2×", value: 2 }, { label: "4×", value: 4 }] },
      ], outputs: [{ nodeId: "3", kind: "VIDEO", primary: true, maxCount: 1 }] },
    } })] }] };
}
