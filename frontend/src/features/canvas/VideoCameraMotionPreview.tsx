import { ArrowCounterClockwise, Camera, Circle, Pause, Play } from "@/shared/ui/icons";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { t, translate, useLocale } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Range, setRangeValue } from "../../shared/ui/Range";
import { sampleVideoCameraMotion, VIDEO_CAMERA_MOTIONS } from "./videoCameraMotion";
import { disposeVideoCameraPreview, drawVideoCameraPreview, type VideoCameraPreviewPalette } from "./videoCameraPreviewRenderer";
import type { VideoPromptPreset } from "./videoPromptPresets";
import "./VideoCameraMotionPreview.css";

const MOTION_QUERY = "(prefers-reduced-motion: reduce)";

function previewPalette(element: HTMLElement): VideoCameraPreviewPalette {
  const style = getComputedStyle(element);
  const token = (name: string) => style.getPropertyValue(name).trim();
  return {
    background: token("--ui-field"), surface: token("--ui-surface-raised"), grid: token("--ui-border"),
    border: token("--ui-border-strong"), muted: token("--ui-text-subtle"), text: token("--ui-text"),
    accent: token("--ui-accent"), accentLight: token("--ui-accent-text"),
    subject: token("--ui-text-secondary"), secondary: token("--ui-relation-reference"),
  };
}

