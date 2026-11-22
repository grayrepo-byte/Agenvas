import { create } from "zustand";
import type { VideoStudioCommand, VideoStudioFrame, VideoStudioGeneration } from "../../shared/api/videoStudio";

export type VideoStudioSettings = Pick<
  VideoStudioGeneration,
  "capabilityId" | "capabilityVersion" | "mode" | "aspectRatio" | "startFrame" | "endFrame"
> & { duration: string };
export type FrameSlot = "startFrame" | "endFrame";

export type VideoStudioDraft = {
  prompt: string;
  settings: VideoStudioSettings | null;
  promptSource: { id: string; prompt: string; previous: string } | null;
  selectedId: string | null;
  command: VideoStudioCommand | null;
  viewingSubmission: boolean;
  acceptanceError: string | null;
};

const EMPTY_DRAFT: VideoStudioDraft = {
  prompt: "",
  settings: null,
  promptSource: null,
  selectedId: null,
  command: null,
  viewingSubmission: false,
  acceptanceError: null,
};

type DraftStore = {
  owners: Record<string, VideoStudioDraft>;
  update: (ownerId: string, patch: Partial<VideoStudioDraft>) => void;
  clear: () => void;
};

/** The database owns history; drafts and unresolved commands stay in their owner's session. */
export const useVideoStudioDraftStore = create<DraftStore>(set => ({
  owners: {},
  update: (ownerId, patch) =>
    set(state => ({
      owners: { ...state.owners, [ownerId]: { ...(state.owners[ownerId] ?? EMPTY_DRAFT), ...patch } },
    })),
  clear: () => set({ owners: {} }),
}));

export function useVideoStudioDraft(ownerId: string) {
  return useVideoStudioDraftStore(state => state.owners[ownerId] ?? EMPTY_DRAFT);
}

export function studioFrames(settings: Pick<VideoStudioSettings, FrameSlot>): [FrameSlot, VideoStudioFrame | null][] {
  return [
    ["startFrame", settings.startFrame],
    ["endFrame", settings.endFrame],
  ];
}
