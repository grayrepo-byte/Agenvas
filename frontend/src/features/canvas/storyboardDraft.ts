import { create } from "zustand";
import type { Storyboard, SaveStoryboardRequest, StoryboardShot, Task, AgentMediaApproval } from "../../shared/api/client";
import { approvalMatchesShot, currentShotRevision, selectedCandidateRevision, shotMediaCandidates, shotMediaTaskStatuses } from "./storyboardMedia";

export const STORYBOARD_ROLES = ["KEYFRAME", "VIDEO", "AUDIO"] as const;
export const STORYBOARD_MAX_SHOTS = 200;
export const STORYBOARD_BATCH_LIMIT = 6;
export type ShotInput = SaveStoryboardRequest["shots"][number];
export type ProductionInput = ShotInput["productions"][number];
export type StoryboardDraft = { base: Storyboard; input: SaveStoryboardRequest; dirty: boolean };
export type ShotStatus = "PREPARING" | "APPROVAL" | "RUNNING" | "READY" | "FAILED" | "UNKNOWN" | "REVIEW" | "CANCELED" | "REJECTED" | "EXPIRED";

/** Strip server-authored review/revision fields; only an explicit user action sets confirmReviewed. */
export function storyboardInput(board: Storyboard): SaveStoryboardRequest {
  return { expectedVersion: board.version,
    shots: board.shots.map((shot) => ({ id: shot.id, code: shot.code, scene: shot.scene, title: shot.title,
      description: shot.description, dialogue: shot.dialogue, durationSeconds: shot.durationSeconds,
      sourceVersionId: shot.sourceVersionId, referenceVersionIds: [...shot.referenceVersionIds],
      productions: shot.productions.map(({ role, canvasItemId, selectedVersionId }) => ({ role, canvasItemId, selectedVersionId, confirmReviewed: false })) })),
    composition: board.composition ? { canvasItemId: board.composition.canvasItemId,
      selectedVersionId: board.composition.selectedVersionId, confirmReviewed: false } : null };
}
export function newStoryboardShot(index: number): ShotInput {
  return { id: crypto.randomUUID(), code: `SHOT-${String(index + 1).padStart(2, "0")}`, scene: "", title: "",
    description: "", dialogue: "", durationSeconds: 5, sourceVersionId: null, referenceVersionIds: [], productions: [] };
}
function creative(shot: ShotInput | StoryboardShot): string {
  return JSON.stringify([shot.description, shot.dialogue,
    shot.durationSeconds, shot.sourceVersionId, shot.referenceVersionIds]);
}
/** Editing one shot keeps every paid result and only invalidates that shot's reviews. */
export function productionNeedsReview(shot: ShotInput, production: ProductionInput, base?: StoryboardShot, approvals: readonly AgentMediaApproval[] = []): boolean {
  if (production.confirmReviewed) return false;
  const previous = base?.productions.find((item) => item.role === production.role && item.canvasItemId === production.canvasItemId);
  const basisRevision = selectedCandidateRevision(shot, production, approvals);
  const currentRevision = currentShotRevision(shot, base);
  const alreadyReviewed = previous?.selectedVersionId === production.selectedVersionId && !previous.needsReview && previous.reviewedRevision >= currentRevision;
  if (basisRevision !== undefined && basisRevision < currentRevision && !alreadyReviewed) return true;
  if (!production.selectedVersionId || production.selectedVersionId !== previous?.selectedVersionId) return false;
  return previous.needsReview || !base || creative(shot) !== creative(base);
}
/** Ordering and frozen production choices affect the composition; display labels and review clicks do not. */
export function storyboardContentSignature(shots: readonly ShotInput[]): string {
  return JSON.stringify(shots.map((shot) => [shot.id, creative(shot),
    shot.productions.map((production) => [production.role, production.canvasItemId, production.selectedVersionId]).sort()]));
}
export function compositionNeedsReview(draft: StoryboardDraft): boolean {
  const composition = draft.input.composition;
  if (!composition?.selectedVersionId || composition.confirmReviewed) return false;
  const previous = draft.base.composition;
  if (previous?.selectedVersionId !== composition.selectedVersionId || previous.canvasItemId !== composition.canvasItemId) return false;
  return previous.needsReview || storyboardContentSignature(storyboardInput(draft.base).shots) !== storyboardContentSignature(draft.input.shots);
}
export function shotStatus(shot: ShotInput, base: StoryboardShot | undefined, tasks: readonly Task[], approvals: readonly AgentMediaApproval[]): ShotStatus {
  const ids = new Set(shot.productions.map((item) => item.canvasItemId));
  const relevant = tasks.filter((task) => typeof task.input.canvasItemId === "string" && ids.has(task.input.canvasItemId));
  const linked = approvals.filter((approval) => approvalMatchesShot(approval, shot));
  const latest = [...linked].sort((left, right) => right.createdAt.localeCompare(left.createdAt))[0];
  const projectedStatuses = latest ? shotMediaTaskStatuses(latest, shot) : [];
  if (relevant.some((task) => task.status === "UNKNOWN") || projectedStatuses.includes("UNKNOWN")) return "UNKNOWN";
  if (relevant.some((task) => ["READY", "RUNNING", "SUBMITTING", "WAITING_PROVIDER"].includes(task.status))) return "RUNNING";
  if (linked.some((approval) => approval.status === "APPROVED")) return "RUNNING";
  if (linked.some((approval) => approval.status === "PENDING")) return "APPROVAL";
  if (shot.productions.some((production) => productionNeedsReview(shot, production, base, approvals))) return "REVIEW";
  // Prefer the latest associated attempt; a historical rejection must not mask a newer usable result.
  if (latest?.status === "FAILED" && !shotMediaCandidates([latest], shot).length) return "FAILED";
  if (relevant.some((task) => task.status === "FAILED" || task.status === "BLOCKED")) return "FAILED";
  if (relevant.some((task) => task.status === "CANCELED")) return "CANCELED";
  if (shot.productions.some((production) => production.selectedVersionId) || relevant.some((task) => task.status === "SUCCEEDED")
    || shotMediaCandidates(linked, shot).length) return "READY";
  if (latest?.status === "REJECTED" || latest?.status === "EXPIRED" || latest?.status === "CANCELED") return latest.status;
  return "PREPARING";
}

