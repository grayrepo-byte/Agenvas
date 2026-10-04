import type { MediaCapability, MediaTemplateImport, MediaTemplateKind } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { runningHubFieldValue } from "../canvas/RunningHubForm";
import { MENTION_MARKER, promptForMediaInputs } from "../canvas/mediaPrompt";
import { workflowDefinition, workflowDraftValues } from "../canvas/workflowDraft";

export type TemplateApplyOptions = {
  videoInputMode: NonNullable<MediaDraftFields["videoInputMode"]> | null;
  imageSlots: string[];
};
const START_END_LIMIT = 2;
const MAX_TEMPLATE_PROMPT = 20000;

/** Save readable positional labels, never the original project's structured version identities. */
export function templateSeedPrompt(fields: MediaDraftFields): string {
  const labels = new Map(fields.mediaInputs.map((input, index) => [input.versionId,
    input.role === "START_FRAME" ? "Start Frame" : input.role === "END_FRAME" ? "End Frame"
      : `${input.role === "AUDIO_REFERENCE" ? "Audio" : input.role === "VIDEO_REFERENCE" ? "Video" : "Image"} ${fields.mediaInputs.slice(0, index + 1).filter((reference) => reference.role === input.role).length}`]));
  let mentionIndex = 0;
  return Array.from(fields.prompt, (character) => {
    if (character !== MENTION_MARKER) return character;
    const mention = fields.mentions[mentionIndex++];
    return mention ? `@${labels.get(mention.versionId) ?? "Reference"}` : "";
  }).join("");
}

/** Only active, administrator-published image fields can receive template images. */
export function templateImageSlots(capability: MediaCapability | undefined, fields: MediaDraftFields) {
  const definition = workflowDefinition(capability);
  if (!definition) return [];
  const values = workflowDraftValues(capability, fields);
  const effective = Object.fromEntries(definition.fields.map((field) => [field.key,
    runningHubFieldValue(field, values, fields.prompt, fields.durationSeconds)]));
  return definition.fields.filter((field) => field.type === "IMAGE" && (field.source == null || field.source === "PARAMETER")
    && (!field.enabledWhen || effective[field.enabledWhen.field] === field.enabledWhen.value));
}

/** Templates cannot write a prompt when the published workflow exposes no active prompt input. */
export function templatePromptEnabled(capability: MediaCapability | undefined, fields: MediaDraftFields) {
  const definition = workflowDefinition(capability);
  if (!definition) return true;
  const prompt = definition.fields.find((field) => field.source === "PROMPT");
  if (!prompt) return false;
  if (!prompt.enabledWhen) return true;
  const condition = definition.fields.find((field) => field.key === prompt.enabledWhen?.field);
  return Boolean(condition && runningHubFieldValue(condition, workflowDraftValues(capability, fields), fields.prompt,
    fields.durationSeconds) === prompt.enabledWhen.value);
}

/** Validate the whole replacement before importing bytes; no reference is silently truncated. */
export function templateApplicationError(kind: MediaTemplateKind, fields: MediaDraftFields,
  capability: MediaCapability | undefined, imageCount: number, options: TemplateApplyOptions,
  prompt: string): string | null {
  const promptEnabled = templatePromptEnabled(capability, fields);
  const promptField = workflowDefinition(capability)?.fields.find((field) => field.source === "PROMPT");
  if (promptEnabled && prompt.length > Math.min(MAX_TEMPLATE_PROMPT, promptField?.maxLength ?? MAX_TEMPLATE_PROMPT)) return t("templates.promptTooLong");
  if (!promptEnabled && imageCount === 0) return t("media.workflow.noPrompt");
  if (imageCount === 0) return null;
  if (!capability) return t("templates.chooseCapability");
  if (workflowDefinition(capability)) {
    const slots = templateImageSlots(capability, { ...fields, prompt: promptEnabled ? prompt : fields.prompt });
    if (imageCount > slots.length) return t("templates.imageLimit", { "0": slots.length });
    if (options.imageSlots.length !== imageCount || options.imageSlots.some((key) => !slots.some((slot) => slot.key === key))
      || new Set(options.imageSlots).size !== imageCount) return t("templates.assignSlots");
    return null;
  }
  let limit = capability.maxReferenceImages;
  if (kind === "VIDEO") {
    const mode = options.videoInputMode;
    if (!mode || mode === "TEXT" || !capability.supportedVideoInputModes.includes(mode)) return t("templates.chooseImageMode");
    if (mode === "START_END") limit = capability.supportsEndFrame ? START_END_LIMIT : 1;
  }
  return imageCount > limit ? t("templates.imageLimit", { "0": limit }) : null;
}

/** A template changes prompt/references only; model, duration and other parameters remain owned by the draft. */
export function templateDraftChanges(kind: MediaTemplateKind, fields: MediaDraftFields,
  capability: MediaCapability | undefined, imported: MediaTemplateImport,
  options: TemplateApplyOptions, colors: readonly string[]): Partial<MediaDraftFields> {
  const invalid = templateApplicationError(kind, fields, capability, imported.images.length, options, imported.prompt);
  if (invalid) throw new Error(invalid);
  const promptEnabled = templatePromptEnabled(capability, fields);
  const changes: Partial<MediaDraftFields> = promptEnabled ? { prompt: imported.prompt, mentions: [] } : {};
  if (imported.images.length === 0) return changes;
  const definition = workflowDefinition(capability);
  changes.mediaInputs = imported.images.map((image, index) => ({ versionId: image.versionId,
    role: !definition && kind === "VIDEO" && options.videoInputMode === "START_END"
      ? index === 0 ? "START_FRAME" : "END_FRAME" : "REFERENCE",
    color: colors[index % colors.length]! }));
  if (!promptEnabled) Object.assign(changes, promptForMediaInputs(fields, changes.mediaInputs));
  if (kind === "VIDEO") changes.videoInputMode = definition ? "GENERAL_REFERENCE" : options.videoInputMode;
  if (definition) {
    const dynamicValues = workflowDraftValues(capability, fields);
    // Replaced references must not linger in dynamic fields after leaving the reference list.
    for (const field of definition.fields) {
      if (["IMAGE", "VIDEO", "AUDIO"].includes(field.type)) delete dynamicValues[field.key];
    }
    imported.images.forEach((image, index) => { dynamicValues[options.imageSlots[index]!] = image.versionId; });
    changes.parameters = { ...fields.parameters, dynamicValues };
  }
  return changes;
}
