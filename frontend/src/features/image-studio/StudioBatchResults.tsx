import { DownloadSimple, ImageSquare } from "@/shared/ui/icons";
import type { CSSProperties } from "react";
import { assetContentUrl } from "../../shared/api/client";
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

export function StudioBatchResults({
  batch,
  onPreview,
}: {
  batch: ImageStudioBatch;
  onPreview: (preview: StudioPreview) => void;
}) {
  const slotCount = batch.tasks.reduce((count, task) => count + Math.max(1, task.results.length), 0);
  let resultNumber = 0;
  return (
    <>
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

      <div className="image-studio-result-grid" data-single={slotCount === 1}>
        {batch.tasks.map((task, taskIndex) => {
          if (!task.results.length) {
            return (
              <article
                className="image-studio-task-card"
                key={task.id}
                aria-label={t("imageStudio.taskLabel", { "0": taskIndex + 1 })}
              >
                <ImageSquare size={32} aria-hidden="true" />
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
                {task.errorCode ? <p>{task.errorCode}</p> : null}
              </article>
            );
          }
          return task.results.map(result => {
            const index = ++resultNumber;
            return (
              <div className="image-studio-result-cell" key={result.artifactVersionId}>
                <article
                  className="image-studio-result-card"
                  style={{ "--studio-result-ratio": result.width / result.height } as CSSProperties}
                >
                  <Button
                    type="button"
                    variant="ghost"
                    className="image-studio-image-button"
                    aria-label={t("imageStudio.previewImage", { "0": index })}
                    onClick={() => onPreview({ result, projectId: batch.projectId, index })}
                  >
                    <img
                      src={assetContentUrl(batch.projectId, result.assetId)}
                      width={result.width}
                      height={result.height}
                      alt={t("imageStudio.resultImage", { "0": index })}
                    />
                  </Button>
                  <footer>
                    <span>
                      {result.width} × {result.height}
                    </span>
                    <Button asChild variant="ghost" size="icon-sm">
                      <a
                        href={assetContentUrl(batch.projectId, result.assetId)}
                        download={downloadFilename(index, result.contentType)}
                        aria-label={t("imageStudio.downloadImage", { "0": index })}
                      >
                        <DownloadSimple aria-hidden="true" />
                      </a>
                    </Button>
                  </footer>
                </article>
              </div>
            );
          });
        })}
      </div>
    </>
  );
}
