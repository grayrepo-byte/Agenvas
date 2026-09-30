import type { MediaConnection } from "../../shared/api/client";

/** Presentation metadata for compiled protocols; the server validates their actual bounds. */
export const mediaAdapters = {
  LOCAL_IMAGE_PROCESSOR: { label: "本地图片处理", kind: "IMAGE_GENERATION", references: 1, minimum: 0, maximum: 0 },
  MOCK_IMAGE: { label: "Mock 图片演示", kind: "IMAGE_GENERATION", references: 4, minimum: 0, maximum: 0 },
  MOCK_AUDIO: { label: "Mock 音频演示", kind: "AUDIO_GENERATION", references: 1, minimum: 0, maximum: 0 },
  VOLC_SEED_AUDIO_1: { label: "Seed Audio 1.0 · 火山引擎", model: "seed-audio-1.0", kind: "AUDIO_GENERATION", references: 1, minimum: 0, maximum: 0 },
  MOCK_VIDEO: { label: "Mock 视频演示", kind: "VIDEO_GENERATION", references: 4, minimum: 1, maximum: 30 },
  COMFY_IMAGE_V1: { label: "ComfyUI · 图片", kind: "IMAGE_GENERATION", references: 1, minimum: 0, maximum: 0 },
  COMFY_VIDEO_V1: { label: "ComfyUI · 视频", kind: "VIDEO_GENERATION", references: 1, minimum: 1, maximum: 5 },
  OPENAI_GPT_IMAGE_2: { label: "GPT Image 2 · OpenAI", model: "gpt-image-2", kind: "IMAGE_GENERATION", references: 4, minimum: 0, maximum: 0 },
  GOOGLE_NANO_BANANA_2: { label: "Nano Banana 2 · Google Gemini", model: "gemini-3.1-flash-image", kind: "IMAGE_GENERATION", references: 14, minimum: 0, maximum: 0 },
  ARK_SEEDANCE_2_I2V: { label: "Seedance 2 · 火山方舟", model: "doubao-seedance-2-0-260128", kind: "VIDEO_GENERATION", references: 9, minimum: 4, maximum: 15 },
} as const;

export const platformAdapters: Record<MediaConnection["platform"], string[]> = {
  LOCAL: ["LOCAL_IMAGE_PROCESSOR"], MOCK: ["MOCK_IMAGE", "MOCK_VIDEO", "MOCK_AUDIO"],
  COMFYUI: ["COMFY_IMAGE_V1", "COMFY_VIDEO_V1"], OPENAI: ["OPENAI_GPT_IMAGE_2"],
  VOLCENGINE: ["VOLC_SEED_AUDIO_1"], GOOGLE: ["GOOGLE_NANO_BANANA_2"], ARK: ["ARK_SEEDANCE_2_I2V"],
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
