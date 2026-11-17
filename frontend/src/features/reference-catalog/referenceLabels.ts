import { t, type Locale, type MessageKey } from "../../shared/i18n";
export function referenceLabel(
  fallback: string,
  localized: Record<string, string> | undefined,
  locale: Locale,
): string {
  return localized?.[locale === "zh" ? "zh" : "en"] || localized?.en || localized?.zh || fallback;
}
const FACET_LABELS: Record<string, MessageKey> = {
  female: "reference.female",
  male: "reference.male",
  model: "reference.model",
  dance: "reference.dance",
  photo: "reference.photo",
  anime: "reference.anime",
  "line-art": "reference.lineArt",
  builtin: "reference.builtin",
  custom: "reference.custom",
  front: "reference.front",
  high: "reference.high",
  low: "reference.low",
  side: "reference.side",
  diag: "reference.diag",
};
export function referenceFacetLabel(value: string): string {
  const key = FACET_LABELS[value];
  return key ? t(key) : value;
}
