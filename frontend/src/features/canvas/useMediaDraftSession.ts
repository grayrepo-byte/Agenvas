import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, HTTP_STATUS, getMediaDraft, saveMediaDraft,
  type Artifact, type MediaDraft, type SaveMediaDraftRequest } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { useCanvasStore } from "./canvasStore";
import { ASPECT_RATIO_OPTIONS, type MediaDraftFields } from "./mediaDraftCapability";
import { saveClosedMediaDraft, type PendingMediaDraftSave } from "./mediaDraftCloseSave";
import { mediaDraftQueryOptions } from "./mediaDisplay";

const AUTOSAVE_DELAY_MS = 650;

type SessionState = {
  fields: MediaDraftFields | null;
  expectedVersion: number | null;
  dirty: boolean;
  error: Error | null;
};

type SavedFields = { fields: MediaDraftFields; dirty: boolean; error?: Error | null };
type PendingBusinessWrite = { result: Promise<Error | null> };

/** These gates deliberately differ: removing an input pauses autosave, but not clean reads. */
type SessionPolicy = {
  readOnly: boolean;
  refreshBlocked: boolean;
  autosaveBlocked: boolean;
  closeBlocked: boolean;
};

export function fieldsFromMediaDraft(draft: MediaDraft): MediaDraftFields {
  return {
    prompt: draft.prompt, parameters: draft.parameters ?? {}, styleId: draft.styleId ?? null,
    durationSeconds: draft.durationSeconds, capabilityId: draft.capabilityId,
    videoInputMode: draft.videoInputMode,
    mediaInputs: (draft.mediaInputs ?? []).map(({ versionId, role, color }) => ({ versionId, role, color })),
    mentions: draft.mentions ?? [],
  };
}

