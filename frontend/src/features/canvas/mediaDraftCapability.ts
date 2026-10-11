import type { Artifact, ImageGenerationParameters, MediaCapability, SaveMediaDraftRequest } from "../../shared/api/client";
import { MINIMAX_H3_ADAPTER, MINIMAX_H3_RESOLUTIONS, MINIMAX_H3_DEFAULT_RESOLUTION } from "../../shared/minimaxH3";
import { KLING_VIDEO_ADAPTER, KLING_VIDEO_RESOLUTIONS, KLING_VIDEO_DEFAULT_RESOLUTION } from "../../shared/kling";
import { AUTODL_ADAPTER, publishedAutoDlResolutions, resolveAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { creatorInputMapping } from "../../shared/creatorInputs";
import { nativeVersionAssignable } from "../../shared/nativeMedia";
import { isWorkflowMediaField, type WorkflowValue } from "./workflowFields";
import { promptForMediaInputs } from "./mediaPrompt";
import { activeWorkflowMediaFields, workflowDefinition, workflowDraftValues } from "./workflowDraft";

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
type CapabilityChangeInput = {
  kind: Artifact["kind"]; fields: DraftFields; capabilityId: string | null;
  resolvedCapabilityId: string | undefined; previous?: MediaCapability; next?: MediaCapability;
};
type WorkflowDefinition = NonNullable<ReturnType<typeof workflowDefinition>>;
const inputMediaType = (input: DraftFields["mediaInputs"][number]) => input.role === "AUDIO_REFERENCE" ? "AUDIO"
  : input.role === "VIDEO_REFERENCE" ? "VIDEO" : "IMAGE";

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
  if (capability?.adapterId === MINIMAX_H3_ADAPTER) return { aspectRatio, videoResolution: raw.videoResolution ?? MINIMAX_H3_DEFAULT_RESOLUTION };
  if (capability?.adapterId === KLING_VIDEO_ADAPTER) return { aspectRatio, videoResolution: raw.videoResolution ?? KLING_VIDEO_DEFAULT_RESOLUTION };
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
  if (capability?.supportedVideoInputModes.includes("TEXT")) return "TEXT";
  return "GENERAL_REFERENCE";
}

