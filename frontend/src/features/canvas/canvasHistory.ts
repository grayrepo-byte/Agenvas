import type { CanvasEditReceipt } from "../../shared/api/client";

export const CANVAS_HISTORY_LIMIT = 50;
export type CanvasHistoryEntry = { receipt: CanvasEditReceipt; itemIds: string[] };
export type CanvasHistory = { undo: CanvasHistoryEntry[]; redo: CanvasHistoryEntry[] };
export const emptyCanvasHistory = (): CanvasHistory => ({ undo: [], redo: [] });

/** Only acknowledged server receipts enter history; failed attempts never move a stack. */
export function acknowledgeCanvasEdit(history: CanvasHistory, entry: CanvasHistoryEntry): CanvasHistory {
  return { undo: [...history.undo, entry].slice(-CANVAS_HISTORY_LIMIT), redo: [] };
}

export function acknowledgeCanvasReplay(history: CanvasHistory, direction: "UNDO" | "REDO",
  receipt: CanvasEditReceipt): CanvasHistory {
  const source = direction === "UNDO" ? history.undo : history.redo;
  const entry = source.at(-1);
  if (!entry || entry.receipt.id !== receipt.id) return history;
  const updated = { ...entry, receipt };
  return direction === "UNDO"
    ? { undo: source.slice(0, -1), redo: [...history.redo, updated] }
    : { undo: [...history.undo, updated], redo: source.slice(0, -1) };
}
