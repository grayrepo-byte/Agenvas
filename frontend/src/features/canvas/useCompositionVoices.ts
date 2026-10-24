import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { assetContentUrl, previewVideoComposition, type CanvasItem, type VideoCompositionRequest } from '../../shared/api/client';
import { readContentText } from './artifactContent';
import { COMPOSITION_MIN_SOURCE_MS, type CompositionVoice } from './compositionTimeline';

/** Voice edits require analysis. Without voices, a fixed pinned prefix reads the configured font
 * without decoding audio or refetching on every trim, caption, volume or framing change. */
export function useCompositionVoices(projectId: string, shots: VideoCompositionRequest['shots'], sources: readonly CanvasItem[], enabled: boolean) {
  const hasVoices = shots.some((shot) => shot.lines.length > 0);
  const analysisShots: VideoCompositionRequest['shots'] = hasVoices ? shots : shots.slice(0, 1).map((shot) => ({
    videoVersionId: shot.videoVersionId, sourceStartMs: 0, durationMs: COMPOSITION_MIN_SOURCE_MS, speed: 1, fadeMs: 0, lines: [],
  }));
  const payload = JSON.stringify(analysisShots);
  const [settled, setSettled] = useState(payload);
  useEffect(() => { const timer = window.setTimeout(() => setSettled(payload), 350); return () => window.clearTimeout(timer); }, [payload]);
  const plan = useQuery({ queryKey: ['video-composition-preview', projectId, settled],
    queryFn: ({ signal }) => previewVideoComposition(projectId, { title: 'Preview', originalAudioMode: 'MUTE', shots: JSON.parse(settled) as VideoCompositionRequest['shots'] }, signal),
    enabled: enabled && payload === settled, staleTime: 30_000, retry: false, refetchOnWindowFocus: false,
  });
  const current = enabled && payload === settled;
  const voices: CompositionVoice[] = hasVoices && current && plan.data ? plan.data.shots.flatMap((shot, shotIndex) => shot.lines.flatMap((line, index) => {
    const source = sources.find((item) => item.selectedVersionId === line.audioVersionId);
    const assetId = readContentText(source?.selectedVersion?.content, 'assetId');
    return assetId ? [{ id: `${shotIndex}:${index}`, src: assetContentUrl(projectId, assetId), startMs: shot.timelineStartMs + line.startMs,
      endMs: shot.timelineStartMs + line.endMs, trimStartMs: line.trimStartMs, speed: line.speed }] : [];
  })) : [];
  return { voices, pending: enabled && (!current || plan.isPending || plan.isFetching), error: current ? plan.error : null,
    blocked: !current || plan.isPending || !!plan.error, retry: plan.refetch, fontFamily: plan.data?.fontFamily };
}
