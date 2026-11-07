import { describe, expect, it } from "vitest";
import { composePromptInputs, projectPromptInputs } from "./canvasPromptInputs";
import type { CanvasConnection, CanvasItem } from "../../shared/api/client";

const now = "2026-10-20T00:00:00Z";
const edge = (id: string, source: string, createdAt = now): CanvasConnection => ({ id, sourceCanvasItemId: source,
  targetCanvasItemId: "target", relationType: "PROMPT_INPUT", sourceArtifactVersionId: "original", projectId: "project",
  version: 0, createdAt, updatedAt: now });
const item = (id: string, text: string): CanvasItem => ({ id, subjectType: "ARTIFACT", subjectId: id, title: id,
  x: 0, y: 0, width: 320, height: 240, zIndex: 0, groupId: null, locked: false, version: 0,
  selectedVersionId: null, selectedVersion: null, artifact: { id, projectId: "project", kind: "TEXT", title: id,
    resourceDefaultVersionId: `${id}-latest`, version: 1, createdAt: now, updatedAt: now,
    resourceDefaultVersion: { id: `${id}-latest`, versionNo: 2, schemaVersion: 1, content: { text, format: "PLAIN_TEXT" },
      inputReferences: [], createdByKind: "USER", runId: null, createdAt: now } } });

describe("shared canvas prompts", () => {
  it("sorts by creation instant then UUID, resolves the latest saved body and omits disconnected inputs", () => {
    const inputs = projectPromptInputs([item("a", "New a"), item("b", "New b")],
      [edge("2", "b"), edge("1", "a")], "target");
    expect(inputs?.map((input) => input.text)).toEqual(["New a", "New b"]);
    expect(inputs?.[0]?.versionId).toBe("a-latest");
    expect(projectPromptInputs([item("a", "New a")], [], "target")).toEqual([]);
  });
  it("keeps PostgreSQL microsecond creation order even within one browser millisecond", () => {
    const inputs = projectPromptInputs([item("a", "First"), item("b", "Second")],
      [edge("1", "b", "2026-10-20T00:00:00.000200Z"), edge("2", "a", "2026-10-20T00:00:00.000100Z")], "target");
    expect(inputs?.map((input) => input.text)).toEqual(["First", "Second"]);
  });

  it("skips blank sources, permits upstream-only and never modifies the local text", () => {
    const inputs = projectPromptInputs([item("a", " Shared \n"), item("b", "  ")], [edge("1", "a"), edge("2", "b")], "target")!;
    const local = "  Local subject  ";
    expect(composePromptInputs(inputs, local)).toBe("Shared\n\nLocal subject");
    expect(composePromptInputs(inputs, "")).toBe("Shared");
    expect(composePromptInputs([], local)).toBe(local);
    expect(local).toBe("  Local subject  ");
  });
  it("reports a missing saved source rather than silently sending an incomplete prompt", () => {
    expect(projectPromptInputs([], [edge("1", "missing")], "target")).toBeNull();
  });
});
