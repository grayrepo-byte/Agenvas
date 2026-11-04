import type { MediaCapability } from "../../shared/api/client";
import { klingOutputCount, klingResolutionTier } from "../../shared/kling";
import { nativeOutputCount, nativeResolutionTier, nativeValue } from "../../shared/nativeMedia";
import type { MediaDraftFields } from "./mediaDraftCapability";
import { workflowDraftValues } from "./workflowDraft";

type PricingInputs = { imageCount: number; durationSeconds: number | null | undefined; videoResolution?: string };

/** Preview and batch confirmation must interpret the same saved provider inputs.
 * Reference billing and provider-selected durations stay unknown without their metadata.
 */
export function mediaDraftPricingInputs(capability: MediaCapability | undefined, fields: MediaDraftFields,
  fallback: PricingInputs, motionDurationMs?: number | null): PricingInputs {
  const native = capability?.settings.nativeInputs;
  const kling = capability?.settings.klingInputs;
  const values = workflowDraftValues(capability, fields);
  const durationSeconds = capability?.settings.operation === "MOTION_CONTROL"
    ? motionDurationMs ? Math.ceil(motionDurationMs / 1000) : null
    : native && capability && (capability.settings.operation === "EXTEND"
      || nativeValue(capability, values, "smart_duration") === true
      || capability.adapterId === "WAN_VIDEO" && fields.mediaInputs.some((input) => input.role === "VIDEO_REFERENCE"))
      ? null : fallback.durationSeconds;
  return { durationSeconds,
    imageCount: native && capability ? nativeOutputCount(capability, values)
      : kling && capability ? klingOutputCount(capability, values)
        : capability?.settings.runningHub ? 1 : fallback.imageCount,
    videoResolution: native && capability ? nativeResolutionTier(capability, values)
      : kling && capability ? klingResolutionTier(capability, values) : fallback.videoResolution };
}
