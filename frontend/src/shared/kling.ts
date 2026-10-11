/** Compiled Kling image and video protocols; connection credentials stay server-side. */
import type { MediaCapability } from "./api/client";
import type { CreatorInputValue } from "./creatorInputs";

export const KLING_IMAGE_ADAPTER = "KLING_IMAGE_3";
export const KLING_VIDEO_ADAPTER = "KLING_VIDEO_3";
export const KLING_NATIVE_IMAGE_ADAPTER = "KLING_IMAGE";
export const KLING_NATIVE_VIDEO_ADAPTER = "KLING_VIDEO";
export const KLING_API_ORIGIN = "https://api-singapore.klingai.com";
export const KLING_API_ORIGINS = [KLING_API_ORIGIN, "https://api-beijing.klingai.com"] as const;
export const KLING_IMAGE_RESOLUTIONS = ["1K", "2K"] as const;
export const KLING_IMAGE_ASPECT_RATIOS = ["AUTO", "16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3", "21:9"] as const;
export const KLING_VIDEO_RESOLUTIONS = ["720p", "1080p", "2160p"] as const;
export const KLING_VIDEO_DEFAULT_RESOLUTION = "1080p";
export const klingVideoResolutionLabel = (tier: string) => tier === "2160p" ? "4K" : tier.toUpperCase();

export const isKlingNativeAdapter = (adapterId: string) => adapterId === KLING_NATIVE_IMAGE_ADAPTER || adapterId === KLING_NATIVE_VIDEO_ADAPTER;
export const isKlingAdapter = (adapterId: string) => isKlingNativeAdapter(adapterId) || adapterId === KLING_IMAGE_ADAPTER || adapterId === KLING_VIDEO_ADAPTER;

/** Unknown series length remains unknown until the provider returns its actual results. */
export function klingOutputCount(capability: MediaCapability, values: Record<string, CreatorInputValue>): number {
  const effective = (key: string) => values[key] ?? capability.settings.klingInputs?.find((field) => field.key === key)?.defaultValue;
  if (capability.settings.operation === "SUBJECT_COMPLETION") return 9;
  const count = effective("result_type") === "series" ? effective("series_amount") : effective("n") ?? 1;
  return typeof count === "number" && Number.isInteger(count) && count >= 1 && count <= 9 ? count : 0;
}

export function klingResolutionTier(capability: MediaCapability, values: Record<string, CreatorInputValue>): string | undefined {
  const value = values.resolution ?? capability.settings.klingInputs?.find((field) => field.key === "resolution")?.defaultValue;
  return value === "4k" ? "2160p" : typeof value === "string" ? value : undefined;
}
