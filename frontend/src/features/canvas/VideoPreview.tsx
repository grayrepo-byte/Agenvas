import { ArrowClockwise,ArrowsOutSimple,SpeakerHigh,SpeakerSlash,VideoCamera } from "@phosphor-icons/react";
import { useEffect,useRef,useState,type SyntheticEvent } from "react";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import "./VideoPreview.css";

const SEEK_STEP_SECONDS = 0.01;
const SECONDS_PER_MINUTE = 60;
const TIME_DIGITS = 2;

function timeLabel(value: number) {
  const seconds = Math.floor(value);
  return `${String(Math.floor(seconds / SECONDS_PER_MINUTE)).padStart(TIME_DIGITS, "0")}:${String(seconds % SECONDS_PER_MINUTE).padStart(TIME_DIGITS, "0")}`;
}

function isolateControl(event: SyntheticEvent) { event.stopPropagation(); }

/** Hover loads and plays the original; only the picture's clicks reach node selection. */
export function VideoPreview({ src, posterSrc, title, demo }: {
  src: string; posterSrc: string; title: string; demo: boolean;
}) {
  useLocale();
  const video = useRef<HTMLVideoElement>(null);
  const [activated, setActivated] = useState(false);
  const [hovered, setHovered] = useState(false);
  const [posterFailed, setPosterFailed] = useState(false);
  const [playbackFailed, setPlaybackFailed] = useState(false);
  const [buffering, setBuffering] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [duration, setDuration] = useState(0);
  const [position, setPosition] = useState(0);
  const [muted, setMuted] = useState(false);

  useEffect(() => {
    const element = video.current;
    if (!element) return;
    let active = true;
    if (hovered) {
      void element.play().catch((error: unknown) => {
        // Leaving or replacing the result can abort a pending play without a media failure.
        if (!active || (error instanceof DOMException && error.name === "AbortError")) return;
        setPlaybackFailed(true);
        setBuffering(false);
      });
    } else element.pause();
    return () => { active = false; element.pause(); };
  }, [hovered, attempt, playbackFailed]);

  function retryPlayback() {
    setPlaybackFailed(false);
    setBuffering(true);
    setDuration(0);
    setPosition(0);
    setAttempt((value) => value + 1);
  }

  return <div className={`media-card-preview video-card-preview${hovered ? " is-hovered" : ""}`}
    onMouseEnter={() => { if (!activated) setBuffering(true); setActivated(true); setHovered(true); }}
    onMouseLeave={() => setHovered(false)}>
    {activated && !playbackFailed ? <video key={attempt} ref={video} className="nowheel nopan"
      aria-label={t("media.card.videoLabel", { "0": title })} src={src} poster={posterSrc}
      muted={muted} loop playsInline preload="metadata"
      onLoadedMetadata={(event) => {
        const value = event.currentTarget.duration;
        setDuration(Number.isFinite(value) && value > 0 ? value : 0);
      }}
      onTimeUpdate={(event) => setPosition(event.currentTarget.currentTime)}
      onCanPlay={() => setBuffering(false)} onPlaying={() => setBuffering(false)} onWaiting={() => setBuffering(true)}
      onError={() => { setPlaybackFailed(true); setBuffering(false); }} />
      : !posterFailed ? <img alt={t("media.card.mediaLabel", { "0": title, "1": t("media.card.videoPoster") })}
        decoding="async" draggable={false} loading="lazy" onError={() => setPosterFailed(true)} src={posterSrc} />
        : <div className="media-card-empty"><VideoCamera size={36} /><span>{t("media.card.posterUnavailable")}</span>
          <div className="nodrag nowheel nopan" onPointerDown={isolateControl} onMouseDown={isolateControl} onClick={isolateControl}>
            <Button variant="ghost" type="button" className="media-upload-button" onClick={() => setPosterFailed(false)}>
              {t("media.card.retryPreview")}</Button>
          </div>
        </div>}
    {hovered && buffering ? <div className="media-playback-loading">
      <LoadingState compact label={t("media.card.videoLoading")} />
    </div> : null}
    {playbackFailed ? <div className="media-playback-error" role="alert">
      <VideoCamera size={28} /><p>{t("media.card.videoPlaybackFailed")}</p><span>{t("media.card.videoRetryHint")}</span>
      <div className="nodrag nowheel nopan" onPointerDown={isolateControl} onMouseDown={isolateControl}
        onClick={isolateControl} onDoubleClick={isolateControl}>
        <Button variant="ghost" type="button" className="media-upload-button" onClick={retryPlayback}>
          <ArrowClockwise size={15} />{t("media.retryPlayback")}</Button>
      </div>
    </div> : null}
    {activated && !playbackFailed ? <div className="video-preview-controls nodrag nowheel nopan"
      onPointerDown={isolateControl} onMouseDown={isolateControl} onTouchStart={isolateControl}
      onClick={isolateControl} onDoubleClick={isolateControl} onKeyDown={isolateControl}>
      <Button variant="ghost" type="button" className="video-preview-mute" onClick={() => setMuted((value) => !value)}
        aria-label={muted ? t("media.card.unmuteVideo") : t("media.card.muteVideo")}>
        {muted ? <SpeakerSlash size={16} /> : <SpeakerHigh size={16} />}</Button>
      <input className="video-preview-seek" type="range" aria-label={t("media.card.videoProgressLabel")}
        aria-valuetext={`${timeLabel(position)} / ${timeLabel(duration)}`} min={0} max={duration}
        step={SEEK_STEP_SECONDS} value={Math.min(position, duration)} disabled={duration <= 0}
        onChange={(event) => {
          const value = Number(event.currentTarget.value);
          if (video.current) video.current.currentTime = value;
          setPosition(value);
        }} />
      <span className="video-preview-time">{timeLabel(position)} / {timeLabel(duration)}</span>
    </div> : null}
    {demo ? <span className="media-demo-badge">{t("media.card.mockVideo")}</span> : null}
    <a className="media-expand-button nodrag nowheel nopan" href={src}
      aria-label={t("media.card.openVideo")} title={t("media.card.openVideo")} rel="noopener noreferrer" target="_blank"
      onPointerDown={isolateControl} onMouseDown={isolateControl} onClick={isolateControl}>
      <ArrowsOutSimple size={19} /></a>
  </div>;
}
