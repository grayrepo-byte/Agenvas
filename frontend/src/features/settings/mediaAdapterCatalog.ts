import { t } from "../../shared/i18n";
import type { MediaConnection } from "../../shared/api/client";

/** Presentation metadata for compiled protocols; the server validates their actual bounds. */
export const mediaAdapters = {
  RUNNINGHUB_IMAGE: { get label() { return t("RunningHub · 图片工作流 / 应用"); }, kind: "IMAGE_GENERATION", references: 14, minimum: 0, maximum: 0 },
  RUNNINGHUB_VIDEO: { get label() { return t("RunningHub · 视频工作流 / 应用"); }, kind: "VIDEO_GENERATION", references: 14, minimum: 0, maximum: 60 },
  RUNNINGHUB_AUDIO: { get label() { return t("RunningHub · 音频工作流 / 应用"); }, kind: "AUDIO_GENERATION", references: 14, minimum: 0, maximum: 0 },
  AUTODL_COMFY_VIDEO: { get label() { return t("AutoDL · 视频工作流"); }, kind: "VIDEO_GENERATION", references: 9, minimum: 1, maximum: 15 },
  LOCAL_IMAGE_PROCESSOR: { get label() { return t("本地图片处理"); }, kind: "IMAGE_GENERATION", references: 1, minimum: 0, maximum: 0 },
  MOCK_IMAGE: { get label() { return t("Mock 图片演示"); }, kind: "IMAGE_GENERATION", references: 4, minimum: 0, maximum: 0 },
  MOCK_AUDIO: { get label() { return t("Mock 音频演示"); }, kind: "AUDIO_GENERATION", references: 1, minimum: 0, maximum: 0 },
  VOLC_SEED_AUDIO_1: { get label() { return t("Seed Audio 1.0 · 火山引擎"); }, model: "seed-audio-1.0", kind: "AUDIO_GENERATION", references: 1, minimum: 0, maximum: 0 },
  MOCK_VIDEO: { get label() { return t("Mock 视频演示"); }, kind: "VIDEO_GENERATION", references: 4, minimum: 1, maximum: 30 },
  COMFY_IMAGE_V1: { get label() { return t("ComfyUI · 图片"); }, kind: "IMAGE_GENERATION", references: 1, minimum: 0, maximum: 0 },
  COMFY_VIDEO_V1: { get label() { return t("ComfyUI · 视频"); }, kind: "VIDEO_GENERATION", references: 1, minimum: 1, maximum: 5 },
  OPENAI_GPT_IMAGE_2: { label: "GPT Image 2 · OpenAI", model: "gpt-image-2", kind: "IMAGE_GENERATION", references: 4, minimum: 0, maximum: 0 },
  GOOGLE_NANO_BANANA_2: { label: "Nano Banana 2 · Google Gemini", model: "gemini-3.1-flash-image", kind: "IMAGE_GENERATION", references: 14, minimum: 0, maximum: 0 },
  ARK_SEEDANCE_2_I2V: { get label() { return t("Seedance 2 · 火山方舟"); }, model: "doubao-seedance-2-0-260128", kind: "VIDEO_GENERATION", references: 9, minimum: 4, maximum: 15 },
} as const;

export const platformAdapters: Record<MediaConnection["platform"], string[]> = {
  RUNNINGHUB: ["RUNNINGHUB_IMAGE", "RUNNINGHUB_VIDEO", "RUNNINGHUB_AUDIO"],
  LOCAL: ["LOCAL_IMAGE_PROCESSOR"], MOCK: ["MOCK_IMAGE", "MOCK_VIDEO", "MOCK_AUDIO"],
  COMFYUI: ["COMFY_IMAGE_V1", "COMFY_VIDEO_V1"], OPENAI: ["OPENAI_GPT_IMAGE_2"],
  AUTODL: ["AUTODL_COMFY_VIDEO"], VOLCENGINE: ["VOLC_SEED_AUDIO_1"], GOOGLE: ["GOOGLE_NANO_BANANA_2"], ARK: ["ARK_SEEDANCE_2_I2V"],
};

export function adapterMetadata(id: string) {
  return mediaAdapters[id as keyof typeof mediaAdapters];
}

export function adapterLabel(id: string) {
  return adapterMetadata(id)?.label ?? id;
}

export function adapterModel(id: string) {
  const adapter = adapterMetadata(id);
  return adapter && "model" in adapter ? adapter.model : undefined;
}
