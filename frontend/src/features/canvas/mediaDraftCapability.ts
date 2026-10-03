import type { Artifact, ImageGenerationParameters, MediaCapability, SaveMediaDraftRequest } from "../../shared/api/client";
import { AUTODL_ADAPTER, publishedAutoDlResolutions, resolveAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t } from "../../shared/i18n";
import type { RunningHubValue } from "./RunningHubForm";
import { promptForMediaInputs } from "./mediaPrompt";

export const ASPECT_RATIO_OPTIONS = ["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9", "AUTO"] as const;
export const VIDEO_ASPECT_RATIO_OPTIONS = ["AUTO", "16:9", "9:16", "1:1"] as const;
export const RESOLUTION_OPTIONS = ["1K", "2K", "4K"] as const;
export const QUALITY_OPTIONS = ["low", "medium", "high"] as const;
export const GENERATION_COUNT_OPTIONS = [1, 2, 4] as const;
const START_END_FRAME_COUNT = 2;

export type MediaDraftFields = Omit<SaveMediaDraftRequest, "expectedVersion">;
type DraftFields = MediaDraftFields;
type ImageParameters = Required<Omit<ImageGenerationParameters, "speaker" | "speechRate" | "loudnessRate" | "pitchRate" | "dynamicValues" | "videoResolution">>;
type VideoInputMode = NonNullable<DraftFields["videoInputMode"]>;
type VideoParameters = { aspectRatio: (typeof VIDEO_ASPECT_RATIO_OPTIONS)[number]; videoResolution?: ImageGenerationParameters["videoResolution"] };

export function normalizedImageParameters(raw: ImageGenerationParameters | undefined,
  capability?: MediaCapability): ImageParameters {
  raw = { ...capability?.settings.defaultParameters, ...raw };
  const supportedRatios = capability?.supportedImageAspectRatios ?? ["AUTO"];
  const supportedResolutions = capability?.supportedImageResolutions ?? ["1K"];
  const supportedQualities = capability?.supportedImageQualities ?? [];
  const configuredQuality = capability?.settings.quality;
  const aspectRatio = raw.aspectRatio && supportedRatios.includes(raw.aspectRatio)
    ? raw.aspectRatio : supportedRatios.includes("AUTO") ? "AUTO" : supportedRatios[0] ?? "AUTO";
  const resolution = raw.resolution && supportedResolutions.includes(raw.resolution)
    ? raw.resolution : supportedResolutions[0] ?? "1K";
  const quality = raw.quality && (supportedQualities.length === 0 || supportedQualities.includes(raw.quality))
    ? raw.quality : configuredQuality ?? supportedQualities[0] ?? "medium";
  return {
    aspectRatio, resolution, quality,
    transparentBackground: capability?.supportsTransparentBackground
      ? raw.transparentBackground ?? false : false,
    generationCount: GENERATION_COUNT_OPTIONS.find((count) => count === raw.generationCount)
      ?? GENERATION_COUNT_OPTIONS[0],
  };
}

export function normalizedVideoParameters(raw: ImageGenerationParameters | undefined, capability?: MediaCapability): VideoParameters {
  raw = { ...capability?.settings.defaultParameters, ...raw };
  const aspectRatio = VIDEO_ASPECT_RATIO_OPTIONS.find((candidate) => candidate === raw.aspectRatio) ?? "AUTO";
  if (capability?.adapterId === AUTODL_ADAPTER) {
    const defaultResolution = capability.settings.videoResolution
      ?? resolveAutoDlWorkflow(capability.settings)?.defaultResolution as VideoParameters["videoResolution"];
    return { aspectRatio, videoResolution: raw.videoResolution ?? defaultResolution };
  }
  return { aspectRatio, ...(raw.videoResolution ? { videoResolution: raw.videoResolution } : {}) };
}

export function preferredImageVideoMode(capability?: MediaCapability): VideoInputMode {
  if (capability?.supportedVideoInputModes.includes("GENERAL_REFERENCE")) return "GENERAL_REFERENCE";
  if (capability?.supportedVideoInputModes.includes("START_END")) return "START_END";
  return "GENERAL_REFERENCE";
}

export function inputsForVideoMode(inputs: DraftFields["mediaInputs"], mode: VideoInputMode) {
  if (mode === "GENERAL_REFERENCE") {
    return inputs.map((input) => ({ ...input, role: input.role === "AUDIO_REFERENCE" || input.role === "VIDEO_REFERENCE" ? input.role : "REFERENCE" as const }));
  }
  if (mode === "START_END") {
    return inputs.filter((input) => input.role !== "AUDIO_REFERENCE" && input.role !== "VIDEO_REFERENCE").slice(0, START_END_FRAME_COUNT).map((input, index) => ({ ...input,
      role: index === 0 ? "START_FRAME" as const : "END_FRAME" as const }));
  }
  return [];
}

