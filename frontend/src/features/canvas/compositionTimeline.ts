/** All edit and preview times use the exporter's 30 fps frame grid, never rounded per-clip milliseconds. */
export const COMPOSITION_FPS = 30;
export const COMPOSITION_MAX_FRAMES = COMPOSITION_FPS * 60;
export const COMPOSITION_MIN_SOURCE_MS = 100;
export type CompositionSourceClip = {
  id: string; title: string; src: string; poster?: string; sourceDurationMs: number;
  sourceStartMs: number; sourceEndMs: number; speed: number; fadeMs: number;
};
export type CompositionTimelineClip = CompositionSourceClip & { startFrame: number; frames: number };
export type CompositionTimeline = { clips: readonly CompositionTimelineClip[]; frames: number; valid: boolean };
export type CompositionCaption = { id: string; text: string; startMs: number; endMs: number };
export type CompositionAudio = { src: string; sourceStartMs: number; durationMs: number; volume: number };
export type CompositionVoice = { id: string; src: string; startMs: number; endMs: number; trimStartMs: number; speed: number };
export const frameTimeMs = (frame: number) => frame * 1000 / COMPOSITION_FPS;
export const clipEndMs = (clip: CompositionTimelineClip) => frameTimeMs(clip.startFrame + clip.frames);
export const clipStartMs = (clip: CompositionTimelineClip) => frameTimeMs(clip.startFrame);
export function createCompositionTimeline(sources: readonly CompositionSourceClip[]): CompositionTimeline {
  let frames = 0;
  const clips: CompositionTimelineClip[] = [];
  for (const clip of sources) {
    const count = Math.floor((clip.sourceEndMs - clip.sourceStartMs) * COMPOSITION_FPS / (1000 * clip.speed));
    if (!clip.src || !Number.isInteger(clip.sourceStartMs) || !Number.isInteger(clip.sourceEndMs)
      || clip.sourceStartMs < 0 || clip.sourceEndMs > clip.sourceDurationMs || clip.sourceEndMs - clip.sourceStartMs < COMPOSITION_MIN_SOURCE_MS
      || !Number.isFinite(clip.speed) || clip.speed < .5 || clip.speed > 2 || count < 1
      || !Number.isInteger(clip.fadeMs) || clip.fadeMs < 0 || clip.fadeMs > 500 || clip.fadeMs * 2 > Math.floor(frameTimeMs(count))) {
      return { clips: [], frames: 0, valid: false };
    }
    clips.push({ ...clip, frames: count, startFrame: frames }); frames += count;
  }
  return { clips, frames, valid: clips.length > 0 && frames <= COMPOSITION_MAX_FRAMES };
}
export function compositionClipAt(timeline: CompositionTimeline, positionMs: number): CompositionTimelineClip | undefined {
  const frame = Math.max(0, Math.min(timeline.frames - 1, Math.floor(positionMs * COMPOSITION_FPS / 1000 + 1e-7)));
  return timeline.clips.find((clip) => frame >= clip.startFrame && frame < clip.startFrame + clip.frames);
}
export function compositionSourceTime(clip: CompositionTimelineClip, positionMs: number): number {
  return Math.max(clip.sourceStartMs, Math.min(clip.sourceEndMs, clip.sourceStartMs + (positionMs - clipStartMs(clip)) * clip.speed));
}
export function compositionFade(clip: CompositionTimelineClip | undefined, positionMs: number): number {
  if (!clip || clip.fadeMs === 0) return 1;
  return Math.max(0, Math.min(1, (positionMs - clipStartMs(clip)) / clip.fadeMs, (clipEndMs(clip) - positionMs) / clip.fadeMs));
}
/** Match ASS centisecond timing and literal escaping; React renders the result as text, never markup. */
export function visibleCompositionCaptions(captions: readonly CompositionCaption[], positionMs: number): string[] {
  return captions.filter((caption) => Math.floor(caption.startMs / 10) * 10 <= positionMs && Math.floor(caption.endMs / 10) * 10 > positionMs)
    .map((caption) => caption.text.trim().replaceAll('\\', '＼').replaceAll('{', '｛').replaceAll('}', '｝').replace(/\r\n?/g, '\n'));
}
export function compositionOutput(resolution: '720P' | '1080P', ratio: '16:9' | '9:16' | '1:1') {
  const edge = resolution === '720P' ? 720 : 1080, wide = edge * 16 / 9;
  return { width: ratio === '16:9' ? wide : edge, height: ratio === '9:16' ? wide : edge };
}
export function trimCompositionClip(timeline: CompositionTimeline, clip: CompositionTimelineClip, side: 'start' | 'end', sourceMs: number) {
  const availableFrames = COMPOSITION_MAX_FRAMES - (timeline.frames - clip.frames);
  const minimum = Math.max(COMPOSITION_MIN_SOURCE_MS, Math.ceil(frameTimeMs(Math.ceil(clip.fadeMs * 2 * COMPOSITION_FPS / 1000)) * clip.speed));
  const maxSource = Math.max(minimum, Math.floor(frameTimeMs(availableFrames + 1) * clip.speed - 1e-6));
  const original = side === 'start' ? clip.sourceStartMs : clip.sourceEndMs;
  const deltaFrames = Math.round((sourceMs - original) / clip.speed * COMPOSITION_FPS / 1000);
  if (deltaFrames === 0) return { startMs: clip.sourceStartMs, endMs: clip.sourceEndMs };
  const minimumFrames = Math.max(1, Math.floor(minimum * COMPOSITION_FPS / (1000 * clip.speed)));
  const requestedFrames = Math.max(minimumFrames, Math.min(Math.max(minimumFrames, availableFrames), clip.frames + (side === 'start' ? -deltaFrames : deltaFrames)));
  const length = Math.max(minimum, Math.min(maxSource, Math.ceil(frameTimeMs(requestedFrames) * clip.speed - 1e-7)));
  return side === 'start'
    ? { startMs: Math.max(0, clip.sourceEndMs - length), endMs: clip.sourceEndMs }
    : { startMs: clip.sourceStartMs, endMs: Math.min(clip.sourceDurationMs, clip.sourceStartMs + length) };
}
