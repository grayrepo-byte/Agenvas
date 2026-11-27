import {
  ArrowsOutSimple,
  ArrowClockwise,
  CheckCircle,
  DownloadSimple,
  FilmStrip,
  WarningCircle,
} from "@/shared/ui/icons";
import { useCallback, useId, useRef, useState, type CSSProperties, type ReactNode } from "react";
import { assetContentUrl, assetThumbnailUrl } from "../../shared/api/client";
import type { components } from "../../shared/api/schema";
import { formatNumber, t, useLocale, type MessageKey } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia } from "../../shared/ui/primitives/empty";
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

function ArchivedVideo({
  result,
  generation,
  restoreAction,
}: {
  result: VideoResult;
  generation: Generation;
  restoreAction?: ReactNode;
}) {
  const [attempt, setAttempt] = useState(0);
  const [mediaState, setMediaState] = useState<"loading" | "failed" | HTMLVideoElement>("loading");
  const [measuredRatio, setMeasuredRatio] = useState<number>();
  const [buffering, setBuffering] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const playbackBar = useRef<HTMLDivElement>(null);
  const retryButton = useRef<HTMLButtonElement>(null);
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
  const requestedRatio = generation.aspectRatio === "9:16" ? 9 / 16 : generation.aspectRatio === "1:1" ? 1 : 16 / 9;
  const ratio = result.width && result.height ? result.width / result.height : (measuredRatio ?? requestedRatio);
  const title = t("videoStudio.resultTitle");

  function reload() {
    setBuffering(false);
    setMediaState("loading");
    setAttempt(previous => previous + 1);
  }

  function failPreview() {
    // Only replace focus when its control is about to disappear; an error must not interrupt editing elsewhere.
    if (playbackBar.current?.contains(document.activeElement)) retryButton.current?.focus({ preventScroll: true });
    setBuffering(false);
    setMediaState("failed");
  }

  function ready(video: HTMLVideoElement) {
    if ((!result.width || !result.height) && video.videoWidth && video.videoHeight)
      setMeasuredRatio(video.videoWidth / video.videoHeight);
    setMediaState(video);
  }

  return (
    <>
      <div className="video-studio-player">
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
              onLoadedMetadata={event => ready(event.currentTarget)}
              onCanPlay={event => {
                ready(event.currentTarget);
                setBuffering(false);
              }}
              onWaiting={event => setBuffering(!event.currentTarget.paused)}
              onPlaying={() => setBuffering(false)}
              onPause={() => setBuffering(false)}
              onEnded={() => setBuffering(false)}
              onError={failPreview}
            />
          )}
          {buffering ? (
            <div className="video-studio-buffering">
              <LoadingState compact label={t("videoStudio.previewBuffering")} />
            </div>
          ) : null}
        </div>
        <div ref={playbackBar} className="video-studio-playback-bar">
          {mediaState === "loading" ? (
            <LoadingState compact label={t("videoStudio.previewLoading")} />
          ) : mediaState === "failed" ? (
            <Alert variant="destructive">
              <WarningCircle aria-hidden="true" />
              <AlertDescription>{t("videoStudio.previewFailed")}</AlertDescription>
            </Alert>
          ) : (
            <VideoReviewControls video={mediaState} compact markers={false} disabled={expanded} />
          )}
        </div>
      </div>
      <div className="video-studio-result-actions">
        {restoreAction ? <span className="video-studio-result-restore">{restoreAction}</span> : null}
        <Button
          ref={retryButton}
          type="button"
          variant={mediaState === "failed" ? "outline" : "ghost"}
          size={mediaState === "failed" ? "sm" : "icon-sm"}
          title={t("videoStudio.previewReload")}
          aria-label={t("videoStudio.previewReload")}
          onClick={reload}
        >
          <ArrowClockwise data-icon={mediaState === "failed" ? "inline-start" : undefined} aria-hidden="true" />
          {mediaState === "failed" ? t("videoStudio.previewReload") : null}
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

export function VideoStudioResults({
  generation,
  restoreAction,
}: {
  generation?: Generation | undefined;
  restoreAction?: ReactNode;
}) {
  useLocale();
  const headingId = useId();
  const [selectedId, setSelectedId] = useState<string>();

  if (!generation) {
    return (
      <section className="video-studio-results" aria-labelledby={headingId}>
        <Empty className="video-studio-result-empty">
          <EmptyMedia className="video-studio-viewfinder" aria-hidden="true">
            <FilmStrip />
          </EmptyMedia>
          <EmptyHeader>
            <h2 id={headingId}>{t("videoStudio.resultTitle")}</h2>
            <EmptyDescription>{t("videoStudio.emptyPreviewHint")}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      </section>
    );
  }

  const { status, results, errorCode } = generation.task;
  const active = results.find(result => result.artifactVersionId === selectedId) ?? results[0];
  // Pin the first visible archive too: late results may reorder the list while this player is in use.
  if (active && active.artifactVersionId !== selectedId) setSelectedId(active.artifactVersionId);
  const pending = ACTIVE_STATUSES[status] && !active;
  const taskLabel = t(STATUS_LABELS[status]);
  const mediaDetails = [
    active?.width && active.height ? `${active.width} × ${active.height}` : null,
    active?.durationMs != null
      ? t("agent.approval.seconds", { 0: formatNumber(active.durationMs / 1000, { maximumFractionDigits: 2 }) })
      : null,
  ].filter(Boolean);

  return (
    <section className="video-studio-results" data-has-video={!!active} aria-labelledby={headingId}>
      <div className="video-studio-result-header">
        <h2 id={headingId}>{t("videoStudio.resultTitle")}</h2>
        {!pending && !(active && status === "SUCCEEDED") ? (
          <span role="status">
            <Badge variant={status === "FAILED" || status === "BLOCKED" ? "destructive" : "outline"}>{taskLabel}</Badge>
          </span>
        ) : null}
        {active ? (
          <span role="status" className="video-studio-result-saved">
            <CheckCircle aria-hidden="true" />
            {t("videoStudio.resultSaved")}
          </span>
        ) : null}
        {pending ? <LoadingState compact label={taskLabel} /> : null}
        {mediaDetails.length ? <span className="video-studio-result-specs">{mediaDetails.join(" · ")}</span> : null}
        {active && results.length > 1 ? (
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
        {!active && restoreAction ? <span className="video-studio-status-restore">{restoreAction}</span> : null}
      </div>
      {pending && generation.prompt ? (
        <p className="video-studio-pending-prompt" title={generation.prompt}>
          {generation.prompt}
        </p>
      ) : null}
      {errorCode || status === "UNKNOWN" ? (
        <Alert variant={status === "UNKNOWN" ? "default" : "destructive"}>
          <WarningCircle aria-hidden="true" />
          <AlertDescription>
            {status === "UNKNOWN" ? <p>{t("imageStudio.unknownHint")}</p> : null}
            {errorCode ? (
              <>
                {taskErrorMessage(errorCode)}
                <details className="video-studio-error-details">
                  <summary>{t("imageStudio.errorDetails")}</summary>
                  <code>{errorCode}</code>
                </details>
              </>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      {active ? (
        <ArchivedVideo
          key={active.artifactVersionId}
          result={active}
          generation={generation}
          restoreAction={restoreAction}
        />
      ) : !pending ? (
        <div className="video-studio-result-footer">
          <span>{t("videoStudio.noResults")}</span>
        </div>
      ) : null}
    </section>
  );
}
