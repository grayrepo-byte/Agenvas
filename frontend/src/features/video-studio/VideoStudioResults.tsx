import { ArrowsOutSimple, ArrowClockwise, DownloadSimple, FilmStrip, WarningCircle } from "@/shared/ui/icons";
import { useCallback, useId, useState, type CSSProperties } from "react";
import { assetContentUrl, assetThumbnailUrl } from "../../shared/api/client";
import type { components } from "../../shared/api/schema";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyHeader, EmptyMedia, EmptyTitle } from "../../shared/ui/primitives/empty";
import { MediaPreviewDialog } from "../canvas/MediaPreviewDialog";
import { VideoReviewControls } from "../canvas/VideoReviewControls";
import { taskErrorMessage } from "../canvas/taskErrorMessages";
import "./VideoStudioResults.css";

type Generation = components["schemas"]["VideoStudioGeneration"];
type VideoResult = components["schemas"]["VideoStudioResult"];
type TaskStatus = Generation["task"]["status"];

const STATUS_LABELS: Record<TaskStatus, MessageKey> = {
  READY: "tasks.status.queued",
  RUNNING: "tasks.status.running",
  SUBMITTING: "logs.calls.submitting",
  WAITING_PROVIDER: "logs.calls.waitingForExternal",
  UNKNOWN: "tasks.status.unknown",
  BLOCKED: "tasks.status.blocked",
  SUCCEEDED: "common.succeeded",
  FAILED: "common.failed",
  CANCELED: "common.canceled",
};

const ACTIVE_STATUSES: Partial<Record<TaskStatus, true>> = {
  READY: true,
  RUNNING: true,
  SUBMITTING: true,
  WAITING_PROVIDER: true,
};
const DOWNLOAD_EXTENSIONS: Readonly<Partial<Record<string, string>>> = {
  "video/mp4": "mp4",
  "video/webm": "webm",
  "video/quicktime": "mov",
};

function ArchivedVideo({ result, generation }: { result: VideoResult; generation: Generation }) {
  const [attempt, setAttempt] = useState(0);
  const [mediaState, setMediaState] = useState<"loading" | "failed" | HTMLVideoElement>("loading");
  const [expanded, setExpanded] = useState(false);
  const attachVideo = useCallback((video: HTMLVideoElement | null) => {
    if (!video) return;
    return () => {
      // A detached media element can keep playing while React replaces it.
      if (!video.paused) video.pause();
    };
  }, []);
  const originalUrl = assetContentUrl(generation.projectId, result.assetId);
  const poster = assetThumbnailUrl(generation.projectId, result.assetId);
  const extension = DOWNLOAD_EXTENSIONS[result.contentType];
  const [requestedWidth = 16, requestedHeight = 9] = generation.aspectRatio.split(":").map(Number);
  const ratio = result.width && result.height ? result.width / result.height : requestedWidth / requestedHeight;
  const title = t("videoStudio.resultTitle");

  function reload() {
    setMediaState("loading");
    setAttempt(previous => previous + 1);
  }

  return (
    <>
      <div className="video-studio-preview" style={{ "--video-studio-preview-ratio": ratio } as CSSProperties}>
        {mediaState === "failed" ? (
          <img src={poster} alt="" />
        ) : (
          <video
            key={attempt}
            ref={attachVideo}
            className="video-studio-inline-video"
            aria-label={title}
            src={attempt === 0 ? originalUrl : `${originalUrl}?previewRetry=${attempt}`}
            poster={poster}
            playsInline
            preload="metadata"
            onLoadedMetadata={event => setMediaState(event.currentTarget)}
            onCanPlay={event => setMediaState(event.currentTarget)}
            onError={() => setMediaState("failed")}
          />
        )}
      </div>
      {mediaState === "loading" ? <LoadingState compact label={t("videoStudio.previewLoading")} /> : null}
      {mediaState === "failed" ? (
        <Alert variant="destructive">
          <WarningCircle aria-hidden="true" />
          <AlertDescription>{t("videoStudio.previewFailed")}</AlertDescription>
        </Alert>
      ) : null}
      {typeof mediaState !== "string" ? (
        <VideoReviewControls video={mediaState} compact markers={false} disabled={expanded} />
      ) : null}
      <div className="video-studio-result-actions">
        <Button type="button" variant="ghost" size="sm" onClick={reload}>
          <ArrowClockwise data-icon="inline-start" aria-hidden="true" />
          {t("videoStudio.previewReload")}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => {
            if (typeof mediaState !== "string" && !mediaState.paused) mediaState.pause();
            setExpanded(true);
          }}
        >
          <ArrowsOutSimple data-icon="inline-start" aria-hidden="true" />
          {t("videoStudio.expand")}
        </Button>
        <Button asChild variant="outline" size="sm">
          <a href={originalUrl} download={extension ? `video-${result.assetId}.${extension}` : true}>
            <DownloadSimple data-icon="inline-start" aria-hidden="true" />
            {t("videoStudio.download")}
          </a>
        </Button>
      </div>
      {expanded ? (
        <MediaPreviewDialog
          kind="video"
          title={title}
          sourceUrl={originalUrl}
          posterSrc={poster}
          contentType={result.contentType}
          width={result.width ?? undefined}
          height={result.height ?? undefined}
          onClose={() => setExpanded(false)}
        />
      ) : null}
    </>
  );
}

