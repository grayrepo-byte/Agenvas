import type { MediaCapability, NativeMediaProfile } from "./api/client";
import type { CreatorInputValue } from "./creatorInputs";
import { t } from "./i18n";

export const NATIVE_MEDIA_ADAPTERS = ["WAN_IMAGE", "WAN_VIDEO", "ARK_SEEDREAM_IMAGE", "GOOGLE_VEO", "GOOGLE_OMNI_VIDEO"] as const;
export const isNativeMediaAdapter = (adapterId: string) => NATIVE_MEDIA_ADAPTERS.some((value) => value === adapterId);
export type NativeInputLimits = Partial<NativeMediaProfile["inputLimits"]>;

export function nativeValue(capability: MediaCapability, values: Record<string, CreatorInputValue>, key: string): CreatorInputValue | undefined {
  return values[key] ?? capability.settings.defaultParameters?.dynamicValues?.[key]
    ?? capability.settings.nativeInputs?.find((field) => field.key === key)?.defaultValue ?? undefined;
}

/** Native groups are one request with a provider-selected number of outputs, so their estimate stays unknown. */
export function nativeOutputCount(capability: MediaCapability, values: Record<string, CreatorInputValue>): number {
  if (capability.settings.operation === "LAYER_DECOMPOSITION"
      || nativeValue(capability, values, "sequential_image_generation") === "auto"
      || nativeValue(capability, values, "enable_sequential") === true) return 0;
  if (capability.adapterId !== "WAN_IMAGE") return 1;
  const count = nativeValue(capability, values, "n") ?? 1;
  return typeof count === "number" && Number.isInteger(count) && count >= 1 && count <= 4 ? count : 0;
}

export function nativeResolutionTier(capability: MediaCapability, values: Record<string, CreatorInputValue>): string | undefined {
  if (capability.adapterId === "WAN_IMAGE") return undefined;
  if (capability.adapterId !== "ARK_SEEDREAM_IMAGE") {
    const resolution = nativeValue(capability, values, "resolution");
    return resolution === "4k" ? "2160p" : typeof resolution === "string" ? resolution.toLowerCase() : undefined;
  }
  const size = nativeValue(capability, values, "size");
  if (typeof size !== "string" || size === "auto") return undefined;
  if (/^(1|1\.5|2|3|4)K$/.test(size)) return size;
  const dimensions = /^([1-9]\d{0,5})x([1-9]\d{0,5})$/.exec(size);
  if (!dimensions) return undefined;
  const pixels = Number(dimensions[1]) * Number(dimensions[2]);
  return pixels <= 1024 ** 2 ? "1K" : pixels <= 1536 ** 2 ? "1.5K" : pixels <= 2048 ** 2 ? "2K" : pixels <= 3072 ** 2 ? "3K" : "4K";
}

/** Pricing tiers come from compiled choices; size strings retain their vendor's finite preset tiers. */
export function nativePricingTiers(adapterId: string, fields: NonNullable<MediaCapability["settings"]["nativeInputs"]>): string[] {
  if (adapterId === "ARK_SEEDREAM_IMAGE") return fields.find((field) => field.key === "size")?.options
    ?.map((option) => String(option.value)).filter((value) => value !== "auto") ?? [];
  return [...new Set(fields.find((field) => field.key === "resolution")?.options?.map((option) => {
    const value = String(option.value).toLowerCase();
    return value === "4k" ? "2160p" : value;
  }) ?? [])];
}

/** Reuse is restricted to the server-declared frame slots, while authorization stays per version. */
export function nativeVersionAssignable(capability: MediaCapability, values: Record<string, CreatorInputValue>,
  slot: string, versionId: string): boolean {
  const assigned = (capability.settings.nativeInputs ?? []).filter((field) => ["IMAGE", "VIDEO", "AUDIO"].includes(field.type)
    && field.key !== slot && nativeValue(capability, values, field.key) === versionId);
  if (!assigned.length) return true;
  const reusable: readonly string[] = capability.settings.nativeReusableSlots ?? [];
  return reusable.includes(slot) && assigned.every((field) => reusable.includes(field.key));
}

