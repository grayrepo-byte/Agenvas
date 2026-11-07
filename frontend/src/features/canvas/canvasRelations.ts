import type { Connection, Edge } from "@xyflow/react";
import type { CanvasConnection, CanvasItem } from "../../shared/api/client";
import { canvasItemVersionId } from "./versionedArtifact";

/** Persisted canvas relations describe prompt inputs, exact media inputs or lineage, never execution dependencies. */
export function projectCanvasRelations(items: CanvasItem[], connections: CanvasConnection[] = []): Edge[] {
  const byId = new Map(items.map((item) => [item.id, item]));
  return connections.flatMap((connection) => {
    const source = byId.get(connection.sourceCanvasItemId);
    const target = byId.get(connection.targetCanvasItemId);
    if (!source || !target) return [];
    const historical = connection.relationType !== "PROMPT_INPUT" && canvasItemVersionId(source) !== connection.sourceArtifactVersionId;
    const derivation = connection.relationType === "MEDIA_DERIVATION";
    return [{ id: `canvas-connection:${connection.id}`, source: source.id, sourceHandle: "artifact-output",
      target: target.id, targetHandle: "artifact-input",
      className: `relation-edge ${derivation ? "relation-edge--derivation" : "relation-edge--reference"}${historical ? " relation-edge--historical" : ""}` }];
  });
}

/** A gesture uses the source node's exact selection and never its resource-library default. */
export function mediaInputSource(items: CanvasItem[], sourceId: string, sourceHandle: string | null) {
  if (sourceHandle !== "artifact-output") return null;
  const source = items.find((item) => item.id === sourceId);
  const sourceVersionId = source ? canvasItemVersionId(source) : null;
  if (!source?.artifact || !["IMAGE", "AUDIO", "VIDEO"].includes(source.artifact.kind) || !sourceVersionId) return null;
  return { sourceCanvasItemId: source.id, sourceVersionId };
}

/** Text sources follow their saved body; the creation version only guards a stale drag gesture. */
export function canvasInputSource(items: CanvasItem[], sourceId: string, sourceHandle: string | null) {
  if (sourceHandle !== "artifact-output") return null;
  const source = items.find((item) => item.id === sourceId);
  const sourceVersionId = source ? canvasItemVersionId(source) : null;
  return source?.artifact && sourceVersionId ? { sourceCanvasItemId: source.id, sourceVersionId } : null;
}

export function promptInputConnection(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.targetHandle !== "artifact-input") return null;
  const source = items.find((item) => item.id === connection.source);
  const target = items.find((item) => item.id === connection.target);
  const input = canvasInputSource(items, connection.source, connection.sourceHandle ?? null);
  return input && source?.artifact?.kind === "TEXT" && target?.artifact && target.artifact.kind !== "TEXT"
    && source.id !== target.id ? { ...input, targetCanvasItemId: target.id } : null;
}

/** Targets may be empty media drafts; the source must already have a selected result. */
export function mediaInputConnection(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.targetHandle !== "artifact-input") return null;
  const source = mediaInputSource(items, connection.source, connection.sourceHandle ?? null);
  const target = items.find((item) => item.id === connection.target);
  if (!source || !target?.artifact || target.artifact.kind === "TEXT" || source.sourceCanvasItemId === target.id) return null;
  return { ...source, targetCanvasItemId: target.id };
}

/** Published input contracts decide media compatibility; a persisted relation never generates. */
export function isCanvasConnectionValid(items: CanvasItem[], connection: Connection | Edge) {
  return promptInputConnection(items, connection) !== null || mediaInputConnection(items, connection) !== null;
}

/** Deletion resolves only a real server-owned input or lineage record. */
export function canvasConnectionForEdge(connections: CanvasConnection[], edge: Edge): CanvasConnection | null {
  return connections.find((candidate) => edge.id === `canvas-connection:${candidate.id}`) ?? null;
}
