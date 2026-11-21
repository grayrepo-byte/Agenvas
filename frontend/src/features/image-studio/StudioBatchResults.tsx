import {
  Aperture,
  Archive,
  ArrowsOutSimple,
  CheckCircle,
  Clock,
  ClockCounterClockwise,
  DownloadSimple,
  FilmStrip,
  MinusCircle,
  Question,
  WarningCircle,
} from "@/shared/ui/icons";
import { useState, type CSSProperties } from "react";
import { assetContentUrl, assetThumbnailUrl } from "../../shared/api/client";
import type { CreateImageStudioBatch, ImageStudioBatch } from "../../shared/api/imageStudio";
import { t } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import {
  ARCHIVE_MISSING_CODE,
  ARCHIVE_RETRY_CODE,
  batchReference,
  batchStatus,
  studioGallery,
  taskStatus,
  type StudioPreview,
  type StudioSlot,
  type StudioStatus,
} from "./studioGallery";

const DOWNLOAD_EXTENSIONS: Readonly<Record<string, string>> = {
  "image/png": "png",
  "image/jpeg": "jpg",
  "image/webp": "webp",
};
function downloadFilename(index: number, contentType: string) {
  const extension = DOWNLOAD_EXTENSIONS[contentType];
  return extension ? `image-${index}.${extension}` : `image-${index}`;
}

function statusAppearance(status: StudioStatus) {
  switch (status) {
    case "imageStudio.status.ready":
      return { tone: "working", Icon: Clock };
    case "imageStudio.status.archiving":
      return { tone: "working", Icon: Archive };
    case "imageStudio.acceptanceUncertain":
    case "imageStudio.status.unknown":
      return { tone: "warning", Icon: Question };
    case "imageStudio.status.blocked":
    case "imageStudio.status.archiveMissing":
    case "imageStudio.status.partial":
      return { tone: "warning", Icon: WarningCircle };
    case "imageStudio.status.failed":
      return { tone: "danger", Icon: WarningCircle };
    case "imageStudio.status.canceled":
    case "imageStudio.emptyTitle":
      return { tone: "idle", Icon: MinusCircle };
    case "imageStudio.status.succeeded":
      return { tone: "complete", Icon: CheckCircle };
    default:
      return { tone: "working", Icon: Aperture };
  }
}

function BatchNotices({ batch }: { batch: ImageStudioBatch }) {
  const unknown = batch.tasks.some(task => task.status === "UNKNOWN");
  const blocked = batch.tasks.some(task => task.status === "BLOCKED");
  const archiving = batch.tasks.some(task => task.errorCode === ARCHIVE_RETRY_CODE);
  const missing = batch.tasks.some(task => task.errorCode === ARCHIVE_MISSING_CODE);
  if (!unknown && !blocked && !batch.tasks.some(task => task.errorCode)) return null;
  return (
    <div className="image-studio-task-feedback">
      {unknown ? (
        <Notice tone="warning" title={t("imageStudio.status.unknown")}>
          <p>{t("imageStudio.unknownHint")}</p>
        </Notice>
      ) : null}
      {archiving ? (
        <Notice title={t("imageStudio.archiveRetryTitle")}>
          <p>{t("imageStudio.archiveRetryHint")}</p>
        </Notice>
      ) : null}
      {missing ? (
        <Notice tone="warning" title={t("imageStudio.archiveMissingTitle")}>
          <p>{t("imageStudio.archiveMissingHint")}</p>
        </Notice>
      ) : blocked ? (
        <Notice tone="warning" title={t("imageStudio.status.blocked")}>
          <p>{t("imageStudio.blockedHint")}</p>
        </Notice>
      ) : null}
      {batch.tasks.map((task, index) =>
        task.errorCode ? (
          <p className="image-studio-task" key={task.id}>
            <strong>{t("imageStudio.taskLabel", { "0": index + 1 })}</strong>
            <span>{t(taskStatus(task))}</span>
            <code>{task.errorCode}</code>
          </p>
        ) : null,
      )}
    </div>
  );
}

function GenerationFrame({ status, prompt, format }: { status: StudioStatus; prompt: string; format: string }) {
  const { tone, Icon } = statusAppearance(status);
  return (
    <div className="image-studio-generation-frame" data-tone={tone}>
      <span className="image-studio-frame-corners" aria-hidden="true" />
      <span className="image-studio-frame-format">{format}</span>
      <div className="image-studio-frame-content">
        <span className="image-studio-iris" aria-hidden="true">
          <Icon weight="duotone" />
        </span>
        <h2>{t(status)}</h2>
        <p title={prompt}>{prompt}</p>
      </div>
    </div>
  );
}

function ResultPhoto({ preview }: { preview: StudioPreview }) {
  const { result, projectId, index } = preview;
  const [state, setState] = useState<"loading" | "ready" | "failed">("loading");
  return (
    <span
      className="image-studio-photo"
      style={{ backgroundImage: `url("${assetThumbnailUrl(projectId, result.assetId)}")` }}
    >
      <img
        src={assetContentUrl(projectId, result.assetId)}
        width={result.width}
        height={result.height}
        alt={t("imageStudio.resultImage", { "0": index })}
        data-loaded={state === "ready"}
        onLoad={() => setState("ready")}
        onError={() => setState("failed")}
      />
      {state === "failed" ? (
        <span className="image-studio-photo-error">{t("media.card.previewUnavailable")}</span>
      ) : null}
    </span>
  );
}

