import type { MediaCapabilityDraft } from "./mediaCapabilityDraft";

const VERIFIED_AT = "2026-11-04";
const GUIDE = "https://developers.openai.com/api/docs/guides/image-generation#cost-and-latency";
type Rates = NonNullable<MediaCapabilityDraft["tokenPricing"]>;
type Definition = { rates: Rates; source: string; availableUntil?: string };
const currentModels = ["gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst-2026-09-08",
  "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08"];
const standard: Rates = { currency: "USD", textInputPerMillion: "5", imageInputPerMillion: "8", imageOutputPerMillion: "30" };
const legacy15: Definition = { rates: { ...standard, imageOutputPerMillion: "32" },
  source: "https://developers.openai.com/api/docs/models/gpt-image-1.5", availableUntil: "2026-12-01" };
const models = new Map<string, Definition>([
  ...currentModels.map((model): [string, Definition] => [model, { rates: standard, source: GUIDE }]),
  ["gpt-image-1.5", legacy15], ["gpt-image-1.5-2025-12-16", legacy15],
  ["gpt-image-1-mini", { rates: { currency: "USD", textInputPerMillion: "2", imageInputPerMillion: "2.5", imageOutputPerMillion: "8" },
    source: "https://developers.openai.com/api/docs/models/gpt-image-1-mini", availableUntil: "2026-12-01" }],
]);

/** Matches the actual direct Images API base, never a proxy or a lookalike host. */
function officialImagesEndpoint(origin: string | null | undefined): boolean {
  if (origin === null) return true; // The server's default is https://api.openai.com/v1/.
  if (origin === undefined) return false;
  try {
    const url = new URL(origin);
    return url.origin === "https://api.openai.com" && /^\/v1\/?$/.test(url.pathname)
      && !url.username && !url.password && !url.search && !url.hash;
  } catch { return false; }
}

export function openAiImagePricePreset(model: string | undefined, origin: string | null | undefined,
  asOf = new Date().toISOString().slice(0, 10)) {
  const selected = model?.trim() || "gpt-image-2";
  const definition = models.get(selected);
  if (!officialImagesEndpoint(origin) || !definition || definition.availableUntil && asOf >= definition.availableUntil) return undefined;
  // Never infer another model's prices, text-output rates, Responses cache discounts, or Batch rates.
  return { model: selected, verifiedAt: VERIFIED_AT, ...definition, rates: { ...definition.rates } };
}

/** Explicitly entering token mode may use a preset, including when replacing legacy per-image pricing. */
export function applyOpenAiImageTokenPreset(draft: MediaCapabilityDraft, origin: string | null | undefined): MediaCapabilityDraft {
  const preset = openAiImagePricePreset(draft.model, origin);
  return { ...draft, pricing: undefined, tokenPricing: preset?.rates
    ?? { currency: "USD", textInputPerMillion: "", imageInputPerMillion: "", imageOutputPerMillion: "" },
    openAiPricePresetModel: draft.model?.trim() || "gpt-image-2", openAiPricePresetOrigin: origin };
}

/** Only rates auto-filled in this editing session follow model changes; saved/custom values always survive. */
export function updateOpenAiImagePricingDraft(previous: MediaCapabilityDraft, next: MediaCapabilityDraft,
  origin: string | null | undefined): MediaCapabilityDraft {
  if (previous.openAiPricePresetModel && (previous.model?.trim() || "gpt-image-2") !== (next.model?.trim() || "gpt-image-2"))
    return applyOpenAiImageTokenPreset(next, origin);
  if (previous.openAiPricePresetModel && JSON.stringify(previous.tokenPricing) !== JSON.stringify(next.tokenPricing))
    return { ...next, openAiPricePresetModel: undefined, openAiPricePresetOrigin: undefined };
  if (previous.openAiPricePresetModel && previous.openAiPricePresetOrigin !== origin)
    return applyOpenAiImageTokenPreset(next, origin);
  return next;
}
