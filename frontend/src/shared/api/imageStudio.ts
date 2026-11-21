import type { components } from "./schema";
import { ApiError, readJson, writeJson } from "./client";
import { t } from "../i18n";

export type ImageStudioContext = components["schemas"]["ImageStudioContext"];
export type ImageStudioCapability = components["schemas"]["ImageStudioCapability"];
export type ImageStudioParameters = components["schemas"]["ImageStudioParameters"];
export type ImageStudioBatch = components["schemas"]["ImageStudioBatch"];
export type ImageStudioTask = components["schemas"]["ImageStudioTask"];
export type ImageStudioResult = components["schemas"]["ImageStudioResult"];
export type ImageStudioReference = components["schemas"]["ImageStudioReference"];
export type CreateImageStudioBatch = components["schemas"]["CreateImageStudioBatch"];
export const IMAGE_STUDIO_COUNTS = [1, 2, 4] as const;
export const IMAGE_STUDIO_POLL_MS = 1_500;
const MAX_IMAGE_BYTES = 20 * 1024 * 1024;
const BASE = "/api/v1/image-studio";
const ACTIVE_STATUSES = new Set<ImageStudioTask["status"]>(["READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER"]);

export function getImageStudioContext(): Promise<ImageStudioContext> {
  return readJson(`${BASE}/context`, t("imageStudio.loadFailed"));
}
export function listImageStudioBatches(before?: string): Promise<components["schemas"]["ImageStudioBatchPage"]> {
  const query = new URLSearchParams({ limit: "20" });
  if (before) query.set("before", before);
  return readJson(`${BASE}/batches?${query}`, t("imageStudio.historyFailed"));
}
export function getImageStudioBatch(id: string): Promise<ImageStudioBatch> {
  return readJson(`${BASE}/batches/${encodeURIComponent(id)}`, t("imageStudio.statusFailed"));
}
/** Retrying acceptance always uses the original immutable command and key. */
export function createImageStudioBatch(command: {
  key: string;
  request: CreateImageStudioBatch;
}): Promise<ImageStudioBatch> {
  return writeJson(
    `${BASE}/batches`,
    {
      method: "POST",
      headers: { "Idempotency-Key": command.key },
      body: JSON.stringify(command.request),
    },
    t("imageStudio.submitFailed"),
  );
}
export function uploadImageStudioReference(file: File): Promise<ImageStudioReference> {
  if (file.size > MAX_IMAGE_BYTES)
    return Promise.reject(new ApiError(413, "ASSET_TOO_LARGE", t("api.errors.imageSizeLimit"), false));
  const form = new FormData();
  form.append("file", file);
  return writeJson(`${BASE}/references`, { method: "POST", body: form }, t("imageStudio.uploadFailed"));
}
/** Uncertain and blocked tasks require an explicit user decision; polling never resubmits. */
export function imageStudioBatchActive(batch: ImageStudioBatch): boolean {
  return batch.tasks.some(imageStudioTaskActive);
}
export function imageStudioTaskActive(task: ImageStudioTask): boolean {
  return ACTIVE_STATUSES.has(task.status);
}
