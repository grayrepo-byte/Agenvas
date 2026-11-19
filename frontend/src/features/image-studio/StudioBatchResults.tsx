import { ArrowsOutSimple, ClockCounterClockwise, DownloadSimple, ImageSquare } from "@/shared/ui/icons";
import type { CSSProperties } from "react";
import { assetContentUrl, assetThumbnailUrl } from "../../shared/api/client";
import type { ImageStudioBatch, ImageStudioResult, ImageStudioTask } from "../../shared/api/imageStudio";
import { t, type MessageKey } from "../../shared/i18n";
import { Notice, StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";

const ARCHIVE_RETRY_CODE = "STUDIO_IMAGE_ARCHIVE_RETRY";
const ARCHIVE_MISSING_CODE = "STUDIO_IMAGE_ARCHIVE_MISSING";
const STATUS_LABELS: Record<ImageStudioTask["status"], MessageKey> = {
  READY: "imageStudio.status.ready",
  RUNNING: "imageStudio.status.running",
  SUBMITTING: "imageStudio.status.submitting",
  WAITING_PROVIDER: "imageStudio.status.waiting",
  UNKNOWN: "imageStudio.status.unknown",
  BLOCKED: "imageStudio.status.blocked",
  SUCCEEDED: "imageStudio.status.succeeded",
  FAILED: "imageStudio.status.failed",
  CANCELED: "imageStudio.status.canceled",
};
function taskStatus(task: ImageStudioTask): MessageKey {
  if (task.errorCode === ARCHIVE_RETRY_CODE) return "imageStudio.status.archiving";
  if (task.errorCode === ARCHIVE_MISSING_CODE) return "imageStudio.status.archiveMissing";
  return STATUS_LABELS[task.status];
}

export function batchStatus(batch: ImageStudioBatch): MessageKey | undefined {
  const unresolved = batch.tasks.find(task => task.status === "UNKNOWN" || task.status === "BLOCKED");
  if (unresolved) return taskStatus(unresolved);
  const active = batch.tasks.find(task => ["READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER"].includes(task.status));
  if (active) return taskStatus(active);
  const incomplete = batch.tasks.find(task => task.status === "FAILED" || task.status === "CANCELED");
  if (incomplete && batch.tasks.some(task => task.results.length > 0)) return "imageStudio.status.partial";
  const task = incomplete ?? batch.tasks[0];
  return task && taskStatus(task);
}

const DOWNLOAD_EXTENSIONS: Readonly<Record<string, string>> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/webp": "webp",
};
function downloadFilename(index: number, contentType: string) {
  const extension = DOWNLOAD_EXTENSIONS[contentType];
  return extension ? `image-${index}.${extension}` : `image-${index}`;
}

export type StudioPreview = { result: ImageStudioResult; projectId: string; index: number };

/** All outputs are private, immutable asset versions, including native multi-output tasks. */
export function studioGallery(batch: ImageStudioBatch, preferredVersion?: string) {
  const results = batch.tasks.flatMap(task => task.results);
  const result = results.find(item => item.artifactVersionId === preferredVersion) ?? results[0];
  return { results, active: result && { result, projectId: batch.projectId, index: results.indexOf(result) + 1 } };
}

