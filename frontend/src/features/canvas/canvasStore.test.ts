import { beforeEach, describe, expect, it } from "vitest";
import { CANVAS_SELECTION_MODE, useCanvasStore } from "./canvasStore";

describe("canvas interaction store", () => {
  beforeEach(() => {
    useCanvasStore.setState({ drafts: {}, selectedIds: [], selectionMode: CANVAS_SELECTION_MODE.SINGLE, saveState: "saved" });
  });

  it("retains the local layout draft when a save fails", () => {
    useCanvasStore.getState().updateDraft("item-1", { x: 42, y: 84 });
    useCanvasStore.getState().setSaveState("failed");

    expect(useCanvasStore.getState().saveState).toBe("failed");
    expect(useCanvasStore.getState().drafts["item-1"]).toEqual({ x: 42, y: 84 });
  });

  it("keeps an unsaved draft while showing a distinct conflict state", () => {
    useCanvasStore.getState().updateDraft("item-1", { x: 42 });
    useCanvasStore.getState().setSaveState("conflict");
    expect(useCanvasStore.getState().saveState).toBe("conflict");
    expect(useCanvasStore.getState().drafts["item-1"]).toEqual({ x: 42 });
  });

  it("clears only the acknowledged item draft", () => {
    useCanvasStore.getState().updateDraft("item-1", { x: 42 });
    useCanvasStore.getState().updateDraft("item-2", { x: 99 });
    useCanvasStore.getState().clearDraft("item-1");

    expect(useCanvasStore.getState().drafts["item-1"]).toBeUndefined();
    expect(useCanvasStore.getState().drafts["item-2"]).toEqual({ x: 99 });
  });

  it("automatically enters multi-select for multiple cards and supports explicit single-card focus", () => {
    useCanvasStore.getState().setSelectedIds(["item-1", "item-2"]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
    useCanvasStore.getState().setSelectedIds(["item-1"]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
  });

  it("updates mode even with unchanged IDs and preserves empty multi-select until explicitly cleared", () => {
    useCanvasStore.getState().setSelectedIds(["item-1"], CANVAS_SELECTION_MODE.MULTIPLE);
    useCanvasStore.getState().setSelectedIds(["item-1"]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
    useCanvasStore.getState().setSelectedIds([], CANVAS_SELECTION_MODE.MULTIPLE);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.MULTIPLE);
    useCanvasStore.getState().setSelectedIds([]);
    expect(useCanvasStore.getState().selectionMode).toBe(CANVAS_SELECTION_MODE.SINGLE);
    const unchanged = useCanvasStore.getState();
    useCanvasStore.getState().setSelectedIds([]);
    expect(useCanvasStore.getState()).toBe(unchanged);
  });
});