export type StudioResultsView =
  | { batch: ImageStudioBatch; gallery: ReturnType<typeof studioGallery> }
  | { request: CreateImageStudioBatch; pending: boolean };

export function StudioBatchResults({
  view,
  onChoose,
  onPreview,
  onRestore,
  locked,
}: {
  view: StudioResultsView;
  onChoose: (batchId: string, version: string) => void;
  onPreview: (preview: StudioPreview) => void;
  onRestore: (batch: ImageStudioBatch) => void;
  locked: boolean;
}) {
  const batch = "batch" in view ? view.batch : undefined;
  const { parameters, prompt } = "batch" in view ? view.batch : view.request;
  const status: StudioStatus =
    "batch" in view
      ? batchStatus(view.batch)
      : view.pending
        ? "imageStudio.sendingRequest"
        : "imageStudio.acceptanceUncertain";
  const gallery = "gallery" in view ? view.gallery : undefined;
  const active = gallery?.active;
  const slots: StudioSlot[] =
    gallery?.slots ?? Array.from({ length: parameters.generationCount ?? 1 }, (_, i) => ({ key: String(i), status }));
  const ratio = parameters.aspectRatio && parameters.aspectRatio !== "AUTO" ? parameters.aspectRatio : "1:1";
  const aspect = active ? active.result.width / active.result.height : ratio.replace(":", " / ");
  const format = [ratio, parameters.resolution].filter(Boolean).join(" · ");
  const { tone } = statusAppearance(status);
  return (
    <>
      {batch ? <BatchNotices batch={batch} /> : null}
      <div className="image-studio-hero" style={{ "--studio-result-ratio": aspect } as CSSProperties}>
        {active ? (
          <Button
            className="image-studio-image-button"
            variant="ghost"
            type="button"
            data-version-id={active.result.artifactVersionId}
            aria-label={t("imageStudio.previewImage", { "0": active.index })}
            onClick={() => onPreview(active)}
          >
            <ResultPhoto key={active.result.artifactVersionId} preview={active} />
            <span className="image-studio-photo-index" aria-hidden="true">
              {String(active.index).padStart(2, "0")} / {String(slots.length).padStart(2, "0")}
            </span>
          </Button>
        ) : (
          <GenerationFrame status={status} prompt={prompt} format={format} />
        )}
      </div>
      <div className="image-studio-gallery">
        <div className="image-studio-gallery-caption">
          <div className="image-studio-gallery-summary" role="status" aria-atomic="true">
            <FilmStrip aria-hidden="true" />
            <strong>
              {gallery
                ? t("imageStudio.readyCount", { "0": gallery.readyCount, "1": slots.length })
                : t("imageStudio.countLabel", { "0": slots.length })}
            </strong>
            <span className={active ? "image-studio-gallery-status" : "sr-only"} data-tone={tone}>
              {t(status)}
            </span>
          </div>
          {batch ? (
            <span className="image-studio-batch-reference" title={batch.id}>
              {t("imageStudio.batchReference", { "0": batchReference(batch.id) })}
            </span>
          ) : null}
        </div>
        <div className="image-studio-result-tools">
          <div className="image-studio-filmstrip" role="group" aria-label={t("imageStudio.variants")}>
            {slots.map((slot, index) => {
              const number = String(index + 1).padStart(2, "0");
              const item = slot.result;
              if (item && batch) {
                const label = t("imageStudio.chooseVariant", { "0": index + 1 });
                return (
                  <Button
                    key={slot.key}
                    type="button"
                    variant="ghost"
                    className="image-studio-variant"
                    data-version-id={item.artifactVersionId}
                    aria-pressed={item.artifactVersionId === active?.result.artifactVersionId}
                    aria-label={label}
                    title={label}
                    onClick={() => onChoose(batch.id, item.artifactVersionId)}
                  >
                    <img src={assetThumbnailUrl(batch.projectId, item.assetId)} alt="" />
                    <span className="image-studio-slot-number" aria-hidden="true">
                      {number}
                    </span>
                  </Button>
                );
              }
              const { tone: slotTone, Icon } = statusAppearance(slot.status);
              const label = t("imageStudio.slotStatus", { "0": index + 1, "1": t(slot.status) });
              return (
                <span
                  key={slot.key}
                  className="image-studio-slot"
                  role="img"
                  aria-label={label}
                  title={label}
                  data-tone={slotTone}
                >
                  <Icon aria-hidden="true" />
                  {active || slot.status !== status ? (
                    <span className="image-studio-slot-status" aria-hidden="true">
                      {t(slot.status)}
                    </span>
                  ) : null}
                  <span className="image-studio-slot-number" aria-hidden="true">
                    {number}
                  </span>
                </span>
              );
            })}
          </div>
          {batch ? (
            <div className="image-studio-result-actions">
              <Button
                className="image-studio-restore"
                variant="ghost"
                size="icon-sm"
                type="button"
                disabled={locked}
                aria-label={t("imageStudio.restore")}
                title={t("imageStudio.restore")}
                onClick={() => onRestore(batch)}
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
          ) : null}
        </div>
      </div>
    </>
  );
}
