import type { MediaCapability } from "../../shared/api/client";
import { AUTODL_ADAPTER, AUTODL_DEFAULT_WORKFLOW, autoDlResolutionTiers, resolveAutoDlWorkflow, type AutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t } from "../../shared/i18n";
import { KLING_NATIVE_VIDEO_ADAPTER, KLING_VIDEO_ADAPTER, isKlingNativeAdapter } from "../../shared/kling";
import { isNativeMediaAdapter } from "../../shared/nativeMedia";
import { MINIMAX_H3_ADAPTER } from "../../shared/minimaxH3";
import { isComfyAdapter } from "./comfyWorkflow";
import { selectRunningHubNodes } from "./runningHubSelection";

type Settings = MediaCapability["settings"];
type AutoDlDefinition = NonNullable<Settings["workflowDefinition"]>;

// Publishing projects these choices into the contract; they never become API settings.
export type MediaCapabilityDraft = Settings & {
  runningHubSelectedNodeIds?: string[];
  runningHubSelectedFieldKeys?: string[];
};
export class CapabilityDraftError extends Error {}

/** A blank administrator price means unknown; preserve explicit zero and the original decimal string. */
function configuredTierPrices(prices: NonNullable<Settings["pricingByResolution"]>) {
  return Object.fromEntries(Object.entries(prices).filter(([, price]) => price?.amount.trim()));
}

function commonSettings(draft: Settings): Settings {
  const settings: Settings = {};
  if (draft.defaultParameters) settings.defaultParameters = draft.defaultParameters;
  if (draft.defaultDurationSeconds !== undefined) settings.defaultDurationSeconds = draft.defaultDurationSeconds;
  if (draft.minimumSeconds !== undefined) settings.minimumSeconds = draft.minimumSeconds;
  if (draft.maximumSeconds !== undefined) settings.maximumSeconds = draft.maximumSeconds;
  if (draft.maxReferenceImages !== undefined) settings.maxReferenceImages = draft.maxReferenceImages;
  if (draft.maxReferenceAudios !== undefined) settings.maxReferenceAudios = draft.maxReferenceAudios;
  if (draft.maxReferenceVideos !== undefined) settings.maxReferenceVideos = draft.maxReferenceVideos;
  if (draft.pricing?.amount.trim()) settings.pricing = draft.pricing;
  return settings;
}

function autoDlSettings(draft: Settings): Settings {
  return {
    workflowId: draft.workflowDefinition?.id ?? draft.workflowId ?? AUTODL_DEFAULT_WORKFLOW,
    ...(draft.workflowDefinition ? { workflowDefinition: draft.workflowDefinition } : {}),
    ...(draft.videoResolution ? { videoResolution: draft.videoResolution } : {}),
    videoResolutions: selectedAutoDlDraftTiers(draft),
    ...(draft.pricingByResolution ? { pricingByResolution: configuredTierPrices(draft.pricingByResolution) } : {}),
    ...(draft.seed !== undefined ? { seed: draft.seed } : {}),
  };
}

function providerSettings(adapterId: string, draft: Settings): Settings {
  if (isComfyAdapter(adapterId)) return { comfyWorkflow: draft.comfyWorkflow };
  if (isKlingNativeAdapter(adapterId) || isNativeMediaAdapter(adapterId)) return {
    model: draft.model ?? "", operation: draft.operation ?? "GENERATE",
    ...((isNativeMediaAdapter(adapterId) || adapterId === KLING_NATIVE_VIDEO_ADAPTER) && draft.pricingByResolution
      ? { pricingByResolution: configuredTierPrices(draft.pricingByResolution) } : {}),
  };
  switch (adapterId) {
    case AUTODL_ADAPTER: return autoDlSettings(draft);
    case "OPENAI_GPT_IMAGE_2": return { model: draft.model?.toString().trim() ?? "", quality: draft.quality ?? "medium" };
    case "GOOGLE_NANO_BANANA_2": return { model: draft.model?.toString().trim() ?? "" };
    case KLING_VIDEO_ADAPTER:
    case MINIMAX_H3_ADAPTER: return draft.pricingByResolution
      ? { pricingByResolution: configuredTierPrices(draft.pricingByResolution) } : {};
    default: return {};
  }
}

