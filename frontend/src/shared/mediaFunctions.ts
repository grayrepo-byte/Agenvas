import type { MediaCapability, MediaSettings, MediaFunction, ImageOperation, VideoOperation } from "./api/client";
import { t } from "./i18n";

export const IMAGE_OPERATIONS: readonly ImageOperation[] = ["SMART_EDIT", "RELIGHT", "OUTPAINT", "THREE_VIEW", "LAYER_SPLIT", "EXPRESSION_EDIT", "REMOVE_BACKGROUND", "OBJECT_REMOVE", "VIEW_ANGLE", "DEPTH_MAP", "UPSCALE", "RESIZE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL"];
export const LOCAL_IMAGE_OPERATIONS: readonly ImageOperation[] = ["DEPTH_MAP", "UPSCALE", "RESIZE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL"];
export function imageFunction(operation: ImageOperation): MediaFunction { return `IMAGE_${operation}`; }
export function videoFunction(operation: VideoOperation): MediaFunction { return `VIDEO_${operation}`; }
export const VIDEO_OPERATIONS: readonly VideoOperation[] = ["UPSCALE", "DEPTH_MAP", "EXTRACT_AUDIO"];
export const LOCAL_VIDEO_ADAPTERS = {
  DEPTH_MAP: "LOCAL_VIDEO_PROCESSOR", EXTRACT_AUDIO: "LOCAL_VIDEO_AUDIO_EXTRACTOR",
} as const;
export const MEDIA_FUNCTIONS_QUERY_KEY = ["media-functions"] as const;

export function videoOperationLabel(operation: VideoOperation): string {
  return operation === "UPSCALE" ? t("media.video.upscale") : operation === "DEPTH_MAP"
    ? t("media.card.extractDepth") : t("media.video.extractAudio");
}

export function compatibleVideoFunction(operation: VideoOperation, capability: MediaCapability): boolean {
  const kind = operation === "EXTRACT_AUDIO" ? "AUDIO_GENERATION" : "VIDEO_GENERATION";
  if (!capability.enabled || capability.kind !== kind) return false;
  if (operation !== "UPSCALE" && capability.adapterId === LOCAL_VIDEO_ADAPTERS[operation]) return true;
  const definition = capability.settings.runningHub;
  if (!definition) return false;
  const media = definition.fields.filter((field) => ["IMAGE", "VIDEO", "AUDIO"].includes(field.type));
  return media.length === 1 && media[0]?.type === "VIDEO" && !media[0].enabledWhen;
}

export function videoFunctionChoices(settings: MediaSettings, operation: VideoOperation) {
  return settings.connections.filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities.filter((capability) => compatibleVideoFunction(operation, capability))
      .map((capability) => ({ capability, label: `${connection.platform === "LOCAL" ? t("media.functions.localMedia") : `${connection.platform} · ${connection.name}`} · ${capability.name}` })));
}

const IMAGE_LABELS: Record<ImageOperation, Parameters<typeof t>[0]> = {
  SMART_EDIT: "media.card.smartEdit", RELIGHT: "media.card.lighting", OUTPAINT: "media.card.outpaint",
  THREE_VIEW: "media.card.threeView", LAYER_SPLIT: "media.card.splitLayers", EXPRESSION_EDIT: "media.card.changeExpression",
  REMOVE_BACKGROUND: "media.card.removeBackground", OBJECT_REMOVE: "media.card.removeObject", VIEW_ANGLE: "media.card.changeAngle",
  DEPTH_MAP: "media.card.extractDepth", UPSCALE: "media.card.upscaleTitle", RESIZE: "image.resize.title", CROP: "media.card.crop", ROTATE: "media.card.rotate",
  FLIP_HORIZONTAL: "media.card.flipHorizontal", FLIP_VERTICAL: "media.card.flipVertical",
};
export function imageOperationLabel(operation: ImageOperation): string { return t(IMAGE_LABELS[operation]); }
export function mediaFunctionLabel(operation: MediaFunction): string {
  const image = IMAGE_OPERATIONS.find((entry) => imageFunction(entry) === operation);
  return image ? imageOperationLabel(image) : videoOperationLabel(VIDEO_OPERATIONS.find((entry) => videoFunction(entry) === operation) ?? "UPSCALE");
}

export function compatibleImageFunction(operation: ImageOperation, capability: MediaCapability): boolean {
  if (!capability.enabled || capability.kind !== "IMAGE_GENERATION") return false;
  if (LOCAL_IMAGE_OPERATIONS.includes(operation) && capability.adapterId === "LOCAL_IMAGE_PROCESSOR") return true;
  if (["RESIZE", "CROP", "ROTATE", "FLIP_HORIZONTAL", "FLIP_VERTICAL"].includes(operation)) return false;
  const transparent = operation === "REMOVE_BACKGROUND" || operation === "LAYER_SPLIT";
  const nativeEdit = !LOCAL_IMAGE_OPERATIONS.includes(operation) && capability.maxReferenceImages > 0
    && ["OPENAI_GPT_IMAGE_2", "GOOGLE_NANO_BANANA_2", "COMFY_IMAGE_V1"].includes(capability.adapterId)
    && (!transparent || capability.supportsTransparentBackground);
  if (nativeEdit) return true;
  const definition = capability.settings.runningHub;
  const media = definition?.fields.filter((field) => ["IMAGE", "VIDEO", "AUDIO"].includes(field.type));
  return !transparent && media?.length === 1 && media[0]?.type === "IMAGE" && !media[0].enabledWhen;
}

export function mediaFunctionChoices(settings: MediaSettings, operation: MediaFunction) {
  const image = IMAGE_OPERATIONS.find((entry) => imageFunction(entry) === operation);
  const video = VIDEO_OPERATIONS.find((entry) => videoFunction(entry) === operation);
  return settings.connections.filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities.filter((capability) => image
      ? compatibleImageFunction(image, capability) : video ? compatibleVideoFunction(video, capability) : false)
      .map((capability) => ({ capability, label: `${connection.platform === "LOCAL" ? t("media.functions.localMedia") : `${connection.platform} · ${connection.name}`} · ${capability.name}` })));
}
