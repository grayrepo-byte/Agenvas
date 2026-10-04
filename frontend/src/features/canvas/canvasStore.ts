import { create } from "zustand";
import type { ImageGenerationParameters, SaveMediaDraftRequest } from "../../shared/api/client";

export type MediaDraftRecovery = { request: SaveMediaDraftRequest; saving: boolean; error: Error | null };

type LayoutDraft = {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
};

type SaveState = "saved" | "saving" | "failed" | "conflict";

export const CANVAS_SELECTION_MODE = { SINGLE: "single", MULTIPLE: "multiple" } as const;
type CanvasSelectionMode = typeof CANVAS_SELECTION_MODE[keyof typeof CANVAS_SELECTION_MODE];

type CanvasInteractionState = {
  drafts: Record<string, LayoutDraft>;
  selectedIds: string[];
  /** Box selection remains a group gesture even when it contains only one card. */
  selectionMode: CanvasSelectionMode;
  saveState: SaveState;
  imageRatioDrafts: Record<string, ImageGenerationParameters["aspectRatio"]>;
  setImageRatioDraft: (key: string, ratio: ImageGenerationParameters["aspectRatio"]) => void;
  clearImageRatioDraft: (key: string) => void;
  mediaDraftRecoveries: Record<string, MediaDraftRecovery>;
  setMediaDraftRecovery: (key: string, recovery: MediaDraftRecovery) => void;
  clearMediaDraftRecovery: (key: string) => void;
  updateDraft: (itemId: string, patch: LayoutDraft) => void;
  clearDraft: (itemId: string) => void;
  setSelectedIds: (itemIds: string[], mode?: CanvasSelectionMode) => void;
  setSaveState: (saveState: SaveState) => void;
};

/** Interaction-only state: persisted entities remain exclusively in TanStack Query. */
export const useCanvasStore = create<CanvasInteractionState>((set) => ({
  drafts: {},
  selectedIds: [],
  selectionMode: CANVAS_SELECTION_MODE.SINGLE,
  saveState: "saved",
  imageRatioDrafts: {},
  mediaDraftRecoveries: {},
  setMediaDraftRecovery: (key, recovery) => set((state) => ({
    mediaDraftRecoveries: { ...state.mediaDraftRecoveries, [key]: recovery },
  })),
  clearMediaDraftRecovery: (key) => set((state) => {
    if (!(key in state.mediaDraftRecoveries)) return state;
    const { [key]: ignored, ...remaining } = state.mediaDraftRecoveries;
    void ignored;
    return { mediaDraftRecoveries: remaining };
  }),
  setImageRatioDraft: (key, ratio) => set((state) => state.imageRatioDrafts[key] === ratio
    ? state : { imageRatioDrafts: { ...state.imageRatioDrafts, [key]: ratio } }),
  clearImageRatioDraft: (key) => set((state) => {
    if (!(key in state.imageRatioDrafts)) return state;
    const { [key]: ignored, ...remaining } = state.imageRatioDrafts;
    void ignored;
    return { imageRatioDrafts: remaining };
  }),
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
  setSelectedIds: (selectedIds, mode) =>
    set((state) => {
      const selectionMode = selectedIds.length > 1 ? CANVAS_SELECTION_MODE.MULTIPLE
        : mode ?? CANVAS_SELECTION_MODE.SINGLE;
      return state.selectionMode === selectionMode && state.selectedIds.length === selectedIds.length &&
        state.selectedIds.every((itemId, index) => itemId === selectedIds[index])
        ? state
        : { selectedIds, selectionMode };
    }),
  setSaveState: (saveState) => set({ saveState }),
}));
