import { CircleNotch, FilmStrip } from '@/shared/ui/icons';
import { memo, useEffect, useState } from 'react';
import type { FilmstripFrame } from './compositionFilmstrip';
import { CompositionFrameReader } from './compositionFrameReader';

export const VideoCompositionFilmstrip = memo(function VideoCompositionFilmstrip({ reader, src, poster, frames, retry, suspended, onError }: {
  reader: CompositionFrameReader; src: string; poster?: string; frames: readonly FilmstripFrame[]; retry: number; suspended: boolean; onError: () => void;
}) {
  return <span className="video-composition-filmstrip" aria-hidden="true">{frames.map((frame) => <FrameTile key={frame.frame} reader={reader} src={src} poster={poster}
    sourceMs={frame.sourceMs} frame={frame.frame} left={frame.left} width={frame.width} retry={retry} suspended={suspended} onError={onError} />)}</span>;
});

const FrameTile = memo(function FrameTile({ reader, src, poster, sourceMs, frame, left, width, retry, suspended, onError }: {
  reader: CompositionFrameReader; src: string; poster?: string; sourceMs: number; frame: number; left: number; width: number; retry: number; suspended: boolean; onError: () => void;
}) {
  const [image, setImage] = useState<{ key: string; url?: string; failed?: boolean }>();
  const key = `${src}:${sourceMs}:${retry}`;
  useEffect(() => {
    if (suspended) return;
    const controller = new AbortController();
    void reader.read(src, sourceMs, controller.signal).then((url) => { if (!controller.signal.aborted) setImage({ key, url }); }, () => {
      if (!controller.signal.aborted) { setImage({ key, failed: true }); onError(); }
    });
    return () => controller.abort();
  }, [reader, src, sourceMs, key, suspended, onError]);
  const current = image?.key === key ? image : undefined;
  return <span className="video-composition-frame-tile" data-frame={frame} data-source-ms={sourceMs} data-frame-state={current?.url ? 'ready' : current?.failed ? 'failed' : 'loading'}
    style={{ left, width }} aria-busy={!current}>
    {current?.url || poster ? <img src={current?.url ?? poster} alt="" draggable={false} /> : <FilmStrip />}
    {!current ? <CircleNotch className="video-edit-spinner" /> : null}
  </span>;
});