/** Calculates one complete draft change before the UI confirms or applies any part of it. */
export function planMediaCapabilityChange({ kind, fields, capabilityId, resolvedCapabilityId, previous, next }: {
  kind: Artifact["kind"]; fields: DraftFields; capabilityId: string | null;
  resolvedCapabilityId: string | undefined; previous?: MediaCapability; next?: MediaCapability;
}): { fields: Partial<DraftFields>; confirmation: string | null } {
  const beforeDefinition = previous?.settings.runningHub;
  const nextDefinition = next?.settings.runningHub;
  if (nextDefinition || beforeDefinition) {
    const oldValues = fields.parameters.dynamicValues ?? {};
    const compatible: Record<string, RunningHubValue> = {};
    if (nextDefinition && beforeDefinition) {
      const beforeFields = new Map(beforeDefinition.fields.map((field) => [field.key, field]));
      for (const candidate of nextDefinition.fields) {
        const before = beforeFields.get(candidate.key);
        const value = oldValues[candidate.key];
        if (value === undefined || before?.type !== candidate.type || before.source !== candidate.source
          || before.nodeId !== candidate.nodeId || before.fieldName !== candidate.fieldName) continue;
        if (typeof value === "number" && !((candidate.minimum == null || value >= candidate.minimum)
          && (candidate.maximum == null || value <= candidate.maximum))) continue;
        if (candidate.type === "SELECT" && !candidate.options?.some((option) => option.value === value)) continue;
        compatible[candidate.key] = value;
      }
    }
    const used = new Set(Object.values(compatible).filter((value) => typeof value === "string"));
    const retained = nextDefinition ? fields.mediaInputs.filter((input) => used.has(input.versionId)) : [];
    const removed = Object.keys(oldValues).filter((key) => !(key in compatible));
    const confirmation = removed.length || retained.length !== fields.mediaInputs.length
      || !beforeDefinition && Object.keys(fields.parameters).length
      ? t("media.capabilitySwitch.inputResetConfirmation", { "0": removed.length ? `（${removed.join("、")}）` : "" }) : null;
    return { fields: { capabilityId: resolvedCapabilityId ?? null,
      parameters: nextDefinition ? { dynamicValues: compatible } : {},
      mediaInputs: retained, ...promptForMediaInputs(fields, retained),
      durationSeconds: nextDefinition?.fields.some((field) => field.source === "DURATION_SECONDS") ? fields.durationSeconds : null,
      ...(kind === "VIDEO" ? { videoInputMode: retained.length ? "GENERAL_REFERENCE" : "TEXT" } : {}) }, confirmation };
  }
  if (kind === "IMAGE") {
    const parameters = normalizedImageParameters(fields.parameters, next);
    const confirmation = Object.keys(fields.parameters).length > 0
      && JSON.stringify(parameters) !== JSON.stringify(normalizedImageParameters(fields.parameters, previous))
      ? t("media.capabilitySwitch.parameterResetConfirmation") : null;
    return { fields: { capabilityId, parameters }, confirmation };
  }
  if (kind !== "VIDEO") return { fields: { capabilityId }, confirmation: null };

  const videoInputMode = fields.mediaInputs.length > 0
    && (!fields.videoInputMode || fields.videoInputMode === "TEXT"
      || !next?.supportedVideoInputModes.includes(fields.videoInputMode))
    ? preferredImageVideoMode(next) : fields.videoInputMode;
  const mediaInputs = videoInputMode ? inputsForVideoMode(fields.mediaInputs, videoInputMode) : fields.mediaInputs;
  const parameters = normalizedVideoParameters(fields.parameters, next);
  const previousResolution = fields.parameters.videoResolution;
  const resolutionIncompatible = previousResolution !== undefined
    && (next?.adapterId !== AUTODL_ADAPTER || !publishedAutoDlResolutions(next.settings).includes(previousResolution));
  if (resolutionIncompatible) {
    if (next?.adapterId === AUTODL_ADAPTER) parameters.videoResolution = next.settings.videoResolution;
    else delete parameters.videoResolution;
  }
  const confirmation = mediaInputs.length < fields.mediaInputs.length || resolutionIncompatible
    ? resolutionIncompatible
      ? t("media.capabilitySwitch.resolutionResetConfirmation")
      : t("media.capabilitySwitch.referenceRemovalConfirmation") : null;
  return { fields: { capabilityId, parameters, videoInputMode, mediaInputs,
    ...promptForMediaInputs(fields, mediaInputs) }, confirmation };
}
