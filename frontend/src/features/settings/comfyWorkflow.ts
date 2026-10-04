import type { ComfyUiWorkflowDefinition } from "../../shared/api/client";
import { t } from "../../shared/i18n";
export const COMFY_WORKFLOW_LIMITS = {
  jsonBytes: 256 * 1024, nodes: 256, bindings: 128, references: 14,
  minimumSide: 8, maximumSide: 4096, fps: 120, frameMultiple: 64, seconds: 30,
} as const;
export function isComfyAdapter(adapterId: string) { return adapterId === "COMFY_IMAGE_V1" || adapterId === "COMFY_VIDEO_V1"; }

export function comfyReferenceCount(definition: ComfyUiWorkflowDefinition) {
  return Math.max(0, ...definition.bindings.filter((binding) => binding.source === "REFERENCE_IMAGE").map((binding) => (binding.referenceIndex ?? 0) + 1));
}

/** Mirrors the publish invariants for immediate feedback; the server remains authoritative. */
export function comfyWorkflowProblem(definition: ComfyUiWorkflowDefinition | undefined, video: boolean): string | null {
  if (!definition) return t("settings.comfy.importRequired");
  if (!definition.graph[definition.output.nodeId]) return t("settings.comfy.outputRequired");
  const bounded = (value: number, min: number, max: number) => Number.isInteger(value) && value >= min && value <= max;
  const limits = COMFY_WORKFLOW_LIMITS;
  if (!bounded(definition.width, limits.minimumSide, limits.maximumSide) || !bounded(definition.height, limits.minimumSide, limits.maximumSide)
    || !bounded(definition.fps, 1, limits.fps) || !bounded(definition.frameMultiple, 1, limits.frameMultiple)
    || !bounded(definition.frameOffset, 0, definition.frameMultiple - 1)
    || !bounded(definition.minimumSeconds, video ? 1 : 0, video ? limits.seconds : 0)
    || !bounded(definition.maximumSeconds, definition.minimumSeconds, video ? limits.seconds : 0)) return t("settings.comfy.boundsRequired");
  const sources = new Set(definition.bindings.map((binding) => binding.source));
  if (!sources.has("PROMPT")) return t("settings.comfy.promptRequired");
  if (sources.has("WIDTH") !== sources.has("HEIGHT")) return t("settings.comfy.dimensionsRequired");
  if (video && (!sources.has("DURATION_SECONDS") && !sources.has("FRAME_COUNT") || sources.has("FRAME_COUNT") && !sources.has("FPS"))) return t("settings.comfy.durationRequired");
  const indices = new Set(definition.bindings.filter((binding) => binding.source === "REFERENCE_IMAGE").map((binding) => binding.referenceIndex));
  if ([...indices].some((index) => index == null || index < 0 || index >= limits.references) || indices.size !== comfyReferenceCount(definition)) return t("settings.comfy.referencesRequired");
  const ancestors = new Set<string>();
  function visit(id: string) {
    if (ancestors.has(id)) return;
    ancestors.add(id);
    for (const input of Object.values(definition?.graph[id]?.inputs ?? {})) {
      if (Array.isArray(input) && input.length === 2 && typeof input[0] === "string" && typeof input[1] === "number") visit(input[0]);
    }
  }
  visit(definition.output.nodeId);
  if (definition.bindings.some((binding) => !ancestors.has(binding.nodeId))) return t("settings.comfy.ancestryRequired");
  return null;
}
