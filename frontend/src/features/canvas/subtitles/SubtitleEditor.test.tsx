import { QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import userEvent from '@testing-library/user-event';
import { createQueryClient } from '../../../app/queryClient';
import { server } from '../../../test/server';
import { t } from '../../../shared/i18n';
import { SubtitleEditor } from './SubtitleEditor';
import { useSubtitleWorkbench } from './useSubtitleWorkbench';
import type { SubtitleDraft } from './subtitlesApi';

let saved: SubtitleDraft;
let failure = false;
let work: ReturnType<typeof useSubtitleWorkbench>;
function Harness({ includeB = true }: { includeB?: boolean }) {
  work = useSubtitleWorkbench('project-1', [{ versionId: 'version-a', title: 'Video A', kind: 'VIDEO' },
    ...(includeB ? [{ versionId: 'version-b', title: 'Video B', kind: 'VIDEO' as const }] : [])]);
  return <SubtitleEditor workbench={work} />;
}
function mount() {
  const client = createQueryClient();
  return render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>);
}
beforeEach(() => {
  failure = false;
  saved = { id: 'draft-a', projectId: 'project-1', sourceVersionId: 'version-a', sourceDurationMs: 3000,
    language: 'auto', cues: [{ text: 'Original', startMs: 100, endMs: 2000 }], version: 0, taskId: null, updatedAt: '2026-11-01T00:00:00Z' };
  server.use(http.get('/api/v1/auth/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: 'synthetic' })),
    http.get('/api/v1/projects/project-1/subtitles', () => HttpResponse.json([saved])),
    http.get('/api/v1/projects/project-1/subtitles/capabilities', () => HttpResponse.json({ availability: 'NOT_CONFIGURED', maxCues: 5000, maxFileBytes: 2097152, maxSourceDurationMs: 600000 })),
    http.get('/api/v1/projects/project-1/subtitles/draft-a', () => HttpResponse.json(saved)),
    http.put('/api/v1/projects/project-1/subtitles/draft-a', async ({ request }) => {
      const body = await request.json() as { expectedVersion: number; cues: SubtitleDraft['cues'] };
      if (failure) return HttpResponse.json({ code: 'SAVE_FAILED', detail: 'Synthetic save failure' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
      if (body.expectedVersion !== saved.version) return HttpResponse.json({ code: 'SUBTITLE_VERSION_CONFLICT', detail: 'Synthetic conflict' }, { status: 409 });
      saved = { ...saved, cues: body.cues, version: saved.version + 1 };
      return HttpResponse.json(saved);
    }));
});
describe('source subtitle correction workbench', () => {
  it.each([true, false])('keeps overlapping source-list caches current after saving (cached draft: %s)', async (hasDraft) => {
    const client = createQueryClient();
    const subsetKey = ['subtitle-drafts', 'project-1', JSON.stringify(['version-a'])];
    client.setQueryData(subsetKey, hasDraft ? [saved] : []);
    const mounted = render(<QueryClientProvider client={client}><Harness /></QueryClientProvider>);
    await screen.findByDisplayValue('Original');
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'Latest correction' } });
    await act(async () => { expect(await work.flush()).toBe(true); });
    expect(client.getQueryData<SubtitleDraft[]>(subsetKey)?.[0]?.cues[0]?.text).toBe('Latest correction');
    mounted.rerender(<QueryClientProvider client={client}><Harness includeB={false} /></QueryClientProvider>);
    expect(work.tracks[0]?.cues[0]?.text).toBe('Latest correction');
    expect(work.dirty).toBe(false);
  });
  it.each([false, true])('keeps a finished recognition source blocked until the corrected draft has been fetched (refresh failure: %s)', async (failsInitially) => {
    saved = { ...saved, taskId: 'asr-task' };
    const recognized = { ...saved, version: 1, cues: [{ text: 'Recognized draft', startMs: 200, endMs: 1900 }] };
    let requests = 0;
    let fails = failsInitially;
    let releaseDraft!: () => void;
    const delayedDraft = new Promise<void>((resolve) => { releaseDraft = resolve; });
    server.use(http.get('/api/v1/projects/project-1/subtitles', async () => {
      if (++requests === 1) return HttpResponse.json([saved]);
      await delayedDraft;
      if (fails) return HttpResponse.json({ detail: 'Synthetic refresh failure' }, { status: 503, headers: { 'Content-Type': 'application/problem+json' } });
      return HttpResponse.json([recognized]);
    }), http.get('/api/v1/projects/project-1/tasks/asr-task', () => HttpResponse.json({
      id: 'asr-task', projectId: 'project-1', runId: null, stepKey: 'synthetic-asr-command', kind: 'SUBTITLE_TRANSCRIPTION',
      status: 'SUCCEEDED', cancelRequested: false, input: {}, output: { applied: true }, providerRequestId: null, attemptNo: 1,
      nextActionAt: '2026-11-01T00:00:00Z', version: 1, errorCode: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:01Z', completedAt: '2026-11-01T00:00:01Z',
    })));
    mount();
    await waitFor(() => { expect(work.task?.status).toBe('SUCCEEDED'); expect(requests).toBeGreaterThan(1); });
    expect(work.tracks[0]?.cues[0]?.text).toBe('Original');
    expect(work.pendingSourceVersions).toContain('version-a');
    expect(work.blocking).toBe(true);
    await act(async () => { releaseDraft(); });
    if (failsInitially) {
      await screen.findByText('Synthetic refresh failure');
      expect(work.pendingSourceVersions).toContain('version-a');
      expect(work.blocking).toBe(true);
      fails = false;
      act(() => { work.reload(); });
    }
    await waitFor(() => expect(work.pendingSourceVersions).toEqual([]));
    expect(work.tracks[0]?.cues[0]?.text).toBe('Recognized draft');
    expect(work.blocking).toBe(false);
  });
  it('does not let an older in-flight list response overwrite a newer saved correction', async () => {
    mount(); await screen.findByDisplayValue('Original');
    const older = saved;
    let requested = false;
    let releaseDraft!: () => void;
    const delayedDraft = new Promise<void>((resolve) => { releaseDraft = resolve; });
    server.use(http.get('/api/v1/projects/project-1/subtitles', async () => {
      requested = true; await delayedDraft; return HttpResponse.json([older]);
    }));
    act(() => { work.reload(); });
    await waitFor(() => expect(requested).toBe(true));
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'Newer correction' } });
    await act(async () => { expect(await work.flush()).toBe(true); });
    await act(async () => { releaseDraft(); });
    await waitFor(() => expect(work.blocking).toBe(false));
    expect(work.tracks[0]?.cues[0]?.text).toBe('Newer correction');
    expect(work.selected?.version).toBe(1);
  });
  it('persists corrected text and timing before close and reads them again on reopen without an ASR engine', async () => {
    const mounted = mount();
    await screen.findByDisplayValue('Original');
    expect(screen.getByText(t('subtitle.notConfigured'))).toBeVisible();
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'Corrected 中文' } });
    fireEvent.change(screen.getByRole('spinbutton', { name: t('subtitle.start', { index: 1 }) }), { target: { value: '250' } });
    await act(async () => { expect(await work.flush()).toBe(true); });
    expect(saved.cues[0]).toEqual({ text: 'Corrected 中文', startMs: 250, endMs: 2000 });
    mounted.unmount(); mount();
    await screen.findByDisplayValue('Corrected 中文');
    expect(screen.getByRole('spinbutton', { name: t('subtitle.start', { index: 1 }) })).toHaveValue(250);
  });
  it('retains failed or invalid corrections and refuses a close flush until the problem is resolved', async () => {
    mount(); await screen.findByDisplayValue('Original');
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'Keep this edit' } });
    failure = true;
    await act(async () => { expect(await work.flush()).toBe(false); });
    expect(screen.getByDisplayValue('Keep this edit')).toBeVisible();
    failure = false;
    fireEvent.change(screen.getByRole('spinbutton', { name: t('subtitle.end', { index: 1 }) }), { target: { value: '4000' } });
    await act(async () => { expect(await work.flush()).toBe(false); });
    expect(saved.cues[0]?.text).toBe('Original');
    fireEvent.change(screen.getByRole('spinbutton', { name: t('subtitle.end', { index: 1 }) }), { target: { value: '2500' } });
    await act(async () => { expect(await work.flush()).toBe(true); });
    await waitFor(() => expect(saved.cues[0]?.text).toBe('Keep this edit'));
  });
  it('keeps the current source and corrections after a failed source switch', async () => {
    mount(); await screen.findByDisplayValue('Original');
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'Keep A' } });
    failure = true;
    await act(async () => { await work.selectSource('version-b'); });
    expect(work.selectedSource?.versionId).toBe('version-a');
    expect(screen.getByDisplayValue('Keep A')).toBeVisible();
  });
  it('retains a conflicted edit until the user explicitly saves it over the latest server version', async () => {
    mount(); await screen.findByDisplayValue('Original');
    fireEvent.change(screen.getByRole('textbox', { name: t('subtitle.ctext', { index: 1 }) }), { target: { value: 'My correction' } });
    saved = { ...saved, version: 1, cues: [{ text: 'Another browser', startMs: 100, endMs: 1800 }] };
    await act(async () => { expect(await work.flush()).toBe(false); });
    expect(screen.getByDisplayValue('My correction')).toBeVisible();
    await userEvent.setup().click(screen.getByRole('button', { name: t('subtitle.retryMine') }));
    await waitFor(() => expect(saved.cues[0]?.text).toBe('My correction'));
    expect(saved.version).toBe(2);
  });
  it('accepts and cancels recognition for the pinned draft while keeping existing corrections', async () => {
    const task = { id: 'asr-task', projectId: 'project-1', runId: null, stepKey: 'synthetic-asr-command', kind: 'SUBTITLE_TRANSCRIPTION',
      status: 'READY', cancelRequested: false, input: {}, output: null, providerRequestId: null, attemptNo: 1,
      nextActionAt: '2026-11-01T00:00:00Z', version: 0, errorCode: null, createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z', completedAt: null };
    let acceptedBody: unknown;
    server.use(http.get('/api/v1/projects/project-1/subtitles/capabilities', () => HttpResponse.json({ availability: 'AVAILABLE', maxCues: 5000, maxFileBytes: 2097152, maxSourceDurationMs: 600000 })),
      http.post('/api/v1/projects/project-1/subtitles/draft-a/recognition', async ({ request }) => {
        acceptedBody = await request.json(); saved = { ...saved, taskId: task.id, version: 1 }; return HttpResponse.json(task);
      }),
      http.get('/api/v1/projects/project-1/tasks/asr-task', () => HttpResponse.json(task)),
      http.post('/api/v1/projects/project-1/subtitles/draft-a/recognition/cancel', () => HttpResponse.json({ ...task, status: 'CANCELED', cancelRequested: true })));
    mount(); await screen.findByDisplayValue('Original');
    await userEvent.setup().click(screen.getByRole('button', { name: t('subtitle.recognize') }));
    await screen.findByRole('button', { name: t('subtitle.cancel') });
    expect(acceptedBody).toEqual({ expectedVersion: 0, language: 'auto' });
    await userEvent.setup().click(screen.getByRole('button', { name: t('subtitle.cancel') }));
    await screen.findByText(t('subtitle.recognitionFailed'));
    expect(screen.getByDisplayValue('Original')).toBeVisible();
  });
  it('keeps an accepted recognition pending and cancellable when the following draft read fails', async () => {
    const task = { id: 'asr-task', status: 'READY' };
    let posts = 0;
    server.use(http.post('/api/v1/projects/project-1/subtitles/draft-a/recognition', () => { posts++; return HttpResponse.json(task); }),
      http.get('/api/v1/projects/project-1/subtitles/draft-a', () => HttpResponse.json({ detail: 'Read failed' }, { status: 503 })),
      http.get('/api/v1/projects/project-1/tasks/asr-task', () => HttpResponse.json(task)));
    mount(); await screen.findByDisplayValue('Original');
    await act(async () => { await work.recognize(); });
    expect(work.recognitionPending).toBe(true);
    expect(work.selected?.taskId).toBe('asr-task');
    expect(work.activeTask).toBe(true);
    await act(async () => { await work.recognize(); });
    expect(posts).toBe(1);
  });
  it('serializes task reads, drops background transcript bodies, and adopts only the selected result explicitly', async () => {
    saved = { ...saved, taskId: 'task-a' };
    const second = { ...saved, id: 'draft-b', sourceVersionId: 'version-b', taskId: 'task-b' };
    let active = 0, peak = 0, reads = 0;
    server.use(http.get('/api/v1/projects/project-1/subtitles', () => HttpResponse.json([saved, second])),
      http.get('/api/v1/projects/project-1/tasks/:id', async ({ params }) => {
        active++; peak = Math.max(peak, active); reads++;
        await new Promise((resolve) => setTimeout(resolve, 20)); active--;
        return HttpResponse.json({ id: params.id, status: 'SUCCEEDED', output: { applied: false,
          sourceVersionId: params.id === 'task-a' ? 'version-a' : 'version-b',
          cues: [{ text: `Recognized ${params.id}`, startMs: 0, endMs: 1000 }] } });
      }));
    mount();
    await waitFor(() => { expect(work.tasks).toHaveLength(2); expect(work.recognitionPending).toBe(false); });
    expect(peak).toBe(1);
    expect(work.tasks.every((task) => task.output?.cues === undefined)).toBe(true);
    expect(work.cues[0]?.text).toBe('Original');
    expect(reads).toBe(2);
    await act(async () => { await work.loadResult(); });
    expect(reads).toBe(3);
    expect(work.cues[0]?.text).toBe('Recognized task-a');
    expect(work.dirty).toBe(true);
    expect(work.tasks.every((task) => task.output?.cues === undefined)).toBe(true);
  });
  it.each(['task', 'source', 'read'] as const)('retains corrections when explicit result adoption fails on %s', async (failure) => {
    saved = { ...saved, taskId: 'task-a' };
    const task = { id: 'task-a', status: 'SUCCEEDED', output: { applied: false, sourceVersionId: 'version-a',
      cues: [{ text: 'Recognized', startMs: 0, endMs: 1000 }] } };
    server.use(http.get('/api/v1/projects/project-1/tasks/task-a', () => HttpResponse.json(task)));
    mount(); await waitFor(() => expect(work.task?.status).toBe('SUCCEEDED'));
    act(() => work.updateCues([{ text: 'Keep correction', startMs: 0, endMs: 1000 }]));
    server.use(http.get('/api/v1/projects/project-1/tasks/task-a', () => failure === 'read'
      ? HttpResponse.json({}, { status: 503 })
      : HttpResponse.json({ ...task, id: failure === 'task' ? 'wrong-task' : task.id,
        output: { ...task.output, sourceVersionId: failure === 'source' ? 'wrong-source' : task.output.sourceVersionId } })));
    await act(async () => { await work.loadResult(); });
    expect(work.error).toBeTruthy();
    expect(work.cues[0]?.text).toBe('Keep correction');
    expect(work.dirty).toBe(true);
  });
  it('blocks a source when its previously completed task status can no longer refresh', async () => {
    saved = { ...saved, taskId: 'task-a' };
    server.use(http.get('/api/v1/projects/project-1/tasks/task-a', () => HttpResponse.json({ id: 'task-a', status: 'SUCCEEDED', output: { applied: true } })));
    mount(); await waitFor(() => { expect(work.task?.status).toBe('SUCCEEDED'); expect(work.recognitionPending).toBe(false); });
    server.use(http.get('/api/v1/projects/project-1/tasks/task-a', () => HttpResponse.json({}, { status: 503 })));
    act(() => work.reload());
    await waitFor(() => expect(work.taskError).toBeTruthy());
    expect(work.pendingSourceVersions).toContain('version-a');
    expect(work.recognitionPending).toBe(true);
  });
  it('keeps local corrections and blocks composition after a budget-rejected refresh', async () => {
    mount(); await screen.findByDisplayValue('Original');
    act(() => work.updateCues([{ text: 'Keep local correction', startMs: 0, endMs: 1 }]));
    server.use(http.get('/api/v1/projects/project-1/subtitles', () => HttpResponse.json({ code: 'SUBTITLE_BATCH_TOO_LARGE', detail: 'Budget exceeded' },
      { status: 413, headers: { 'Content-Type': 'application/problem+json' } })));
    act(() => work.reload());
    await waitFor(() => expect(work.error).toBe('Budget exceeded'));
    expect(work.cues[0]?.text).toBe('Keep local correction');
    expect(work.dirty).toBe(true);
    expect(work.blocking).toBe(true);
  });
  it('serializes dirty-source saves while retaining failed corrections', async () => {
    const second = { ...saved, id: 'draft-b', sourceVersionId: 'version-b' };
    let active = 0, peak = 0;
    server.use(http.get('/api/v1/projects/project-1/subtitles', () => HttpResponse.json([saved, second])),
      http.put('/api/v1/projects/project-1/subtitles/:id', async ({ params, request }) => {
        active++; peak = Math.max(peak, active);
        const body = await request.json() as { cues: SubtitleDraft['cues'] };
        await new Promise((resolve) => setTimeout(resolve, 20)); active--;
        if (params.id === 'draft-b') return HttpResponse.json({}, { status: 503 });
        return HttpResponse.json({ ...saved, version: 1, cues: body.cues });
      }));
    const client = createQueryClient();
    const hook = renderHook(({ id }) => useSubtitleWorkbench('project-1', [{ versionId: id, title: id, kind: 'VIDEO' }]), {
      initialProps: { id: 'version-a' }, wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
    });
    await waitFor(() => expect(hook.result.current.selected).toBeDefined());
    act(() => hook.result.current.updateCues([{ text: 'A correction', startMs: 0, endMs: 1 }]));
    hook.rerender({ id: 'version-b' });
    await waitFor(() => expect(hook.result.current.selected?.id).toBe('draft-b'));
    act(() => hook.result.current.updateCues([{ text: 'B correction', startMs: 0, endMs: 1 }]));
    await act(async () => { expect(await hook.result.current.flush()).toBe(false); });
    expect(peak).toBe(1);
    expect(hook.result.current.dirty).toBe(true);
    expect(hook.result.current.cues[0]?.text).toBe('B correction');
  });
  it.each(['SRT', 'VTT'] as const)('imports a selected %s file through the versioned server API and updates the correction draft', async (format) => {
    const fileText = format === 'SRT' ? '1\n00:00:00,200 --> 00:00:02,000\nImported text\n\n' : 'WEBVTT\n\n00:00.200 --> 00:02.000\nImported text\n\n';
    let imported: unknown;
    server.use(http.post('/api/v1/projects/project-1/subtitles/draft-a/import', async ({ request }) => {
      imported = await request.json(); saved = { ...saved, version: 1, cues: [{ text: 'Imported text', startMs: 200, endMs: 2000 }] };
      return HttpResponse.json(saved);
    }));
    mount(); await screen.findByDisplayValue('Original');
    const file = new File([fileText], `captions.${format.toLowerCase()}`, { type: format === 'SRT' ? 'application/x-subrip' : 'text/vtt' });
    Object.defineProperty(file, 'arrayBuffer', { value: async () => new TextEncoder().encode(fileText).buffer });
    await act(async () => { await work.importFile(file); });
    expect(imported).toEqual({ expectedVersion: 0, format, contents: fileText });
    expect(screen.getByDisplayValue('Imported text')).toBeVisible();
  });
});
