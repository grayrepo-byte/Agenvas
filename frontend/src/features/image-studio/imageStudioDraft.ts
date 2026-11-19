import { create } from "zustand";
import type { CreateImageStudioBatch, ImageStudioParameters, ImageStudioReference } from "../../shared/api/imageStudio";

export type StudioReference = Pick<ImageStudioReference, "versionId"> &
  Partial<Omit<ImageStudioReference, "versionId">>;
export type StudioCommand = { key: string; request: CreateImageStudioBatch };
export type StudioDraft = {
  prompt: string;
  capabilityId: string | null;
  capabilityVersion: number | null;
  parameters: ImageStudioParameters;
  references: StudioReference[];
  selectedBatchId: string | null;
  command: StudioCommand | null;
};
const emptyDraft = (): StudioDraft => ({
  prompt: "",
  capabilityId: null,
  capabilityVersion: null,
  parameters: {},
  references: [],
  selectedBatchId: null,
  command: null,
});
const EMPTY_DRAFT = emptyDraft();
type DraftStore = {
  owners: Record<string, StudioDraft>;
  update: (ownerId: string, update: Partial<StudioDraft>) => void;
  clear: () => void;
};
/** Unsaved text and unresolved acceptance stay owner scoped in memory; the database owns history. */
export const useImageStudioDraftStore = create<DraftStore>(set => ({
  owners: {},
  update: (ownerId, update) =>
    set(state => ({
      owners: { ...state.owners, [ownerId]: { ...(state.owners[ownerId] ?? emptyDraft()), ...update } },
    })),
  clear: () => set({ owners: {} }),
}));
export function useImageStudioDraft(ownerId: string) {
  return useImageStudioDraftStore(state => state.owners[ownerId] ?? EMPTY_DRAFT);
}
