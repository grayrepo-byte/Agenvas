import { describe, expect, it } from "vitest";
import type { CanvasEditReceipt } from "../../shared/api/client";
import { acknowledgeCanvasEdit, acknowledgeCanvasReplay, CANVAS_HISTORY_LIMIT, emptyCanvasHistory,
  type CanvasHistoryEntry } from "./canvasHistory";

const receipt = (id: string, revision = 0, state: CanvasEditReceipt["state"] = "APPLIED"): CanvasEditReceipt =>
  ({ id, revision, state, operation: "DISPLAY" });
const entry = (id: string): CanvasHistoryEntry => ({ receipt: receipt(id), itemIds: ["item-" + id] });

describe("acknowledged canvas history", () => {
  it("keeps the latest 50 accepted operations and can undo them in order", () => {
    let history = emptyCanvasHistory();
    for (let index = 0; index < CANVAS_HISTORY_LIMIT + 7; index += 1) {
      history = acknowledgeCanvasEdit(history, entry(String(index)));
    }
    expect(history.undo).toHaveLength(50);
    expect(history.undo[0]?.receipt.id).toBe("7");
    const undone: string[] = [];
    while (history.undo.length) {
      const latest = history.undo.at(-1)!;
      undone.push(latest.receipt.id);
      history = acknowledgeCanvasReplay(history, "UNDO", { ...latest.receipt, state: "UNDONE", revision: 1 });
    }
    expect(undone).toEqual(Array.from({ length: 50 }, (_, index) => String(56 - index)));
    expect(history.redo).toHaveLength(50);
    expect(history.redo.at(-1)).toEqual({ receipt: receipt("7", 1, "UNDONE"), itemIds: ["item-7"] });
  });

  it("preserves item ownership while replay acknowledgements advance receipt revisions", () => {
    const original = acknowledgeCanvasEdit(emptyCanvasHistory(), entry("a"));
    const undone = acknowledgeCanvasReplay(original, "UNDO", receipt("a", 1, "UNDONE"));
    expect(undone).toEqual({ undo: [], redo: [{ receipt: receipt("a", 1, "UNDONE"), itemIds: ["item-a"] }] });
    const redone = acknowledgeCanvasReplay(undone, "REDO", receipt("a", 2));
    expect(redone).toEqual({ undo: [{ receipt: receipt("a", 2), itemIds: ["item-a"] }], redo: [] });
    expect(original.undo[0]?.receipt.revision).toBe(0);
  });

  it("does not move either stack for a response belonging to a different receipt", () => {
    const history = acknowledgeCanvasEdit(emptyCanvasHistory(), entry("a"));
    expect(acknowledgeCanvasReplay(history, "UNDO", receipt("unrelated", 1, "UNDONE"))).toBe(history);
    expect(acknowledgeCanvasReplay(history, "REDO", receipt("a", 2))).toBe(history);
  });

  it("clears the redo branch only when a new edit is acknowledged", () => {
    const applied = acknowledgeCanvasEdit(emptyCanvasHistory(), entry("a"));
    const undone = acknowledgeCanvasReplay(applied, "UNDO", receipt("a", 1, "UNDONE"));
    const branched = acknowledgeCanvasEdit(undone, entry("b"));
    expect(branched.undo.map((item) => item.receipt.id)).toEqual(["b"]);
    expect(branched.redo).toEqual([]);
    expect(undone.redo).toHaveLength(1);
  });
});
