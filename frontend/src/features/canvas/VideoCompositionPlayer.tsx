import { ArrowLeft, ArrowRight, CircleNotch, Pause, Play, SpeakerHigh, SpeakerSlash } from '@/shared/ui/icons';
import { useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { t, useLocale } from '../../shared/i18n';
import { Alert, AlertDescription } from '../../shared/ui/primitives/alert';
import { Button } from '../../shared/ui/primitives/button';
import { Field, FieldError, FieldGroup, FieldLabel } from '../../shared/ui/primitives/field';
import { Input } from '../../shared/ui/primitives/input';
import { CompositionPlayback, type CompositionPlaybackModel } from './compositionPlayback';
import { COMPOSITION_FPS, compositionClipAt, compositionFade, compositionOutput, compositionSourceTime, frameTimeMs, visibleCompositionCaptions, type CompositionCaption } from './compositionTimeline';
import { videoTimeLabel } from './VideoReviewControls';
import './VideoCompositionPlayer.css';

export function VideoCompositionPlayer({ controller, model, captions, resolution, aspectRatio, disabled = false, fontFamily = 'Noto Sans CJK SC' }: {
  controller: CompositionPlayback; model: CompositionPlaybackModel; captions: readonly CompositionCaption[];
  resolution: '720P' | '1080P'; aspectRatio: '16:9' | '9:16' | '1:1'; disabled?: boolean; fontFamily?: string;
}) {
  useLocale();
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const video = useRef<HTMLVideoElement>(null), music = useRef<HTMLAudioElement>(null), voice = useRef<HTMLAudioElement>(null);
  const area = useRef<HTMLDivElement>(null);
  const [bounds, setBounds] = useState({ width: 0, height: 0 });
  const output = compositionOutput(resolution, aspectRatio);
  const total = frameTimeMs(model.timeline.frames), current = compositionClipAt(model.timeline, state.positionMs);
  useLayoutEffect(() => { controller.configure({ ...model, blocked: disabled || model.blocked }); }, [controller, model, disabled]);
  useEffect(() => {
    if (!video.current || !music.current || !voice.current) return;
    return controller.attach(video.current, music.current, voice.current);
  }, [controller]);
  useLayoutEffect(() => {
    const element = area.current; if (!element) return;
    const measure = () => setBounds({ width: element.clientWidth, height: element.clientHeight });
    measure(); const observer = new ResizeObserver(measure); observer.observe(element); return () => observer.disconnect();
  }, []);
  const scale = Math.max(0, Math.min(bounds.width / output.width, bounds.height / output.height));
  const displayPosition = Math.min(state.positionMs, Math.max(0, total - frameTimeMs(1)));
  const text = visibleCompositionCaptions(captions, displayPosition);
  const ready = !disabled && model.timeline.valid && !model.blocked && !state.error;
  return <div className="video-composition-player">
    <div ref={area} className="video-composition-stage">
      <div className="video-composition-frame" style={{ width: output.width * scale, height: output.height * scale }}>
        <video ref={video} aria-label={t('video.edit.compositionPreview')} playsInline preload="auto" poster={current?.poster}
          style={{ opacity: compositionFade(current, displayPosition) }} />
        <div className="video-composition-captions" aria-live="off" style={{ fontFamily: `"${fontFamily}", sans-serif`, fontSize: Math.max(24, Math.floor(output.height / 24)) * scale,
          bottom: Math.max(24, Math.floor(output.height / 18)) * scale, left: 30 * scale, right: 30 * scale,
          WebkitTextStrokeWidth: 2 * scale, textShadow: `${scale}px ${scale}px ${scale}px var(--ui-video-subtitle-outline)` }}>
          {text.map((line, index) => <p key={index}>{line}</p>)}
        </div>
        {state.buffering ? <div className="video-composition-buffering" role="status"><CircleNotch className="video-edit-spinner" />{t('video.edit.buffering')}</div> : null}
      </div>
    </div>
    <audio ref={music} preload="auto" data-composition-audio="music" /><audio ref={voice} preload="auto" data-composition-audio="voice" />
    <div className="video-composition-transport">
      <Button type="button" variant="outline" size="icon-sm" aria-label={t(state.playing ? 'video.edit.pauseComposition' : 'video.edit.playComposition')} disabled={!ready}
        onClick={controller.toggle}>{state.playing ? <Pause /> : <Play />}</Button>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={t('video.review.previousFrame')} disabled={!ready} onClick={() => { controller.pause(); controller.seek(state.positionMs - frameTimeMs(1)); }}><ArrowLeft /></Button>
      <Button type="button" variant="ghost" size="icon-sm" aria-label={t('video.review.nextFrame')} disabled={!ready} onClick={() => { controller.pause(); controller.seek(state.positionMs + frameTimeMs(1)); }}><ArrowRight /></Button>
      <input type="range" aria-label={t('video.edit.compositionProgress')} min={0} max={model.timeline.frames} step={1} value={Math.min(model.timeline.frames, Math.round(state.positionMs * COMPOSITION_FPS / 1000))} disabled={!ready}
        aria-valuetext={`${videoTimeLabel(state.positionMs / 1000)} / ${videoTimeLabel(total / 1000)}`}
        onChange={(event) => controller.seek(frameTimeMs(event.target.valueAsNumber))} />
      <span className="video-review-time">{videoTimeLabel(state.positionMs / 1000)} / {videoTimeLabel(total / 1000)}</span>
      <Button type="button" variant="ghost" size="icon-sm" disabled={disabled} aria-label={t(state.muted ? 'video.review.unmute' : 'video.review.mute')}
        onClick={() => controller.setMuted(!state.muted)}>{state.muted ? <SpeakerSlash /> : <SpeakerHigh />}</Button>
    </div>
    {state.error ? <Alert variant="destructive"><AlertDescription>{t('video.edit.previewFailed')}
      <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={controller.retry}>{t('media.preview.retry')}</Button></AlertDescription></Alert> : null}
  </div>;
}

/** Trim inputs and point-setting use the same composition cursor as the timeline. */
export function VideoCompositionClipControls({ controller, timeline, clipId, startMs, endMs, sourceDurationMs, disabled, playbackBlocked, onChange }: {
  controller: CompositionPlayback; timeline: CompositionPlaybackModel['timeline']; clipId: string; startMs: number; endMs: number;
  sourceDurationMs: number; disabled?: boolean; playbackBlocked?: boolean; onChange: (range: { startMs: number; endMs: number }) => void;
}) {
  useLocale();
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const id = useId();
  const current = compositionClipAt(timeline, state.positionMs);
  const valid = Number.isFinite(startMs) && Number.isFinite(endMs) && startMs >= 0 && endMs > startMs && endMs <= sourceDurationMs;
  const ready = !disabled && current?.id === clipId && timeline.valid;
  const point = current ? Math.round(compositionSourceTime(current, state.positionMs)) : 0;
  return <FieldGroup>
    <FieldGroup className="video-edit-grid">{(['start', 'end'] as const).map((edge) => <Field key={edge} data-invalid={!valid}>
      <FieldLabel htmlFor={`${id}-${edge}`}>{t(edge === 'start' ? 'video.review.start' : 'video.review.end')}</FieldLabel>
      <Input id={`${id}-${edge}`} type="number" min={0} max={sourceDurationMs / 1000} step={.001} disabled={disabled} aria-invalid={!valid}
        value={Number.isFinite(edge === 'start' ? startMs : endMs) ? (edge === 'start' ? startMs : endMs) / 1000 : ''}
        onChange={(event) => onChange(edge === 'start' ? { startMs: Math.round(event.target.valueAsNumber * 1000), endMs } : { startMs, endMs: Math.round(event.target.valueAsNumber * 1000) })} />
    </Field>)}</FieldGroup>
    {!valid ? <FieldError>{t('video.review.rangeInvalid')}</FieldError> : null}
    <div className="video-edit-range-buttons"><Button type="button" variant="outline" size="sm" disabled={!ready} onClick={() => onChange({ startMs: point, endMs })}>{t('video.review.setStart')}</Button>
      <Button type="button" variant="outline" size="sm" disabled={!ready} onClick={() => onChange({ startMs, endMs: point })}>{t('video.review.setEnd')}</Button>
      <Button type="button" variant="secondary" size="sm" disabled={disabled || playbackBlocked || state.error || !valid || !timeline.valid} onClick={() => controller.playClip(clipId)}>{t('video.review.rangePreview')}</Button></div>
  </FieldGroup>;
}
