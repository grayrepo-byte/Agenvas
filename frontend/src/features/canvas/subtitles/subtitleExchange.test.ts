import { describe, expect, it } from 'vitest';
import { encodeSubtitleFile } from './subtitleExchange';
describe('subtitle file download', () => {
  it('exports corrected timeline milliseconds and multilingual text safely in SRT and VTT', () => {
    const cues = [{ text: '中文 <b>&\nSecond line', startMs: 1250, endMs: 2400 }];
    expect(encodeSubtitleFile(cues, 3000, 'SRT')).toBe('1\n00:00:01,250 --> 00:00:02,400\n中文 &lt;b&gt;&amp;\nSecond line\n\n');
    expect(encodeSubtitleFile(cues, 3000, 'VTT')).toBe('WEBVTT\n\n00:00:01.250 --> 00:00:02.400\n中文 &lt;b&gt;&amp;\nSecond line\n\n');
  });
  it('rejects an out of date projection beyond the edited composition instead of truncating corrected text', () => {
    expect(() => encodeSubtitleFile([{ text: 'caption', startMs: 100, endMs: 2000 }], 1000, 'SRT')).toThrow();
  });
});
