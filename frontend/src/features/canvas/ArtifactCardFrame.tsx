import { t, useLocale } from "../../shared/i18n";
import { NodeToolbar, Position } from "@xyflow/react";
import type { ReactNode } from "react";
import type { CanvasItem } from "../../shared/api/client";
import { CanvasItemTitleEditor } from "./CanvasItemTitleEditor";
import "./ArtifactCardFrame.css";

const TOOLBAR_NODE_GAP = 44;

/** Shared media/content surface and node-anchored toolbar; callers own their content and actions. */
export function ArtifactCardFrame({ title, titleIcon, kindLabel, selected, locked, toolbar, toolbarLabel,
  toolbarRaised = false, toolbarVisible = true, toolbarStage = "result", editableTitle, className = "", children }: {
  title: string; kindLabel: string; selected: boolean; locked: boolean;
  titleIcon?: ReactNode;
  toolbar: ReactNode; toolbarLabel?: string;
  toolbarRaised?: boolean;
  toolbarVisible?: boolean;
  toolbarStage?: "draft" | "result";
  editableTitle?: { projectId: string; item: CanvasItem };
  className?: string; children: ReactNode;
}) {
  useLocale();
  return <>
    {/* Toolbar portals sit outside the node; exclude their gestures from viewport panning. */}
    <NodeToolbar className={`nopan${toolbarRaised ? " artifact-card-toolbar-raised" : ""}`}
      isVisible={selected && toolbarVisible ? undefined : false} position={Position.Top} offset={TOOLBAR_NODE_GAP}>
      <div className="artifact-card-toolbar nodrag nowheel" aria-label={toolbarLabel ?? t("canvas.card.toolbarLabel", { "0": kindLabel })}>
        <span className="studio-result-label">{t(toolbarStage === "draft" ? "canvas.studio.draft" : "canvas.studio.result")}</span>
        {toolbar}
      </div>
    </NodeToolbar>
    <article className={`artifact-canvas-card ${className}${selected ? " is-selected" : ""}`}
      aria-label={`${title} · ${kindLabel}${locked ? t("canvas.card.lockedSuffix") : ""}`}>
      <div className="artifact-card-title">
        {titleIcon ? <span className="artifact-card-title-icon" aria-hidden="true">{titleIcon}</span> : null}
        {editableTitle
        ? <CanvasItemTitleEditor {...editableTitle} kindLabel={kindLabel} />
        : <span className="artifact-card-caption" title={title}>{title}</span>}
      </div>
      {children}
    </article>
  </>;
}
