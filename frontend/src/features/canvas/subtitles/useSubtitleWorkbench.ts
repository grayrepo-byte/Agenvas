import { useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import { createStore } from "zustand/vanilla";
import { useStore } from "zustand";
import { ApiError, type Task } from "../../../shared/api/client";
import { t } from "../../../shared/i18n";
import * as api from "./subtitlesApi";
import { downloadSubtitleFile, SUBTITLE_MAX_FILE_BYTES, validSubtitleCues } from "./subtitleExchange";
import type { SubtitleCue } from "./subtitleTimeline";

export type SubtitleSource = { versionId: string; title: string; kind: "VIDEO" | "AUDIO" };
type Edit = { draft: api.SubtitleDraft; cues: SubtitleCue[]; revision: number; error?: string; conflict?: boolean };
const active = (task?: Task) => task?.status === "READY" || task?.status === "RUNNING";
const message = (error: unknown) => (error instanceof Error ? error.message : t("subtitle.error"));
const mergeDrafts = (existing: api.SubtitleDraft[], incoming: api.SubtitleDraft[]) => {
  const result = new Map(existing.map(draft => [draft.id, draft]));
  for (const draft of incoming) {
    if ((result.get(draft.id)?.version ?? -1) <= draft.version) result.set(draft.id, draft);
  }
  return [...result.values()];
};

/** Query owns saved drafts; the per-dialog Zustand store owns only unsaved corrections and CAS bases. */
export function useSubtitleWorkbench(projectId: string, sources: readonly SubtitleSource[]) {
  const queryClient = useQueryClient();
  const sourceKey = JSON.stringify([...new Set(sources.map(source => source.versionId))].sort());
  const queryKey = ["subtitle-drafts", projectId, sourceKey] as const;
  // A fixed source can appear in many clip/voice combinations. Keep even inactive or previously
  // empty lists current, and never let an older in-flight GET overwrite a newer CAS response.
  const updateCache = useCallback(
    (draft: api.SubtitleDraft) => {
      queryClient.setQueriesData<api.SubtitleDraft[]>(
        {
          queryKey: ["subtitle-drafts", projectId],
          predicate: query => {
            const key = query.queryKey[2];
            return typeof key === "string" && (JSON.parse(key) as string[]).includes(draft.sourceVersionId);
          },
        },
        (old = []) => mergeDrafts(old, [draft]),
      );
    },
    [queryClient, projectId],
  );
  const drafts = useQuery({
    queryKey,
    queryFn: async ({ signal }) => {
      const loaded = await api.listSubtitleDrafts(projectId, JSON.parse(sourceKey) as string[], signal);
      for (const draft of loaded) updateCache(draft);
      return mergeDrafts(queryClient.getQueryData<api.SubtitleDraft[]>(queryKey) ?? [], loaded);
    },
    // Complete lists are large; old source combinations must not each retain another budget.
    enabled: sources.length > 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  const capabilities = useQuery({
    queryKey: ["subtitle-capabilities", projectId],
    queryFn: ({ signal }) => api.subtitleCapabilities(projectId, signal),
    retry: false,
  });
  const [store] = useState(() => createStore<{ edits: Record<string, Edit> }>(() => ({ edits: {} })));
  const edits = useStore(store, state => state.edits);
  const [selectedId, setSelectedId] = useState(sources[0]?.versionId ?? "");
  const selectedSource = sources.find(source => source.versionId === selectedId) ?? sources[0];
  const selected = drafts.data?.find(draft => draft.sourceVersionId === selectedSource?.versionId);
  const selectedEdit = selected ? edits[selected.id] : undefined;
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [operating, setOperating] = useState(false);
  const [language, setLanguage] = useState("auto");
  const flushing = useRef<Promise<boolean> | null>(null);
  const completed = useRef(new Set<string>());
  const refreshing = useRef(new Set<string>());
  const [refreshEpoch, setRefreshEpoch] = useState(0);
  const taskQueries = useQueries({
    queries: (drafts.data ?? [])
      .filter(draft => draft.taskId)
      .map(draft => ({
        queryKey: ["subtitle-task-status", projectId, draft.taskId],
        queryFn: ({ signal }: { signal: AbortSignal }) => api.readSubtitleTaskStatus(projectId, draft.taskId!, signal),
        retry: false,
        gcTime: 0,
        refetchInterval: (query: { state: { data?: Task } }) =>
          !query.state.data || active(query.state.data) ? 1000 : (false as const),
      })),
  });
  const taskSignature = taskQueries.map(query => (query.data ? `${query.data.id}:${query.data.status}` : "")).join("|");
  const taskList = taskQueries.flatMap(query => (query.data ? [query.data] : []));
  const task = taskList.find(candidate => candidate.id === selected?.taskId);
  const selectedTaskIndex = (drafts.data ?? [])
    .filter(draft => draft.taskId)
    .findIndex(draft => draft.taskId === selected?.taskId);
  const taskError = taskQueries[selectedTaskIndex]?.error;
  useEffect(() => {
    const entries = taskSignature
      .split("|")
      .filter(entry => entry && !entry.endsWith(":READY") && !entry.endsWith(":RUNNING"))
      .map(entry => `${sourceKey}|${entry}`)
      .filter(entry => !completed.current.has(entry) && !refreshing.current.has(entry));
    if (entries.length === 0) return;
    for (const entry of entries) refreshing.current.add(entry);
    // A terminal Task can precede its draft GET. Keep composition blocked until that GET succeeds;
    // observing SUCCEEDED alone must never unlock export with pre-recognition or empty captions.
    void queryClient
      .invalidateQueries({ queryKey: ["subtitle-drafts", projectId, sourceKey] }, { throwOnError: true })
      .then(() => {
        for (const entry of entries) completed.current.add(entry);
        setRefreshEpoch(value => value + 1);
      })
      .catch(() => {
        /* The query error stays visible; explicit reload retries this transition. */
      })
      .finally(() => {
        for (const entry of entries) refreshing.current.delete(entry);
      });
  }, [taskSignature, queryClient, projectId, sourceKey, refreshEpoch]);
  const pendingDraft = (draft: api.SubtitleDraft) =>
    !!draft.taskId &&
    (taskQueries[(drafts.data ?? []).filter(entry => entry.taskId).findIndex(entry => entry.id === draft.id)]
      ?.isError ||
      !taskList.some(entry => entry.id === draft.taskId) ||
      taskList.some(
        entry =>
          entry.id === draft.taskId &&
          (active(entry) || !completed.current.has(`${sourceKey}|${entry.id}:${entry.status}`)),
      ));
  const flush = useCallback((): Promise<boolean> => {
    if (flushing.current) return flushing.current;
    const promise = (async () => {
      setSaving(true);
      try {
        for (let round = 0; round < 4; round++) {
          const pending = Object.entries(store.getState().edits);
          if (pending.length === 0) return true;
          let failed = false;
          // One in-flight PUT at a time, including large corrections. Capture each queued
          // edit immediately before saving so edits made while waiting are not lost.
          for (const [id] of pending) {
            const edit = store.getState().edits[id];
            if (!edit) continue;
            try {
              if (!validSubtitleCues(edit.cues, edit.draft.sourceDurationMs)) throw new Error(t("subtitle.invalid"));
              const saved = await api.saveSubtitleDraft(projectId, id, edit.draft.version, edit.cues);
              updateCache(saved);
              store.setState(state => {
                const next = { ...state.edits },
                  current = next[id];
                if (current?.revision === edit.revision) delete next[id];
                else if (current) next[id] = { ...current, draft: saved };
                return { edits: next };
              });
            } catch (failure) {
              store.setState(state => ({
                edits: {
                  ...state.edits,
                  [id]: {
                    ...state.edits[id]!,
                    error: message(failure),
                    conflict: failure instanceof ApiError && failure.status === 409,
                  },
                },
              }));
              failed = true;
            }
          }
          if (failed) return false;
        }
        return Object.keys(store.getState().edits).length === 0;
      } finally {
        setSaving(false);
      }
    })();
    flushing.current = promise;
    void promise.finally(() => {
      flushing.current = null;
    });
    return promise;
  }, [store, projectId, updateCache]);
  function updateCues(cues: SubtitleCue[]) {
    if (!selected) return;
    setError(null);
    store.setState(state => ({
      edits: {
        ...state.edits,
        [selected.id]: {
          draft: state.edits[selected.id]?.draft ?? selected,
          cues,
          revision: (state.edits[selected.id]?.revision ?? 0) + 1,
        },
      },
    }));
  }
  async function perform(action: () => Promise<void>) {
    setOperating(true);
    setError(null);
    try {
      await action();
    } catch (failure) {
      setError(message(failure));
    } finally {
      setOperating(false);
    }
  }
  async function selectSource(id: string) {
    if (await flush()) {
      setSelectedId(id);
      setError(null);
    }
  }
  async function create() {
    if (!selectedSource) return;
    await perform(async () => updateCache(await api.createSubtitleDraft(projectId, selectedSource.versionId)));
  }
  async function recognize() {
    if (!selected || active(task) || pendingDraft(selected) || !(await flush())) return;
    await perform(async () => {
      const current =
        queryClient.getQueryData<api.SubtitleDraft[]>(queryKey)?.find(draft => draft.id === selected.id) ?? selected;
      const accepted = await api.recognizeSubtitles(
        projectId,
        current.id,
        current.version,
        language,
        crypto.randomUUID(),
      );
      queryClient.setQueryData(["subtitle-task-status", projectId, accepted.id], api.subtitleTaskStatus(accepted));
      // Acceptance already persisted attachTask's CAS increment. Publish that identity
      // before the follow-up GET: a failed read must not lose polling/cancel or unlock export.
      updateCache({ ...current, taskId: accepted.id, version: current.version + 1 });
      updateCache(await api.getSubtitleDraft(projectId, current.id));
    });
  }
  async function cancel() {
    if (!selected?.taskId) return;
    await perform(async () => {
      const canceled = await api.cancelSubtitleRecognition(projectId, selected.id, selected.taskId!);
      queryClient.setQueryData(["subtitle-task-status", projectId, canceled.id], api.subtitleTaskStatus(canceled));
    });
  }
  async function retryMine() {
    if (!selectedEdit || !selected) return;
    await perform(async () => {
      const latest = await api.getSubtitleDraft(projectId, selected.id);
      store.setState(state => ({
        edits: {
          ...state.edits,
          [selected.id]: { ...state.edits[selected.id]!, draft: latest, error: undefined, conflict: false },
        },
      }));
      await flush();
    });
  }
  async function importFile(file: File) {
    if (!selected || !(await flush())) return;
    await perform(async () => {
      const extension = file.name.split(".").at(-1)?.toUpperCase();
      if (file.size > SUBTITLE_MAX_FILE_BYTES || (extension !== "SRT" && extension !== "VTT"))
        throw new Error(t("subtitle.invalid"));
      const contents = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer());
      const current =
        queryClient.getQueryData<api.SubtitleDraft[]>(queryKey)?.find(draft => draft.id === selected.id) ?? selected;
      updateCache(await api.importSubtitleFile(projectId, selected.id, current.version, extension, contents));
    });
  }
  async function exportFile(format: api.SubtitleFormat) {
    if (!selected || !(await flush())) return;
    await perform(async () =>
      downloadSubtitleFile(await api.exportSourceSubtitles(projectId, selected.id, format), format),
    );
  }
  async function loadResult() {
    if (!selected || !task || task.status !== "SUCCEEDED") return;
    await perform(async () => {
      const result = await api.readSubtitleTaskResult(projectId, task.id);
      const output = result.output;
      if (
        result.id !== selected.taskId ||
        result.status !== "SUCCEEDED" ||
        !output ||
        output.sourceVersionId !== selected.sourceVersionId ||
        !Array.isArray(output.cues)
      )
        throw new Error(t("subtitle.invalid"));
      const cues = output.cues.map((value: unknown): SubtitleCue => {
        if (
          !value ||
          typeof value !== "object" ||
          !("text" in value) ||
          typeof value.text !== "string" ||
          !("startMs" in value) ||
          typeof value.startMs !== "number" ||
          !("endMs" in value) ||
          typeof value.endMs !== "number"
        )
          throw new Error(t("subtitle.invalid"));
        return { text: value.text, startMs: value.startMs, endMs: value.endMs };
      });
      if (!validSubtitleCues(cues, selected.sourceDurationMs)) throw new Error(t("subtitle.invalid"));
      updateCues(cues);
    });
  }

  const tracks = (drafts.data ?? []).map(draft =>
    edits[draft.id] ? { ...draft, cues: edits[draft.id]!.cues } : draft,
  );
  return {
    sources,
    selectedSource,
    selected,
    cues: selectedEdit?.cues ?? selected?.cues ?? [],
    tracks,
    task,
    tasks: taskList,
    taskError,
    taskPending: !!selected?.taskId && !task,
    recognitionPending: (drafts.data ?? []).some(pendingDraft),
    pendingSourceVersions: (drafts.data ?? []).filter(pendingDraft).map(draft => draft.sourceVersionId),
    activeTask: active(task),
    capabilities: capabilities.data,
    loading: sources.length > 0 && drafts.isPending,
    blocking:
      (sources.length > 0 && (drafts.isPending || drafts.isFetching || !!drafts.error)) ||
      Object.values(edits).some(edit => !validSubtitleCues(edit.cues, edit.draft.sourceDurationMs)),
    error:
      selectedEdit?.error ??
      error ??
      (drafts.error
        ? message(drafts.error)
        : taskError
          ? message(taskError)
          : capabilities.error
            ? message(capabilities.error)
            : null),
    conflict: !!selectedEdit?.conflict,
    dirty: Object.keys(edits).length > 0,
    saving,
    operating,
    language,
    setLanguage,
    flush,
    updateCues,
    selectSource,
    create,
    recognize,
    cancel,
    retryMine,
    importFile,
    exportFile,
    loadResult,
    reload: () => {
      setRefreshEpoch(value => value + 1);
      void drafts.refetch();
      void capabilities.refetch();
      for (const query of taskQueries) void query.refetch();
    },
  };
}
