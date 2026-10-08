import type { MediaCapability, ThirdPartyPromptEntry, ThirdPartyPromptReference } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { workflowDefinition } from "../canvas/workflowDraft";
import { templateApplicationError, type TemplateApplyOptions } from "./templateApplication";

export function thirdPartyReferences(entry: ThirdPartyPromptEntry): ThirdPartyPromptReference[] {
  return entry.image ? entry.image.referenceImageUrls.map((url) => ({ kind: "IMAGE", role: "REFERENCE", url })) : entry.video?.references ?? [];
}
export function thirdPartyVideoMode(entry: ThirdPartyPromptEntry): TemplateApplyOptions["videoInputMode"] {
  if (!entry.video) return null;
  if (entry.video.videoMode === "text_to_video") return "TEXT";
  return entry.video.references.some((ref) => ref.role === "START_FRAME") ? "START_END" : "GENERAL_REFERENCE";
}
export function thirdPartyApplicationError(entry: ThirdPartyPromptEntry, fields: MediaDraftFields,
  capability: MediaCapability | undefined, imageSlots: string[]): string | null {
  const data = entry.image ?? entry.video;
  if (!data) return t("thirdParty.invalid");
  if (entry.video?.missingReferences?.length) return t("thirdParty.referencesRequired");
  if (entry.video?.videoMode === "text_to_image_to_video") return t("thirdParty.imageStage");
  const refs = thirdPartyReferences(entry);
  const images = refs.filter((ref) => ref.kind === "IMAGE");
  const definition = workflowDefinition(capability);
  if (definition && refs.some((ref) => ref.kind !== "IMAGE")) return t("thirdParty.workflowReferences");
  const mode = thirdPartyVideoMode(entry);
  if (entry.video && !definition && (!capability || !mode || !capability.supportedVideoInputModes.includes(mode))) return t("thirdParty.modeMismatch");
  if (entry.image?.imageMode === "edit" && images.length === 0 && fields.mediaInputs.every((ref) => ref.role !== "REFERENCE")) return t("thirdParty.imageRequired");
  if (capability && (refs.filter((ref) => ref.kind === "VIDEO").length > capability.maxReferenceVideos
    || refs.filter((ref) => ref.kind === "AUDIO").length > capability.maxReferenceAudios)) return t("thirdParty.referenceLimit");
  if (entry.video?.videoMode === "text_to_video" && fields.mediaInputs.length > 0) return t("thirdParty.textModeReferences");
  return templateApplicationError(entry.targetKind, fields, capability, images.length,
    { videoInputMode: mode, imageSlots }, data.prompt);
}
