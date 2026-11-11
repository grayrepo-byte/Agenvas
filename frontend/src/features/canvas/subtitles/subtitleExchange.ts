import type { SubtitleCue } from './subtitleTimeline';
import type { SubtitleFormat } from './subtitlesApi';

export const SUBTITLE_MAX_CUES = 5000;
export const SUBTITLE_MAX_TEXT = 500;
export const SUBTITLE_MAX_FILE_BYTES = 2 * 1024 * 1024;
export function validSubtitleCues(cues: readonly SubtitleCue[], durationMs: number): boolean {
  return Number.isSafeInteger(durationMs) && durationMs > 0 && cues.length <= SUBTITLE_MAX_CUES && cues.every((cue) =>
    typeof cue.text === 'string' && !!cue.text.trim() && cue.text.length <= SUBTITLE_MAX_TEXT
    && !/\n[ \t]*\n/.test(cue.text.replace(/\r\n?/g, '\n'))
    && !Array.from(cue.text).some((character) => { const code = character.charCodeAt(0); return code < 32 && code !== 9 && code !== 10 && code !== 13 || code >= 127 && code <= 159; })
    && Number.isSafeInteger(cue.startMs) && Number.isSafeInteger(cue.endMs) && cue.startMs >= 0 && cue.endMs > cue.startMs && cue.endMs <= durationMs);
}
/** Plain-text exchange matches the server exporter; this also exports the final mapped composition. */
export function encodeSubtitleFile(cues: readonly SubtitleCue[], durationMs: number, format: SubtitleFormat): string {
  if (!validSubtitleCues(cues, durationMs)) throw new Error('SUBTITLE_INVALID_CUES');
  const timestamp = (ms: number) => `${String(Math.floor(ms / 3600000)).padStart(2, '0')}:${String(Math.floor(ms / 60000) % 60).padStart(2, '0')}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}${format === 'SRT' ? ',' : '.'}${String(ms % 1000).padStart(3, '0')}`;
  const contents = (format === 'VTT' ? 'WEBVTT\n\n' : '') + [...cues].sort((a, b) => a.startMs - b.startMs || a.endMs - b.endMs).map((cue, index) =>
    `${format === 'SRT' ? `${index + 1}\n` : ''}${timestamp(cue.startMs)} --> ${timestamp(cue.endMs)}\n${cue.text.trim().replace(/\r\n?/g, '\n').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')}\n\n`).join('');
  if (new TextEncoder().encode(contents).byteLength > SUBTITLE_MAX_FILE_BYTES) throw new Error('SUBTITLE_INVALID_FILE');
  return contents;
}
export function downloadSubtitleFile(contents: string, format: SubtitleFormat) {
  const url = URL.createObjectURL(new Blob([contents], { type: format === 'VTT' ? 'text/vtt;charset=utf-8' : 'application/x-subrip;charset=utf-8' }));
  const link = document.createElement('a'); link.href = url; link.download = `subtitles.${format.toLowerCase()}`;
  document.body.append(link); link.click(); link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
