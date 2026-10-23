import { beforeEach, describe, expect, it } from "vitest";
import type { Storyboard, StoryboardShot, Task, AgentMediaApproval } from "../../shared/api/client";
import { compositionNeedsReview, newStoryboardShot, parseStoryboardImport, productionNeedsReview, shotStatus, storyboardInput, useStoryboardDraftStore } from "./storyboardDraft";

function shot(id = "shot-1"): StoryboardShot {
  return { ...newStoryboardShot(0), id, revision: 2, productions: [{ role: "VIDEO", canvasItemId: "video-1", selectedVersionId: "result-1", reviewedRevision: 2, needsReview: false }] };
}
function board(): Storyboard {
  return { projectId: "project-1", version: 5, shots: [shot(), { ...shot("shot-2"), code: "SHOT-02", productions: [{ role: "AUDIO", canvasItemId: "audio-2", selectedVersionId: "paid-2", reviewedRevision: 2, needsReview: false }] }],
    composition: { canvasItemId: "final-video", selectedVersionId: "final-1", needsReview: false } };
}
beforeEach(() => useStoryboardDraftStore.setState({ projects: {} }));
describe("storyboard session drafts and immutable versions", () => {
  it("strips server revision assertions and never invents review confirmation", () => {
    const input = storyboardInput(board());
    expect(input.expectedVersion).toBe(5);
    expect(input.shots[0]).not.toHaveProperty("revision");
    expect(input.shots[0]?.productions[0]).toEqual({ role: "VIDEO", canvasItemId: "video-1", selectedVersionId: "result-1", confirmReviewed: false });
    expect(input.composition).toEqual({ canvasItemId: "final-video", selectedVersionId: "final-1", confirmReviewed: false });
  });
  it("edits only one shot and preserves other paid results and the original CAS version", () => {
    const state = useStoryboardDraftStore.getState(); state.accept("project-1", board());
    state.edit("project-1", (input) => ({ ...input, shots: input.shots.map((entry) => entry.id === "shot-1" ? { ...entry, dialogue: "Changed line" } : entry) }));
    const draft = useStoryboardDraftStore.getState().projects["project-1"]!;
    expect(draft.dirty).toBe(true); expect(draft.input.expectedVersion).toBe(5);
    expect(draft.input.shots[0]?.productions[0]?.selectedVersionId).toBe("result-1");
    expect(draft.input.shots[1]?.productions[0]?.selectedVersionId).toBe("paid-2");
    expect(productionNeedsReview(draft.input.shots[0]!, draft.input.shots[0]!.productions[0]!, draft.base.shots[0])).toBe(true);
    expect(productionNeedsReview(draft.input.shots[1]!, draft.input.shots[1]!.productions[0]!, draft.base.shots[1])).toBe(false);
    expect(compositionNeedsReview(draft)).toBe(true);
  });
  it("metadata renaming and explicit review do not invalidate the final result", () => {
    const base = board(); const input = storyboardInput(base);
    input.shots[0] = { ...input.shots[0]!, code: "RENAMED", scene: "SC-02", title: "Renamed", productions: input.shots[0]!.productions.map((entry) => ({ ...entry, confirmReviewed: true })) };
    expect(productionNeedsReview(input.shots[0], input.shots[0].productions[0]!, base.shots[0])).toBe(false);
    expect(compositionNeedsReview({ base, input, dirty: true })).toBe(false);
  });
  it("reordering shots marks only composition for review", () => {
    const base = board(); const input = storyboardInput(base); input.shots.reverse();
    expect(compositionNeedsReview({ base, input, dirty: true })).toBe(true);
    expect(productionNeedsReview(input.shots[0]!, input.shots[0]!.productions[0]!, base.shots[1])).toBe(false);
  });
  it("explicitly selecting a new result records a fresh selection rather than mutating the previous version", () => {
    const base = board(); const input = storyboardInput(base); const selected = input.shots[0]!;
    selected.dialogue = "New dialogue"; selected.productions[0]!.selectedVersionId = "result-2";
    expect(productionNeedsReview(selected, selected.productions[0]!, base.shots[0])).toBe(false);
    expect(base.shots[0]?.productions[0]?.selectedVersionId).toBe("result-1");
    expect(compositionNeedsReview({ base, input, dirty: true })).toBe(true);
  });
  it("can retain drafts across panel navigation and explicitly discard them", () => {
    useStoryboardDraftStore.getState().accept("project-1", board());
    useStoryboardDraftStore.getState().edit("project-1", (input) => ({ ...input, shots: [...input.shots, newStoryboardShot(2)] }));
    expect(useStoryboardDraftStore.getState().projects["project-1"]?.input.shots).toHaveLength(3);
    useStoryboardDraftStore.getState().discard("project-1");
    expect(useStoryboardDraftStore.getState().projects["project-1"]).toBeUndefined();
  });
});
describe("creative-only JSON import", () => {
  it("imports eight shots with fresh identities and excludes media/permissions/review assertions", () => {
    const raw = Array.from({ length: 8 }, (_, index) => ({ id: "attacker-controlled", code: `SHOT-${index + 1}`, durationSeconds: 2.125,
      description: "Scene", dialogue: "Line", sourceVersionId: "foreign-version", referenceVersionIds: ["foreign"],
      productions: [{ role: "VIDEO", canvasItemId: "foreign", selectedVersionId: "foreign", confirmReviewed: true }], approved: true }));
    const imported = parseStoryboardImport(JSON.stringify(raw));
    expect(imported).toHaveLength(8); expect(new Set(imported.map((entry) => entry.id)).size).toBe(8);
    expect(imported[0]).toMatchObject({ sourceVersionId: null, referenceVersionIds: [], productions: [], durationSeconds: 2.125 });
    expect(imported[0]?.id).not.toBe("attacker-controlled"); expect(imported[0]).not.toHaveProperty("approved");
  });
  it.each(["{}", "[]", "null", JSON.stringify([{ code: "" }]), JSON.stringify([{ code: " A" }]), JSON.stringify([{ code: "A" }, { code: "A" }]),
    JSON.stringify([{ code: "A", durationSeconds: -1 }]), JSON.stringify([{ code: "A", durationSeconds: 0.1234 }]), JSON.stringify([{ code: "A", dialogue: 42 }]),
    JSON.stringify([{ code: "A".repeat(81) }]), JSON.stringify(Array.from({ length: 201 }, (_, index) => ({ code: String(index) })))])("rejects invalid input %s", (input) => {
      expect(() => parseStoryboardImport(input)).toThrow();
  });
});
describe("shot status projection", () => {
  const task = (status: Task["status"]): Task => ({ id: "task-1", projectId: "project-1", runId: null,
    stepKey: "direct", kind: "VIDEO_GENERATION", status, cancelRequested: false,
    input: { canvasItemId: "video-1" }, providerRequestId: null, attemptNo: 1, nextActionAt: "2026-10-23T00:00:00Z",
    version: 0, createdAt: "2026-10-23T00:00:00Z", updatedAt: "2026-10-23T00:00:00Z" });
  it("reflects uncertainty, active work, failure, and ready results without calling a provider", () => {
    const base = shot(); const input = storyboardInput({ ...board(), shots: [base] }).shots[0]!;
    expect(shotStatus(input, base, [task("UNKNOWN")], [])).toBe("UNKNOWN");
    expect(shotStatus(input, base, [task("RUNNING")], [])).toBe("RUNNING");
    expect(shotStatus(input, base, [task("FAILED")], [])).toBe("FAILED");
    expect(shotStatus(input, base, [task("SUCCEEDED")], [])).toBe("READY");
    input.dialogue = "Changed"; expect(shotStatus(input, base, [], [])).toBe("REVIEW");
  });
  it("matches pending approval outputs to their production node", () => {
    const base = shot(); const input = storyboardInput({ ...board(), shots: [base] }).shots[0]!;
    const approval: AgentMediaApproval = { id: "approval-1", projectId: "project-1", runId: "run-1", operationId: "operation-1",
      status: "PENDING", version: 0, taskIds: [], result: null, createdAt: "2026-10-23T00:00:00Z",
      expiresAt: "2026-10-24T00:00:00Z", executionDeadline: null,
      outputs: [{ kind: "VIDEO", title: "Shot", artifactId: "video-artifact", canvasItemId: "video-1", draftVersion: 0, preview: {} }] };
    expect(shotStatus(input, base, [], [approval])).toBe("APPROVAL");
    expect(shotStatus({ ...input, productions: [] }, base, [], [approval])).toBe("PREPARING");
  });
});
