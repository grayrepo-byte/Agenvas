import { t } from "../i18n";
import { ApiError, assetContentUrl, readJson, writeJson } from "./client";
import type { components } from "./schema";

type Schemas = components["schemas"];
export type VideoStudioCapability = Schemas["VideoStudioCapability"];
export type VideoStudioContext = Schemas["VideoStudioContext"];
export type VideoStudioFrame = Schemas["VideoStudioFrame"];
export type VideoStudioReferenceAsset = Schemas["VideoStudioReferenceAsset"];
export type VideoStudioGeneration = Schemas["VideoStudioGeneration"];
export type VideoStudioGenerationPage = Schemas["VideoStudioGenerationPage"];
export type VideoStudioCommand = { key: string; request: Schemas["CreateVideoStudioGeneration"] };

const BASE = "/api/v1/video-studio";
const MAX_FRAME_BYTES = 20 * 1024 * 1024;

export function getVideoStudioContext(signal?: AbortSignal): Promise<VideoStudioContext> {
  return readJson(`${BASE}/context`, t("videoStudio.loadFailed"), signal);
}

export function listVideoStudioGenerations(before?: string, signal?: AbortSignal): Promise<VideoStudioGenerationPage> {
  const query = new URLSearchParams({ limit: "20" });
  if (before) query.set("before", before);
  return readJson(`${BASE}/generations?${query}`, t("imageStudio.historyFailed"), signal);
}

export function getVideoStudioGeneration(id: string, signal?: AbortSignal): Promise<VideoStudioGeneration> {
  return readJson(`${BASE}/generations/${encodeURIComponent(id)}`, t("imageStudio.statusFailed"), signal);
}

export function acceptVideoStudioGeneration(command: VideoStudioCommand): Promise<VideoStudioGeneration> {
  return writeJson(
    `${BASE}/generations`,
    {
      method: "POST",
      headers: { "Idempotency-Key": command.key },
      body: JSON.stringify(command.request),
    },
    t("imageStudio.submitFailed"),
  );
}

export function uploadVideoStudioFrame(file: File): Promise<VideoStudioFrame> {
  if (file.size > MAX_FRAME_BYTES)
    return Promise.reject(new ApiError(413, "ASSET_TOO_LARGE", t("api.errors.imageSizeLimit"), false));
  const form = new FormData();
  form.append("file", file);
  return writeJson(`${BASE}/frames`, { method: "POST", body: form }, t("videoStudio.uploadFailed"));
}

export function uploadVideoStudioReference(
  kind: VideoStudioReferenceAsset["kind"],
  file: File,
): Promise<VideoStudioReferenceAsset> {
  const form = new FormData();
  form.append("file", file);
  return writeJson(`${BASE}/references/${kind}`, { method: "POST", body: form }, t("media.runningHub.uploadFailed"));
}

export async function checkVideoStudioFrame(frame: Pick<VideoStudioFrame, "projectId" | "assetId">): Promise<void> {
  const response = await fetch(assetContentUrl(frame.projectId, frame.assetId), {
    method: "HEAD",
    credentials: "same-origin",
    cache: "no-store",
    redirect: "error",
  });
  if (!response.ok) throw new Error(t("videoStudio.restoreFrameFailed"));
}
