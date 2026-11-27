import { create } from "zustand";
import type { CreateImageStudioBatch, ImageStudioParameters, ImageStudioReference } from "../../shared/api/imageStudio";

export type StudioReference = Pick<ImageStudioReference, "versionId"> &
  Partial<Omit<ImageStudioReference, "versionId">>;
export type StudioCommand = { key: string; request: CreateImageStudioBatch };
export type StudioDraft = {
  prompt: string;
  historyPrompt: { previousPrompt: string; sourceBatchId: string; sourceDeleted?: boolean } | null;
  capabilityId: string | null;
  capabilityVersion: number | null;
  parameters: ImageStudioParameters;
  references: StudioReference[];
  selectedBatchId: string | null;
  selectedVersions: Record<string, string>;
  awaitingHistory: string[];
  command: StudioCommand | null;
  viewingSubmission: boolean;
};
const emptyDraft = (): StudioDraft => ({
  prompt: "",
  historyPrompt: null,
  capabilityId: null,
  capabilityVersion: null,
  parameters: {},
  references: [],
  selectedBatchId: null,
  selectedVersions: {},
  awaitingHistory: [],
  command: null,
  viewingSubmission: false,
});
const EMPTY_DRAFT = emptyDraft();
type DraftStore = {
  owners: Record<string, StudioDraft>;
  /** Discards responses started before sign-out, including when the same owner signs in again. */
  session: number;
  update: (ownerId: string, update: Partial<StudioDraft>) => void;
  clear: () => void;
};
/** Unsaved text and unresolved acceptance stay owner scoped in memory; the database owns history. */
export const useImageStudioDraftStore = create<DraftStore>(set => ({
  owners: {},
  session: 0,
  update: (ownerId, update) =>
    set(state => ({
      owners: { ...state.owners, [ownerId]: { ...(state.owners[ownerId] ?? emptyDraft()), ...update } },
    })),
  clear: () => set(state => ({ owners: {}, session: state.session + 1 })),
}));
export function useImageStudioDraft(ownerId: string) {
  return useImageStudioDraftStore(state => state.owners[ownerId] ?? EMPTY_DRAFT);
}
