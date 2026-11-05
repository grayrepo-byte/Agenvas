import type { MediaCapability, MediaConnection } from "./api/client";
import { t, translate, type MessageKey } from "./i18n";

const INSTALLED_NAMES: Readonly<Record<string, MessageKey>> = {
  "00000000-0000-4000-8000-000000000201": "settings.adapters.localImage",
  "00000000-0000-4000-8000-000000000202": "settings.adapters.localImage",
  "00000000-0000-4000-8000-000000000203": "settings.adapters.localVideoDepth",
  "00000000-0000-4000-8000-000000000204": "settings.localCapabilities.videoAudio",
  "00000000-0000-4000-8000-000000000205": "settings.localCapabilities.composition",
  "00000000-0000-4000-8000-000000000206": "settings.localCapabilities.audioTrim",
};

/** Translate only a known installed name; renamed services and capabilities remain literal. */
export function mediaDisplayName(value: Pick<MediaCapability | MediaConnection, "id" | "name">): string {
  const key = INSTALLED_NAMES[value.id];
  return key && value.name === translate("zh", key) ? t(key) : value.name;
}
