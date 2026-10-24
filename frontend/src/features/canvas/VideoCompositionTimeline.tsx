import { FilmStrip, MagnifyingGlassMinus, MagnifyingGlassPlus, MusicNotes, TextT } from '@/shared/ui/icons';
import { cn } from 'cn';
import { useEffect, useRef, useState, useSyncExternalStore, type PointerEvent } from 'react';
import { t, useLocale } from '../../shared/i18n';
import { Button } from '../../shared/ui/primitives/button';
import { CompositionPlayback } from './compositionPlayback';
import { clipStartMs, compositionClipAt, frameTimeMs, trimCompositionClip, type CompositionAudio, type CompositionCaption, type CompositionTimeline, type CompositionTimelineClip } from './compositionTimeline';
import { videoTimeLabel } from './VideoReviewControls';
import './VideoCompositionTimeline.css';

type Trim = { clip: CompositionTimelineClip; side: 'start' | 'end'; pointerId: number; x: number; pixelsPerSecond: number; timeline: CompositionTimeline };
export function VideoCompositionTimeline({ timeline, controller, activeId, onSelect, onTrim, onReorder, captions, music, musicTitle, voiceCount, onAudio, onCaptions, disabled = false, onTrimmingChange }: {
  timeline: CompositionTimeline; controller: CompositionPlayback; activeId?: string; onSelect: (id: string) => void;
  onTrim: (id: string, range: { startMs: number; endMs: number }) => void; onReorder: (source: string, target: string) => void;
  captions: readonly CompositionCaption[]; music?: CompositionAudio; musicTitle?: string; voiceCount: number; onAudio: () => void; onCaptions: () => void; disabled?: boolean; onTrimmingChange: (value: boolean) => void;
}) {
  useLocale();
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const [zoom, setZoom] = useState(1), [dragged, setDragged] = useState<string | null>(null), [drop, setDrop] = useState<string | null>(null);
  const scroll = useRef<HTMLDivElement>(null), content = useRef<HTMLDivElement>(null), trim = useRef<Trim | null>(null);
  const [trimming, setTrimming] = useState<string | null>(null);
  const scale = 70 * zoom, total = frameTimeMs(timeline.frames), width = Math.max(1, total * scale / 1000);
  const currentId = compositionClipAt(timeline, state.positionMs)?.id;
  const tickSeconds = scale >= 140 ? .5 : scale >= 45 ? 1 : scale >= 20 ? 2 : 5;
  const ticks = Array.from({ length: Math.min(121, Math.ceil(total / (tickSeconds * 1000)) + 1) }, (_, index) => index * tickSeconds * 1000).filter((at) => at <= total);
  useEffect(() => {
    const element = scroll.current; if (!element || trimming) return;
    const head = state.positionMs * scale / 1000 + 76;
    if (head < element.scrollLeft || head > element.scrollLeft + element.clientWidth - 20)
      element.scrollLeft = Math.max(0, head - element.clientWidth / 2);
  }, [state.positionMs, scale, trimming]);
  function seek(event: PointerEvent<HTMLElement>) {
    if (disabled || !content.current) return;
    controller.seek((event.clientX - content.current.getBoundingClientRect().left) / scale * 1000);
  }
  function finish(event: PointerEvent<HTMLElement>, cancel = false) {
    const gesture = trim.current; if (!gesture || gesture.pointerId !== event.pointerId) return;
    trim.current = null; setTrimming(null); onTrimmingChange(false);
    if (cancel && !disabled) onTrim(gesture.clip.id, { startMs: gesture.clip.sourceStartMs, endMs: gesture.clip.sourceEndMs });
    if (event.currentTarget.hasPointerCapture?.(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  }
  function updateTrim(event: PointerEvent<HTMLElement>) {
    const gesture = trim.current; if (disabled || !gesture || gesture.pointerId !== event.pointerId) return;
    const original = gesture.side === 'start' ? gesture.clip.sourceStartMs : gesture.clip.sourceEndMs;
    const next = original + (event.clientX - gesture.x) / gesture.pixelsPerSecond * 1000 * gesture.clip.speed;
    controller.previewTrim(gesture.clip.id, gesture.side);
    onTrim(gesture.clip.id, trimCompositionClip(gesture.timeline, gesture.clip, gesture.side, next));
  }
  return <section className="video-composition-timeline" aria-label={t('video.edit.timeline')}>
    <div className="video-edit-timeline-toolbar"><h3><FilmStrip />{t('video.edit.timeline')}</h3><p>{t('video.edit.trimHint')}</p>
      <div className="video-composition-zoom"><Button type="button" variant="ghost" size="icon-xs" aria-label={t('video.edit.zoomOut')} disabled={zoom <= .2} onClick={() => setZoom((value) => Math.max(.2, value / 1.5))}><MagnifyingGlassMinus /></Button>
        <Button type="button" variant="ghost" size="xs" onClick={() => setZoom(Math.min(20, Math.max(.2, ((scroll.current?.clientWidth ?? 600) - 92) / Math.max(1, total / 1000) / 70)))}>{t('video.edit.fitTimeline')}</Button>
        <Button type="button" variant="ghost" size="icon-xs" aria-label={t('video.edit.zoomIn')} disabled={zoom >= 20} onClick={() => setZoom((value) => Math.min(20, value * 1.5))}><MagnifyingGlassPlus /></Button></div>
    </div>
    <div ref={scroll} className="video-composition-timeline-scroll">
      <div className="video-composition-timeline-grid" style={{ minWidth: width + 92 }}>
        <div className="video-composition-track-labels"><span>{t('video.edit.track')}</span><span><FilmStrip />{t('video.edit.videoTrack')}</span>
          <Button type="button" variant="ghost" size="xs" onClick={onAudio}><MusicNotes />{t('video.edit.audioTab')}</Button><Button type="button" variant="ghost" size="xs" onClick={onCaptions}><TextT />{t('video.edit.captionsTab')}</Button></div>
        <div ref={content} className="video-composition-time-area" style={{ minWidth: width }}>
          <div className="video-composition-ruler ui-focusable" role="slider" tabIndex={0} aria-label={t('video.edit.timelinePosition')} aria-valuemin={0} aria-valuemax={total / 1000} aria-valuenow={state.positionMs / 1000}
            aria-valuetext={videoTimeLabel(state.positionMs / 1000)} aria-disabled={disabled} onPointerDown={(event) => { event.preventDefault(); event.currentTarget.setPointerCapture?.(event.pointerId); seek(event); }}
            onPointerMove={(event) => { if (event.currentTarget.hasPointerCapture?.(event.pointerId)) seek(event); }} onKeyDown={(event) => {
              if (disabled) return;
              if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') { event.preventDefault(); controller.seek(state.positionMs + (event.key === 'ArrowRight' ? 1 : -1) * frameTimeMs(event.shiftKey ? 10 : 1)); }
              if (event.key === 'Home' || event.key === 'End') { event.preventDefault(); controller.seek(event.key === 'Home' ? 0 : total); }
            }}>
            {ticks.map((at) => <time key={at} style={{ left: at * scale / 1000, transform: at === total ? 'translateX(-100%)' : undefined }}>{videoTimeLabel(at / 1000)}</time>)}
          </div>
          <div className="video-composition-video-track">
            {timeline.clips.map((clip, index) => <div key={clip.id} className={cn('video-composition-timeline-clip', clip.id === activeId && 'is-active', clip.id === currentId && 'is-current', drop === clip.id && 'is-drop-target')}
              style={{ left: clipStartMs(clip) * scale / 1000, width: frameTimeMs(clip.frames) * scale / 1000 }}>
              <Button type="button" variant="outline" className="video-edit-timeline-clip-body" draggable={!disabled && !trimming} disabled={disabled}
                aria-label={t('video.edit.timelineClip', { '0': index + 1, '1': clip.title })} aria-pressed={clip.id === activeId} onClick={() => onSelect(clip.id)}
                onDragStart={(event) => { if (disabled || trim.current) { event.preventDefault(); return; } event.dataTransfer.setData('text/plain', clip.id); event.dataTransfer.effectAllowed = 'move'; setDragged(clip.id); }}
                onDragOver={(event) => { if (!disabled && dragged && dragged !== clip.id) { event.preventDefault(); setDrop(clip.id); } }}
                onDrop={(event) => { event.preventDefault(); if (!disabled && dragged) onReorder(dragged, clip.id); setDragged(null); setDrop(null); }} onDragEnd={() => { setDragged(null); setDrop(null); }}>
                {clip.poster ? <img src={clip.poster} alt="" draggable={false} /> : <FilmStrip />}
                <span><strong>{String(index + 1).padStart(2, '0')} {clip.title}</strong><small>{videoTimeLabel(frameTimeMs(clip.frames) / 1000)} · {clip.speed}×</small></span>
              </Button>
              {(['start', 'end'] as const).map((side) => <Button key={side} type="button" variant="ghost" size="icon-xs" role="slider" draggable={false}
                className={cn('video-composition-trim-handle', side === 'start' ? 'is-start' : 'is-end')} disabled={disabled}
                aria-label={t(side === 'start' ? 'video.edit.trimStart' : 'video.edit.trimEnd', { '0': index + 1 })} aria-valuemin={0} aria-valuemax={clip.sourceDurationMs / 1000}
                aria-valuenow={(side === 'start' ? clip.sourceStartMs : clip.sourceEndMs) / 1000} aria-valuetext={videoTimeLabel((side === 'start' ? clip.sourceStartMs : clip.sourceEndMs) / 1000)}
                onPointerDown={(event) => { if (disabled || event.button !== 0) return; event.preventDefault(); event.stopPropagation(); event.currentTarget.focus();
                  trim.current = { clip, side, pointerId: event.pointerId, x: event.clientX, pixelsPerSecond: scale, timeline }; setTrimming(clip.id); onTrimmingChange(true); controller.pause(); onSelect(clip.id); event.currentTarget.setPointerCapture?.(event.pointerId); }}
                onPointerMove={updateTrim} onPointerUp={(event) => finish(event)} onPointerCancel={(event) => finish(event, true)} onLostPointerCapture={(event) => finish(event, true)}
                onKeyDown={(event) => {
                  if (disabled) return;
                  if (event.key === 'Escape' && trim.current) { const gesture = trim.current; trim.current = null; setTrimming(null); onTrimmingChange(false); onTrim(clip.id, { startMs: gesture.clip.sourceStartMs, endMs: gesture.clip.sourceEndMs }); return; }
                  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home' && event.key !== 'End') return;
                  event.preventDefault(); const original = side === 'start' ? clip.sourceStartMs : clip.sourceEndMs;
                  const next = event.key === 'Home' ? 0 : event.key === 'End' ? clip.sourceDurationMs : original + (event.key === 'ArrowRight' ? 1 : -1) * frameTimeMs(event.shiftKey ? 10 : 1) * clip.speed;
                  controller.pause(); onSelect(clip.id); controller.previewTrim(clip.id, side); onTrim(clip.id, trimCompositionClip(timeline, clip, side, next));
                }}><span aria-hidden="true" /></Button>)}
            </div>)}
          </div>
          <div className="video-composition-audio-track"><Button type="button" variant="ghost" size="sm" className={cn('video-composition-music-block', music && 'has-content')}
            style={{ width: music ? Math.max(0, Math.min(total, music.durationMs - music.sourceStartMs)) * scale / 1000 : width }} disabled={disabled} onClick={onAudio}>
            <MusicNotes />{music ? musicTitle : t('video.edit.noMusic')}{music ? ` · ${Math.round(music.volume * 100)}%` : ''}{voiceCount > 0 ? ` · ${t('video.edit.voicesSummary', { '0': voiceCount })}` : ''}</Button></div>
          <div className="video-composition-caption-track">{captions.filter((caption) => caption.startMs < total && caption.endMs > 0).map((caption) => <Button type="button" variant="secondary" size="sm" key={caption.id} disabled={disabled}
            style={{ left: Math.max(0, caption.startMs) * scale / 1000, width: Math.max(0, Math.min(total, caption.endMs) - Math.max(0, caption.startMs)) * scale / 1000 }}
            onClick={() => { controller.seek(caption.startMs); onCaptions(); }} aria-label={t('video.edit.previewCaption', { '0': caption.text })}><TextT />{caption.text || t('video.edit.subtitle')}</Button>)}</div>
          <div className="video-composition-playhead" style={{ left: state.positionMs * scale / 1000 }} aria-hidden="true"><span /></div>
        </div>
      </div>
    </div>
  </section>;
}