/** Empty drafts prefer text only when the selected capability actually supports it. */
export function preferredVideoMode(capability: MediaCapability | undefined, hasInputs: boolean): VideoInputMode {
  if (!hasInputs && (!capability || capability.supportedVideoInputModes.includes("TEXT"))) return "TEXT";
  return preferredImageVideoMode(capability);
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

/** Explicit switches reconcile every regular model with its declared per-type limits.
 * Audio uses either images or audio/voice; when both arrive from a workflow, keep the
 * first compatible reference modality unless an effective speaker already selects audio.
 */
function inputsForRegularCapability(kind: Artifact["kind"], fields: DraftFields, capability?: MediaCapability,
  mode?: VideoInputMode | null): DraftFields["mediaInputs"] {
  const inputs = kind === "VIDEO" && mode ? inputsForVideoMode(fields.mediaInputs, mode) : fields.mediaInputs;
  if (!capability) return inputs;
  const speaker = kind === "AUDIO" ? fields.parameters.speaker ?? capability.settings.defaultParameters?.speaker : undefined;
  const limits = {
    IMAGE: kind === "VIDEO" && mode === "TEXT" ? 0 : Math.min(capability.maxReferenceImages,
      kind === "VIDEO" && mode === "START_END" ? capability.supportsEndFrame ? START_END_FRAME_COUNT : 1 : Infinity),
    AUDIO: kind === "IMAGE" || kind === "VIDEO" && mode !== "GENERAL_REFERENCE" ? 0
      : Math.max(0, capability.maxReferenceAudios - (speaker ? 1 : 0)),
    VIDEO: kind === "VIDEO" && mode === "GENERAL_REFERENCE" ? capability.maxReferenceVideos : 0,
  };
  let retained = inputs.filter((input) => {
    const type = inputMediaType(input);
    if (limits[type] <= 0) return false;
    limits[type]--;
    return true;
  }).map((input) => ({ ...input, role: kind !== "VIDEO" && inputMediaType(input) === "IMAGE" ? "REFERENCE" as const : input.role }));
  if (kind === "AUDIO") {
    const modality = speaker ? "AUDIO" : retained[0] && inputMediaType(retained[0]);
    retained = retained.filter((input) => inputMediaType(input) === modality);
  }
  return retained;
}

function changedRegularInputs(fields: DraftFields, inputs: DraftFields["mediaInputs"]): Partial<DraftFields> {
  if (fields.mediaInputs.length === inputs.length && fields.mediaInputs.every((input, index) =>
    input.versionId === inputs[index]?.versionId && input.role === inputs[index]?.role)) return {};
  return { mediaInputs: inputs, ...promptForMediaInputs(fields, inputs) };
}

/** Explicit switches preserve the common controls while keeping provider-specific values scoped. */
function regularParametersFromDeclaredInputs(kind: Artifact["kind"], fields: DraftFields, capability?: MediaCapability) {
  const parameters = { ...fields.parameters };
  if (!capability?.settings.klingInputs && !capability?.settings.nativeInputs) return parameters;
  const values = workflowDraftValues(capability, fields);
  const ratioValue = values.aspect_ratio ?? values.aspectRatio ?? values.ratio;
  const ratio = ASPECT_RATIO_OPTIONS.find((option) => option === ratioValue || option === "AUTO" && ["auto", "adaptive"].includes(String(ratioValue)));
  if (ratio) parameters.aspectRatio = ratio;
  if (kind === "IMAGE") {
    const resolution = RESOLUTION_OPTIONS.find((option) => option.toLowerCase() === String(values.resolution ?? values.size).toLowerCase());
    if (resolution) parameters.resolution = resolution;
    const quantity = values.result_type === "series" ? values.series_amount : values.n;
    parameters.generationCount = GENERATION_COUNT_OPTIONS.find((count) => count === quantity) ?? 1;
  } else if (kind === "VIDEO" && typeof values.resolution === "string") {
    parameters.videoResolution = values.resolution.toLowerCase() === "4k" ? "2160p" : values.resolution.toLowerCase();
  }
  return parameters;
}

/** A saved scalar is reusable only when it still targets the same published input. */
function compatibleWorkflowValues(beforeDefinition: WorkflowDefinition | undefined,
  nextDefinition: WorkflowDefinition, oldValues: Record<string, WorkflowValue>): Record<string, WorkflowValue> {
  const compatible: Record<string, WorkflowValue> = {};
  if (!beforeDefinition) return compatible;
  const beforeFields = new Map(beforeDefinition.fields.map((field) => [field.key, field]));
  for (const candidate of nextDefinition.fields) {
    const before = beforeFields.get(candidate.key);
    const value = oldValues[candidate.key];
    if (value === undefined || before?.type !== candidate.type || (before.source ?? "PARAMETER") !== (candidate.source ?? "PARAMETER")
      || creatorInputMapping(before) !== creatorInputMapping(candidate)) continue;
    if (typeof value === "number" && !((candidate.minimum == null || value >= candidate.minimum)
      && (candidate.maximum == null || value <= candidate.maximum))) continue;
    if (candidate.type === "SELECT" && !candidate.options?.some((option) => option.value === value)) continue;
    if (candidate.type === "STRING" && typeof value === "string" && candidate.maxLength != null
      && [...value].length > candidate.maxLength) continue;
    compatible[candidate.key] = value;
  }
  return compatible;
}

function fieldsForWorkflowCapability({ kind, fields, resolvedCapabilityId, previous, next }: CapabilityChangeInput,
  nextDefinition: WorkflowDefinition): Partial<DraftFields> {
  const beforeDefinition = workflowDefinition(previous);
  const compatible = compatibleWorkflowValues(beforeDefinition, nextDefinition, workflowDraftValues(previous, fields));
  if (next?.settings.klingInputs && !beforeDefinition) {
    const bridged: Record<string, WorkflowValue | undefined> = {
      aspect_ratio: fields.parameters.aspectRatio === "AUTO" ? undefined : fields.parameters.aspectRatio,
      resolution: kind === "IMAGE" ? fields.parameters.resolution?.toLowerCase()
        : fields.parameters.videoResolution === "2160p" ? "4k" : fields.parameters.videoResolution,
      n: kind === "IMAGE" ? fields.parameters.generationCount : undefined,
    };
    for (const candidate of nextDefinition.fields) {
      const value = bridged[candidate.key];
      if (value === undefined || candidate.source === "PROMPT" || candidate.source === "DURATION_SECONDS") continue;
      if (candidate.type === "SELECT" && !candidate.options?.some((option) => option.value === value)) continue;
      if (typeof value === "number" && ((candidate.minimum != null && value < candidate.minimum)
        || (candidate.maximum != null && value > candidate.maximum))) continue;
      compatible[candidate.key] = value;
    }
  }
  const slots = activeWorkflowMediaFields(nextDefinition, compatible, fields.prompt, fields.durationSeconds);
  // Preserve exact compatible assignments, respecting native reuse declarations, then
  // move remaining references into active slots by media type and published order.
  const nativeAssignments: Record<string, WorkflowValue> = {};
  for (const slot of nextDefinition.fields.filter(isWorkflowMediaField)) {
    if (!slots.includes(slot) || !fields.mediaInputs.some((input) =>
      input.versionId === compatible[slot.key] && inputMediaType(input) === slot.type)) delete compatible[slot.key];
    const value = compatible[slot.key];
    if (next?.settings.nativeInputs && typeof value === "string") {
      if (!nativeVersionAssignable(next, nativeAssignments, slot.key, value)) delete compatible[slot.key];
      else nativeAssignments[slot.key] = value;
    }
  }
  const used = new Set(slots.map((slot) => compatible[slot.key]).filter((value) => typeof value === "string"));
  for (const input of fields.mediaInputs) {
    if (used.has(input.versionId)) continue;
    const slot = slots.find((slot) => slot.type === inputMediaType(input) && compatible[slot.key] === undefined);
    if (!slot) continue;
    compatible[slot.key] = input.versionId;
    used.add(input.versionId);
  }
  const retained = fields.mediaInputs.filter((input) => used.has(input.versionId)).map((input) => ({ ...input,
    role: inputMediaType(input) === "IMAGE" ? "REFERENCE" as const : input.role }));
  return { capabilityId: resolvedCapabilityId ?? null,
    parameters: { ...(next?.settings.comfyInputs
      ? kind === "IMAGE" ? normalizedImageParameters(fields.parameters, next) : normalizedVideoParameters(fields.parameters, next)
      : {}), dynamicValues: compatible },
    mediaInputs: retained, ...promptForMediaInputs(fields, retained),
    durationSeconds: nextDefinition.fields.some((field) => field.source === "DURATION_SECONDS") ? fields.durationSeconds : null,
    ...(kind === "VIDEO" ? { videoInputMode: next?.settings.klingInputs || next?.settings.nativeInputs || retained.length ? "GENERAL_REFERENCE" : "TEXT" } : {}) };
}

function fieldsForRegularCapability({ kind, fields, capabilityId, next }: CapabilityChangeInput): Partial<DraftFields> {
  if (kind === "IMAGE") {
    const parameters = normalizedImageParameters(fields.parameters, next);
    return { capabilityId, parameters,
      ...changedRegularInputs(fields, inputsForRegularCapability(kind, fields, next)) };
  }
  if (kind !== "VIDEO") return { capabilityId,
    ...changedRegularInputs(fields, inputsForRegularCapability(kind, fields, next)) };

  const videoInputMode = !fields.videoInputMode || !next?.supportedVideoInputModes.includes(fields.videoInputMode)
    || fields.mediaInputs.length > 0 && fields.videoInputMode === "TEXT"
    ? preferredVideoMode(next, fields.mediaInputs.length > 0) : fields.videoInputMode;
  const mediaInputs = inputsForRegularCapability(kind, fields, next, videoInputMode);
  const parameters = normalizedVideoParameters(fields.parameters, next);
  const previousResolution = fields.parameters.videoResolution;
  const resolutionIncompatible = previousResolution !== undefined
    && (next?.adapterId === MINIMAX_H3_ADAPTER ? !MINIMAX_H3_RESOLUTIONS.some((tier) => tier === previousResolution)
      : next?.adapterId === KLING_VIDEO_ADAPTER ? !KLING_VIDEO_RESOLUTIONS.some((tier) => tier === previousResolution)
      : next?.adapterId !== AUTODL_ADAPTER || !publishedAutoDlResolutions(next.settings).includes(previousResolution));
  if (resolutionIncompatible) {
    if (next?.adapterId === AUTODL_ADAPTER) parameters.videoResolution = next.settings.videoResolution;
    else if (next?.adapterId === MINIMAX_H3_ADAPTER) parameters.videoResolution = next.settings.defaultParameters?.videoResolution ?? MINIMAX_H3_DEFAULT_RESOLUTION;
    else if (next?.adapterId === KLING_VIDEO_ADAPTER) parameters.videoResolution = next.settings.defaultParameters?.videoResolution ?? KLING_VIDEO_DEFAULT_RESOLUTION;
    else delete parameters.videoResolution;
  }
  return { capabilityId, parameters, videoInputMode, mediaInputs,
    ...promptForMediaInputs(fields, mediaInputs) };
}

/** Leaving a workflow discards its dynamic mappings, then applies the regular model's input rules. */
function fieldsAfterWorkflow(change: CapabilityChangeInput): Partial<DraftFields> {
  const { kind, fields } = change;
  const parameters = regularParametersFromDeclaredInputs(kind, fields, change.previous);
  delete parameters.dynamicValues;
  const regular = fieldsForRegularCapability({ ...change, fields: { ...fields, parameters } });
  const retained = regular.mediaInputs ?? fields.mediaInputs;
  return { ...regular, parameters: regular.parameters ?? parameters,
    ...(kind !== "VIDEO" ? { durationSeconds: null } : {}),
    mediaInputs: retained, ...promptForMediaInputs(fields, retained) };
}

/** Explicit switches produce one atomic field change; background capability updates never use it. */
export function planMediaCapabilityChange(change: CapabilityChangeInput): { fields: Partial<DraftFields> } {
  const nextDefinition = workflowDefinition(change.next);
  if (nextDefinition) return { fields: fieldsForWorkflowCapability(change, nextDefinition) };
  if (workflowDefinition(change.previous)) return { fields: fieldsAfterWorkflow(change) };
  return { fields: fieldsForRegularCapability(change) };
}
