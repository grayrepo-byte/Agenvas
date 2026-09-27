import { Clock, FilmSlate, LinkSimple, LockSimple, MapPin, PencilSimple,
  SlidersHorizontal, Stack, TextT, UserCircle } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { useState } from "react";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { readContentNumber, readContentText as readText } from "./artifactContent";
import { TextCanvasEditor } from "./TextCanvasEditor";
import { TextVersionPicker } from "./TextVersionPicker";
import { hasCurrentVersion } from "./versionedArtifact";
import "./ContentCanvasCard.css";

/**
 * Content bars and source chips adapted from Beautiful UI's ContextCards;
 * contextual action layout adapted from SelectionActions (MIT).
 * Copyright (c) 2026 Shane Levine. See beautiful-ui-LICENSE.txt.
 * Every field is persisted content; these cards do not simulate edits or generation.
 */
type ContentCanvasCardProps = {
  artifact: Artifact;
  item: CanvasItem;
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

function positiveNumber(content: unknown, key: string): number | null {
  const value = readContentNumber(content, key);
  return Number.isFinite(value) && value > 0 ? value : null;
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

/** Text edits in place; structured content keeps its dedicated node-anchored editor. */
export function ContentCanvasCard({ artifact, item, selected, locked, onEdit, onInspect,
  children }: ContentCanvasCardProps) {
  const [editingText, setEditingText] = useState(false);
  const content = artifact.currentVersion?.content;
  const { label, icon: Icon } = presentation(artifact.kind);
  const text = readText(content, "text");
  const duration = artifact.kind === "SHOT" ? durationText(content) : null;
  const header = artifact.kind === "TEXT"
    ? readText(content, "format") === "MARKDOWN" ? "Markdown" : "正文"
    : readText(content, "name") || artifact.title;
  const references = artifact.currentVersion?.inputReferences.length ?? 0;
  const emptyText = artifact.kind === "TEXT" && !text.trim();

  return <ArtifactCardFrame title={item.title} kindLabel={label} selected={selected} locked={locked}
    editableTitle={{ projectId: artifact.projectId, item }} className="content-canvas-card" toolbar={<>
        <button type="button" disabled={artifact.kind === "TEXT" && !hasCurrentVersion(artifact)}
          onClick={artifact.kind === "TEXT"
          ? () => setEditingText(true) : onEdit}>
          <PencilSimple size={17} aria-hidden />编辑内容</button>
        <button type="button" onClick={onInspect}><SlidersHorizontal size={17} aria-hidden />卡片详情</button>
      </>}>
      {children}
      {artifact.kind === "TEXT" && hasCurrentVersion(artifact) && editingText
        ? <TextCanvasEditor artifact={artifact} locked={locked}
          onDone={() => setEditingText(false)} /> : <>
      <div className="content-card-bar">
        <Icon size={17} aria-hidden />
        <h3 title={header}>{header}</h3>
        {duration ? <span className="content-card-duration" aria-label={`镜头时长：${duration}`}>
          <Clock size={12} aria-hidden />{duration}</span> : null}
      </div>
      {emptyText ? <div className="content-card-empty">
        <TextT size={44} aria-hidden />
        <span>写下想法，让创作开始</span>
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
        {artifact.kind === "TEXT" && hasCurrentVersion(artifact)
          ? <TextVersionPicker artifact={artifact} />
          : <span className="content-card-chip"><Stack size={12} aria-hidden />
            {artifact.currentVersion ? `v${artifact.currentVersion.versionNo}` : "暂无版本"}</span>}
        {references > 0 ? <span className="content-card-chip"><LinkSimple size={12} aria-hidden />{references} 个引用</span> : null}
        {locked ? <LockSimple className="content-card-locked" size={13} aria-label="已锁定" /> : null}
      </footer>
      </>}
  </ArtifactCardFrame>;
}
