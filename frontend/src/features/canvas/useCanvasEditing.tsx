import { createClientId } from "../../shared/lib/clientId";
import { createContext, useCallback, useContext, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  HTTP_STATUS,
  editCanvas,
  replayCanvasEdit,
  type Canvas,
  type CanvasEditRequest,
  type CanvasEditResponse,
  type CanvasEditReplayRequest,
  type CanvasItem,
} from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { useCanvasStore } from "./canvasStore";
import {
  acknowledgeCanvasEdit,
  acknowledgeCanvasReplay,
  emptyCanvasHistory,
  type CanvasHistory,
} from "./canvasHistory";
import { flushCanvasEditors } from "./canvasEditingGuards";
import { captureCanvasAcknowledgement } from "./canvasAcknowledgement";

type EditInput = Omit<CanvasEditRequest, "commandId"> & { commandId?: string };
type EditFeedback = { showPendingNotice?: boolean };
type Pending =
  | {
      type: "EDIT";
      request: CanvasEditRequest;
      onAccepted?: (saved: CanvasEditResponse) => void;
      showPendingNotice?: boolean;
    }
  | {
      type: "REPLAY";
      receiptId: string;
      request: CanvasEditReplayRequest;
    };
/** Layout saves can suppress the transient notice while keeping busy state and failure recovery. */
export type CanvasEditExecutor = (
  input: EditInput,
  onAccepted?: (saved: CanvasEditResponse) => void,
  feedback?: EditFeedback,
) => Promise<CanvasEditResponse>;
export const CanvasEditingContext = createContext<CanvasEditExecutor | null>(null);
export const useCanvasEditingExecutor = () => useContext(CanvasEditingContext);

export function canvasItemPresentation(item: CanvasItem) {
  return {
    itemId: item.id,
    expectedVersion: item.version,
    title: item.title,
    x: item.x,
    y: item.y,
    width: item.width,
    height: item.height,
    zIndex: item.zIndex,
    groupId: item.groupId ?? null,
    locked: item.locked,
  };
}

/** Project-local session history points at bounded, server-validated edit receipts. */
export function useCanvasEditing(projectId: string) {
  const queryClient = useQueryClient();
  const [history, setHistory] = useState<CanvasHistory>(emptyCanvasHistory);
  const historyRef = useRef(history);
  const pending = useRef<Pending | null>(null);
  const busyRef = useRef(false);
  const [active, setActive] = useState<EditFeedback | null>(null);
  const busy = active !== null;
  const [error, setError] = useState<Error | null>(null);
  const updateHistory = useCallback((next: CanvasHistory) => {
    historyRef.current = next;
    setHistory(next);
  }, []);
  const accept = useCallback(
    (saved: CanvasEditResponse) => {
      void queryClient.invalidateQueries({ queryKey: ["project-characters", projectId] });
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      void queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
      if (saved.receipt.operation === "DUPLICATE") {
        void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
      }
    },
    [projectId, queryClient],
  );
  const run = useCallback(
    async (intent: Pending) => {
      if (busyRef.current) throw new Error(t("canvas.edit.pending"));
      busyRef.current = true;
      setActive({ showPendingNotice: intent.type === "REPLAY" || intent.showPendingNotice !== false });
      setError(null);
      pending.current = intent;
      useCanvasStore.getState().setSaveState("saving");
      const acknowledge = captureCanvasAcknowledgement(queryClient, projectId);
      try {
        const saved =
          intent.type === "EDIT"
            ? await editCanvas(projectId, intent.request)
            : await replayCanvasEdit(projectId, intent.receiptId, intent.request);
        await acknowledge(saved);
        accept(saved);
        if (intent.type === "EDIT") intent.onAccepted?.(saved);
        const current = historyRef.current;
        if (intent.type === "EDIT") {
          const ids = [
            ...new Set([
              ...(intent.request.itemChanges?.map(item => item.itemId) ?? []),
              ...(intent.request.copies?.map(copy => copy.targetItemId) ?? []),
            ]),
          ];
          updateHistory(acknowledgeCanvasEdit(current, { receipt: saved.receipt, itemIds: ids }));
        } else updateHistory(acknowledgeCanvasReplay(current, intent.request.direction, saved.receipt));
        pending.current = null;
        if (!Object.keys(useCanvasStore.getState().drafts).length) useCanvasStore.getState().setSaveState("saved");
        return saved;
      } catch (failure) {
        const reason = failure instanceof Error ? failure : new Error(t("canvas.edit.failed"));
        setError(reason);
        useCanvasStore
          .getState()
          .setSaveState(reason instanceof ApiError && reason.status === HTTP_STATUS.CONFLICT ? "conflict" : "failed");
        throw reason;
      } finally {
        busyRef.current = false;
        setActive(null);
      }
    },
    [accept, projectId, queryClient, updateHistory],
  );
  const execute = useCallback<CanvasEditExecutor>(
    async (input, onAccepted, feedback) => {
      if (pending.current) throw new Error(t("canvas.edit.failed"));
      return run({
        type: "EDIT",
        request: { ...input, commandId: input.commandId ?? createClientId() },
        onAccepted,
        showPendingNotice: feedback?.showPendingNotice,
      });
    },
    [run],
  );
  const replay = useCallback(
    async (direction: "UNDO" | "REDO") => {
      if (pending.current) throw new Error(t("canvas.edit.failed"));
      // Other projects retain their drafts across navigation; only this canvas blocks its replay.
      const drafts = useCanvasStore.getState().drafts;
      const canvas = queryClient.getQueryData<Canvas>(["canvas", projectId]);
      if (canvas?.items.some(item => drafts[item.id])) throw new Error(t("canvas.edit.draftsPending"));
      const entry = (direction === "UNDO" ? historyRef.current.undo : historyRef.current.redo).at(-1);
      if (!entry) return;
      if (entry.receipt.operation === "DUPLICATE" && direction === "UNDO") {
        await flushCanvasEditors(projectId, entry.itemIds);
      }
      return run({
        type: "REPLAY",
        receiptId: entry.receipt.id,
        request: { commandId: createClientId(), expectedRevision: entry.receipt.revision, direction },
      });
    },
    [projectId, queryClient, run],
  );
  const retry = useCallback(async () => (pending.current ? run(pending.current) : undefined), [run]);
  const discardPending = useCallback(() => {
    if (!busyRef.current) {
      pending.current = null;
      setError(null);
    }
  }, []);
  const clearHistory = useCallback(() => updateHistory(emptyCanvasHistory()), [updateHistory]);
  return {
    execute,
    replay,
    retry,
    discardPending,
    clearHistory,
    busy,
    error,
    showPendingNotice: active?.showPendingNotice === true,
    canUndo: Boolean(history.undo.length) && !busy && !error,
    canRedo: Boolean(history.redo.length) && !busy && !error,
  };
}