export function StudioBatchResults({
  batch,
  gallery,
  onChoose,
  onPreview,
  onRestore,
  locked,
}: {
  batch: ImageStudioBatch;
  gallery: ReturnType<typeof studioGallery>;
  onChoose: (version: string) => void;
  onPreview: (preview: StudioPreview) => void;
  onRestore: () => void;
  locked: boolean;
}) {
  const { results, active } = gallery;
  const status = batchStatus(batch);
  const attention = batch.tasks.some(task => task.status !== "SUCCEEDED" || !task.results.length);
  return (
    <>
      {attention ? (
        <div className="image-studio-task-feedback" role="region" aria-label={t("imageStudio.results")}>
          {status === "imageStudio.status.partial" ? <strong>{t(status)}</strong> : null}
          {batch.tasks.some(task => task.status === "UNKNOWN") ? (
            <Notice tone="warning" title={t("imageStudio.status.unknown")}>
              <p>{t("imageStudio.unknownHint")}</p>
            </Notice>
          ) : null}
          {batch.tasks.some(task => task.errorCode === ARCHIVE_RETRY_CODE) ? (
            <Notice title={t("imageStudio.archiveRetryTitle")}>
              <p>{t("imageStudio.archiveRetryHint")}</p>
            </Notice>
          ) : null}
          {batch.tasks.some(task => task.errorCode === ARCHIVE_MISSING_CODE) ? (
            <Notice tone="warning" title={t("imageStudio.archiveMissingTitle")}>
              <p>{t("imageStudio.archiveMissingHint")}</p>
            </Notice>
          ) : batch.tasks.some(task => task.status === "BLOCKED") ? (
            <Notice tone="warning" title={t("imageStudio.status.blocked")}>
              <p>{t("imageStudio.blockedHint")}</p>
            </Notice>
          ) : null}
          {batch.tasks.map((task, index) =>
            task.status === "SUCCEEDED" && task.results.length ? null : (
              <article
                key={task.id}
                className="image-studio-task"
                aria-label={t("imageStudio.taskLabel", { "0": index + 1 })}
              >
                <span>{t("imageStudio.taskLabel", { "0": index + 1 })}</span>
                <StatusBadge
                  tone={
                    task.status === "FAILED"
                      ? "danger"
                      : task.status === "UNKNOWN" || task.status === "BLOCKED"
                        ? "warning"
                        : "neutral"
                  }
                >
                  {t(taskStatus(task))}
                </StatusBadge>
                {task.errorCode ? <span>{task.errorCode}</span> : null}
              </article>
            ),
          )}
        </div>
      ) : null}
      <div
        className="image-studio-hero"
        style={
          active
            ? ({ "--studio-result-ratio": active.result.width / active.result.height } as CSSProperties)
            : undefined
        }
      >
        {active ? (
          <Button
            className="image-studio-image-button"
            variant="ghost"
            type="button"
            data-version-id={active.result.artifactVersionId}
            aria-label={t("imageStudio.previewImage", { "0": active.index })}
            onClick={() => onPreview(active)}
          >
            <img
              src={assetContentUrl(batch.projectId, active.result.assetId)}
              width={active.result.width}
              height={active.result.height}
              alt={t("imageStudio.resultImage", { "0": active.index })}
            />
          </Button>
        ) : (
          <ImageSquare className="image-studio-awaiting-image" size={40} aria-hidden="true" />
        )}
      </div>
      <div className="image-studio-result-tools">
        {results.length > 0 ? (
          <div className="image-studio-filmstrip" role="group" aria-label={t("imageStudio.variants")}>
            {results.map((item, i) => (
              <Button
                key={item.artifactVersionId}
                type="button"
                variant="ghost"
                className="image-studio-variant"
                data-version-id={item.artifactVersionId}
                aria-pressed={item.artifactVersionId === active?.result.artifactVersionId}
                aria-label={t("imageStudio.chooseVariant", { "0": i + 1 })}
                title={t("imageStudio.chooseVariant", { "0": i + 1 })}
                onClick={() => onChoose(item.artifactVersionId)}
              >
                <img src={assetThumbnailUrl(batch.projectId, item.assetId)} alt="" />
              </Button>
            ))}
          </div>
        ) : null}
        <div className="image-studio-result-actions">
          <Button
            className="image-studio-restore"
            variant="ghost"
            size="icon-sm"
            type="button"
            disabled={locked}
            aria-label={t("imageStudio.restore")}
            title={t("imageStudio.restore")}
            onClick={onRestore}
          >
            <ClockCounterClockwise aria-hidden="true" />
          </Button>
          {active ? (
            <>
              <Button
                type="button"
                variant="ghost"
                size="icon-sm"
                aria-label={t("imageStudio.expandImage")}
                title={t("imageStudio.expandImage")}
                onClick={() => onPreview(active)}
              >
                <ArrowsOutSimple aria-hidden="true" />
              </Button>
              <Button asChild variant="ghost" size="icon-sm">
                <a
                  href={assetContentUrl(batch.projectId, active.result.assetId)}
                  download={downloadFilename(active.index, active.result.contentType)}
                  title={t("imageStudio.downloadImage", { "0": active.index })}
                  aria-label={t("imageStudio.downloadImage", { "0": active.index })}
                >
                  <DownloadSimple aria-hidden="true" />
                </a>
              </Button>
            </>
          ) : null}
        </div>
      </div>
    </>
  );
}
