import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { CanvasItem, VideoCompositionPreview } from '../../shared/api/client';
import { server } from '../../test/server';
import { useCompositionVoices } from './useCompositionVoices';
const source = { selectedVersionId: 'voice', selectedVersion: { content: { assetId: 'voice-asset' } } } as CanvasItem;
const shot = { videoVersionId: 'video', sourceStartMs: 0, durationMs: 3000, speed: 1, fadeMs: 0, lines: [{ audioVersionId: 'voice' }] };
const result: VideoCompositionPreview = { durationMs: 3000, frames: 90, fontFamily: 'Noto Sans CJK SC', shots: [{ videoVersionId: 'video', timelineStartMs: 0, durationMs: 3000,
  lines: [{ audioVersionId: 'voice', startMs: 1000, endMs: 2000, trimStartMs: 100, trimEndMs: 1100, speed: 1 }] }] };
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return renderHook(({ shots }) => useCompositionVoices('project', shots, [source], true), { initialProps: { shots: [shot] }, wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
}
describe('composition voice preview', () => {
  it('uses server timing and never plays an old voice plan after the input changes', async () => {
    server.use(http.get('/api/v1/auth/csrf', () => HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' })),
      http.post('/api/v1/projects/project/video-compositions/preview', () => HttpResponse.json(result)));
    const view = setup(); await waitFor(() => expect(view.result.current.voices).toHaveLength(1));
    expect(view.result.current.voices[0]).toMatchObject({ startMs: 1000, endMs: 2000, trimStartMs: 100, speed: 1, src: '/api/v1/projects/project/assets/voice-asset/content' });
    act(() => view.rerender({ shots: [{ ...shot, speed: 2 }] }));
    expect(view.result.current.blocked).toBe(true); expect(view.result.current.voices).toEqual([]);
    act(() => view.rerender({ shots: [{ ...shot, lines: [] }] }));
    expect(view.result.current.blocked).toBe(false); expect(view.result.current.voices).toEqual([]);
  });
  it('keeps errors explicit and only retries a failed read when requested', async () => {
    let calls = 0;
    server.use(http.get('/api/v1/auth/csrf', () => HttpResponse.json({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' })),
      http.post('/api/v1/projects/project/video-compositions/preview', () => { calls++; return calls === 1 ? HttpResponse.json({ detail: 'Preview failed' }, { status: 503 }) : HttpResponse.json(result); }));
    const view = setup(); await waitFor(() => expect(view.result.current.error).toBeTruthy());
    expect(view.result.current.blocked).toBe(true); expect(calls).toBe(1);
    await act(async () => { await view.result.current.retry(); });
    await waitFor(() => expect(view.result.current.blocked).toBe(false)); expect(calls).toBe(2);
  });
});