/** Owns one card's local input and ordinary CAS saves; business writes choose their own merged fields. */
export function useMediaDraftSession(projectId: string, canvasItemId: string) {
  const queryClient = useQueryClient();
  const draftOptions = useMemo(() => mediaDraftQueryOptions(projectId, canvasItemId), [projectId, canvasItemId]);
  const key = draftOptions.queryKey;
  const draft = useQuery(draftOptions);
  const recoveryKey = `${projectId}:${canvasItemId}`;
  const recovery = useCanvasStore((store) => store.mediaDraftRecoveries[recoveryKey]);
  const [state, setState] = useState<SessionState>({ fields: null, expectedVersion: null, dirty: false, error: null });
  const latest = useRef(state);
  const readOnly = useRef(false);
  const pendingSave = useRef<PendingMediaDraftSave | undefined>(undefined);
  const pendingBusinessWrites = useRef(new Set<PendingBusinessWrite>());
  // Every transition updates the async snapshot together with its rendered state.
  const update = useCallback((change: (current: SessionState) => SessionState) => {
    const next = change(latest.current);
    latest.current = next;
    setState(next);
  }, []);
  const getLatest = useCallback(() => latest.current, []);
  const isReadOnly = useCallback(() => readOnly.current, []);
  const setError = useCallback((error: Error | null) => update((current) => ({ ...current, error })), [update]);
  const edit = useCallback((change: (fields: MediaDraftFields) => MediaDraftFields) => {
    if (readOnly.current) return;
    update((current) => ({ ...current,
      fields: current.fields ? change(current.fields) : null,
      dirty: true,
      error: current.error instanceof ApiError && current.error.status === HTTP_STATUS.CONFLICT ? current.error : null,
    }));
  }, [update]);
  const acceptSaved = useCallback((saved: MediaDraft, next: SavedFields = { fields: fieldsFromMediaDraft(saved), dirty: false, error: null }) => {
    update((current) => ({ ...current, fields: next.fields, expectedVersion: saved.version, dirty: next.dirty,
      ...(next.error !== undefined ? { error: next.error } : {}) }));
    queryClient.setQueryData(key, saved);
  }, [update, queryClient, key]);
  const saveMutation = useMutation({
    mutationFn: (request: SaveMediaDraftRequest) => {
      if (readOnly.current) throw new Error(t("media.editor.editingLocked"));
      const result = saveMediaDraft(projectId, canvasItemId, request);
      pendingSave.current = { request, result };
      return result;
    },
    onMutate: (request) => ({ capabilityChanged: queryClient.getQueryData<MediaDraft>(key)?.capabilityId !== request.capabilityId }),
    onSuccess: (saved, request, context) => {
      pendingSave.current = undefined;
      const { expectedVersion: _submittedVersion, ...submitted } = request;
      void _submittedVersion;
      update((current) => ({ ...current, expectedVersion: saved.version, error: null,
        dirty: current.fields && JSON.stringify(current.fields) === JSON.stringify(submitted) ? false : current.dirty }));
      queryClient.setQueryData(key, saved);
      // A capability switch may remove unmatched connection sources in the save transaction.
      if (context?.capabilityChanged) void queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
    },
    onError: (error) => { pendingSave.current = undefined; setError(error); },
  });
  const { isPending, mutate, mutateAsync } = saveMutation;
  const awaitPendingSave = useCallback(async () => pendingSave.current ? await pendingSave.current.result : undefined, []);
  const beginBusinessWrite = useCallback(() => {
    let finish: (error: Error | null) => void = () => {};
    const result = new Promise<Error | null>((resolve) => { finish = resolve; });
    const pending = { result };
    pendingBusinessWrites.current.add(pending);
    // Finish from onSettled, after the caller has merged its fields and acknowledged the Query cache.
    return (error: Error | null) => {
      if (!pendingBusinessWrites.current.delete(pending)) return;
      finish(error);
    };
  }, []);
  const refreshVersion = useCallback(async () => {
    try {
      const fresh = await getMediaDraft(projectId, canvasItemId);
      queryClient.setQueryData(key, fresh);
      update((current) => ({ ...current, expectedVersion: fresh.version, error: null }));
      return true;
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(t("media.editor.draftRefreshFailed")));
      return false;
    }
  }, [projectId, canvasItemId, queryClient, key, update, setError]);
  const restore = useCallback(() => {
    if (latest.current.fields || !recovery || recovery.saving) return;
    const { expectedVersion, ...fields } = recovery.request;
    update(() => ({ fields, expectedVersion, dirty: true, error: recovery.error }));
    useCanvasStore.getState().clearMediaDraftRecovery(recoveryKey);
  }, [recovery, recoveryKey, update]);
  const adoptCleanDraft = useCallback(() => {
    const current = latest.current;
    if (recovery || !draft.data || current.dirty || isPending || current.error) return;
    // Earlier GETs may finish after a newer save; acknowledged CAS versions never move backward.
    if (current.expectedVersion !== null && draft.data.version < current.expectedVersion) return;
    const saved = draft.data;
    update((current) => ({ ...current, fields: fieldsFromMediaDraft(saved), expectedVersion: saved.version }));
  }, [recovery, draft.data, isPending, update]);
  const flushAfterBusinessWrites = useCallback(async (pendingWrites: PendingBusinessWrite[], blocked: boolean) => {
    const failures = await Promise.all(pendingWrites.map((pending) => pending.result));
    // onSettled runs after each caller's operation-specific merge and Query acknowledgement.
    const acknowledged = latest.current;
    if (!acknowledged.fields || acknowledged.expectedVersion === null) return;
    const store = useCanvasStore.getState();
    const request = { ...acknowledged.fields, expectedVersion: acknowledged.expectedVersion };
    const error = acknowledged.error ?? failures.find((failure) => failure !== null);
    if (!error && !acknowledged.dirty) {
      store.clearMediaDraftRecovery(recoveryKey);
      store.clearImageRatioDraft(recoveryKey);
      return;
    }
    if (error || readOnly.current || blocked) {
      store.setMediaDraftRecovery(recoveryKey, { request, saving: false,
        error: error ?? new Error(t(readOnly.current ? "media.editor.editingLocked" : "media.editor.transferPendingHint")) });
      return;
    }
    await saveClosedMediaDraft(queryClient, projectId, canvasItemId, request, pendingSave.current);
  }, [queryClient, projectId, canvasItemId, recoveryKey]);
  const close = useCallback((blocked: boolean) => {
    const store = useCanvasStore.getState();
    const pendingWrites = [...pendingBusinessWrites.current];
    const current = latest.current;
    if (pendingWrites.length && current.fields && current.expectedVersion !== null) {
      store.setMediaDraftRecovery(recoveryKey, { request: { ...current.fields, expectedVersion: current.expectedVersion },
        saving: true, error: null });
      void flushAfterBusinessWrites(pendingWrites, blocked);
      return;
    }
    if (current.dirty && current.fields && current.expectedVersion !== null) {
      const request = { ...current.fields, expectedVersion: current.expectedVersion };
      // Accepted transfers own this CAS version; retain local edits rather than racing them.
      if (readOnly.current || current.error || blocked) {
        store.setMediaDraftRecovery(recoveryKey, { request, saving: false,
          error: current.error ?? new Error(t(readOnly.current ? "media.editor.editingLocked" : "media.editor.transferPendingHint")) });
      } else void saveClosedMediaDraft(queryClient, projectId, canvasItemId, request, pendingSave.current);
    } else if (!store.mediaDraftRecoveries[recoveryKey]) {
      store.clearImageRatioDraft(recoveryKey);
    }
  }, [queryClient, projectId, canvasItemId, recoveryKey, flushAfterBusinessWrites]);

  return { draft, queryKey: key, recovery, state, save: { isPending, mutate, mutateAsync }, edit, acceptSaved, setError,
    refreshVersion, getLatest, awaitPendingSave, beginBusinessWrite, isReadOnly,
    // Only the companion lifecycle hook coordinates these transitions with editor activity.
    lifecycle: { readOnly, recoveryKey, restore, adoptCleanDraft, close } };
}

