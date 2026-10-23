import { describe, expect, it } from "vitest";
import type { AgentMediaApproval, StoryboardShot } from "../../shared/api/client";
import { newStoryboardShot, productionNeedsReview, shotStatus, type ShotInput } from "./storyboardDraft";
import { approvalMatchesShot, shotMediaCandidates } from "./storyboardMedia";

const link = { shotId: "shot-1", shotCode: "SHOT-01", shotRevision: 1, role: "VIDEO" as const };
function shot(): StoryboardShot & ShotInput { return { ...newStoryboardShot(0), id: link.shotId, revision: 2, productions: [] }; }
function approval(status: AgentMediaApproval["status"] = "SUCCEEDED"): AgentMediaApproval {
  return { id: "approval-1", projectId: "project-1", runId: "run-1", operationId: "operation-1", status, version: 2,
    createdAt: "2026-10-23T00:00:00Z", expiresAt: "2026-10-24T00:00:00Z", executionDeadline: null, taskIds: ["task-1"],
    outputs: [{ kind: "VIDEO", title: "Director video", artifactId: "artifact-1", canvasItemId: "node-1", draftVersion: 1, preview: {}, storyboard: link }],
    result: status === "SUCCEEDED" ? { schemaVersion: 1, status, tasks: [{ taskId: "task-1", kind: "VIDEO_GENERATION", status: "SUCCEEDED",
      outputIndex: 0, canvasItemId: "node-1", storyboard: link, artifactVersionId: "version-1", selected: true }] } : null };
}

describe("storyboard media projection", () => {
  it("matches stable shot identity before node bindings, including unbound shots", () => {
    const current = shot(), proposed = approval();
    expect(approvalMatchesShot(proposed, current)).toBe(true);
    expect(approvalMatchesShot(proposed, { ...current, id: "other-shot", productions: [{ canvasItemId: "node-1" }] })).toBe(false);
    proposed.outputs[0]!.storyboard = null;
    expect(approvalMatchesShot(proposed, current)).toBe(false);
    expect(approvalMatchesShot(proposed, { ...current, productions: [{ canvasItemId: "node-1" }] })).toBe(true);
  });
  it("keeps exact primary and extra result targets while ignoring unmapped or inconsistent results", () => {
    const proposed = approval();
    proposed.result!.tasks[0]!.additionalResults = [
      { ordinal: 1, artifactId: "audio-artifact", canvasItemId: "audio-node", artifactVersionId: "audio-version", selected: true },
      { ordinal: 2, artifactId: "missing-artifact", canvasItemId: null, artifactVersionId: "missing-version", selected: false },
    ];
    expect(shotMediaCandidates([proposed], shot()).map((candidate) => [candidate.canvasItemId, candidate.versionId]))
      .toEqual([["node-1", "version-1"], ["audio-node", "audio-version"]]);
    proposed.result!.tasks[0]!.canvasItemId = "unrelated-node";
    expect(shotMediaCandidates([proposed], shot())).toEqual([]);
  });
  it("does not guess a result target from array position or media type", () => {
    const proposed = approval();
    delete proposed.result!.tasks[0]!.outputIndex; delete proposed.result!.tasks[0]!.canvasItemId;
    expect(shotMediaCandidates([proposed], shot())).toEqual([]);
  });
  it.each(["CANCELED", "EXPIRED"] as const)("keeps later archived results available after an immutable %s approval outcome", (status) => {
    const proposed = approval(status), archived = approval().result!.tasks[0]!;
    proposed.result = { schemaVersion: 1, status, tasks: [] }; proposed.archivedResults = [archived];
    expect(shotMediaCandidates([proposed], shot()).map((candidate) => candidate.versionId)).toEqual(["version-1"]);
    expect(proposed.result).toEqual({ schemaVersion: 1, status, tasks: [] });
    expect(shot().productions).toEqual([]);
  });
  it("retains multiple late archives from the same Task and deduplicates only the exact node and version", () => {
    const proposed = approval("CANCELED"), first = approval().result!.tasks[0]!;
    proposed.archivedResults = [first, { ...first, artifactVersionId: "version-2", additionalResults: [{ artifactId: "extra-artifact",
      canvasItemId: "extra-node", artifactVersionId: "extra-version", selected: false }] }, first];
    expect(shotMediaCandidates([proposed], shot()).map((candidate) => [candidate.canvasItemId, candidate.versionId]))
      .toEqual([["node-1", "version-1"], ["node-1", "version-2"], ["extra-node", "extra-version"]]);
  });
  it("requires review when an old-revision candidate is newly chosen, until explicit user confirmation", () => {
    const current = shot(), production = { role: "VIDEO" as const, canvasItemId: "node-1", selectedVersionId: "version-1", confirmReviewed: false };
    expect(productionNeedsReview(current, production, current, [approval()])).toBe(true);
    expect(productionNeedsReview(current, { ...production, confirmReviewed: true }, current, [approval()])).toBe(false);
    const reviewed: StoryboardShot = { ...current, productions: [{ role: "VIDEO", canvasItemId: "node-1", selectedVersionId: "version-1", reviewedRevision: 2, needsReview: false }] };
    expect(productionNeedsReview(current, production, reviewed, [approval()])).toBe(false);
    expect(productionNeedsReview({ ...current, dialogue: "Changed after reviewing" }, production, reviewed, [approval()])).toBe(true);
    const unchanged = { ...current, revision: 1 };
    expect(productionNeedsReview(unchanged, production, unchanged, [approval()])).toBe(false);
    expect(productionNeedsReview({ ...unchanged, dialogue: "Edited while awaiting generation" }, production, unchanged, [approval()])).toBe(true);
  });
  it.each([ ["PENDING", "APPROVAL"], ["APPROVED", "RUNNING"], ["SUCCEEDED", "READY"], ["FAILED", "FAILED"],
    ["REJECTED", "REJECTED"], ["EXPIRED", "EXPIRED"], ["CANCELED", "CANCELED"] ] as const)("projects %s onto an unbound shot as %s", (state, expected) => {
    expect(shotStatus(shot(), shot(), [], [approval(state)])).toBe(expected);
  });
  it("shows the latest UNKNOWN attempt without offering a new generation, and allows a later explicit attempt to supersede its status", () => {
    const unknown = approval("FAILED"); unknown.result = { schemaVersion: 1, status: "FAILED", tasks: [{ taskId: "unknown-task", kind: "VIDEO_GENERATION",
      status: "UNKNOWN", outputIndex: 0, canvasItemId: "node-1", storyboard: link }] };
    expect(shotStatus(shot(), shot(), [], [unknown])).toBe("UNKNOWN");
    const later = approval(); later.createdAt = "2026-10-23T01:00:00Z";
    expect(shotStatus(shot(), shot(), [], [unknown, later])).toBe("READY");
  });
});
