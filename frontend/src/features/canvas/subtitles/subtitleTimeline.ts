import { COMPOSITION_FPS, type CompositionCaption } from "../compositionTimeline";

export type SubtitleCue = { text: string; startMs: number; endMs: number };
/** Source times belong to this immutable media version, never to a node's current selection. */
export type SubtitleTrack = { sourceVersionId: string; sourceDurationMs: number; cues: readonly SubtitleCue[] };
export type SubtitleClip = {
  id: string;
  sourceVersionId: string;
  sourceStartMs: number;
  sourceEndMs: number;
  speed: number;
  startFrame: number;
  frames: number;
};
export type SubtitleAudioPlacement = {
  id: string;
  sourceVersionId: string;
  sourceStartMs: number;
  sourceEndMs: number;
  speed: number;
  startMs: number;
  endMs: number;
};

/** Recompute from source times after every trim, speed or order change; never transform a prior projection. */
export function mapSubtitleTrack(track: SubtitleTrack, clip: SubtitleClip): CompositionCaption[] {
  if (
    !Number.isSafeInteger(clip.startFrame) ||
    clip.startFrame < 0 ||
    !Number.isSafeInteger(clip.frames) ||
    clip.frames < 1 ||
    clip.frames !== Math.floor(((clip.sourceEndMs - clip.sourceStartMs) * COMPOSITION_FPS) / (1000 * clip.speed))
  ) {
    throw new Error("SUBTITLE_INVALID_PLACEMENT");
  }
  return mapCues(track, {
    ...clip,
    startMs: (clip.startFrame * 1000) / COMPOSITION_FPS,
    endMs: Math.floor(((clip.startFrame + clip.frames) * 1000) / COMPOSITION_FPS),
  });
}

/** Uses the actual voice plan, including exterior silence trimming and optional speech speed-up. */
export function mapSubtitleTrackToAudio(track: SubtitleTrack, placement: SubtitleAudioPlacement): CompositionCaption[] {
  return mapCues(track, placement);
}

function mapCues(track: SubtitleTrack, clip: SubtitleAudioPlacement): CompositionCaption[] {
  if (track.sourceVersionId !== clip.sourceVersionId) throw new Error("SUBTITLE_SOURCE_MISMATCH");
  if (
    !clip.id ||
    !Number.isSafeInteger(track.sourceDurationMs) ||
    track.sourceDurationMs < 1 ||
    !Number.isSafeInteger(clip.sourceStartMs) ||
    clip.sourceStartMs < 0 ||
    !Number.isSafeInteger(clip.sourceEndMs) ||
    clip.sourceEndMs > track.sourceDurationMs ||
    clip.sourceEndMs <= clip.sourceStartMs ||
    !Number.isFinite(clip.speed) ||
    clip.speed < 0.5 ||
    clip.speed > 2 ||
    !Number.isFinite(clip.startMs) ||
    clip.startMs < 0 ||
    !Number.isSafeInteger(clip.endMs) ||
    clip.endMs <= clip.startMs
  ) {
    throw new Error("SUBTITLE_INVALID_PLACEMENT");
  }
  if (
    track.cues.some(
      cue =>
        !cue.text.trim() ||
        cue.text.length > 500 ||
        !Number.isSafeInteger(cue.startMs) ||
        !Number.isSafeInteger(cue.endMs) ||
        cue.startMs < 0 ||
        cue.endMs > track.sourceDurationMs ||
        cue.endMs <= cue.startMs,
    )
  ) {
    throw new Error("SUBTITLE_INVALID_CUES");
  }
  return track.cues.flatMap((cue, index) => {
    const start = Math.max(cue.startMs, clip.sourceStartMs),
      end = Math.min(cue.endMs, clip.sourceEndMs);
    const mappedStart = Math.floor(clip.startMs + (start - clip.sourceStartMs) / clip.speed);
    const mappedEnd = Math.min(clip.endMs, Math.floor(clip.startMs + (end - clip.sourceStartMs) / clip.speed));
    return end > start && mappedEnd > mappedStart
      ? // Source exchange accepts tabs and normalizes line endings; composition accepts LF only.
        // Project the same plain text to preview, MP4 and timeline files without changing the draft.
        [
          {
            id: `${clip.id}:${index}`,
            text: cue.text.replace(/\r\n?/g, "\n").replaceAll("\t", " "),
            startMs: mappedStart,
            endMs: mappedEnd,
          },
        ]
      : [];
  });
}
