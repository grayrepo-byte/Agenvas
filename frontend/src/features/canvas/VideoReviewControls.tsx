import { ArrowLeft, ArrowRight, BookmarkSimple, Pause, Play, SpeakerHigh, SpeakerSlash, X } from "@/shared/ui/icons";
import { useEffect, useId, useState } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import "./VideoReviewControls.css";

const PLAYBACK_RATES = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 2];
const STEP_FRAME_RATES = [24, 25, 30, 60];
const SYNC_TOLERANCE_SECONDS = 0.08;
const END_TOLERANCE_SECONDS = 0.005;
const MILLISECONDS_PER_SECOND = 1000;

export function videoTimeLabel(seconds: number): string {
  const millis = Math.max(0, Math.round(seconds * MILLISECONDS_PER_SECOND));
  return `${String(Math.floor(millis / 60000)).padStart(2, "0")}:${String(Math.floor(millis / 1000) % 60).padStart(2, "0")}.${String(millis % 1000).padStart(3, "0")}`;
}

function playableDuration(video: HTMLVideoElement | null): number {
  return video && Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0;
}

/** Review state belongs to this mounted review, never to a media draft or immutable result. */
export function VideoReviewControls({ video, partner = null, markers = true }: {
  video: HTMLVideoElement | null; partner?: HTMLVideoElement | null; markers?: boolean;
}) {
  useLocale();
  const id = useId();
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [muted, setMuted] = useState(video?.muted ?? false);
  const [frameRate, setFrameRate] = useState(30);
  const [error, setError] = useState(false);
  const [note, setNote] = useState("");
  const [points, setPoints] = useState<{ id: number; seconds: number; note: string }[]>([]);

  useEffect(() => {
    if (!video) return;
    let active = true;
    let animation: number | undefined;
    const videos = partner ? [video, partner] : [video];
    function pause() { videos.forEach((element) => element.pause()); setPlaying(false); }
    function failed() { if (active) { pause(); setError(true); } }
    function update() {
      const lengths = videos.map(playableDuration);
      const limit = lengths.every((length) => length > 0) ? Math.min(...lengths) : 0;
      setDuration(limit);
      setPosition(video!.currentTime);
      setPlaying(!video!.paused && !video!.ended);
      setRate(video!.playbackRate);
      setMuted(video!.muted);
      if (partner && limit > 0) {
        if (video!.currentTime >= limit - END_TOLERANCE_SECONDS && !video!.paused) {
          pause(); video!.currentTime = limit; partner.currentTime = limit;
        } else if (Math.abs(partner.currentTime - video!.currentTime) > SYNC_TOLERANCE_SECONDS) {
          partner.currentTime = Math.min(video!.currentTime, limit);
        }
        partner.playbackRate = video!.playbackRate;
        partner.muted = true;
      }
    }
    function tick() {
      update();
      if (active && !video!.paused) animation = window.requestAnimationFrame(tick);
    }
    function playPartner() {
      setError(false);
      if (partner) {
        partner.currentTime = Math.min(video!.currentTime, playableDuration(partner));
        partner.playbackRate = video!.playbackRate;
        partner.muted = true;
        void partner.play().catch(failed);
      }
      if (animation !== undefined) window.cancelAnimationFrame(animation);
      animation = window.requestAnimationFrame(tick);
      update();
    }
    function pausePartner() { partner?.pause(); update(); }
    const events = ["timeupdate", "durationchange", "loadedmetadata", "seeking", "ratechange", "volumechange"];
    events.forEach((name) => video.addEventListener(name, update));
    video.addEventListener("play", playPartner);
    video.addEventListener("pause", pausePartner);
    video.addEventListener("ended", pause);
    videos.forEach((element) => element.addEventListener("error", failed));
    partner?.addEventListener("loadedmetadata", update);
    update();
    return () => {
      active = false;
      if (animation !== undefined) window.cancelAnimationFrame(animation);
      events.forEach((name) => video.removeEventListener(name, update));
      video.removeEventListener("play", playPartner);
      video.removeEventListener("pause", pausePartner);
      video.removeEventListener("ended", pause);
      videos.forEach((element) => element.removeEventListener("error", failed));
      partner?.removeEventListener("loadedmetadata", update);
      // A close or source switch must not leave either player audible in the background.
      videos.forEach((element) => element.pause());
    };
  }, [video, partner]);

  function seek(value: number) {
    const next = Math.min(duration, Math.max(0, value));
    if (video) video.currentTime = next;
    if (partner) partner.currentTime = next;
    setPosition(next);
  }
  function step(direction: -1 | 1) {
    video?.pause(); partner?.pause(); setPlaying(false);
    seek((video?.currentTime ?? position) + direction / frameRate);
  }
  async function togglePlayback() {
    if (!video) return;
    if (!video.paused) { video.pause(); partner?.pause(); setPlaying(false); return; }
    if (video.currentTime >= duration - END_TOLERANCE_SECONDS) seek(0);
    setError(false);
    const results = await Promise.allSettled((partner ? [video, partner] : [video]).map((element) => element.play()));
    if (results.some((result) => result.status === "rejected")) {
      video.pause(); partner?.pause(); setPlaying(false); setError(true);
    } else setPlaying(true);
  }
  const ready = Boolean(video) && duration > 0;
  return <div className="video-review-controls nodrag nowheel nopan" onClick={(event) => event.stopPropagation()}
    onPointerDown={(event) => event.stopPropagation()} onKeyDown={(event) => { if (event.key !== "Tab") event.stopPropagation(); }}>
    <div className="video-review-transport">
      <Button type="button" variant="outline" size="icon-sm" disabled={!ready} aria-label={t(playing ? "video.review.pause" : "video.review.play")}
        onClick={() => void togglePlayback()}>{playing ? <Pause /> : <Play />}</Button>
      <Button type="button" variant="ghost" size="icon-sm" disabled={!ready} aria-label={t("video.review.previousFrame")} onClick={() => step(-1)}><ArrowLeft /></Button>
      <Button type="button" variant="ghost" size="icon-sm" disabled={!ready} aria-label={t("video.review.nextFrame")} onClick={() => step(1)}><ArrowRight /></Button>
      <input type="range" aria-label={t("video.review.progress")} min={0} max={duration} step={0.001}
        disabled={!ready} value={Math.min(position, duration)} aria-valuetext={`${videoTimeLabel(position)} / ${videoTimeLabel(duration)}`}
        onChange={(event) => seek(Number(event.target.value))} />
      <span className="video-review-time">{videoTimeLabel(position)} / {videoTimeLabel(duration)}</span>
      <Button type="button" variant="ghost" size="icon-sm" disabled={!video} aria-label={t(muted ? "video.review.unmute" : "video.review.mute")}
        onClick={() => { if (video) { video.muted = !video.muted; setMuted(video.muted); } }}>{muted ? <SpeakerSlash /> : <SpeakerHigh />}</Button>
    </div>
    <FieldGroup className="video-review-options">
      <Field><FieldLabel htmlFor={`${id}-speed`}>{t("video.review.speed")}</FieldLabel>
        <Select id={`${id}-speed`} value={rate} disabled={!video} onChange={(event) => {
          const next = Number(event.target.value); if (video) video.playbackRate = next; if (partner) partner.playbackRate = next; setRate(next);
        }}>{PLAYBACK_RATES.map((value) => <option key={value} value={value}>{value}×</option>)}</Select></Field>
      <Field><FieldLabel htmlFor={`${id}-frame-rate`}>{t("video.review.frameRate")}</FieldLabel>
        <Select id={`${id}-frame-rate`} value={frameRate} aria-describedby={`${id}-frame-hint`} onChange={(event) => setFrameRate(Number(event.target.value))}>
          {STEP_FRAME_RATES.map((value) => <option key={value} value={value}>{value} fps</option>)}</Select></Field>
    </FieldGroup>
    <p className="video-review-hint" id={`${id}-frame-hint`}>{t("video.review.frameHint")}</p>
    {error ? <Alert variant="destructive"><AlertDescription>{t("video.review.playFailed")}</AlertDescription></Alert> : null}
    {markers ? <>
      <FieldGroup className="video-review-marker-form"><Field><FieldLabel htmlFor={`${id}-note`}>{t("video.review.note")}</FieldLabel>
        <Input id={`${id}-note`} maxLength={160} value={note} onChange={(event) => setNote(event.target.value)} /></Field>
        <Button type="button" variant="outline" disabled={!ready} onClick={() => {
          setPoints((current) => [...current, { id: current.reduce((max, point) => Math.max(max, point.id), 0) + 1,
            seconds: video?.currentTime ?? position, note: note.trim() }]); setNote("");
        }}><BookmarkSimple data-icon="inline-start" />{t("video.review.addMarker")}</Button></FieldGroup>
      <p className="video-review-hint">{t("video.review.markerHint")}</p>
      <ul className="video-review-markers" aria-label={t("video.review.markers")}>
        {points.map((point) => <li key={point.id}><Button type="button" variant="ghost" disabled={!ready}
          aria-label={`${videoTimeLabel(point.seconds)} ${point.note}`} onClick={() => seek(point.seconds)}>
          <time>{videoTimeLabel(point.seconds)}</time><span>{point.note}</span></Button>
          <Button type="button" variant="ghost" size="icon-sm" aria-label={t("video.review.removeMarker", { "0": videoTimeLabel(point.seconds) })}
            onClick={() => setPoints((current) => current.filter((value) => value.id !== point.id))}><X /></Button></li>)}
      </ul>
    </> : null}
  </div>;
}
