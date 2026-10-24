import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { t, useLocale } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { VideoReviewControls } from "./VideoReviewControls";

export type VideoReviewRange = { startMs: number; endMs: number };

/** Prepare an exact interval; this component never edits or uploads media bytes. */
export function VideoRangeEditor({ src, posterSrc, title, startMs, endMs, onChange, disabled = false, studio = false, controlsContainer }: {
  src: string; posterSrc?: string; title?: string; startMs: number; endMs: number;
  onChange: (range: VideoReviewRange) => void; disabled?: boolean;
  studio?: boolean;
  /** A studio can place trim controls in its inspector while keeping one player mounted across tabs. */
  controlsContainer?: HTMLElement | null;
}) {
  useLocale();
  const id = useId();
  const [video, setVideo] = useState<HTMLVideoElement | null>(null);
  const [durationMs, setDurationMs] = useState(0);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const previewing = useRef(false);
  useEffect(() => () => { video?.pause(); }, [video]);
  const invalid = !Number.isFinite(startMs) || !Number.isFinite(endMs) || startMs < 0 || endMs <= startMs
    || durationMs > 0 && endMs > durationMs;
  const ready = !disabled && durationMs > 0 && !failed;
  const fields = <fieldset disabled={disabled}>
      <FieldGroup className="video-range-fields">
        <Field data-invalid={invalid}><FieldLabel htmlFor={`${id}-start`}>{t("video.review.start")}</FieldLabel>
          <Input id={`${id}-start`} type="number" min={0} max={durationMs / 1000 || undefined} step={0.001} value={Number.isFinite(startMs) ? startMs / 1000 : ""}
            aria-invalid={invalid} onChange={(event) => onChange({ startMs: Math.round(event.target.valueAsNumber * 1000), endMs })} /></Field>
        <Field data-invalid={invalid}><FieldLabel htmlFor={`${id}-end`}>{t("video.review.end")}</FieldLabel>
          <Input id={`${id}-end`} type="number" min={0} max={durationMs / 1000 || undefined} step={0.001} value={Number.isFinite(endMs) ? endMs / 1000 : ""}
            aria-invalid={invalid} onChange={(event) => onChange({ startMs, endMs: Math.round(event.target.valueAsNumber * 1000) })} /></Field>
      </FieldGroup>
      {invalid ? <FieldError>{t("video.review.rangeInvalid")}</FieldError> : null}
      <div className="video-range-actions">
        <Button type="button" size={studio ? "sm" : "default"} variant="outline" disabled={!ready} onClick={() => onChange({ startMs: Math.round((video?.currentTime ?? 0) * 1000), endMs })}>{t("video.review.setStart")}</Button>
        <Button type="button" size={studio ? "sm" : "default"} variant="outline" disabled={!ready} onClick={() => onChange({ startMs, endMs: Math.round((video?.currentTime ?? 0) * 1000) })}>{t("video.review.setEnd")}</Button>
        <Button type="button" size={studio ? "sm" : "default"} variant="secondary" disabled={!ready || invalid} onClick={() => {
          if (!video) return;
          video.currentTime = startMs / 1000; previewing.current = true;
          void video.play().catch(() => { setFailed(true); previewing.current = false; });
        }}>{t("video.review.rangePreview")}</Button>
      </div>
    </fieldset>;
  return <div className={studio ? "video-range-editor video-range-editor--studio" : "video-range-editor"}>
    <video key={`${src}:${attempt}`} ref={setVideo} src={src} poster={posterSrc} aria-label={title}
      controls={!studio} playsInline preload="metadata" onLoadedMetadata={(event) => {
        const duration = event.currentTarget.duration;
        setDurationMs(Number.isFinite(duration) ? Math.round(duration * 1000) : 0); setFailed(false);
      }} onError={() => { setFailed(true); previewing.current = false; }} onTimeUpdate={(event) => {
        if (previewing.current && event.currentTarget.currentTime * 1000 >= endMs) {
          event.currentTarget.pause(); event.currentTarget.currentTime = endMs / 1000; previewing.current = false;
        }
      }} />
    {failed ? <Alert variant="destructive"><AlertDescription>{t("media.preview.loadFailed")}
      <Button type="button" variant="outline" onClick={() => { setFailed(false); setDurationMs(0); setAttempt((value) => value + 1); }}>{t("media.preview.retry")}</Button>
    </AlertDescription></Alert> : null}
    <VideoReviewControls key={`review:${src}:${attempt}`} video={video} markers={false} compact={studio} disabled={disabled} />
    {controlsContainer === undefined ? fields : controlsContainer ? createPortal(fields, controlsContainer) : null}
  </div>;
}
