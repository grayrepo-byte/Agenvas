import { create } from "zustand";
import type { CreatorInputField } from "../../shared/creatorInputs";
import type {
  VideoStudioCapability,
  VideoStudioCommand,
  VideoStudioFrame,
  VideoStudioGeneration,
} from "../../shared/api/videoStudio";

export type VideoStudioSettings = Pick<
  VideoStudioGeneration,
  | "capabilityId"
  | "capabilityVersion"
  | "mode"
  | "aspectRatio"
  | "videoResolution"
  | "dynamicValues"
  | "references"
  | "startFrame"
  | "endFrame"
> & { duration: string };
export type FrameSlot = "startFrame" | "endFrame";

export const MAX_REFERENCES = 14;
export const LIMIT_KEYS = { IMAGE: "image", VIDEO: "video", AUDIO: "audio" } as const;
export const FRAME_LABELS = { startFrame: "videoStudio.startFrame", endFrame: "videoStudio.endFrame" } as const;

export function allowsFrame(model: VideoStudioCapability, mode: VideoStudioSettings["mode"], slot: FrameSlot) {
  return mode === "START_END" && model.modes.includes(mode) && (slot === "startFrame" || model.supportsEndFrame);
}

export type VideoStudioDraft = {
  prompt: string;
  settings: VideoStudioSettings | null;
  promptSource: { id: string; prompt: string; previous: string } | null;
  selectedId: string | null;
  command: VideoStudioCommand | null;
  viewingSubmission: boolean;
  acceptanceError: string | null;
  frameUploadError: string | null;
};

const EMPTY_DRAFT: VideoStudioDraft = {
  prompt: "",
  settings: null,
  promptSource: null,
  selectedId: null,
  command: null,
  viewingSubmission: false,
  acceptanceError: null,
  frameUploadError: null,
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

/** Only display a workflow's ratio when all of its published choices identify a frame shape. */
export function workflowRatio(field: CreatorInputField, value: unknown): string | null {
  if (field.type !== "SELECT" || !field.options?.length) return null;
  const ratioPattern = /^([1-9]\d*:[1-9]\d*)(?:\s+\([^)]*\))?$/;
  let selected: string | null = null;
  for (const option of field.options) {
    const ratio = ratioPattern.exec(String(option.value)) ?? ratioPattern.exec(option.label);
    if (!ratio) return null;
    if (option.value === value) selected = ratio[1]!;
  }
  return selected;
}
