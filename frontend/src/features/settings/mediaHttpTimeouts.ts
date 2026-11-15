import type { MediaCapability } from "../../shared/api/client";
import { t } from "../../shared/i18n";

export type MediaHttpTimeouts = NonNullable<MediaCapability["settings"]["httpTimeouts"]>;
export type MediaHttpTimeoutDraft = Record<keyof MediaHttpTimeouts, string>;
export const HTTP_TIMEOUT_FIELDS = ["connectSeconds", "writeSeconds", "readSeconds", "callSeconds"] as const;
const PHASE_FIELDS = ["connectSeconds", "writeSeconds", "readSeconds"] as const;
export const HTTP_TIMEOUT_MAX_SECONDS = {
  connectSeconds: 120,
  writeSeconds: 3600,
  readSeconds: 3600,
  callSeconds: 3600,
} as const;

export function supportsMediaHttpTimeouts(adapterId: string) {
  return Boolean(adapterId) && !adapterId.startsWith("LOCAL_") && !adapterId.startsWith("MOCK_");
}

/** Displaying server defaults does not mark a legacy capability's policy as edited. */
export function mediaHttpTimeoutDraft(policy?: MediaHttpTimeouts): MediaHttpTimeoutDraft {
  return {
    connectSeconds: policy ? String(policy.connectSeconds) : "",
    writeSeconds: policy ? String(policy.writeSeconds) : "",
    readSeconds: policy ? String(policy.readSeconds) : "",
    callSeconds: policy ? String(policy.callSeconds) : "",
  };
}

export function mediaHttpTimeoutErrors(draft: MediaHttpTimeoutDraft) {
  const errors: Partial<Record<keyof MediaHttpTimeouts, string>> = {};
  for (const field of HTTP_TIMEOUT_FIELDS) {
    const value = Number(draft[field]);
    if (!draft[field].trim() || !Number.isInteger(value) || value < 1 || value > HTTP_TIMEOUT_MAX_SECONDS[field]) {
      errors[field] = t("settings.httpTimeouts.integerRange", {
        "0": t(`settings.httpTimeouts.${field}`),
        "1": HTTP_TIMEOUT_MAX_SECONDS[field],
      });
    }
  }
  if (!errors.callSeconds) {
    for (const field of PHASE_FIELDS) {
      if (!errors[field] && Number(draft[field]) > Number(draft.callSeconds)) {
        errors[field] = t("settings.httpTimeouts.phaseExceedsCall", { "0": t(`settings.httpTimeouts.${field}`) });
      }
    }
  }
  return errors;
}

/** Called only after validation; raw input stays in the editor when submission is rejected. */
export function mediaHttpTimeoutPolicy(draft: MediaHttpTimeoutDraft): MediaHttpTimeouts {
  return {
    connectSeconds: Number(draft.connectSeconds),
    writeSeconds: Number(draft.writeSeconds),
    readSeconds: Number(draft.readSeconds),
    callSeconds: Number(draft.callSeconds),
  };
}
