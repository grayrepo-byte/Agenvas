import { describe, expect, it } from 'vitest';
import { compositionFilmstrip, compositionFrameLabel, compositionRuler, FILMSTRIP_FRAME_SCALE } from './compositionFilmstrip';
import { createCompositionTimeline } from './compositionTimeline';

const clip = createCompositionTimeline([{ id: 'clip', title: 'clip', src: '/clip', sourceDurationMs: 10000,
  sourceStartMs: 1000, sourceEndMs: 5000, speed: 2, fadeMs: 0 }]).clips[0]!;
describe('composition filmstrip geometry', () => {
  it('samples the actual trimmed source at output-frame positions, including speed', () => {
    const frames = compositionFilmstrip(clip, 70, { left: 0, width: 800 });
    expect(frames.map((item) => item.sourceMs)).toEqual([1000, 3800]);
    expect(frames.map((item) => item.width)).toEqual([98, 42]);
  });
  it('shows every output frame at frame zoom and stays inside the source out point', () => {
    const frames = compositionFilmstrip(clip, FILMSTRIP_FRAME_SCALE, { left: 0, width: 100000 });
    expect(frames).toHaveLength(60); expect(frames[1]?.sourceMs).toBeCloseTo(1066.6666667);
    expect(frames.at(-1)?.sourceMs).toBeCloseTo(4933.3333333);
    expect(frames.every((item) => item.width === 96)).toBe(true);
    expect(compositionFrameLabel(59)).toBe('00:01:29');
  });
  it('virtualizes both images and ruler at the far end of a minute-long timeline', () => {
    const long = { ...clip, frames: 1800, sourceEndMs: 121000, sourceDurationMs: 121000 };
    const viewport = { left: 170000, width: 600 };
    const frames = compositionFilmstrip(long, FILMSTRIP_FRAME_SCALE, viewport);
    expect(frames.length).toBeLessThanOrEqual(10); expect(frames[0]!.frame).toBeGreaterThan(1700);
    const ruler = compositionRuler(1800, FILMSTRIP_FRAME_SCALE, viewport);
    expect(ruler.step).toBe(1); expect(ruler.ticks.length).toBeLessThan(12); expect(ruler.ticks[0]).toBeGreaterThan(1700);
    expect(compositionFilmstrip({ ...clip, startFrame: 1500 }, 70, { left: 0, width: 600 })).toEqual([]);
  });
});