/** Called after business mutations exist, so their activity can gate the session without a dependency cycle. */
export function useMediaDraftSessionLifecycle(session: ReturnType<typeof useMediaDraftSession>, kind: Artifact["kind"], policy: SessionPolicy) {
  const { state: { fields, expectedVersion, dirty, error }, draft, recovery, save } = session;
  const { readOnly, recoveryKey, restore, adoptCleanDraft, close } = session.lifecycle;
  const closeBlocked = useRef(policy.closeBlocked);
  useLayoutEffect(() => {
    readOnly.current = policy.readOnly;
    closeBlocked.current = policy.closeBlocked;
  }, [readOnly, policy.readOnly, policy.closeBlocked]);
  const localAspectRatio = fields?.parameters.aspectRatio ?? ASPECT_RATIO_OPTIONS.find((ratio) => ratio !== "AUTO"
    && ratio === fields?.parameters.dynamicValues?.aspect_ratio);
  useEffect(() => {
    if (kind === "IMAGE" && localAspectRatio !== undefined) {
      useCanvasStore.getState().setImageRatioDraft(recoveryKey, localAspectRatio);
    } else if (!useCanvasStore.getState().mediaDraftRecoveries[recoveryKey]) {
      useCanvasStore.getState().clearImageRatioDraft(recoveryKey);
    }
  }, [kind, localAspectRatio, recoveryKey]);
  useEffect(() => () => close(closeBlocked.current), [close]);
  useEffect(() => restore(), [restore, fields]);
  useEffect(() => {
    if (!policy.refreshBlocked) adoptCleanDraft();
  }, [adoptCleanDraft, policy.refreshBlocked, draft.data, dirty, save.isPending, error, expectedVersion, recovery]);
  useEffect(() => {
    if (policy.readOnly || policy.autosaveBlocked || !dirty || !fields || expectedVersion === null || save.isPending || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, save.mutate, error, policy.readOnly, policy.autosaveBlocked]);
}
