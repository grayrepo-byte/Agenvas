import { useEffect, useMemo, useRef, type RefObject } from "react";
import type { FitViewOptions, ReactFlowInstance } from "@xyflow/react";
import { canvasViewportMotionOptions } from "./canvasViewportMotion";

type FitOptions = Omit<FitViewOptions, "nodes" | "duration" | "ease" | "interpolate"> & { nodes?: { id: string }[] };
type FlowViewport = Pick<ReactFlowInstance,
  "getViewport" | "setViewport" | "setCenter" | "zoomIn" | "zoomOut" | "zoomTo"> & {
    fitView: (options?: FitOptions) => Promise<boolean>;
  };
export type CanvasViewportNavigation = {
  center: (x: number, y: number, zoom: number) => void;
  fit: (options: FitOptions) => void;
  zoomIn: () => void;
  zoomOut: () => void;
  zoomTo: (zoom: number) => void;
  cancel: () => void;
};

/** Native React Flow transitions own the camera. User input always takes it back before
 * a node drag records its world coordinates. An interrupted D3 promise never settles,
 * so editing and gesture availability must not depend on awaiting camera animation.
 */
export function useCanvasViewportMotion(flow: RefObject<FlowViewport | null>,
  canvas: RefObject<HTMLElement | null>): CanvasViewportNavigation {
  const active = useRef(false);
  const expiry = useRef<number | null>(null);
  const navigation = useMemo<CanvasViewportNavigation>(() => {
    function clear() {
      if (expiry.current !== null) window.clearTimeout(expiry.current);
      expiry.current = null;
      active.current = false;
    }
    function cancel() {
      const instance = flow.current;
      const wasActive = active.current;
      clear();
      if (wasActive && instance) void instance.setViewport(instance.getViewport(), { duration: 0 });
    }
    function start(duration: number, action: () => Promise<boolean>) {
      cancel();
      active.current = duration > 0;
      if (active.current) expiry.current = window.setTimeout(clear, duration + 64);
      void action();
    }
    return {
      cancel,
      center(x, y, zoom) {
        const instance = flow.current;
        if (!instance) return;
        const bounds = canvas.current?.querySelector(".react-flow")?.getBoundingClientRect();
        const from = instance.getViewport();
        const to = bounds ? { x: bounds.width / 2 - x * zoom, y: bounds.height / 2 - y * zoom, zoom } : undefined;
        const motion = canvasViewportMotionOptions("focus", from, to);
        start(motion.duration ?? 0, () => instance.setCenter(x, y, { zoom, ...motion }));
      },
      fit(options) {
        const instance = flow.current;
        if (!instance) return;
        const motion = canvasViewportMotionOptions("overview");
        start(motion.duration ?? 0, () => instance.fitView({ ...options, ...motion }));
      },
      zoomIn() {
        const instance = flow.current;
        if (!instance) return;
        const motion = canvasViewportMotionOptions("zoom");
        start(motion.duration ?? 0, () => instance.zoomIn(motion));
      },
      zoomOut() {
        const instance = flow.current;
        if (!instance) return;
        const motion = canvasViewportMotionOptions("zoom");
        start(motion.duration ?? 0, () => instance.zoomOut(motion));
      },
      zoomTo(zoom) {
        const instance = flow.current;
        if (!instance) return;
        const motion = canvasViewportMotionOptions("zoom");
        start(motion.duration ?? 0, () => instance.zoomTo(zoom, motion));
      },
    };
  }, [flow, canvas]);
  useEffect(() => {
    const preference = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    const changed = () => { if (preference?.matches) navigation.cancel(); };
    const hidden = () => { if (document.hidden) navigation.cancel(); };
    preference?.addEventListener?.("change", changed);
    document.addEventListener("visibilitychange", hidden);
    window.addEventListener("blur", navigation.cancel);
    return () => {
      preference?.removeEventListener?.("change", changed);
      document.removeEventListener("visibilitychange", hidden);
      window.removeEventListener("blur", navigation.cancel);
      navigation.cancel();
    };
  }, [navigation]);
  return navigation;
}