/** Build the provider's existing settings whitelist without changing or normalizing the editor draft. */
export function buildMediaCapabilitySettings(adapterId: string, draft: MediaCapabilityDraft): Settings {
  if (adapterId.startsWith("RUNNINGHUB_")) {
    const runningHub = draft.runningHub
      ? selectRunningHubNodes(draft.runningHub, draft.runningHubSelectedNodeIds, draft.runningHubSelectedFieldKeys) : undefined;
    const orphaned = runningHub?.fields.find((field) => field.enabledWhen
      && !runningHub.fields.some((parent) => parent.key === field.enabledWhen?.field));
    if (orphaned) throw new CapabilityDraftError(t("settings.runningHub.unselectedCondition", { "0": orphaned.label }));
    // RunningHub has its own contract and does not publish ordinary-model defaults or limits.
    return { runningHub, ...(draft.pricing?.amount.trim() ? { pricing: draft.pricing } : {}) };
  }
  return { ...providerSettings(adapterId, draft), ...commonSettings(draft) };
}

/** Administrator drafts offer all supported tiers when unset; published legacy capabilities remain single-tier. */
export function selectedAutoDlDraftTiers(draft: Settings, supportedTiers?: readonly string[]): string[] {
  return draft.videoResolutions ?? (draft.videoResolution ? [draft.videoResolution]
    : [...(supportedTiers ?? autoDlResolutionTiers(resolveAutoDlWorkflow(draft)!))]);
}

function retainTierPrices(draft: Settings, tiers: readonly string[]) {
  return Object.fromEntries(Object.entries(draft.pricingByResolution ?? {}).filter(([tier]) => tiers.includes(tier)));
}

const AUTODL_TARGET_RESET = {
  pricingByResolution: undefined, seed: undefined,
  minimumSeconds: undefined, maximumSeconds: undefined, maxReferenceImages: undefined,
  maxReferenceAudios: undefined, defaultDurationSeconds: undefined,
};

/** Previewing another target resets its options; manual definition edits deliberately keep the current options. */
export function applyAutoDlDraftDefinition(draft: Settings, definition: AutoDlDefinition, resetTarget = false): Settings {
  const tiers = autoDlResolutionTiers(definition);
  const retained = resetTarget ? tiers : selectedAutoDlDraftTiers(draft, tiers).filter((tier) => tiers.includes(tier));
  const videoResolutions = retained.length ? retained : tiers;
  const videoResolution = !resetTarget && videoResolutions.includes(draft.videoResolution ?? "") ? draft.videoResolution
    : videoResolutions.includes(definition.defaultResolution) ? definition.defaultResolution : videoResolutions[0];
  const next = {
    ...draft, workflowId: definition.id,
    workflowDefinition: { ...definition, defaultResolution: videoResolution ?? definition.defaultResolution },
    videoResolution, videoResolutions,
    ...(draft.pricingByResolution ? { pricingByResolution: retainTierPrices(draft, videoResolutions) } : {}),
    ...(!definition.supportsSeed ? { seed: undefined } : {}),
  };
  return resetTarget ? { ...next, ...AUTODL_TARGET_RESET } : next;
}

/** Choosing an offline preset resets target options and removes any imported definition. */
export function selectAutoDlDraftPreset(draft: Settings, preset: AutoDlWorkflow): Settings {
  return {
    ...draft, workflowDefinition: undefined, workflowId: preset.id,
    videoResolution: preset.defaultResolution, videoResolutions: autoDlResolutionTiers(preset),
    ...AUTODL_TARGET_RESET,
  };
}

/** Tier choices follow provider order; removing a tier also removes its price and repairs the default. */
export function selectAutoDlDraftTiers(draft: Settings, choices: readonly string[], supportedTiers: readonly string[]): Settings | null {
  const videoResolutions = supportedTiers.filter((tier) => choices.includes(tier));
  if (videoResolutions.length === 0) return null;
  const current = draft.videoResolution ?? resolveAutoDlWorkflow(draft)?.defaultResolution;
  const videoResolution = videoResolutions.find((tier) => tier === current) ?? videoResolutions[0];
  return {
    ...draft, videoResolutions, videoResolution, pricingByResolution: retainTierPrices(draft, videoResolutions),
    ...(draft.workflowDefinition && videoResolution
      ? { workflowDefinition: { ...draft.workflowDefinition, defaultResolution: videoResolution } } : {}),
  };
}
