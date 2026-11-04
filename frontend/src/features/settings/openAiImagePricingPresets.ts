import type { MediaCapabilityDraft } from "./mediaCapabilityDraft";

const VERIFIED_AT = "2026-11-04";
const GUIDE = "https://developers.openai.com/api/docs/guides/image-generation#cost-and-latency";
const MODELS = new Set(["gpt-image-2", "gpt-image-2-2026-04-21", "gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst-2026-09-08",
  "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08"]);

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

export function openAiImagePricePreset(model: string | undefined, origin: string | null | undefined) {
  const selected = model?.trim() || "gpt-image-2";
  if (!officialImagesEndpoint(origin) || !MODELS.has(selected)) return undefined;
  // Official model pages state that GPT Image 2.5 and GPT Image 2 share these Standard rates.
  // Direct Images requests do not receive the Responses image tool's cache discount or Batch rates.
  return { model: selected, verifiedAt: VERIFIED_AT, source: GUIDE,
    rates: { currency: "USD" as const, textInputPerMillion: "5", imageInputPerMillion: "8", imageOutputPerMillion: "30" } };
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
