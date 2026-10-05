import { queryOptions } from "@tanstack/react-query";
import { listMediaStyles } from "./api/client";

export const MEDIA_STYLES_KEY = ["media-styles"] as const;
export const MEDIA_STYLE_SETTINGS_KEY = ["settings", "media-styles"] as const;
export const MEDIA_STYLE_PREVIEW_ACCEPT = "image/png,image/jpeg,image/webp";
export const MAX_MEDIA_STYLE_NAME_LENGTH = 80;
export const MAX_MEDIA_STYLE_CATEGORY_LENGTH = 40;
export const MAX_MEDIA_STYLE_PROMPT_LENGTH = 4000;

export function mediaStylesQueryOptions() {
  return queryOptions({ queryKey: MEDIA_STYLES_KEY, queryFn: listMediaStyles, retry: false });
}
