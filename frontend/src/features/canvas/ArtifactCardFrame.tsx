import { NodeToolbar, Position } from "@xyflow/react";
import type { ReactNode } from "react";
import "./ArtifactCardFrame.css";

const TOOLBAR_NODE_GAP = 32;

/** Shared media/content surface and node-anchored toolbar; callers own their content and actions. */
export function ArtifactCardFrame({ title, kindLabel, selected, locked, toolbar, toolbarLabel,
  className = "", children }: {
  title: string; kindLabel: string; selected: boolean; locked: boolean;
  toolbar: ReactNode; toolbarLabel?: string; className?: string; children: ReactNode;
}) {
  return <>
    <NodeToolbar isVisible={selected ? undefined : false} position={Position.Top} offset={TOOLBAR_NODE_GAP}>
      <div className="artifact-card-toolbar nodrag nowheel" aria-label={toolbarLabel ?? `${kindLabel}卡片操作`}>
        {toolbar}
      </div>
    </NodeToolbar>
    <article className={`artifact-canvas-card ${className}${selected ? " is-selected" : ""}`}
      aria-label={`${title} · ${kindLabel}${locked ? " · 已锁定" : ""}`}>
      <span className="artifact-card-caption" title={title}>{title}</span>
      {children}
    </article>
  </>;
}
