import type { Task } from "../../shared/api/client";

export const MEDIA_TASK_REFRESH_INTERVAL_MS = 3_000;
const RUNNING_STATUSES: ReadonlySet<Task["status"]> = new Set([
  "READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER",
]);

export function isMediaTaskRunning(task: Task): boolean {
  return RUNNING_STATUSES.has(task.status);
}

/** UNKNOWN and accepted-but-blocked requests still occupy the card, without a running animation. */
export function occupiesMediaCard(task: Task): boolean {
  return isMediaTaskRunning(task) || task.status === "UNKNOWN"
    || task.status === "BLOCKED" && Boolean(task.providerRequestId);
}

export function latestMediaTask(tasks: readonly Task[] | undefined): Task | undefined {
  return tasks?.find(occupiesMediaCard) ?? tasks?.[0];
}
