import type { Task } from "../../shared/api/client";

export const MEDIA_TASK_REFRESH_INTERVAL_MS = 3_000;
const RUNNING_STATUSES: ReadonlySet<Task["status"]> = new Set([
  "READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER",
]);

export function isMediaTaskRunning(task: Task): boolean {
  return RUNNING_STATUSES.has(task.status);
}

/** The server-authored composition input distinguishes local work from Provider generation. */
export function isVideoCompositionTask(task: Task): boolean {
  const composition = task.input.videoComposition;
  return task.kind === "VIDEO_GENERATION" && composition != null
    && typeof composition === "object" && !Array.isArray(composition);
}

export function isAudioTrimTask(task: Task): boolean {
  const trim = task.input.audioTrim;
  return task.kind === "AUDIO_GENERATION" && trim != null
    && typeof trim === "object" && !Array.isArray(trim);
}

/** UNKNOWN and accepted-but-blocked requests still occupy the card, without a running animation. */
export function occupiesMediaCard(task: Task): boolean {
  return isMediaTaskRunning(task) || task.status === "UNKNOWN"
    || task.status === "BLOCKED" && Boolean(task.providerRequestId);
}

export function latestMediaTask(tasks: readonly Task[] | undefined): Task | undefined {
  return tasks?.find(occupiesMediaCard) ?? tasks?.[0];
}
