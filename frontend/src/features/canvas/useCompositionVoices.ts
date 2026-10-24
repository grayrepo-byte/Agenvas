import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { assetContentUrl, previewVideoComposition, type CanvasItem, type VideoCompositionRequest } from '../../shared/api/client';
import { readContentText } from './artifactContent';
import type { CompositionVoice } from './compositionTimeline';

/** Only shot/voice changes require server analysis. Draft captions, volume and framing remain immediate. */
export function useCompositionVoices(projectId: string, shots: VideoCompositionRequest['shots'], sources: readonly CanvasItem[], enabled: boolean) {
  const payload = JSON.stringify(shots), hasVoices = shots.some((shot) => shot.lines.length > 0);
  const [settled, setSettled] = useState(payload);
  useEffect(() => { const timer = window.setTimeout(() => setSettled(payload), 350); return () => window.clearTimeout(timer); }, [payload]);
  const plan = useQuery({ queryKey: ['video-composition-preview', projectId, settled],
    queryFn: ({ signal }) => previewVideoComposition(projectId, { title: 'Preview', originalAudioMode: 'MUTE', shots: JSON.parse(settled) as VideoCompositionRequest['shots'] }, signal),
    enabled: enabled && hasVoices && payload === settled, staleTime: 30_000, retry: false, refetchOnWindowFocus: false,
  });
  const current = enabled && payload === settled;
  const voices: CompositionVoice[] = current && plan.data ? plan.data.shots.flatMap((shot, shotIndex) => shot.lines.flatMap((line, index) => {
    const source = sources.find((item) => item.selectedVersionId === line.audioVersionId);
    const assetId = readContentText(source?.selectedVersion?.content, 'assetId');
    return assetId ? [{ id: `${shotIndex}:${index}`, src: assetContentUrl(projectId, assetId), startMs: shot.timelineStartMs + line.startMs,
      endMs: shot.timelineStartMs + line.endMs, trimStartMs: line.trimStartMs, speed: line.speed }] : [];
  })) : [];
  return { voices, pending: hasVoices && enabled && (!current || plan.isPending || plan.isFetching), error: hasVoices && current ? plan.error : null,
    blocked: hasVoices && (!current || plan.isPending || !!plan.error), retry: plan.refetch, fontFamily: plan.data?.fontFamily };
}
