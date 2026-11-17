import { describe, expect, it } from "vitest";
import { changeOpenAiImageFormat, changeOpenAiImageModel } from "./openAiImageOptions";
import { buildMediaCapabilitySettings } from "./mediaCapabilityDraft";

describe("OpenAI image output switching", () => {
  it("publishes GIF options with safe defaults and removes incompatible static-image defaults", () => {
    const draft = changeOpenAiImageFormat(
      {
        model: "gpt-image-2.6-sunburst",
        quality: "xhigh",
        maxReferenceImages: 4,
        defaultParameters: { aspectRatio: "16:9", resolution: "4K", transparentBackground: true, generationCount: 2 },
      },
      "gif",
    );
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", draft)).toEqual({
      model: "gpt-image-2.6-sunburst",
      quality: "xhigh",
      outputFormat: "gif",
      frameCount: 9,
      frameDurationMs: 200,
      maxReferenceImages: 0,
      defaultParameters: { aspectRatio: "AUTO", resolution: "1K", transparentBackground: false, generationCount: 2 },
    });
    const png = changeOpenAiImageFormat(draft, "png");
    expect(png.maxReferenceImages).toBeUndefined();
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", png)).not.toHaveProperty("frameCount");
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", png)).not.toHaveProperty("frameDurationMs");
  });
  it("retains animation controls across 2.6 models and repairs them when explicitly choosing an older model", () => {
    const gif = changeOpenAiImageFormat({ model: "gpt-image-2.6-flare", quality: "max", complexity: "high" }, "gif");
    expect(changeOpenAiImageModel(gif, "gpt-image-2.6-sunburst")).toMatchObject({
      outputFormat: "gif",
      frameCount: 9,
      complexity: "high",
      quality: "max",
    });
    const old = changeOpenAiImageModel(gif, "gpt-image-2");
    expect(buildMediaCapabilitySettings("OPENAI_GPT_IMAGE_2", old)).toMatchObject({
      model: "gpt-image-2",
      outputFormat: "png",
      quality: "medium",
    });
    expect(old.complexity).toBeUndefined();
    expect(old.frameCount).toBeUndefined();
    expect(old.maxReferenceImages).toBeUndefined();
  });
});

it.each(["gpt-image-2.5-flare", "gpt-image-2.5-sunburst"])(
  "switches GIF to %s without losing supported quality, moderation or custom rates",
  model => {
    const tokenPricing = {
      currency: "CNY" as const,
      textInputPerMillion: "1",
      imageInputPerMillion: "2",
      imageOutputPerMillion: "3",
    };
    const before = changeOpenAiImageFormat(
      { model: "gpt-image-2.6-flare", quality: "max", moderation: "low", tokenPricing, complexity: "high" },
      "gif",
    );
    const after = changeOpenAiImageModel(before, model);
    expect(after).toMatchObject({ model, quality: "max", moderation: "low", tokenPricing, outputFormat: "png" });
    expect(after.frameCount).toBeUndefined();
    expect(after.frameDurationMs).toBeUndefined();
    expect(after.complexity).toBeUndefined();
  },
);
