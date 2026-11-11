import { describe, expect, it } from 'vitest';
import { mapSubtitleTrack, mapSubtitleTrackToAudio, type SubtitleTrack, type SubtitleClip } from './subtitleTimeline';

const track: SubtitleTrack = { sourceVersionId: 'fixed-video-a', sourceDurationMs: 10000,
  cues: [{ text: '先说', startMs: 500, endMs: 1500 }, { text: '后说', startMs: 2000, endMs: 5000 }] };
const clip = (changes: Partial<SubtitleClip> = {}): SubtitleClip => ({ id: 'shot-a', sourceVersionId: 'fixed-video-a',
  sourceStartMs: 1000, sourceEndMs: 4000, speed: 2, startFrame: 30, frames: 45, ...changes });

describe('source subtitle time mapping', () => {
  it('clips source cues and applies speed and the frame based timeline offset', () => {
    expect(mapSubtitleTrack(track, clip())).toEqual([
      { id: 'shot-a:0', text: '先说', startMs: 1000, endMs: 1250 },
      { id: 'shot-a:1', text: '后说', startMs: 1500, endMs: 2500 },
    ]);
  });
  it('remaps repeated and reordered clips from fixed source times without accumulating prior offsets', () => {
    const first = mapSubtitleTrack(track, clip({ id: 'repeat-2', startFrame: 0 }));
    const later = mapSubtitleTrack(track, clip({ id: 'repeat-1', startFrame: 45 }));
    expect(first[0]).toEqual({ id: 'repeat-2:0', text: '先说', startMs: 0, endMs: 250 });
    expect(later[0]).toEqual({ id: 'repeat-1:0', text: '先说', startMs: 1500, endMs: 1750 });
    expect(track.cues[0]?.startMs).toBe(500);
  });
  it('drops cues in the discarded partial source frame and bounds the result to the actual output frames', () => {
    const fractional: SubtitleTrack = { sourceVersionId: track.sourceVersionId, sourceDurationMs: 2000,
      cues: [{ text: '尾部', startMs: 1900, endMs: 2000 }, { text: '仅残帧', startMs: 1980, endMs: 2000 }] };
    const mapped = mapSubtitleTrack(fractional, clip({ sourceStartMs: 100, sourceEndMs: 2000, speed: 1.15,
      startFrame: 17, frames: 49 }));
    expect(mapped).toEqual([{ id: 'shot-a:0', text: '尾部', startMs: 2131, endMs: 2200 }]);
  });
  it('normalizes supported source whitespace for composition without rewriting the source draft', () => {
    const original: SubtitleTrack = { ...track, cues: [{ text: '甲\t乙\r\n丙\r丁', startMs: 1000, endMs: 3000 }] };
    const mapped = mapSubtitleTrack(original, clip());
    expect(mapped).toEqual([{ id: 'shot-a:0', text: '甲 乙\n丙\n丁', startMs: 1000, endMs: 2000 }]);
    expect(original.cues[0]?.text).toBe('甲\t乙\r\n丙\r丁');
    expect(mapSubtitleTrackToAudio(original, { id: 'voice', sourceVersionId: track.sourceVersionId,
      sourceStartMs: 1000, sourceEndMs: 4000, speed: 1, startMs: 0, endMs: 3000 })[0]?.text).toBe('甲 乙\n丙\n丁');
  });
  it('rejects a transcript from a replaced media version and malformed timing', () => {
    expect(() => mapSubtitleTrack(track, clip({ sourceVersionId: 'replacement-b' }))).toThrow('SUBTITLE_SOURCE_MISMATCH');
    expect(() => mapSubtitleTrack(track, clip({ speed: NaN }))).toThrow('SUBTITLE_INVALID_PLACEMENT');
    expect(() => mapSubtitleTrack(track, clip({ frames: 46 }))).toThrow('SUBTITLE_INVALID_PLACEMENT');
    expect(() => mapSubtitleTrack({ ...track, cues: [{ text: 'bad', startMs: 1, endMs: 10001 }] }, clip()))
      .toThrow('SUBTITLE_INVALID_CUES');
  });
  it('maps fixed audio subtitles using the planned voice trim, position and actual speed', () => {
    expect(mapSubtitleTrackToAudio(track, { id: 'voice', sourceVersionId: track.sourceVersionId,
      sourceStartMs: 1000, sourceEndMs: 4000, speed: 1.1, startMs: 2000, endMs: 4727 })).toEqual([
      { id: 'voice:0', text: '先说', startMs: 2000, endMs: 2454 },
      { id: 'voice:1', text: '后说', startMs: 2909, endMs: 4727 },
    ]);
  });
});
