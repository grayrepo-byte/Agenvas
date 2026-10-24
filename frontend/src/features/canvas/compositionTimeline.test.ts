import { describe, expect, it } from 'vitest';
import { clipEndMs, compositionClipAt, compositionFade, compositionOutput, compositionSourceTime, createCompositionTimeline, frameTimeMs, trimCompositionClip, visibleCompositionCaptions, type CompositionSourceClip } from './compositionTimeline';
const clip = (id: string, overrides: Partial<CompositionSourceClip> = {}): CompositionSourceClip => ({ id, title: id, src: `/${id}.mp4`, sourceDurationMs: 10000, sourceStartMs: 1000, sourceEndMs: 2000, speed: 1, fadeMs: 0, ...overrides });
describe('composition timeline', () => {
  it('retains integer frame offsets across fractional clip lengths and maps each boundary to the next clip', () => {
    const timeline = createCompositionTimeline([clip('one', { sourceEndMs: 2133, speed: 2 }), clip('two', { speed: .5 })]);
    expect(timeline.clips.map((item) => [item.startFrame, item.frames])).toEqual([[0, 16], [16, 60]]);
    expect(timeline.frames).toBe(76);
    expect(compositionClipAt(timeline, frameTimeMs(16) - .01)?.id).toBe('one');
    expect(compositionClipAt(timeline, frameTimeMs(16))?.id).toBe('two');
    expect(compositionClipAt(timeline, frameTimeMs(76))?.id).toBe('two');
    expect(compositionSourceTime(timeline.clips[1]!, frameTimeMs(16) + 1000)).toBe(1500);
  });
  it('does not silently skip invalid clips or permit a timeline over sixty seconds', () => {
    expect(createCompositionTimeline([clip('one', { speed: NaN })]).valid).toBe(false);
    expect(createCompositionTimeline([clip('one', { sourceEndMs: 10001 })]).clips).toHaveLength(0);
    expect(createCompositionTimeline([clip('one', { sourceDurationMs: 120000, sourceEndMs: 120000 })]).valid).toBe(false);
  });
  it('clamps trim handles to the source, minimum interval, fades and remaining output frame budget', () => {
    const timeline = createCompositionTimeline([clip('one', { sourceStartMs: 0, sourceEndMs: 10000, speed: 2 }), clip('two', { sourceStartMs: 0, sourceEndMs: 6000 })]);
    const first = timeline.clips[0]!;
    expect(trimCompositionClip(timeline, first, 'start', -1000)).toEqual({ startMs: 0, endMs: 10000 });
    expect(trimCompositionClip(timeline, first, 'end', 20000).endMs).toBe(10000);
    expect(trimCompositionClip(timeline, first, 'start', 10000).startMs).toBe(9900);
    const withFade = createCompositionTimeline([clip('fade', { fadeMs: 55 })]);
    const trim = trimCompositionClip(withFade, withFade.clips[0]!, 'end', 1010);
    expect(createCompositionTimeline([clip('fade', { fadeMs: 55, sourceEndMs: trim.endMs })]).valid).toBe(true);
    const bounded = createCompositionTimeline([clip('a', { sourceDurationMs: 120000, sourceStartMs: 0, sourceEndMs: 10000 }), clip('b', { sourceDurationMs: 120000, sourceStartMs: 0, sourceEndMs: 49000 })]);
    const next = trimCompositionClip(bounded, bounded.clips[0]!, 'end', 100000);
    const result = createCompositionTimeline([{ ...bounded.clips[0]!, sourceEndMs: next.endMs }, bounded.clips[1]!]);
    expect(result.frames).toBe(1800); expect(result.valid).toBe(true);
  });
  it('uses export fade, caption centiseconds, literal ASS characters and output dimensions', () => {
    const timeline = createCompositionTimeline([clip('one', { fadeMs: 200 })]), current = timeline.clips[0]!;
    expect(compositionFade(current, 100)).toBe(.5);
    expect(compositionFade(current, clipEndMs(current) - 100)).toBe(.5);
    const captions = [{ id: 'c', text: '  {tag}\\N\n第二行  ', startMs: 109, endMs: 999 }];
    expect(visibleCompositionCaptions(captions, 100)).toEqual(['｛tag｝＼N\n第二行']);
    expect(visibleCompositionCaptions(captions, 990)).toEqual([]);
    expect(compositionOutput('1080P', '9:16')).toEqual({ width: 1080, height: 1920 });
    expect(compositionOutput('720P', '16:9')).toEqual({ width: 1280, height: 720 });
  });
});
