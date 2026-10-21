import { describe,expect,it } from "vitest";
import type { CanvasConnection,CanvasItem } from "../../shared/api/client";
import { canvasRelationRemoval,isCanvasConnectionValid,mediaInputConnection,projectCanvasRelations } from "./canvasRelations";
import { canvasItemVersionId } from "./versionedArtifact";

const now = "2026-10-20T00:00:00Z";
function item(id: string, kind: "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" = "IMAGE", selected = `${id}-selected`): CanvasItem {
  const version = { id: selected, versionNo: 1, schemaVersion: 1 as const,
    content: { sourceType: "UPLOAD" as const, assetId: `${id}-asset` }, inputReferences: [],
    createdByKind: "USER" as const, runId: null, createdAt: now };
  return { id, subjectType: "ARTIFACT", subjectId: `${id}-artifact`, title: id, x: 0, y: 0,
    width: 320, height: 240, zIndex: 0, groupId: null, locked: false, version: 0,
    selectedVersionId: selected, selectedVersion: version,
    artifact: { id: `${id}-artifact`, projectId: "project", kind, title: id,
      resourceDefaultVersionId: `${id}-default`, resourceDefaultVersion: { ...version, id: `${id}-default` },
      version: 0, createdAt: now, updatedAt: now } };
}
function relation(source: CanvasItem, target: CanvasItem, relationType: CanvasConnection["relationType"] = "MEDIA_INPUT"): CanvasConnection {
  return { id: "relation", projectId: "project", sourceCanvasItemId: source.id,
    targetCanvasItemId: target.id, sourceArtifactVersionId: source.selectedVersionId!, relationType,
    version: 0, createdAt: now, updatedAt: now };
}
const gesture = (source: CanvasItem, target: CanvasItem) => ({ source: source.id, target: target.id,
  sourceHandle: "artifact-output", targetHandle: "artifact-input" });

describe("media canvas relations", () => {
  it.each(["IMAGE", "VIDEO", "AUDIO"] as const)("uses the %s node's exact selection instead of its library default", (kind) => {
    const source = item("source", kind); const target = item("target", "VIDEO");
    expect(canvasItemVersionId(source)).toBe("source-selected");
    expect(mediaInputConnection([source, target], gesture(source, target))).toEqual({
      sourceCanvasItemId: source.id, targetCanvasItemId: target.id, sourceVersionId: "source-selected" });
    source.selectedVersionId = null; source.selectedVersion = null;
    expect(mediaInputConnection([source, target], gesture(source, target))).toBeNull();
  });

  it.each(["IMAGE", "VIDEO", "AUDIO"] as const)("leaves %s workflow input compatibility to the published contract", (kind) => {
    const source = item("source", "VIDEO"); const target = item("target", kind);
    expect(isCanvasConnectionValid([source, target], gesture(source, target))).toBe(true);
  });

  it("accepts empty media destinations without inventing a selected version", () => {
    const source = item("source"); const target = item("target");
    target.selectedVersionId = null; target.selectedVersion = null;
    expect(isCanvasConnectionValid([source, target], gesture(source, target))).toBe(true);
    expect(target.selectedVersionId).toBeNull();
  });

  it("rejects text inputs, text destinations, self relations, missing nodes and reversed handles", () => {
    const source = item("source"); const text = item("text", "TEXT");
    expect(isCanvasConnectionValid([source, text], gesture(source, text))).toBe(false);
    expect(isCanvasConnectionValid([source, text], gesture(text, source))).toBe(false);
    expect(isCanvasConnectionValid([source], gesture(source, source))).toBe(false);
    expect(isCanvasConnectionValid([source], gesture(source, item("missing")))).toBe(false);
    expect(isCanvasConnectionValid([source, item("target")], { ...gesture(source, item("target")), sourceHandle: "artifact-input" })).toBe(false);
  });

  it("projects only persisted relations with two visible endpoints", () => {
    const source = item("source"); const target = item("target"); const line = relation(source, target);
    expect(projectCanvasRelations([source, target], [line])).toEqual([expect.objectContaining({
      id: "canvas-connection:relation", source: source.id, target: target.id,
      sourceHandle: "artifact-output", targetHandle: "artifact-input", className: "relation-edge relation-edge--reference" })]);
    expect(projectCanvasRelations([source], [line])).toEqual([]);
    expect(projectCanvasRelations([target], [line])).toEqual([]);
    expect(projectCanvasRelations([source, target])).toEqual([]);
  });

  it("marks historical inputs without changing their exact version", () => {
    const source = item("source"); const target = item("target"); const line = relation(source, target);
    line.sourceArtifactVersionId = "source-old";
    expect(projectCanvasRelations([source, target], [line])[0]?.className).toBe("relation-edge relation-edge--reference relation-edge--historical");
    expect(line.sourceArtifactVersionId).toBe("source-old");
  });

  it("keeps media derivation removable and distinct from input references", () => {
    const source = item("source"); const target = item("target"); const line = relation(source, target, "MEDIA_DERIVATION");
    const edge = projectCanvasRelations([source, target], [line])[0]!;
    expect(edge.className).toBe("relation-edge relation-edge--derivation");
    expect(canvasRelationRemoval([line], edge)).toEqual({ kind: "mediaConnection", connection: line });
    expect(target.selectedVersionId).toBe("target-selected");
    expect(source.selectedVersionId).toBe("source-selected");
  });

  it("deletes only existing server relation identities", () => {
    const source = item("source"); const target = item("target"); const line = relation(source, target);
    const edge = projectCanvasRelations([source, target], [line])[0]!;
    expect(canvasRelationRemoval([line], edge)).toEqual({ kind: "mediaConnection", connection: line });
    expect(canvasRelationRemoval([], edge)).toBeNull();
    expect(canvasRelationRemoval([line], { ...edge, id: "missing" })).toBeNull();
    expect(isCanvasConnectionValid([source, target], edge)).toBe(true);
  });
});