/** One selected shot owns one local renderer; frame updates never rerender the React canvas. */
export function VideoCameraMotionPreview({ preset }: { preset: VideoPromptPreset }) {
  const locale = useLocale();
  const timelineId = useId();
  const captionId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const frameRef = useRef<HTMLCanvasElement>(null);
  const planRef = useRef<HTMLCanvasElement>(null);
  const sliderRef = useRef<HTMLInputElement>(null);
  const timeRef = useRef<HTMLOutputElement>(null);
  const fovRef = useRef<HTMLElement>(null);
  const heightRef = useRef<HTMLElement>(null);
  const focusRef = useRef<HTMLElement>(null);
  const progressRef = useRef(0);
  const drawRef = useRef<() => void>(() => {});
  const [reducedMotion, setReducedMotion] = useState(() => Boolean(window.matchMedia?.(MOTION_QUERY).matches));
  const [playing, setPlaying] = useState(() => !window.matchMedia?.(MOTION_QUERY).matches);
  const [visible, setVisible] = useState(true);
  const [pageVisible, setPageVisible] = useState(() => !document.hidden);
  const [unavailable, setUnavailable] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const profile = VIDEO_CAMERA_MOTIONS[preset.id];
  const durationSeconds = (profile?.durationMs ?? 6000) / 1000;
  const active = playing && visible && pageVisible && !unavailable && Boolean(profile);

  useEffect(() => {
    const preference = window.matchMedia?.(MOTION_QUERY);
    function changed(event: MediaQueryListEvent) {
      setReducedMotion(event.matches);
      if (event.matches) setPlaying(false);
    }
    preference?.addEventListener?.("change", changed);
    function visibilityChanged() { setPageVisible(!document.hidden); }
    document.addEventListener("visibilitychange", visibilityChanged);
    const observer = typeof IntersectionObserver === "undefined" ? undefined : new IntersectionObserver((entries) => {
      setVisible(entries.some((entry) => entry.isIntersecting));
    });
    if (rootRef.current) observer?.observe(rootRef.current);
    return () => {
      preference?.removeEventListener?.("change", changed);
      document.removeEventListener("visibilitychange", visibilityChanged);
      observer?.disconnect();
    };
  }, []);

  useLayoutEffect(() => {
    function unavailablePreview() { setUnavailable(true); setPlaying(false); }
    const root = rootRef.current;
    const frame = frameRef.current;
    const plan = planRef.current;
    if (!root || !frame || !plan || !profile) { unavailablePreview(); return; }
    let frameContext: CanvasRenderingContext2D | null;
    let planContext: CanvasRenderingContext2D | null;
    try { frameContext = frame.getContext("2d"); planContext = plan.getContext("2d"); }
    catch { unavailablePreview(); return; }
    if (!frameContext || !planContext) { unavailablePreview(); return; }
    const contexts = [frameContext, planContext] as const;
    let palette = previewPalette(root);
    const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 });
    function draw() {
      if (!profile) return;
      const progress = progressRef.current;
      const state = sampleVideoCameraMotion(profile, progress);
      try {
        for (const [index, canvas] of [frame!, plan!].entries()) {
          const rect = canvas.getBoundingClientRect();
          if (!rect.width || !rect.height) continue;
          const ratio = Math.min(window.devicePixelRatio || 1, 2);
          const width = Math.round(rect.width * ratio);
          const height = Math.round(rect.height * ratio);
          if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
          const context = contexts[index]!;
          context.setTransform(ratio, 0, 0, ratio, 0, 0);
          drawVideoCameraPreview(context, rect.width, rect.height, state, profile, progress, palette, index === 0 ? "frame" : "plan");
        }
      } catch { setUnavailable(true); setPlaying(false); return; }
      if (sliderRef.current) {
        setRangeValue(sliderRef.current, Math.round(progress * 1000));
        sliderRef.current.setAttribute("aria-valuetext", translate(locale, "video.presets.preview.progressValue", { value: Math.round(progress * 100) }));
      }
      if (timeRef.current) timeRef.current.textContent = translate(locale, "video.presets.preview.elapsed", {
        current: number.format(progress * durationSeconds), duration: number.format(durationSeconds),
      });
      if (fovRef.current) fovRef.current.textContent = translate(locale, "video.presets.preview.degrees", { value: number.format(state.fov) });
      if (heightRef.current) heightRef.current.textContent = translate(locale, "video.presets.preview.meters", { value: number.format(state.position[1]) });
      if (focusRef.current) focusRef.current.textContent = state.focus === 0 ? translate(locale, "video.presets.preview.deepFocus")
        : translate(locale, "video.presets.preview.meters", { value: number.format(state.focus) });
    }
    drawRef.current = draw;
    draw();
    const resize = new ResizeObserver(draw);
    resize.observe(frame); resize.observe(plan);
    // Refresh semantic colors after an application theme switch without reading styles every frame.
    const theme = new MutationObserver(() => { palette = previewPalette(root); draw(); });
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ["class", "style", "data-theme"] });
    return () => {
      resize.disconnect(); theme.disconnect(); drawRef.current = () => {};
      for (const context of contexts) disposeVideoCameraPreview(context);
    };
  }, [profile, locale, durationSeconds, attempt]);

  useEffect(() => {
    if (!active || !profile) return;
    let frame = 0;
    let previous: number | undefined;
    function tick(time: number) {
      const delta = previous === undefined ? 0 : Math.max(0, Math.min(time - previous, 100));
      previous = time;
      progressRef.current = Math.min(1, progressRef.current + delta / profile!.durationMs);
      drawRef.current();
      if (progressRef.current >= 1) { setPlaying(false); return; }
      frame = requestAnimationFrame(tick);
    }
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [active, profile]);

  function play() {
    if (progressRef.current >= 1) { progressRef.current = 0; drawRef.current(); }
    setPlaying(true);
  }
  return <div ref={rootRef} className="video-camera-preview" role="group" aria-label={t("video.presets.preview.title")}>
    <div className="video-camera-preview-heading"><span><Camera aria-hidden="true" />{t("video.presets.preview.title")}</span>
      <Badge variant="outline">{t(profile?.motion === "locked" ? "video.presets.preview.locked" : "video.presets.preview.moving")}</Badge></div>
    {unavailable ? <Alert role="status"><AlertDescription><span>{t("video.presets.preview.unavailable")}</span>
      <Button type="button" variant="outline" size="sm" onClick={() => { setUnavailable(false); setAttempt((value) => value + 1); }}>{t("video.presets.preview.retry")}</Button></AlertDescription></Alert> : null}
    <div className="video-camera-preview-views" hidden={unavailable}>
      <div className="video-camera-frame"><canvas ref={frameRef} role="img" aria-label={`${t("video.presets.preview.frame")} · ${preset.copy[locale].name}`} aria-describedby={captionId} />
        <span className="video-camera-view-label">{t("video.presets.preview.frame")}</span><span className="video-camera-frame-ratio">16:9</span>
        <div className="video-camera-frame-guides" aria-hidden="true" /></div>
      <div className="video-camera-plan-row"><div className="video-camera-plan"><canvas ref={planRef} role="img" aria-label={`${t("video.presets.preview.plan")} · ${preset.copy[locale].name}`} aria-describedby={captionId} />
        <span className="video-camera-view-label">{t("video.presets.preview.plan")}</span></div>
        <div className="video-camera-measurements"><div className="video-camera-plan-legend"><span><Camera aria-hidden="true" />{t("video.presets.preview.camera")}</span><span><Circle aria-hidden="true" />{t("video.presets.preview.subject")}</span></div>
          <dl><div><dt>{t("video.presets.preview.fov")}</dt><dd><span ref={fovRef}>—</span></dd></div>
            <div><dt>{t("video.presets.preview.height")}</dt><dd><span ref={heightRef}>—</span></dd></div>
            <div><dt>{t("video.presets.preview.focus")}</dt><dd><span ref={focusRef}>—</span></dd></div></dl></div></div>
    </div>
    <div className="video-camera-playback"><Button type="button" variant="ghost" size="icon-sm" disabled={unavailable}
      aria-label={t(playing ? "video.presets.preview.pause" : "video.presets.preview.play")} onClick={() => { if (playing) setPlaying(false); else play(); }}>
      {playing ? <Pause /> : <Play />}</Button>
      <Field className="video-camera-timeline"><FieldLabel htmlFor={timelineId} className="sr-only">{t("video.presets.preview.timeline")}</FieldLabel>
        <Range variant="media" ref={sliderRef} id={timelineId} min={0} max={1000} step={1} defaultValue={0} disabled={unavailable}
          onPointerDown={() => setPlaying(false)} onChange={(event) => { setPlaying(false); progressRef.current = Number(event.target.value) / 1000; drawRef.current(); }} /></Field>
      <output ref={timeRef} role="timer" aria-live="off" htmlFor={timelineId}>{t("video.presets.preview.elapsed", { current: 0, duration: durationSeconds })}</output>
      <Button type="button" variant="ghost" size="icon-sm" disabled={unavailable} aria-label={t("video.presets.preview.replay")}
        onClick={() => { progressRef.current = 0; drawRef.current(); setPlaying(true); }}><ArrowCounterClockwise /></Button></div>
    <p id={captionId} className="video-camera-preview-caption">{t(reducedMotion && !playing ? "video.presets.preview.reducedMotion" : "video.presets.preview.caption")}</p>
  </div>;
}
