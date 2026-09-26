import { NodeToolbar, Position } from "@xyflow/react";
import { Clock, FilmSlate, LinkSimple, LockSimple, MapPin, PencilSimple,
  SlidersHorizontal, Stack, TextT, UserCircle } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import type { Artifact } from "../../shared/api/client";
import "./ContentCanvasCard.css";

/**
 * Content bars and source chips adapted from Beautiful UI's ContextCards;
 * contextual action layout adapted from SelectionActions (MIT).
 * Copyright (c) 2026 Shane Levine. See beautiful-ui-LICENSE.txt.
 * Every field is persisted content; these cards do not simulate edits or generation.
 */
type ContentCanvasCardProps = {
  artifact: Artifact;
  selected: boolean;
  locked: boolean;
  onEdit: () => void;
  onInspect: () => void;
  children: ReactNode;
};

const MILLISECONDS_PER_SECOND = 1_000;

function presentation(kind: Artifact["kind"]) {
  switch (kind) {
    case "TEXT": return { label: "文字", icon: TextT };
    case "CHARACTER": return { label: "角色", icon: UserCircle };
    case "SCENE": return { label: "场景", icon: MapPin };
    case "SHOT": return { label: "镜头", icon: FilmSlate };
    default: return { label: "内容", icon: TextT };
  }
}

function readText(content: unknown, key: string): string {
  if (!content || typeof content !== "object" || !(key in content)) return "";
  const value: unknown = content[key as keyof typeof content];
  return typeof value === "string" ? value : "";
}

function positiveNumber(content: unknown, key: string): number | null {
  if (!content || typeof content !== "object" || !(key in content)) return null;
  const value: unknown = content[key as keyof typeof content];
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
}

function durationText(content: unknown): string | null {
  const seconds = positiveNumber(content, "durationSeconds");
  if (seconds !== null) return `${seconds} 秒`;
  const milliseconds = positiveNumber(content, "durationMs");
  return milliseconds === null ? null : `历史 ${milliseconds / MILLISECONDS_PER_SECOND} 秒`;
}

function ContentField({ label, value }: { label: string; value: string }) {
  if (!value.trim()) return null;
  return <div className="content-card-field"><dt>{label}</dt><dd>{value}</dd></div>;
}

/** Structured content stays readable and selectable; editing and history are separate actions. */
export function ContentCanvasCard({ artifact, selected, locked, onEdit, onInspect,
  children }: ContentCanvasCardProps) {
  const content = artifact.currentVersion?.content;
  const { label, icon: Icon } = presentation(artifact.kind);
  const text = readText(content, "text");
  const duration = artifact.kind === "SHOT" ? durationText(content) : null;
  const header = artifact.kind === "TEXT"
    ? readText(content, "format") === "MARKDOWN" ? "Markdown" : "正文"
    : readText(content, "name") || artifact.title;
  const references = artifact.currentVersion?.inputReferences.length ?? 0;
  const emptyText = artifact.kind === "TEXT" && !text.trim();

  return <>
    <NodeToolbar isVisible={selected ? undefined : false} position={Position.Top}
      style={{ top: 16, left: "50%", transform: "translateX(-50%)", zIndex: 6 }}>
      <div className="media-card-toolbar content-card-toolbar nodrag nowheel" aria-label={`${label}卡片操作`}>
        <button type="button" onClick={onEdit}><PencilSimple size={17} aria-hidden />编辑内容</button>
        <button type="button" onClick={onInspect}><SlidersHorizontal size={17} aria-hidden />卡片详情</button>
      </div>
    </NodeToolbar>
    <article className={`content-canvas-card${selected ? " is-selected" : ""}`}
      aria-label={`${artifact.title} · ${label}${locked ? " · 已锁定" : ""}`}>
      {children}
      <span className="content-card-caption" title={artifact.title}>{artifact.title}</span>
      <div className="content-card-bar">
        <Icon size={17} aria-hidden />
        <h3 title={header}>{header}</h3>
        {duration ? <span className="content-card-duration" aria-label={`镜头时长：${duration}`}>
          <Clock size={12} aria-hidden />{duration}</span> : null}
      </div>
      {emptyText ? <div className="content-card-empty">
        <TextT size={44} aria-hidden />
        <span>写下想法，让创作开始</span>
        <button className="nodrag" type="button" onClick={onEdit}><PencilSimple size={15} aria-hidden />编辑文字</button>
      </div> : <div className="content-card-body nodrag nowheel nopan" tabIndex={0}
        role="region" aria-label={`${label}正文`}>
        {artifact.kind === "TEXT" ? <p className="content-card-text">{text}</p> : <dl>
          {artifact.kind === "CHARACTER" ? <>
            <ContentField label="描述" value={readText(content, "description")} />
            <ContentField label="外观" value={readText(content, "appearance")} />
          </> : null}
          {artifact.kind === "SCENE" ? <>
            <ContentField label="地点" value={readText(content, "location")} />
            <ContentField label="时间" value={readText(content, "timeOfDay")} />
            <ContentField label="光线" value={readText(content, "lighting")} />
            <ContentField label="风格" value={readText(content, "style")} />
          </> : null}
          {artifact.kind === "SHOT" ? <>
            <ContentField label="描述" value={readText(content, "description")} />
            <ContentField label="动作" value={readText(content, "action")} />
            <ContentField label="运镜" value={readText(content, "camera")} />
          </> : null}
        </dl>}
      </div>}
      <footer className="content-card-sources">
        <span className="content-card-chip"><Icon size={12} aria-hidden />{label}</span>
        <span className="content-card-chip"><Stack size={12} aria-hidden />
          {artifact.currentVersion ? `v${artifact.currentVersion.versionNo}` : "暂无版本"}</span>
        {references > 0 ? <span className="content-card-chip"><LinkSimple size={12} aria-hidden />{references} 个引用</span> : null}
        {locked ? <LockSimple className="content-card-locked" size={13} aria-label="已锁定" /> : null}
      </footer>
    </article>
  </>;
}
