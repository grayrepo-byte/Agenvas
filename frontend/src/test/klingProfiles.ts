import type { CreatorInputField, KlingProfile } from "../shared/api/client";

export function klingField(key: string, label: string, type: CreatorInputField["type"], patch: Partial<CreatorInputField> = {}): CreatorInputField {
  return { key, label, type, required: false, advanced: false, source: "PARAMETER", ...patch };
}
const prompt = klingField("prompt", "Prompt", "STRING", { required: true, source: "PROMPT", maxLength: 2500 });
const count = klingField("n", "Image count", "INTEGER", { defaultValue: 1, minimum: 1, maximum: 9 });
const resolution = (options: string[]) => klingField("resolution", "Resolution", "SELECT", {
  defaultValue: options[0], options: options.map((value) => ({ label: value, value })),
});
const ratio = klingField("aspect_ratio", "Aspect ratio", "SELECT", { defaultValue: "16:9",
  options: ["16:9", "9:16", "1:1"].map((value) => ({ label: value, value })) });
const image = (key: string, required = false) => klingField(key, key, "IMAGE", { required });
const imageModels = ["kling-v3", "kling-v2-1", "kling-v3-omni", "kling-image-o1"];
const videoModels = ["kling-3.0-turbo", "kling-3.0", "kling-3.0-omni", "kling-o1", "kling-2.6", "kling-2.5-turbo"];

/** Synthetic catalog metadata exercises graph-free inputs; it is not provider verification. */
export const klingProfiles: KlingProfile[] = [
  ...imageModels.map((model): KlingProfile => ({ adapterId: "KLING_IMAGE", model, operation: "GENERATE", kind: "IMAGE_GENERATION",
    inputs: [prompt, count, resolution(model === "kling-v3-omni" ? ["1k", "2k", "4k"] : ["1k", "2k"]), ratio, image("image_1"),
      ...(model === "kling-v3-omni" ? [klingField("result_type", "Result type", "SELECT", { defaultValue: "single",
        options: ["single", "series"].map((value) => ({ label: value, value })) }),
      klingField("series_amount", "Series amount", "SELECT", { defaultValue: 4, enabledWhen: { field: "result_type", value: "series" },
        options: [2, 3, 4, 5, 6, 7, 8, 9, "auto"].map((value) => ({ label: String(value), value })) })] : [])] })),
  { adapterId: "KLING_IMAGE", model: "kling-v2-1", operation: "MULTI_IMAGE", kind: "IMAGE_GENERATION",
    inputs: [{ ...prompt, required: false }, count, ratio, image("image_1", true), image("image_2"), image("scene_image"), image("style_image")] },
  { adapterId: "KLING_IMAGE", model: null, operation: "EXPAND", kind: "IMAGE_GENERATION", inputs: [{ ...prompt, required: false }, count, image("image_1", true),
    ...["up", "down", "left", "right"].map((side) => klingField(`${side}_expansion_ratio`, `${side} expansion`, "NUMBER", { minimum: 0, maximum: 2, defaultValue: 0, step: 0.1 }))] },
  { adapterId: "KLING_IMAGE", model: null, operation: "SUBJECT_COMPLETION", kind: "IMAGE_GENERATION", inputs: [image("image_1", true)] },
  ...videoModels.map((model): KlingProfile => ({ adapterId: "KLING_VIDEO", model, operation: "GENERATE", kind: "VIDEO_GENERATION",
    inputs: [prompt, klingField("duration", "Duration (seconds)", "INTEGER", { source: "DURATION_SECONDS", defaultValue: 5, minimum: 3, maximum: 15 }),
      resolution(["720p", "1080p", ...(model === "kling-3.0" || model === "kling-3.0-omni" ? ["4k"] : [])]), ratio,
      image("first_frame"), ...(model === "kling-3.0-turbo" ? [] : [image("last_frame")])] })),
  ...["kling-3.0", "kling-2.6"].map((model): KlingProfile => ({ adapterId: "KLING_VIDEO", model, operation: "MOTION_CONTROL", kind: "VIDEO_GENERATION",
    inputs: [{ ...prompt, required: false }, image("image_1", true),
      klingField("motion_video", "Motion reference video", "VIDEO", { required: true }), resolution(["720p", "1080p"])] })),
];
