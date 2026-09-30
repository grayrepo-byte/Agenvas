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

type CanvasInteractionState = {
  drafts: Record<string, LayoutDraft>;
  selectedIds: string[];
  saveState: SaveState;
  imageRatioDrafts: Record<string, ImageGenerationParameters["aspectRatio"]>;
  setImageRatioDraft: (key: string, ratio: ImageGenerationParameters["aspectRatio"]) => void;
  clearImageRatioDraft: (key: string) => void;
  mediaDraftRecoveries: Record<string, MediaDraftRecovery>;
  setMediaDraftRecovery: (key: string, recovery: MediaDraftRecovery) => void;
  clearMediaDraftRecovery: (key: string) => void;
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
  setSelectedIds: (selectedIds) =>
    set((state) =>
      state.selectedIds.length === selectedIds.length &&
      state.selectedIds.every((itemId, index) => itemId === selectedIds[index])
        ? state
        : { selectedIds },
    ),
  setSaveState: (saveState) => set({ saveState }),
}));
