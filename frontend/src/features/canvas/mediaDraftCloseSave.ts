import type { QueryClient } from "@tanstack/react-query";
import { saveMediaDraft, type MediaDraft, type SaveMediaDraftRequest } from "../../shared/api/client";
import { useCanvasStore } from "./canvasStore";

export type PendingMediaDraftSave = { request: SaveMediaDraftRequest; result: Promise<MediaDraft> };

function sameFields(left: SaveMediaDraftRequest, right: SaveMediaDraftRequest) {
  return JSON.stringify({ ...left, expectedVersion: undefined })
    === JSON.stringify({ ...right, expectedVersion: undefined });
}

/** Closing an editor flushes its debounce, but never duplicates or races an existing CAS save. */
export async function saveClosedMediaDraft(client: QueryClient, projectId: string, canvasItemId: string,
  request: SaveMediaDraftRequest, pending?: PendingMediaDraftSave) {
  const key = `${projectId}:${canvasItemId}`;
  const store = useCanvasStore.getState();
  store.setMediaDraftRecovery(key, { request, saving: true, error: null });
  try {
    let saved: MediaDraft;
    if (pending) {
      const previous = await pending.result;
      request = { ...request, expectedVersion: previous.version };
      saved = sameFields(request, pending.request) ? previous
        : await saveMediaDraft(projectId, canvasItemId, request);
    } else {
      saved = await saveMediaDraft(projectId, canvasItemId, request);
    }
    // Persisted query data must be visible before removing the optimistic frame.
    client.setQueryData(["media-draft", projectId, canvasItemId], saved);
    store.clearMediaDraftRecovery(key);
    store.clearImageRatioDraft(key);
  } catch (failure) {
    // Retain the complete input for an explicit retry on reopening; never retry a conflict here.
    store.setMediaDraftRecovery(key, { request, saving: false,
      error: failure instanceof Error ? failure : new Error("工作草稿保存失败") });
  }
}
