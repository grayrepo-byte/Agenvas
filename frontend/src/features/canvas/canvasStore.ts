import { create } from "zustand";

type LayoutDraft = {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
};

type SaveState = "saved" | "saving" | "failed" | "conflict";

type CanvasInteractionState = {
  drafts: Record<string, LayoutDraft>;
  selectedIds: string[];
  saveState: SaveState;
  updateDraft: (itemId: string, patch: LayoutDraft) => void;
  clearDraft: (itemId: string) => void;
  setSelectedIds: (itemIds: string[]) => void;
  setSaveState: (saveState: SaveState) => void;
};

/** Interaction-only state: persisted entities remain exclusively in TanStack Query. */
export const useCanvasStore = create<CanvasInteractionState>((set) => ({
  drafts: {},
  selectedIds: [],
  saveState: "saved",
  updateDraft: (itemId, patch) =>
    set((state) => ({
      drafts: {
        ...state.drafts,
        [itemId]: { ...state.drafts[itemId], ...patch },
      },
    })),
  clearDraft: (itemId) =>
    set((state) => {
      const { [itemId]: ignored, ...remaining } = state.drafts;
      void ignored;
      return { drafts: remaining };
    }),
  setSelectedIds: (selectedIds) =>
    set((state) =>
      state.selectedIds.length === selectedIds.length &&
      state.selectedIds.every((itemId, index) => itemId === selectedIds[index])
        ? state
        : { selectedIds },
    ),
  setSaveState: (saveState) => set({ saveState }),
}));
