import { beforeEach, describe, expect, it } from "vitest";
import { useCanvasStore } from "./canvasStore";

describe("canvas interaction store", () => {
  beforeEach(() => {
    useCanvasStore.setState({ drafts: {}, selectedIds: [], saveState: "saved" });
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
});
