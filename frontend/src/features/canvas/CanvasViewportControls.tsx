import { MiniMap, useReactFlow, useStore } from "@xyflow/react";
import { useState, type ReactNode } from "react";
import { ArrowsOut, Minus, Plus, Stack } from "@/shared/ui/icons";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Separator } from "../../shared/ui/primitives/separator";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "../../shared/ui/primitives/tooltip";

/** Subscribe only this small dock to zoom updates, rather than rebuilding the workspace. */
export function CanvasViewportControls({ hasNodes }: { hasNodes: boolean }) {
  useLocale();
  const flow = useReactFlow();
  const zoom = useStore(state => state.transform[2]);
  const minZoom = useStore(state => state.minZoom);
  const maxZoom = useStore(state => state.maxZoom);
  const [overviewOpen, setOverviewOpen] = useState(false);
  // Read at action time so changes to the OS preference apply without remounting the canvas.
  const motion = () => ({ duration: window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ? 0 : 220 });
  return <TooltipProvider delayDuration={350}>
    {overviewOpen && hasNodes ? <MiniMap className="canvas-overview" position="bottom-left" pannable zoomable
      nodeColor="var(--ui-canvas-minimap-node)" nodeStrokeColor="var(--ui-border-strong)"
      maskColor="var(--ui-canvas-minimap-mask)" /> : null}
    <div className="canvas-viewport-dock nodrag nopan nowheel" role="group" aria-label={t("canvas.workspace.viewportControls")}>
      <ViewportHint label={t("canvas.workspace.zoomOut")}><Button variant="ghost" size="icon-sm"
        aria-label={t("canvas.workspace.zoomOut")} disabled={zoom <= minZoom + 0.001}
        onClick={() => void flow.zoomOut(motion())}><Minus /></Button></ViewportHint>
      <ViewportHint label={t("canvas.viewport.resetZoom")}><Button variant="ghost" size="sm" className="canvas-zoom-value"
        aria-label={t("canvas.viewport.resetZoom")} onClick={() => void flow.zoomTo(1, motion())}>
        {Math.round(zoom * 100)}%
      </Button></ViewportHint>
      <ViewportHint label={t("canvas.workspace.zoomIn")}><Button variant="ghost" size="icon-sm"
        aria-label={t("canvas.workspace.zoomIn")} disabled={zoom >= maxZoom - 0.001}
        onClick={() => void flow.zoomIn(motion())}><Plus /></Button></ViewportHint>
      <Separator orientation="vertical" className="data-[orientation=vertical]:h-4" />
      <ViewportHint label={t("canvas.workspace.fitView")}><Button variant="ghost" size="icon-sm"
        aria-label={t("canvas.workspace.fitView")} disabled={!hasNodes}
        onClick={() => void flow.fitView({ padding: 0.2, maxZoom: 1, ...motion() })}><ArrowsOut /></Button></ViewportHint>
      <ViewportHint label={t("canvas.workspace.minimap")}><Button variant={overviewOpen ? "secondary" : "ghost"} size="icon-sm"
        aria-label={t("canvas.workspace.minimap")} aria-pressed={overviewOpen} disabled={!hasNodes}
        onClick={() => setOverviewOpen(open => !open)}><Stack /></Button></ViewportHint>
    </div>
  </TooltipProvider>;
}

function ViewportHint({ label, children }: { label: string; children: ReactNode }) {
  return <Tooltip><TooltipTrigger asChild>{children}</TooltipTrigger>
    <TooltipContent side="top" sideOffset={10}>{label}</TooltipContent></Tooltip>;
}
