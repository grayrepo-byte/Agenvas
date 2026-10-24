import { useEffect, useRef } from "react";
import { t } from "../../shared/i18n";
import { useCanvasStore } from "./canvasStore";

type Guard = () => Promise<void>;
const editors = new Map<string, Set<Guard>>();
const RECOVERY_WAIT_MS = 30_000;

/** Editors keep their own drafts; a canvas copy waits for their acknowledged saves. */
export function useCanvasEditingGuard(projectId: string, itemId: string | undefined, flush: Guard) {
  const latest = useRef(flush);
  latest.current = flush;
  useEffect(() => {
    if (!itemId) return;
    const key = projectId + ":" + itemId;
    const guard = () => latest.current();
    const guards = editors.get(key) ?? new Set<Guard>();
    guards.add(guard);
    editors.set(key, guards);
    return () => {
      guards.delete(guard);
      if (!guards.size) editors.delete(key);
    };
  }, [projectId, itemId]);
}

async function awaitRecovery(key: string) {
  if (useCanvasStore.getState().mediaDraftRecoveries[key]?.saving) {
    await new Promise<void>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        unsubscribe();
        reject(new Error(t("canvas.edit.draftsPending")));
      }, RECOVERY_WAIT_MS);
      const unsubscribe = useCanvasStore.subscribe((state) => {
        if (state.mediaDraftRecoveries[key]?.saving) return;
        window.clearTimeout(timer);
        unsubscribe();
        resolve();
      });
    });
  }
  if (useCanvasStore.getState().mediaDraftRecoveries[key]) {
    throw new Error(t("canvas.edit.draftsPending"));
  }
}

export async function flushCanvasEditors(projectId: string, itemIds: readonly string[]) {
  for (const id of itemIds) {
    const key = projectId + ":" + id;
    for (const flush of editors.get(key) ?? []) await flush();
    await awaitRecovery(key);
  }
}
