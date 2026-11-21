import {
  imageStudioTaskActive,
  type ImageStudioBatch,
  type ImageStudioResult,
  type ImageStudioTask,
} from "../../shared/api/imageStudio";
import type { MessageKey } from "../../shared/i18n";

export const ARCHIVE_RETRY_CODE = "STUDIO_IMAGE_ARCHIVE_RETRY";
export const ARCHIVE_MISSING_CODE = "STUDIO_IMAGE_ARCHIVE_MISSING";
const STATUS_LABELS = {
  READY: "imageStudio.status.ready",
  RUNNING: "imageStudio.status.running",
  SUBMITTING: "imageStudio.status.submitting",
  WAITING_PROVIDER: "imageStudio.status.waiting",
  UNKNOWN: "imageStudio.status.unknown",
  BLOCKED: "imageStudio.status.blocked",
  SUCCEEDED: "imageStudio.status.succeeded",
  FAILED: "imageStudio.status.failed",
  CANCELED: "imageStudio.status.canceled",
} as const satisfies Record<ImageStudioTask["status"], MessageKey>;

export function taskStatus(task: ImageStudioTask) {
  if (task.errorCode === ARCHIVE_RETRY_CODE) return "imageStudio.status.archiving";
  if (task.errorCode === ARCHIVE_MISSING_CODE) return "imageStudio.status.archiveMissing";
  return STATUS_LABELS[task.status];
}

export function batchStatus(batch: ImageStudioBatch) {
  const unresolved = batch.tasks.find(task => task.status === "UNKNOWN" || task.status === "BLOCKED");
  if (unresolved) return taskStatus(unresolved);
  const active = batch.tasks.filter(imageStudioTaskActive);
  const working = active.find(task => task.status !== "READY") ?? active[0];
  if (working) return taskStatus(working);
  const incomplete = batch.tasks.find(task => task.status === "FAILED" || task.status === "CANCELED");
  if (incomplete && batch.tasks.some(task => task.results.length)) return "imageStudio.status.partial";
  const task = incomplete ?? batch.tasks[0];
  return task ? taskStatus(task) : "imageStudio.emptyTitle";
}

export type StudioStatus =
  ReturnType<typeof batchStatus> | "imageStudio.sendingRequest" | "imageStudio.acceptanceUncertain";
export type StudioSlot = { key: string; status: StudioStatus; result?: ImageStudioResult };
export type StudioPreview = { result: ImageStudioResult; projectId: string; index: number };

/** The server orders tasks by request ordinal. Each owns a slot even if a later task finishes first.
 * Native extra outputs follow all primary slots, so they cannot displace another requested image. */
export function studioGallery(batch: ImageStudioBatch, preferredVersion?: string) {
  const slots: StudioSlot[] = batch.tasks.map(task => ({
    key: task.id,
    status: taskStatus(task),
    result: task.results[0],
  }));
  for (const task of batch.tasks) {
    for (const result of task.results.slice(1)) {
      slots.push({ key: result.artifactVersionId, status: taskStatus(task), result });
    }
  }
  const ready = slots.filter(slot => slot.result);
  const selected = ready.find(slot => slot.result?.artifactVersionId === preferredVersion) ?? ready[0];
  const active: StudioPreview | undefined = selected?.result && {
    result: selected.result,
    projectId: batch.projectId,
    index: slots.indexOf(selected) + 1,
  };
  return { slots, readyCount: ready.length, active };
}

export function batchReference(id: string) {
  return `#${id.slice(0, 8).toUpperCase()}`;
}
