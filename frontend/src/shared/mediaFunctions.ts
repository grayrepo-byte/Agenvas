import type { MediaCapability, MediaSettings, VideoOperation } from "./api/client";
import { t } from "./i18n";

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
