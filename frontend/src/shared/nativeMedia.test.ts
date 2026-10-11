import { describe, expect, it } from "vitest";
import { nativeCapability, nativeField, nativeProfiles } from "../test/nativeProfiles";
import { nativeInputErrors, nativeOutputCount, nativePricingTiers, nativeResolutionTier, nativeVersionAssignable } from "./nativeMedia";

const capability = (adapterId: string, model?: string, operation = "GENERATE") => nativeCapability(nativeProfiles.find((profile) =>
  profile.adapterId === adapterId && (!model || profile.model === model) && profile.operation === operation)!);

describe("native media quantity, pricing, and input combinations", () => {
  it("counts finite Wan outputs and keeps provider-selected groups and layers unknown", () => {
    const wan = capability("WAN_IMAGE");
    expect(nativeOutputCount(wan, {})).toBe(1);
    expect(nativeOutputCount(wan, { n: 4 })).toBe(4);
    expect(nativeOutputCount(wan, { n: 12, enable_sequential: true })).toBe(0);
    expect(nativeOutputCount(wan, { n: 5 })).toBe(0);
    const lite = capability("ARK_SEEDREAM_IMAGE", "doubao-seedream-5-0-260128");
    expect(nativeOutputCount(lite, {})).toBe(1);
    expect(nativeOutputCount(lite, { sequential_image_generation: "auto", max_images: 15 })).toBe(0);
    expect(nativeOutputCount(capability("ARK_SEEDREAM_IMAGE", undefined, "LAYER_DECOMPOSITION"), {})).toBe(0);
    expect(nativeOutputCount(capability("GOOGLE_OMNI_VIDEO"), {})).toBe(1);
  });
  it("uses compiled size choices and actual pixel tiers without guessing automatic sizes", () => {
    const seedream = capability("ARK_SEEDREAM_IMAGE");
    expect(nativePricingTiers(seedream.adapterId, seedream.settings.nativeInputs!)).toEqual(["1K", "1.5K", "2K"]);
    expect(nativeResolutionTier(seedream, {})).toBe("2K");
    expect(nativeResolutionTier(seedream, { size: "1536x1536" })).toBe("1.5K");
    expect(nativeResolutionTier(seedream, { size: "auto" })).toBeUndefined();
    expect(nativeResolutionTier(capability("WAN_VIDEO"), {})).toBe("1080p");
    expect(nativeResolutionTier(capability("GOOGLE_VEO"), { resolution: "4k" })).toBe("2160p");
  });
  it("checks frame/ref exclusivity, required first frames, and duplicate exact versions", () => {
    const wan = capability("WAN_VIDEO");
    expect(nativeInputErrors(wan, {}, "", 5)).toEqual(["请提供提示词或参考素材。"]);
    expect(nativeInputErrors(wan, { first_frame: "frame", video_1: "video" }, "", 5)).toContain("首尾帧与通用参考素材不能混用。");
    expect(nativeInputErrors(wan, { last_frame: "frame" }, "", 5)).toContain("使用尾帧时必须指定首帧。");
    expect(nativeInputErrors(wan, { video_1: "same", video_2: "same" }, "", 5)).toContain("同一素材版本只能分配到一个输入槽。");
    expect(nativeInputErrors(wan, { video_1: "video", audio_1: "audio" }, "", 5)).toEqual([]);
  });
  it("checks Google task modes and Veo model-dependent duration/people choices", () => {
    const veo = capability("GOOGLE_VEO");
    expect(nativeInputErrors(veo, { resolution: "1080p" }, "prompt", 4)).toContain("Veo 参考图、续接或高分辨率生成需要 8 秒时长。");
    expect(nativeInputErrors(veo, { first_frame: "frame", personGeneration: "allow_all" }, "prompt", 8)).toContain("使用图片输入时，人物生成选项仅支持 allow_adult。");
    expect(nativeInputErrors(veo, { first_frame: "frame", personGeneration: "allow_adult" }, "prompt", 8)).toEqual([]);
    const omni = capability("GOOGLE_OMNI_VIDEO");
    expect(nativeInputErrors(omni, { task: "image_to_video" }, "prompt", null)).toContain("素材与所选生成任务不匹配，请调整任务或输入槽。");
    expect(nativeInputErrors(omni, { task: "reference_to_video", video_1: "video" }, "prompt", null)).toEqual([]);
  });
  it("allows an exact image version in only the server-declared reusable frame slots", () => {
    const veo = capability("GOOGLE_VEO");
    veo.settings = { ...veo.settings, nativeReusableSlots: ["first_frame", "last_frame"] };
    const values = { first_frame: "same", last_frame: "same", personGeneration: "allow_adult" };
    expect(nativeInputErrors(veo, values, "prompt", 8)).toEqual([]);
    expect(nativeVersionAssignable(veo, { first_frame: "same" }, "last_frame", "same")).toBe(true);
    expect(nativeVersionAssignable(veo, values, "image_1", "same")).toBe(false);
    expect(nativeInputErrors(veo, { ...values, image_1: "same" }, "prompt", 8)).toContain("同一素材版本只能分配到一个输入槽。");
    veo.settings = { ...veo.settings, nativeReusableSlots: [] };
    expect(nativeVersionAssignable(veo, { first_frame: "same" }, "last_frame", "same")).toBe(false);
  });
  it("checks Seedream contiguous slots, transparent output and bounded edit regions", () => {
    const seedream = capability("ARK_SEEDREAM_IMAGE", undefined, "INTERACTIVE_EDIT");
    seedream.settings.nativeInputs!.push(nativeField("image2", "image2", "IMAGE"));
    expect(nativeInputErrors(seedream, { image2: "image" }, "prompt", null)).toContain("参考图必须从 image1 开始连续填写。");
    expect(nativeInputErrors(seedream, { background: "transparent", output_format: "jpeg", image1: "image" }, "prompt", null)).toContain("透明背景需要一张带透明通道的参考图，并选择 png 输出。");
    const region = JSON.stringify([{ image: 1, type: "bbox", coordinates: [100, 200, 500, 600], instruction: "change color" }]);
    expect(nativeInputErrors(seedream, { image1: "image", edit_regions: region }, "prompt", null)).toEqual([]);
    expect(nativeInputErrors(seedream, { image1: "image", edit_regions: region.replace("500", "50") }, "prompt", null)).toHaveLength(1);
    expect(nativeInputErrors(seedream, { image1: "image", edit_regions: "{\"rawBody\":true}" }, "prompt", null)).toHaveLength(1);
  });
});
