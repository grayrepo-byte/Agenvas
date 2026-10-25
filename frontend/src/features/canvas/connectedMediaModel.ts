import type { Artifact, MediaCapability, MediaSettings } from "../../shared/api/client";
import { preferredImageVideoMode, type MediaDraftFields } from "./mediaDraftCapability";
import { activeWorkflowMediaFields, workflowDefinition } from "./workflowDraft";

export type ConnectedMediaModel = Pick<MediaDraftFields, "capabilityId" | "parameters" | "durationSeconds" | "videoInputMode">;

function connectionFields(capability: MediaCapability, kind: Artifact["kind"], sourceKind: Artifact["kind"]): ConnectedMediaModel | null {
  const fields: ConnectedMediaModel = { capabilityId: capability.id,
    parameters: capability.settings.defaultParameters ?? {},
    durationSeconds: kind === "VIDEO" ? capability.settings.defaultDurationSeconds ?? null : null,
    videoInputMode: kind === "VIDEO" ? "GENERAL_REFERENCE" : null };
  const definition = workflowDefinition(capability);
  // Declared slots are authoritative, including their default-dependent conditions.
  if (definition) return activeWorkflowMediaFields(definition, fields.parameters.dynamicValues ?? {}, "", fields.durationSeconds)
    .some((field) => field.type === sourceKind) ? fields : null;
  if (sourceKind === "IMAGE") {
    if (capability.maxReferenceImages < 1 || kind === "AUDIO" && fields.parameters.speaker) return null;
    if (kind === "VIDEO") {
      if (!capability.supportedVideoInputModes.some((mode) => mode === "GENERAL_REFERENCE" || mode === "START_END")) return null;
      fields.videoInputMode = preferredImageVideoMode(capability);
    }
    return fields;
  }
  if (sourceKind === "AUDIO") {
    if (kind === "IMAGE" || capability.maxReferenceAudios < (kind === "AUDIO" && fields.parameters.speaker ? 2 : 1)) return null;
  } else if (sourceKind === "VIDEO") {
    if (kind !== "VIDEO" || capability.maxReferenceVideos < 1) return null;
  } else return null;
  return kind !== "VIDEO" || capability.supportedVideoInputModes.includes("GENERAL_REFERENCE") ? fields : null;
}

/** New connected nodes prefer the configured default only when it accepts the source media. */
export function connectedMediaModel(settings: MediaSettings, kind: Artifact["kind"], sourceKind: Artifact["kind"]): ConnectedMediaModel | null {
  if (kind === "TEXT") return null;
  const mediaKind = `${kind}_GENERATION`;
  const candidates = settings.connections.filter((connection) => connection.enabled && connection.platform !== "LOCAL")
    .flatMap((connection) => connection.capabilities.filter((capability) => capability.enabled && capability.kind === mediaKind));
  const defaultId = settings.defaults.find((item) => item.kind === mediaKind)?.capabilityId;
  const preferred = candidates.find((capability) => capability.id === defaultId);
  for (const capability of preferred ? [preferred, ...candidates.filter((item) => item.id !== defaultId)] : candidates) {
    const fields = connectionFields(capability, kind, sourceKind);
    if (fields) return fields;
  }
  return null;
}
