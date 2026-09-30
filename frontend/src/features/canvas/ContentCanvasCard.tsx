import { LinkSimple, LockSimple, PencilSimple,
  SlidersHorizontal, Stack, TextT } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { useState } from "react";
import type { Artifact, CanvasItem } from "../../shared/api/client";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { readContentText as readText } from "./artifactContent";
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
  toolbarVisible?: boolean;
  locked: boolean;
  onInspect: () => void;
  children: ReactNode;
};

/** 正文卡片只承载文字产物；图片与视频由 MediaCanvasCard 渲染。 */
const TEXT_PRESENTATION = { label: "文字", icon: TextT };

/** Text edits in place; the persisted content is shown as written. */
export function ContentCanvasCard({ artifact, item, selected, toolbarVisible, locked, onInspect,
  children }: ContentCanvasCardProps) {
  const [editingText, setEditingText] = useState(false);
  const content = artifact.resourceDefaultVersion?.content;
  const { label, icon: Icon } = TEXT_PRESENTATION;
  const text = readText(content, "text");
  const header = readText(content, "format") === "MARKDOWN" ? "Markdown" : "正文";
  const references = artifact.resourceDefaultVersion?.inputReferences.length ?? 0;
  const emptyText = !text.trim();

  return <ArtifactCardFrame title={item.title} kindLabel={label} selected={selected} locked={locked}
    toolbarVisible={toolbarVisible}
    editableTitle={{ projectId: artifact.projectId, item }} className="content-canvas-card" toolbar={<>
        <button type="button" disabled={!hasCurrentVersion(artifact)}
          onClick={() => setEditingText(true)}>
          <PencilSimple size={17} aria-hidden />编辑内容</button>
        <button type="button" onClick={onInspect}><SlidersHorizontal size={17} aria-hidden />卡片详情</button>
      </>}>
      {children}
      {hasCurrentVersion(artifact) && editingText
        ? <TextCanvasEditor artifact={artifact} locked={locked}
          onDone={() => setEditingText(false)} /> : <>
      <div className="content-card-bar">
        <Icon size={17} aria-hidden />
        <h3 title={header}>{header}</h3>
      </div>
      {emptyText ? <div className="content-card-empty">
        <TextT size={44} aria-hidden />
        <span>写下想法，让创作开始</span>
      </div> : <div className="content-card-body nodrag nowheel nopan" tabIndex={0}
        role="region" aria-label={`${label}正文`}>
        <p className="content-card-text">{text}</p>
      </div>}
      <footer className={`content-card-sources${hasCurrentVersion(artifact)
        ? " text-card-version-footer" : ""}`}>
        <span className="content-card-chip"><Icon size={12} aria-hidden />{label}</span>
        {hasCurrentVersion(artifact)
          ? <TextVersionPicker artifact={artifact} />
          : <span className="content-card-chip"><Stack size={12} aria-hidden />
            {artifact.resourceDefaultVersion ? `v${artifact.resourceDefaultVersion.versionNo}` : "暂无版本"}</span>}
        {references > 0 ? <span className="content-card-chip"><LinkSimple size={12} aria-hidden />{references} 个引用</span> : null}
        {locked ? <LockSimple className="content-card-locked" size={13} aria-label="已锁定" /> : null}
      </footer>
      </>}
  </ArtifactCardFrame>;
}
