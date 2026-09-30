import { BaseEdge, getBezierPath, type Edge, type EdgeProps } from "@xyflow/react";
import { memo, useId, useSyncExternalStore } from "react";

export type CanvasDisplayEdge = Edge<{ connectionFlowEnabled: boolean }, "canvasRelation">;

const FLOW_DURATION = "2.8s";
const FLOW_HALF_LENGTH = 44;
const FLOW_HALF_WIDTH = 16;
const FLOW_ENDPOINT_FADE_FRACTION = 0.08;
const FLOW_OPACITY_KEY_TIMES = `0;${FLOW_ENDPOINT_FADE_FRACTION};${1 - FLOW_ENDPOINT_FADE_FRACTION};1`;
const REDUCED_MOTION_QUERY = "(prefers-reduced-motion: reduce)";
const FLOW_OPACITY_PROFILE = [
  { offset: "0%", opacity: 1 },
  { offset: "25%", opacity: 0.85 },
  { offset: "55%", opacity: 0.4 },
  { offset: "80%", opacity: 0.08 },
  { offset: "100%", opacity: 0 },
] as const;

function subscribeReducedMotion(onChange: () => void) {
  const query = window.matchMedia?.(REDUCED_MOTION_QUERY);
  query?.addEventListener("change", onChange);
  return () => query?.removeEventListener("change", onChange);
}
function prefersReducedMotion() {
  return window.matchMedia?.(REDUCED_MOTION_QUERY).matches ?? false;
}

/** Keep React Flow's hit path and static relation style; the light never captures pointer events. */
export const CanvasRelationEdge = memo(function CanvasRelationEdge({ id, sourceX, sourceY,
  targetX, targetY, sourcePosition, targetPosition, style, markerStart, markerEnd,
  interactionWidth, data }: EdgeProps<CanvasDisplayEdge>) {
  const [path] = getBezierPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition });
  const effectId = useId();
  const gradientId = `canvas-flow-gradient-${effectId}`;
  const maskId = `canvas-flow-mask-${effectId}`;
  const reducedMotion = useSyncExternalStore(subscribeReducedMotion, prefersReducedMotion);
  return <>
    <BaseEdge id={id} path={path} style={style} markerStart={markerStart}
      markerEnd={markerEnd} interactionWidth={interactionWidth} />
    {data?.connectionFlowEnabled && !reducedMotion ? <g className="canvas-relation-flow" aria-hidden="true" pointerEvents="none">
      <defs>
        <radialGradient id={gradientId}>
          {FLOW_OPACITY_PROFILE.map(({ offset, opacity }) =>
            <stop key={offset} offset={offset} stopColor="white" stopOpacity={opacity} />)}
        </radialGradient>
        <mask id={maskId} style={{ maskType: "alpha" }}>
          <ellipse rx={FLOW_HALF_LENGTH} ry={FLOW_HALF_WIDTH} fill={`url(#${gradientId})`}>
            <animateMotion path={path} dur={FLOW_DURATION} repeatCount="indefinite" rotate="auto" />
            <animate attributeName="opacity" values="0;1;1;0" keyTimes={FLOW_OPACITY_KEY_TIMES}
              dur={FLOW_DURATION} repeatCount="indefinite" />
          </ellipse>
        </mask>
      </defs>
      <g mask={`url(#${maskId})`}>
        {/* Nonzero geometry keeps the object-bounding-box mask valid for perfectly straight edges.
            The paths themselves extend that box to include all of the Bezier curve. */}
        <rect x={Math.min(sourceX, targetX) - FLOW_HALF_LENGTH} y={Math.min(sourceY, targetY) - FLOW_HALF_WIDTH}
          width={Math.abs(targetX - sourceX) + FLOW_HALF_LENGTH * 2}
          height={Math.abs(targetY - sourceY) + FLOW_HALF_WIDTH * 2} fill="none" />
        <path d={path} className="canvas-relation-flow__glow" />
        <path d={path} className="canvas-relation-flow__core" />
      </g>
    </g> : null}
  </>;
});
