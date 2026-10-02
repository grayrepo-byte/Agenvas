import { ImageSquare, MusicNotes, VideoCamera } from "@phosphor-icons/react";
import type { MediaCapability } from "../../shared/api/client";
import { adapterModel } from "../settings/mediaAdapterCatalog";

/** Use configured model identities as descriptions, including custom endpoints. */
export function mediaModelDetails(capability: MediaCapability, connectionName?: string) {
  const model = capability.settings.model || adapterModel(capability.adapterId)
    || capability.settings.checkpoint || capability.settings.diffusionModel || capability.adapterId;
  const Icon = capability.kind === "AUDIO_GENERATION" ? MusicNotes
    : capability.kind === "VIDEO_GENERATION" ? VideoCamera : ImageSquare;
  return { icon: <Icon />, description: [connectionName, model].filter(Boolean).join(" · ") };
}
