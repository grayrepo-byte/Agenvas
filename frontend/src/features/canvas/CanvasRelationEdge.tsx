import { BaseEdge, getBezierPath, type Edge, type EdgeProps } from "@xyflow/react";
import { memo } from "react";

export type CanvasDisplayEdge = Edge<{ connectionFlowEnabled: boolean }, "canvasRelation">;

/** Keep React Flow's hit path and static relation style; the light never captures pointer events. */
export const CanvasRelationEdge = memo(function CanvasRelationEdge({ id, sourceX, sourceY,
  targetX, targetY, sourcePosition, targetPosition, style, markerStart, markerEnd,
  interactionWidth, data }: EdgeProps<CanvasDisplayEdge>) {
  const [path] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
  return <>
    <BaseEdge id={id} path={path} style={style} markerStart={markerStart}
      markerEnd={markerEnd} interactionWidth={interactionWidth} />
    {data?.connectionFlowEnabled ? <g className="canvas-relation-flow" aria-hidden="true" pointerEvents="none">
      <path d={path} pathLength={100} className="canvas-relation-flow__glow" />
      <path d={path} pathLength={100} className="canvas-relation-flow__trail" />
      <path d={path} pathLength={100} className="canvas-relation-flow__head" />
    </g> : null}
  </>;
});
