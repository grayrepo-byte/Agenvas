import type { Artifact, MediaCapability } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { nativeVersionAssignable } from "../../shared/nativeMedia";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { activeWorkflowMediaFields, workflowDefinition, workflowDraftValues } from "../canvas/workflowDraft";

export type CatalogReferenceContext = {
  fields: MediaDraftFields;
  kind: Artifact["kind"];
  capability?: MediaCapability;
  slotKey?: string | null;
};
/** Catalog selection never changes input mode or replaces existing manual/character contributions. */
export function catalogReferenceUnavailable({
  fields,
  kind,
  capability,
  slotKey,
}: CatalogReferenceContext): string | undefined {
  if (!capability || (kind !== "IMAGE" && kind !== "VIDEO")) return t("reference.capacity");
  const definition = workflowDefinition(capability);
  if (definition) {
    const values = workflowDraftValues(capability, fields);
    const slot = activeWorkflowMediaFields(definition, values, fields.prompt, fields.durationSeconds).find(
      field => field.key === slotKey,
    );
    if (!slot || slot.type !== "IMAGE") return t("reference.ordinaryOnly");
    if (values[slot.key] !== undefined && values[slot.key] !== "") return t("reference.capacity");
  } else if (kind === "VIDEO" && fields.videoInputMode !== "GENERAL_REFERENCE") return t("reference.ordinaryOnly");
  const imageCount = fields.mediaInputs.filter(
    input => input.role !== "AUDIO_REFERENCE" && input.role !== "VIDEO_REFERENCE",
  ).length;
  if (fields.mediaInputs.length >= 14 || imageCount >= capability.maxReferenceImages) return t("reference.capacity");
  return undefined;
}
export function catalogReferenceFields(
  context: CatalogReferenceContext,
  versionId: string,
  color: string,
): MediaDraftFields {
  if (context.fields.mediaInputs.some(input => input.versionId === versionId)) return context.fields;
  const unavailable = catalogReferenceUnavailable(context);
  if (unavailable) throw new Error(unavailable);
  const { fields, capability, slotKey } = context;
  const values = workflowDraftValues(capability, fields);
  if (slotKey && capability?.settings.nativeInputs && !nativeVersionAssignable(capability, values, slotKey, versionId))
    throw new Error(t("media.native.duplicateReference"));
  return {
    ...fields,
    mediaInputs: [...fields.mediaInputs, { versionId, role: "REFERENCE", color }],
    ...(slotKey ? { parameters: { ...fields.parameters, dynamicValues: { ...values, [slotKey]: versionId } } } : {}),
  };
}