/** Import creative text only. Pasted IDs, permissions, results and review assertions are never accepted. */
export function parseStoryboardImport(text: string): ShotInput[] {
  const parsed: unknown = JSON.parse(text);
  if (!Array.isArray(parsed) || parsed.length < 1 || parsed.length > STORYBOARD_MAX_SHOTS) throw new Error("INVALID_SHOT_LIST");
  const imported = parsed.map((item: unknown, index) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) throw new Error("INVALID_SHOT");
    const record = item as Record<string, unknown>;
    const shot = newStoryboardShot(index);
    for (const key of ["code", "scene", "title", "description", "dialogue"] as const) {
      if (record[key] !== undefined && typeof record[key] !== "string") throw new Error("INVALID_TEXT");
      const value = record[key];
      if (typeof value === "string") {
        const max = key === "code" ? 80 : key === "scene" || key === "title" ? 200 : 20_000;
        if (value.length > max) throw new Error("TEXT_TOO_LONG");
        shot[key] = value;
      }
    }
    if (!shot.code.trim() || shot.code !== shot.code.trim()) throw new Error("EMPTY_CODE");
    if (record.durationSeconds !== undefined) {
      if (typeof record.durationSeconds !== "number" || !Number.isFinite(record.durationSeconds)
        || record.durationSeconds < 0.1 || record.durationSeconds > 3600 || Math.abs(record.durationSeconds * 1000 - Math.round(record.durationSeconds * 1000)) > 1e-7) throw new Error("INVALID_DURATION");
      shot.durationSeconds = record.durationSeconds;
    }
    return shot;
  });
  if (new Set(imported.map((shot) => shot.code)).size !== imported.length) throw new Error("DUPLICATE_CODE");
  return imported;
}

type DraftStore = {
  projects: Record<string, StoryboardDraft | undefined>;
  accept: (projectId: string, board: Storyboard) => void;
  edit: (projectId: string, change: (input: SaveStoryboardRequest) => SaveStoryboardRequest) => void;
  discard: (projectId: string) => void;
};
/** Session-only UI drafts survive panel navigation and save/CAS failures; the database remains authoritative. */
export const useStoryboardDraftStore = create<DraftStore>((set) => ({
  projects: {},
  accept: (projectId, board) => set((state) => ({ projects: { ...state.projects,
    [projectId]: { base: board, input: storyboardInput(board), dirty: false } } })),
  edit: (projectId, change) => set((state) => {
    const current = state.projects[projectId];
    if (!current) return state;
    return { projects: { ...state.projects, [projectId]: { ...current, input: change(current.input), dirty: true } } };
  }),
  discard: (projectId) => set((state) => ({ projects: { ...state.projects, [projectId]: undefined } })),
}));
