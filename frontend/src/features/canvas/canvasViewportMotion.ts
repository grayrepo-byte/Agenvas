import type { Viewport, ViewportHelperFunctionOptions } from "@xyflow/react";

export type CanvasMotionIntent = "focus" | "overview" | "zoom";
const DURATION = { focus: 320, overview: 360, zoom: 220 } as const;

/** A quick response with a gentle landing; never overshoot a node or change the travel path. */
export const canvasViewportEase = (progress: number) => 1 - (1 - progress) ** 3;

export function canvasViewportMotionOptions(intent: CanvasMotionIntent,
  from?: Viewport, to?: Viewport): ViewportHelperFunctionOptions {
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false;
  let duration: number = DURATION[intent];
  if (from && to) {
    const distance = Math.hypot(to.x - from.x, to.y - from.y);
    const zoomDistance = Math.abs(Math.log(to.zoom / from.zoom));
    // An already aligned camera should not tremble or start a redundant transition.
    duration = distance < 0.75 && zoomDistance < 0.002 ? 0
      : Math.round(Math.min(420, Math.max(240, 220 + distance * 0.16 + zoomDistance * 140)));
  }
  return { duration: reduced ? 0 : duration, ease: canvasViewportEase, interpolate: "linear" };
}
