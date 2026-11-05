import type { MediaCapabilityDraft } from "./mediaCapabilityDraft";

export const OPENAI_IMAGE_26_MODELS = ["gpt-image-2.6-flare", "gpt-image-2.6-sunburst"] as const;
/** Proper model names stay the same in all interface languages. Snapshots remain editable custom names. */
export const OPENAI_IMAGE_QUICK_MODELS = [
  { id: "gpt-image-2.6-flare", label: "GPT Image 2.6 Flare" },
  { id: "gpt-image-2.6-sunburst", label: "GPT Image 2.6 Sunburst" },
  { id: "gpt-image-2.5-flare", label: "GPT Image 2.5 Flare" },
  { id: "gpt-image-2.5-sunburst", label: "GPT Image 2.5 Sunburst" },
] as const;

export function isOpenAiImage26(model: string | undefined): boolean {
  return OPENAI_IMAGE_26_MODELS.some((value) => value === model?.trim());
}
export function openAiImageQualities(model: string | undefined) {
  return isOpenAiImage26(model) || /^gpt-image-2\.5-(flare|sunburst)(-2026-09-08)?$/.test(model?.trim() ?? "")
    ? ["low", "medium", "high", "xhigh", "max"] as const : ["low", "medium", "high"] as const;
}

/** An explicit output switch repairs parameters and input limits in one editor update. */
export function changeOpenAiImageFormat(draft: MediaCapabilityDraft, outputFormat: "png" | "gif"): MediaCapabilityDraft {
  if (outputFormat === "png") return { ...draft, outputFormat, frameCount: undefined, frameDurationMs: undefined,
    ...(draft.outputFormat === "gif" ? { maxReferenceImages: undefined } : {}) };
  return { ...draft, outputFormat, frameCount: draft.frameCount ?? 9, frameDurationMs: draft.frameDurationMs ?? 200,
    maxReferenceImages: 0, defaultParameters: { ...draft.defaultParameters, aspectRatio: "AUTO", resolution: "1K", transparentBackground: false } };
}

export function changeOpenAiImageModel(draft: MediaCapabilityDraft, model: string): MediaCapabilityDraft {
  let next: MediaCapabilityDraft = { ...draft, model };
  if (!isOpenAiImage26(model)) {
    if (next.outputFormat === "gif") next = changeOpenAiImageFormat(next, "png");
    next = { ...next, complexity: undefined };
  }
  if (next.quality && !openAiImageQualities(model).some((quality) => quality === next.quality)
      || next.defaultParameters?.quality && !openAiImageQualities(model).some((quality) => quality === next.defaultParameters?.quality)) {
    next = { ...next, quality: "medium", ...(next.defaultParameters
      ? { defaultParameters: { ...next.defaultParameters, quality: "medium" } } : {}) };
  }
  return next;
}
