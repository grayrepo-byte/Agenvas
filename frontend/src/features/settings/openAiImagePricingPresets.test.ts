import { describe, expect, it } from "vitest";
import { applyOpenAiImageTokenPreset, openAiImagePricePreset, updateOpenAiImagePricingDraft } from "./openAiImagePricingPresets";

describe("OpenAI Images standard price presets", () => {
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