/** Check combinations that are independent of asset probing before a creator submits the request. */
export function nativeInputErrors(capability: MediaCapability, values: Record<string, CreatorInputValue>, prompt: string,
  duration: number | null | undefined): string[] {
  const effective = (key: string) => nativeValue(capability, values, key);
  const fields = capability.settings.nativeInputs ?? [];
  const slots = fields.filter((field) => ["IMAGE", "VIDEO", "AUDIO"].includes(field.type));
  const assigned = slots.flatMap((field) => typeof effective(field.key) === "string" && effective(field.key) ? [effective(field.key)] : []);
  const errors: string[] = [];
  if (new Set(assigned).size !== assigned.length && slots.some((field) => typeof effective(field.key) === "string"
      && !nativeVersionAssignable(capability, values, field.key, String(effective(field.key)))))
    errors.push(t("media.native.duplicateReference"));
  const first = Boolean(effective("first_frame")), last = Boolean(effective("last_frame"));
  if (last && !first) errors.push(t("media.native.firstFrameRequired"));
  if (capability.adapterId === "WAN_VIDEO") {
    const references = slots.some((field) => /^(image|video|audio)_/.test(field.key) && effective(field.key));
    if ((first || last) && references) errors.push(t("media.native.framesAndReferences"));
    if (!prompt.trim() && !assigned.length) errors.push(t("media.native.promptOrMedia"));
  }
  if (capability.adapterId === "WAN_IMAGE" && effective("enable_sequential") !== true && Number(effective("n") ?? 1) > 4)
    errors.push(t("media.native.singleCount"));
  if (capability.adapterId === "WAN_IMAGE" && !prompt.trim()) errors.push(t("media.editor.promptRequired"));
  if (capability.adapterId === "GOOGLE_VEO") {
    const references = slots.some((field) => field.key.startsWith("image_") && effective(field.key));
    if ((capability.settings.operation === "EXTEND" || references || effective("resolution") !== "720p") && duration !== 8)
      errors.push(t("media.native.veoEightSeconds"));
    if ((first || references) && effective("personGeneration") && effective("personGeneration") !== "allow_adult")
      errors.push(t("media.native.veoAdults"));
  }
  if (capability.adapterId === "GOOGLE_OMNI_VIDEO" && capability.settings.operation === "GENERATE") {
    const references = slots.some((field) => /^(image|video)_/.test(field.key) && effective(field.key));
    const task = effective("task");
    if (task === "text_to_video" && (first || references) || task === "image_to_video" && (!first || references)
        || task === "reference_to_video" && (first || !references)) errors.push(t("media.native.taskInputs"));
  }
  if (capability.adapterId === "ARK_SEEDREAM_IMAGE") {
    const images = slots.filter((field) => field.type === "IMAGE" && effective(field.key));
    if (images.some((_, index) => !effective(`image${index + 1}`))) errors.push(t("media.native.imageOrder"));
    if (effective("background") === "transparent" && (images.length !== 1 || effective("output_format") !== "png"))
      errors.push(t("media.native.transparent"));
    const regions = effective("edit_regions");
    if (typeof regions === "string" && regions.trim() && !validEditRegions(regions, images.length)) errors.push(t("media.native.editRegions"));
  }
  return errors;
}

function validEditRegions(raw: string, imageCount: number): boolean {
  if (raw.length > 16000) return false;
  let regions: unknown;
  try { regions = JSON.parse(raw); } catch { return false; }
  if (!Array.isArray(regions) || regions.length < 1 || regions.length > 16) return false;
  return regions.every((value: unknown) => {
    if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
    const region = value as Record<string, unknown>;
    if (Object.keys(region).sort().join(",") !== "coordinates,image,instruction,type" || !Number.isInteger(region.image)
        || Number(region.image) < 1 || Number(region.image) > imageCount || typeof region.instruction !== "string"
        || !region.instruction.trim() || region.instruction.length > 1000 || !["point", "bbox"].includes(String(region.type))
        || !Array.isArray(region.coordinates) || region.coordinates.length !== (region.type === "point" ? 2 : 4)) return false;
    const coordinates: unknown[] = region.coordinates;
    if (!coordinates.every((coordinate) => typeof coordinate === "number" && Number.isInteger(coordinate) && coordinate >= 0 && coordinate <= 999)) return false;
    return region.type !== "bbox" || Number(coordinates[0]) < Number(coordinates[2]) && Number(coordinates[1]) < Number(coordinates[3]);
  });
}
