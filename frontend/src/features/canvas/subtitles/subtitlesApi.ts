import { ApiError, getTask, readJson, readText, writeJson, type Task } from '../../../shared/api/client';
import type { components } from '../../../shared/api/schema';
import { t } from '../../../shared/i18n';

export type SubtitleDraft = components['schemas']['SubtitleDraft'];
export type SubtitleCapabilities = components['schemas']['SubtitleCapabilities'];
export type SubtitleFormat = 'SRT' | 'VTT';
const base = (project: string) => `/api/v1/projects/${encodeURIComponent(project)}/subtitles`;
// A whole legal source (5,000 × 500 UTF-16 characters) fits; multi-source lists
// have a separate aggregate memory budget. Never publish a partial timeline.
const MAX_LOADED_CUES = 10_000;
const MAX_LOADED_BYTES = 8 * 1024 * 1024;
export async function listSubtitleDrafts(project: string, sources: readonly string[], signal?: AbortSignal): Promise<SubtitleDraft[]> {
  const result: SubtitleDraft[] = [];
  const unique = [...new Set(sources)];
  let cues = 0, bytes = 2;
  for (let i = 0; i < unique.length; i += 100) {
    signal?.throwIfAborted();
    const ids = unique.slice(i, i + 100);
    const loaded = await readJson<SubtitleDraft[]>(`${base(project)}?${new URLSearchParams(ids.map((id) => ['sourceVersionId', id]))}`, t('subtitle.error'), signal);
    for (const draft of loaded) {
      cues += draft.cues.length;
      bytes += new TextEncoder().encode(JSON.stringify(draft)).byteLength + 1;
      if (cues > MAX_LOADED_CUES || bytes > MAX_LOADED_BYTES) throw new ApiError(413, 'SUBTITLE_BATCH_TOO_LARGE', t('subtitle.budget'), false);
    }
    result.push(...loaded);
  }
  return result;
}
export const getSubtitleDraft = (project: string, id: string) => readJson<SubtitleDraft>(`${base(project)}/${id}`, t('subtitle.error'));
export const subtitleCapabilities = (project: string, signal?: AbortSignal) => readJson<SubtitleCapabilities>(`${base(project)}/capabilities`, t('subtitle.error'), signal);
export const createSubtitleDraft = (project: string, sourceVersionId: string) => writeJson<SubtitleDraft>(base(project),
  { method: 'POST', body: JSON.stringify({ sourceVersionId }) }, t('subtitle.error'));
export const saveSubtitleDraft = (project: string, id: string, expectedVersion: number, cues: SubtitleDraft['cues']) => writeJson<SubtitleDraft>(`${base(project)}/${id}`,
  { method: 'PUT', body: JSON.stringify({ expectedVersion, cues }) }, t('subtitle.error'));
export const importSubtitleFile = (project: string, id: string, expectedVersion: number, format: SubtitleFormat, contents: string) => writeJson<SubtitleDraft>(`${base(project)}/${id}/import`,
  { method: 'POST', body: JSON.stringify({ expectedVersion, format, contents }) }, t('subtitle.error'));
export const exportSourceSubtitles = (project: string, id: string, format: SubtitleFormat) => readText(`${base(project)}/${id}/export?format=${format}`, t('subtitle.error'));
export const recognizeSubtitles = (project: string, id: string, expectedVersion: number, language: string, key: string) => writeJson<Task>(`${base(project)}/${id}/recognition`,
  { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ expectedVersion, language }) }, t('subtitle.error'));
export const cancelSubtitleRecognition = (project: string, id: string, taskId: string) => writeJson<Task>(`${base(project)}/${id}/recognition/cancel`,
  { method: 'POST', body: JSON.stringify({ taskId }) }, t('subtitle.error'));

// Task output includes the original transcript. Read one at a time and discard its
// body before populating background status caches; only explicit adoption needs it.
let taskReadQueue: Promise<void> = Promise.resolve();
function queueTaskRead<T>(read: () => Promise<T>): Promise<T> {
  const result = taskReadQueue.then(read);
  taskReadQueue = result.then(() => undefined, () => undefined);
  return result;
}
export function subtitleTaskStatus(task: Task): Task {
  return { ...task, input: {}, output: task.output ? {
    applied: task.output.applied, sourceVersionId: task.output.sourceVersionId,
  } : null };
}
export function readSubtitleTaskStatus(project: string, id: string, signal?: AbortSignal): Promise<Task> {
  return queueTaskRead(async () => {
    signal?.throwIfAborted();
    const task = await getTask(project, id);
    signal?.throwIfAborted();
    return subtitleTaskStatus(task);
  });
}
export function readSubtitleTaskResult(project: string, id: string): Promise<Task> {
  return queueTaskRead(() => getTask(project, id));
}
