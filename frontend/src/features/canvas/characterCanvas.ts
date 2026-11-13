import type { Connection, Edge } from "@xyflow/react";
import type { CharacterNodeSelection } from "../../shared/api/characterNodes";
import type { CanvasItem } from "../../shared/api/client";

/** Character topology describes draft application relationships, never media inputs or executable DAGs. */
export function characterConnectionForGesture(items: CanvasItem[], connection: Connection | Edge) {
  if (connection.sourceHandle !== "artifact-output" || connection.targetHandle !== "artifact-input") return null;
  const source = items.find(item => item.id === connection.source);
  const target = items.find(item => item.id === connection.target);
  if (
    !source?.characterNode ||
    source.locked ||
    !target ||
    target.locked ||
    (target.artifact?.kind !== "IMAGE" && target.artifact?.kind !== "VIDEO")
  )
    return null;
  return { source, target };
}

export function projectCharacterRelations(items: CanvasItem[]): Edge[] {
  const visible = new Set(items.map(item => item.id));
  return items.flatMap(item =>
    (item.characterNode?.connections ?? []).flatMap(connection =>
      visible.has(connection.targetCanvasItemId)
        ? [
            {
              id: `character-connection:${connection.id}`,
              source: connection.sourceCanvasItemId,
              target: connection.targetCanvasItemId,
              sourceHandle: "artifact-output",
              targetHandle: "artifact-input",
              className: "relation-edge relation-edge--reference",
            },
          ]
        : [],
    ),
  );
}

export function characterConnectionForEdge(items: CanvasItem[], edge: Edge) {
  return (
    items
      .flatMap(item => item.characterNode?.connections ?? [])
      .find(connection => edge.id === `character-connection:${connection.id}`) ?? null
  );
}

/** JSON member order is irrelevant; selected reference order remains intentional. */
export function sameCharacterSelection(first: CharacterNodeSelection, second: CharacterNodeSelection): boolean {
  return (
    first.characterId === second.characterId &&
    first.characterVersion === second.characterVersion &&
    first.lookId === second.lookId &&
    first.useAppearance === second.useAppearance &&
    first.useVoice === second.useVoice &&
    first.viewVersionIds.length === second.viewVersionIds.length &&
    first.viewVersionIds.every((value, index) => value === second.viewVersionIds[index])
  );
}
