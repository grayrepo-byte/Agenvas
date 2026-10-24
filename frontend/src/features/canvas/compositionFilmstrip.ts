import { COMPOSITION_FPS, frameTimeMs, type CompositionTimelineClip } from './compositionTimeline';

export const FILMSTRIP_TILE_WIDTH = 96;
export const FILMSTRIP_FRAME_SCALE = FILMSTRIP_TILE_WIDTH * COMPOSITION_FPS;
export type FilmstripViewport = { left: number; width: number };
export type FilmstripFrame = { frame: number; left: number; width: number; sourceMs: number };

/** Sample on the output frame grid, then map into the pinned source. One tile per frame
 * at maximum zoom; only visible tiles plus one on each side are materialized. */
export function compositionFilmstrip(clip: CompositionTimelineClip, scale: number, viewport: FilmstripViewport): FilmstripFrame[] {
  const step = Math.max(1, Math.ceil(FILMSTRIP_TILE_WIDTH / scale * COMPOSITION_FPS - 1e-8));
  const tileWidth = frameTimeMs(step) * scale / 1000;
  const clipLeft = frameTimeMs(clip.startFrame) * scale / 1000;
  const clipWidth = frameTimeMs(clip.frames) * scale / 1000;
  if (clipLeft > viewport.left + viewport.width + tileWidth || clipLeft + clipWidth < viewport.left - tileWidth) return [];
  const first = Math.max(0, Math.floor((viewport.left - clipLeft) / tileWidth) - 1);
  const last = Math.min(Math.ceil(clip.frames / step), Math.ceil((viewport.left + viewport.width - clipLeft) / tileWidth) + 1);
  return Array.from({ length: Math.max(0, last - first) }, (_, index) => {
    const frame = (first + index) * step;
    return { frame, left: frameTimeMs(frame) * scale / 1000, width: frameTimeMs(Math.min(step, clip.frames - frame)) * scale / 1000,
      sourceMs: clip.sourceStartMs + frameTimeMs(frame) * clip.speed };
  });
}

export function compositionFrameLabel(frame: number) {
  const value = Math.max(0, Math.round(frame));
  return [Math.floor(value / (COMPOSITION_FPS * 60)), Math.floor(value / COMPOSITION_FPS) % 60, value % COMPOSITION_FPS]
    .map((part) => String(part).padStart(2, '0')).join(':');
}

export function compositionRuler(frames: number, scale: number, viewport: FilmstripViewport) {
  const step = [1, 5, 15, 30, 60, 150, 300, 900].find((framesPerTick) => frameTimeMs(framesPerTick) * scale / 1000 >= 64) ?? 900;
  const first = Math.max(0, Math.floor(viewport.left / scale * COMPOSITION_FPS / step) - 1);
  const last = Math.min(Math.floor(Math.max(0, frames - 1) / step), Math.ceil((viewport.left + viewport.width) / scale * COMPOSITION_FPS / step) + 1);
  return { step, ticks: Array.from({ length: Math.max(0, last - first + 1) }, (_, index) => (first + index) * step) };
}
