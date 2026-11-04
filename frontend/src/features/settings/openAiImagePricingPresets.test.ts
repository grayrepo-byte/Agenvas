import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { applyOpenAiImageTokenPreset, openAiImagePricePreset, updateOpenAiImagePricingDraft } from "./openAiImagePricingPresets";

describe("OpenAI Images standard price presets", () => {
  beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date("2026-11-04T12:00:00Z")); });
  afterEach(() => vi.useRealTimers());
  it.each([
    ["gpt-image-1.5", "5", "8", "32"], ["gpt-image-1.5-2025-12-16", "5", "8", "32"],
    ["gpt-image-1-mini", "2", "2.5", "8"],
  ])("uses model-specific USD rates per million tokens for %s before retirement", (model, text, image, output) => {
    const preset = openAiImagePricePreset(model, null, "2026-11-04");
    expect(preset?.rates).toEqual({ currency: "USD", textInputPerMillion: text, imageInputPerMillion: image, imageOutputPerMillion: output });
    expect(preset?.source).toBe(`https://developers.openai.com/api/docs/models/${model.startsWith("gpt-image-1.5") ? "gpt-image-1.5" : "gpt-image-1-mini"}`);
    expect(preset?.availableUntil).toBe("2026-12-01");
    expect(openAiImagePricePreset(model, null, "2026-12-01")).toBeUndefined();
  });
  it("stops offering retired presets using the current UTC date by default", () => {
    vi.useFakeTimers();
    try {
      vi.setSystemTime(new Date("2026-12-01T00:00:00Z"));
      expect(openAiImagePricePreset("gpt-image-1-mini", null)).toBeUndefined();
      expect(openAiImagePricePreset("gpt-image-2", null)).toBeDefined();
    } finally { vi.useRealTimers(); }
  });
  it("switches untouched model defaults between distinct tariffs without copying cache or Batch discounts", () => {
    const initial = applyOpenAiImageTokenPreset({}, null);
    const mini = updateOpenAiImagePricingDraft(initial, { ...initial, model: "gpt-image-1-mini" }, null);
    expect(mini.tokenPricing).toEqual({ currency: "USD", textInputPerMillion: "2", imageInputPerMillion: "2.5", imageOutputPerMillion: "8" });
    expect(updateOpenAiImagePricingDraft(mini, { ...mini, model: "gpt-image-1.5" }, null).tokenPricing?.imageOutputPerMillion).toBe("32");
  });
  it.each(["", "gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare-2026-09-08"])("matches the verified exact model %s", (model) => {
    expect(openAiImagePricePreset(model, null)?.rates).toEqual({ currency: "USD", textInputPerMillion: "5", imageInputPerMillion: "8", imageOutputPerMillion: "30" });
    expect(openAiImagePricePreset(model, "https://api.openai.com/v1/")?.verifiedAt).toBe("2026-11-04");
  });
  it.each([undefined, "https://proxy.example.com/v1", "https://api.openai.com.evil/v1", "http://api.openai.com/v1",
    "https://api.openai.com:8443/v1", "https://api.openai.com/proxy/v1", "https://user@api.openai.com/v1"])("does not infer an official tariff for endpoint %s", (origin) => {
    expect(openAiImagePricePreset("gpt-image-2", origin)).toBeUndefined();
  });
  it.each(["custom-image", "gpt-image-2-custom", "gpt-image-2-2099-01-01", "gpt-image-1", "chatgpt-image-latest"])("does not guess the price for %s", (model) => {
    expect(openAiImagePricePreset(model, null)).toBeUndefined();
  });
  it("updates only a draft's untouched automatic rates when the model changes", () => {
    const draft = applyOpenAiImageTokenPreset({}, null);
    const unknown = updateOpenAiImagePricingDraft(draft, { ...draft, model: "custom-image" }, null);
    expect(unknown.tokenPricing?.imageOutputPerMillion).toBe("");
    const known = updateOpenAiImagePricingDraft(unknown, { ...unknown, model: "gpt-image-2.5-flare" }, null);
    expect(known.tokenPricing?.imageOutputPerMillion).toBe("30");
    const custom = updateOpenAiImagePricingDraft(known, { ...known, tokenPricing: { ...known.tokenPricing!, imageOutputPerMillion: "0" } }, null);
    expect(updateOpenAiImagePricingDraft(custom, { ...custom, model: "custom-image" }, null).tokenPricing?.imageOutputPerMillion).toBe("0");
  });
  it("never replaces saved administrator rates while editing a model", () => {
    const saved = { tokenPricing: { currency: "CNY" as const, textInputPerMillion: "1", imageInputPerMillion: "2", imageOutputPerMillion: "3" } };
    expect(updateOpenAiImagePricingDraft(saved, { ...saved, model: "gpt-image-2.5-sunburst" }, null).tokenPricing).toEqual(saved.tokenPricing);
  });
  it("clears untouched official defaults when an endpoint changes and restores them only for official endpoints", () => {
    const official = applyOpenAiImageTokenPreset({}, null);
    const proxy = updateOpenAiImagePricingDraft(official, official, "https://proxy.example.com/v1");
    expect(proxy.tokenPricing?.imageOutputPerMillion).toBe("");
    const returned = updateOpenAiImagePricingDraft(proxy, proxy, "https://api.openai.com/v1");
    expect(returned.tokenPricing?.imageOutputPerMillion).toBe("30");
    const manual = updateOpenAiImagePricingDraft(returned, { ...returned, tokenPricing: { ...returned.tokenPricing!, imageOutputPerMillion: "0" } }, "https://api.openai.com/v1");
    expect(updateOpenAiImagePricingDraft(manual, manual, "https://proxy.example.com/v1").tokenPricing?.imageOutputPerMillion).toBe("0");
  });
});
