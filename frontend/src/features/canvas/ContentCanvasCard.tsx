import {
LinkSimple,LockSimple,PencilSimple,
SlidersHorizontal,Stack,TextT
} from "@phosphor-icons/react";
import type { MouseEvent,ReactNode } from "react";
import { useEffect,useRef,useState } from "react";
import type { Artifact,CanvasItem } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { SaveToLibraryButton } from "../library/SaveToLibraryButton";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { readContentText as readText } from "./artifactContent";
import "./ContentCanvasCard.css";
import { TextCanvasEditor,type TextEditingState } from "./TextCanvasEditor";
import { TextVersionPicker } from "./TextVersionPicker";
import { hasCurrentVersion } from "./versionedArtifact";

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
const TEXT_PRESENTATION = { get label() { return t("common.text"); }, icon: TextT };

/** Text edits in place; the persisted content is shown as written. */
export function ContentCanvasCard({ artifact, item, selected, toolbarVisible, locked, onInspect,
  children }: ContentCanvasCardProps) {
  useLocale();
  const [editingText, setEditingText] = useState(false);
  const editorRef = useRef<HTMLTextAreaElement>(null);
  const [editingState, setEditingState] = useState<TextEditingState | null>(null);
  useEffect(() => { if (editingText) editorRef.current?.focus(); }, [editingText]);
  const content = artifact.resourceDefaultVersion?.content;
  const { label, icon: Icon } = TEXT_PRESENTATION;
  const text = readText(content, "text");
  const header = readText(content, "format") === "MARKDOWN" ? "Markdown" : t("text.card.body");
  const references = artifact.resourceDefaultVersion?.inputReferences.length ?? 0;
  const emptyText = !text.trim();

  function beginEditing(event: MouseEvent<HTMLElement>) {
    event.stopPropagation();
    if (!hasCurrentVersion(artifact)) return;
    setEditingText(true);
    editorRef.current?.focus();
  }

  return <ArtifactCardFrame title={item.title} titleIcon={<Icon size={16} />} kindLabel={label} selected={selected} locked={locked}
    toolbarVisible={toolbarVisible}
    editableTitle={{ projectId: artifact.projectId, item }} className="content-canvas-card" toolbar={<>
        {!editingText ? <SaveToLibraryButton projectId={artifact.projectId} itemId={item.id} disabled={emptyText} /> : null}
        {hasCurrentVersion(artifact)
          ? <TextVersionPicker artifact={editingText && editingState ? editingState.artifact : artifact}
            disabled={editingText && Boolean(editingState?.dirty || editingState?.busy)} />
          : <span className="text-card-count"><Stack size={12} aria-hidden />
            {artifact.resourceDefaultVersion ? `v${artifact.resourceDefaultVersion.versionNo}` : t("text.card.noVersion")}</span>}
        <Button variant="ghost" type="button" disabled={!hasCurrentVersion(artifact)}
          aria-pressed={editingText} className={editingText ? "is-open" : undefined}
          onClick={beginEditing}>
          <PencilSimple size={17} aria-hidden />{t("text.card.edit")}</Button>
        <Button variant="ghost" type="button" onClick={onInspect}><SlidersHorizontal size={17} aria-hidden />{t("common.cardDetails")}</Button>
      </>}>
      {children}
      {hasCurrentVersion(artifact) && editingText
        ? <TextCanvasEditor artifact={artifact} canvasItemId={item.id} locked={locked}
          editorRef={editorRef} onEditingStateChange={setEditingState}
          onDone={() => { setEditingText(false); setEditingState(null); }} /> : <>
      <div className="content-card-bar">
        <Icon size={17} aria-hidden />
        <h3 title={header}>{header}</h3>
      </div>
      {emptyText ? <div className="content-card-empty nodrag nowheel nopan" onDoubleClick={beginEditing}>
        <TextT size={44} aria-hidden />
        <span>{t("text.card.emptyHint")}</span>
      </div> : <div className="content-card-body nodrag nowheel nopan" tabIndex={0}
        role="region" aria-label={t("text.card.bodyLabel", { "0": label })} onDoubleClick={beginEditing}>
        <p className="content-card-text">{text}</p>
      </div>}
      {references > 0 || locked ? <footer className="content-card-sources">
        {references > 0 ? <span className="content-card-chip"><LinkSimple size={12} aria-hidden />{t("text.card.referenceCount", { "0": references })}</span> : null}
        {locked ? <LockSimple className="content-card-locked" size={13} aria-label={t("canvas.card.locked")} /> : null}
      </footer> : null}
      </>}
  </ArtifactCardFrame>;
}
