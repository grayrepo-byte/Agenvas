import type { AgentMediaApproval, StoryboardShot } from "../../shared/api/client";
import type { ProductionInput, ShotInput } from "./storyboardDraft";

export type StoryboardMediaLink = { shotId: string; shotCode: string; shotRevision: number; role: ProductionInput["role"] };
export type StoryboardMediaCandidate = { approvalId: string; taskId: string; canvasItemId: string; versionId: string;
  title: string; storyboard: StoryboardMediaLink };
type ShotTarget = { id: string; productions: readonly { canvasItemId: string }[] };

function record(value: unknown): Readonly<Record<string, unknown>> {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? value as Readonly<Record<string, unknown>> : {};
}
function text(value: unknown): string { return typeof value === "string" ? value : ""; }
export function storyboardMediaLink(value: unknown): StoryboardMediaLink | null {
  const link = record(value);
  if (!text(link.shotId) || !text(link.shotCode) || typeof link.shotRevision !== "number"
    || !Number.isSafeInteger(link.shotRevision) || link.shotRevision < 1
    || !["KEYFRAME", "VIDEO", "AUDIO"].includes(text(link.role))) return null;
  return { shotId: text(link.shotId), shotCode: text(link.shotCode), shotRevision: link.shotRevision,
    role: link.role as ProductionInput["role"] };
}
export function outputStoryboard(output: AgentMediaApproval["outputs"][number]): StoryboardMediaLink | null {
  return storyboardMediaLink(record(output).storyboard);
}
/** Explicit shot identity takes precedence over a node which may later be rebound to another shot. */
export function approvalMatchesShot(approval: AgentMediaApproval, shot: ShotTarget): boolean {
  return approval.outputs.some((output) => {
    const link = outputStoryboard(output);
    return link ? link.shotId === shot.id : shot.productions.some((production) => production.canvasItemId === output.canvasItemId);
  });
}
function matchingTasks(approval: AgentMediaApproval, shot: ShotTarget) {
  const archived = record(approval).archivedResults;
  const tasks: unknown[] = [...(Array.isArray(approval.result?.tasks) ? approval.result.tasks : []), ...(Array.isArray(archived) ? archived : [])];
  // One Task can archive more than once after an uncertain outcome. Preserve every exact result, not just the last Task row.
  return tasks.map(record).flatMap((task) => {
    const output = typeof task.outputIndex === "number" && Number.isInteger(task.outputIndex)
      ? approval.outputs[task.outputIndex] : approval.outputs.find((candidate) => candidate.canvasItemId === text(task.canvasItemId));
    if (!output || text(task.canvasItemId) !== output.canvasItemId) return [];
    const link = outputStoryboard(output);
    if (link ? link.shotId !== shot.id : !shot.productions.some((production) => production.canvasItemId === output.canvasItemId)) return [];
    return [{ task, output, link }];
  });
}
export function shotMediaTaskStatuses(approval: AgentMediaApproval, shot: ShotTarget): string[] {
  return matchingTasks(approval, shot).map(({ task }) => text(task.status));
}
/** Result candidates are projections only. Reading or refreshing them never selects a result. */
export function shotMediaCandidates(approvals: readonly AgentMediaApproval[], shot: ShotTarget): StoryboardMediaCandidate[] {
  const candidates = approvals.flatMap((approval) => matchingTasks(approval, shot).flatMap(({ task, output, link }) => {
    if (!link) return [];
    const primary = { canvasItemId: text(task.canvasItemId), artifactVersionId: text(task.artifactVersionId) };
    const extras: unknown[] = Array.isArray(task.additionalResults) ? task.additionalResults : [];
    return [primary, ...extras.map(record)].flatMap((result) => {
      const canvasItemId = text(result.canvasItemId), versionId = text(result.artifactVersionId);
      return canvasItemId && versionId ? [{ approvalId: approval.id, taskId: text(task.taskId), canvasItemId, versionId,
        title: output.title, storyboard: link }] : [];
    });
  }));
  return [...new Map(candidates.map((candidate) => [`${candidate.canvasItemId}:${candidate.versionId}`, candidate])).values()];
}
export function selectedCandidateRevision(shot: ShotInput, production: ProductionInput, approvals: readonly AgentMediaApproval[]): number | undefined {
  return shotMediaCandidates(approvals, shot).find((candidate) => candidate.canvasItemId === production.canvasItemId
    && candidate.versionId === production.selectedVersionId)?.storyboard.shotRevision;
}
export function currentShotRevision(shot: ShotInput, base?: StoryboardShot): number {
  if (!base) return 1;
  const creative = (value: ShotInput | StoryboardShot) => JSON.stringify([value.description, value.dialogue,
    value.durationSeconds, value.sourceVersionId, value.referenceVersionIds]);
  return base.revision + Number(creative(shot) !== creative(base));
}
