import { queryOptions } from "@tanstack/react-query";
import { listMediaStyles } from "./api/client";
import type { MediaStyleSummary } from "./api/client";
import { t, type MessageKey } from "./i18n";
import seededMetadata from "./mediaStyleDefaults.json";

export const MEDIA_STYLES_KEY = ["media-styles"] as const;
export const MEDIA_STYLE_SETTINGS_KEY = ["settings", "media-styles"] as const;
export const MEDIA_STYLE_PREVIEW_ACCEPT = "image/png,image/jpeg,image/webp";
export const MAX_MEDIA_STYLE_NAME_LENGTH = 80;
export const MAX_MEDIA_STYLE_CATEGORY_LENGTH = 40;
export const MAX_MEDIA_STYLE_PROMPT_LENGTH = 4000;

type BuiltInStyle = { name: MessageKey; category: MessageKey | null };
const originalMetadata: Readonly<Record<string, { name: string; category: string }>> = seededMetadata;
// These are the immutable seed identities and original field values, not a translation
// dictionary for server strings. A renamed preset or any custom style stays literal.
const builtInStyles: Readonly<Record<string, BuiltInStyle>> = {
  "00000000-0000-4000-8000-000000000301": {
    name: "styles.presets.photographic",
    category: "styles.categories.realistic",
  },
  "00000000-0000-4000-8000-000000000302": {
    name: "styles.presets.cinematic",
    category: "styles.categories.realistic",
  },
  "00000000-0000-4000-8000-000000000303": {
    name: "styles.presets.anime",
    category: "styles.categories.illustration",
  },
  "00000000-0000-4000-8000-000000000304": {
    name: "styles.presets.threeDimensional",
    category: null,
  },
  "00000000-0000-4000-8000-000000000305": {
    name: "styles.presets.watercolor",
    category: "styles.categories.painting",
  },
  "00000000-0000-4000-8000-000000000306": {
    name: "styles.presets.inkWash",
    category: "styles.categories.painting",
  },
  "00000000-0000-4000-8000-000000000307": {
    name: "styles.presets.cyberpunk",
    category: "styles.categories.fantasy",
  },
  "00000000-0000-4000-8000-000000000308": {
    name: "styles.presets.clay",
    category: null,
  },
};

/** Render-only labels: keep raw API records, edit drafts and frozen snapshots intact. */
export function getMediaStyleLabels(style: Pick<MediaStyleSummary, "id" | "builtIn" | "name" | "category">) {
  const defaults = style.builtIn && Object.hasOwn(builtInStyles, style.id) ? builtInStyles[style.id] : undefined;
  const original = originalMetadata[style.id];
  const category = defaults?.category && style.category === original?.category ? defaults.category : null;
  return {
    name: defaults && style.name === original?.name ? t(defaults.name) : style.name,
    category: category ? t(category) : style.category,
    // Separate a default category from an identically named custom category. The
    // filter identity is independent of locale and never enters an API payload.
    categoryKey: JSON.stringify(category ? ["builtin", category] : ["literal", style.category]),
  };
}

export function mediaStylesQueryOptions() {
  return queryOptions({ queryKey: MEDIA_STYLES_KEY, queryFn: listMediaStyles, retry: false });
}