export function VideoStudioResults({ generation }: { generation?: Generation | undefined }) {
  useLocale();
  const headingId = useId();
  const [selectedId, setSelectedId] = useState<string>();
  const task = generation?.task;
  const results = task?.results;
  const active = results?.find(result => result.artifactVersionId === selectedId) ?? results?.[0];
  const pending = task && ACTIVE_STATUSES[task.status] && !active;
  const taskError = task?.errorCode;
  const taskLabel = task ? t(STATUS_LABELS[task.status]) : undefined;

  return (
    <section className="video-studio-results" aria-labelledby={headingId}>
      <div className="video-studio-result-header">
        <h2 id={headingId}>{t("videoStudio.resultTitle")}</h2>
        {taskLabel && !pending ? (
          <span role="status">
            <Badge variant={task?.status === "FAILED" || task?.status === "BLOCKED" ? "destructive" : "outline"}>
              {taskLabel}
            </Badge>
          </span>
        ) : null}
        {active ? <span className="video-studio-result-saved">{t("videoStudio.resultSaved")}</span> : null}
      </div>
      {taskError ? (
        <Alert variant="destructive">
          <WarningCircle aria-hidden="true" />
          <AlertDescription>
            {taskErrorMessage(taskError)}
            <code>{taskError}</code>
          </AlertDescription>
        </Alert>
      ) : null}
      {active && generation ? (
        <>
          {results && results.length > 1 ? (
            <Select
              className="video-studio-result-select"
              aria-label={t("videoStudio.resultTitle")}
              density="compact"
              value={active.artifactVersionId}
              onChange={event => setSelectedId(event.target.value)}
            >
              {results.map((result, index) => (
                <option key={result.artifactVersionId} value={result.artifactVersionId}>
                  {t("videoStudio.resultPosition", { "0": index + 1, "1": results.length })}
                </option>
              ))}
            </Select>
          ) : null}
          <ArchivedVideo key={active.artifactVersionId} result={active} generation={generation} />
        </>
      ) : pending && taskLabel ? (
        <div className="video-studio-result-placeholder">
          <LoadingState label={taskLabel} />
        </div>
      ) : (
        <Empty className="video-studio-result-placeholder">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <FilmStrip aria-hidden="true" />
            </EmptyMedia>
            <EmptyTitle>{t("videoStudio.noResults")}</EmptyTitle>
          </EmptyHeader>
        </Empty>
      )}
    </section>
  );
}
