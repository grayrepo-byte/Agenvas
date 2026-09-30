import type { Edge } from "@xyflow/react";
import type { CanvasDisplayEdge } from "./CanvasRelationEdge";
import type { CanvasDisplayPreferences } from "./useCanvasDisplayPreferences";

/** Display only direct incoming/outgoing relations, without altering the server's topology.
 * A selected line stays visible when React Flow clears node selection so Delete still works. */
export function displayCanvasRelations(edges: Edge[], selectedNodeIds: string[], selectedEdgeIds: string[],
  preferences: CanvasDisplayPreferences): CanvasDisplayEdge[] {
  const nodes = new Set(selectedNodeIds);
  const selected = new Set(selectedEdgeIds);
  return edges.map((edge) => ({
    ...edge,
    type: "canvasRelation",
    hidden: !preferences.alwaysShowConnections && !nodes.has(edge.source)
      && !nodes.has(edge.target) && !selected.has(edge.id),
    selected: selected.has(edge.id),
    data: { connectionFlowEnabled: preferences.connectionFlowEnabled },
  }));
}
