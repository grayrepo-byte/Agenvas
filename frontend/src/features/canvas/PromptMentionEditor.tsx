import { ImageSquare, MusicNotes } from "@phosphor-icons/react";
import { useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent } from "react";
import type { SaveMediaDraftRequest } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Command, CommandGroup, CommandItem, CommandList } from "../../shared/ui/primitives/command";
import { MENTION_MARKER } from "./mediaPrompt";

type DraftFields = Pick<SaveMediaDraftRequest, "mentions">;
export type PromptReference = SaveMediaDraftRequest["mediaInputs"][number] & {
  label: string; thumbnailUrl?: string;
};
const MENTION_MENU_MIN_TOP = 30;
const MENTION_MENU_OFFSET = 8;
const MENTION_ICON_SIZE = 28;
const PROMPT_DELIMITERS = new RegExp(`(${MENTION_MARKER}|\n)`, "u");

function readPromptEditor(root: HTMLElement) {
  let prompt = "";
  const mentions: DraftFields["mentions"] = [];
  function visit(node: Node) {
    if (node.nodeType === Node.TEXT_NODE) {
      prompt += (node.textContent ?? "").replaceAll("\u00A0", " ");
      return;
    }
    if (!(node instanceof HTMLElement)) return;
    const versionId = node.dataset.mentionVersion;
    const role = node.dataset.mentionRole as DraftFields["mentions"][number]["role"] | undefined;
    if (versionId && role) {
      prompt += MENTION_MARKER;
      mentions.push({ versionId, role });
      return;
    }
    if (node instanceof HTMLBRElement) {
      prompt += "\n";
      return;
    }
    const block = node.tagName === "DIV" || node.tagName === "P";
    if (block && prompt && !prompt.endsWith("\n")) prompt += "\n";
    node.childNodes.forEach(visit);
  }
  root.childNodes.forEach(visit);
  return { prompt, mentions };
}

function mentionToken(reference: PromptReference) {
  const token = document.createElement("span");
  token.className = "media-draft-inline-mention";
  token.contentEditable = "false";
  token.dataset.mentionVersion = reference.versionId;
  token.dataset.mentionRole = reference.role;
  token.style.setProperty("--reference-color", reference.color);
  if (reference.thumbnailUrl) {
    const image = document.createElement("img");
    image.src = reference.thumbnailUrl;
    image.alt = "";
    token.append(image);
  }
  const label = document.createElement("span");
  label.textContent = `@${reference.label}`;
  token.append(label);
  return token;
}

function renderPromptEditor(root: HTMLElement, prompt: string,
    mentions: DraftFields["mentions"], references: PromptReference[]) {
  root.replaceChildren();
  let mentionIndex = 0;
  for (const fragment of prompt.split(PROMPT_DELIMITERS)) {
    if (fragment === MENTION_MARKER) {
      const mention = mentions[mentionIndex++];
      const reference = mention && references.find((candidate) =>
        candidate.versionId === mention.versionId && candidate.role === mention.role);
      if (reference) root.append(mentionToken(reference));
    } else if (fragment === "\n") root.append(document.createElement("br"));
    else if (fragment) root.append(document.createTextNode(fragment));
  }
}

export function PromptMentionEditor({ id, label, placeholder, prompt, mentions, references, maxLength, readOnly = false, onChange }: {
  id: string; label: string; placeholder: string; prompt: string;
  mentions: DraftFields["mentions"]; references: PromptReference[]; maxLength: number;
  readOnly?: boolean;
  onChange: (prompt: string, mentions: DraftFields["mentions"]) => void;
}) {
  useLocale();
  const editorRef = useRef<HTMLDivElement>(null);
  const triggerRange = useRef<Range | null>(null);
  const presentationRef = useRef("");
  const [menu, setMenu] = useState<{ left: number; top: number; selected: number } | null>(null);

  useLayoutEffect(() => {
    const editor = editorRef.current;
    if (!editor) return;
    const current = readPromptEditor(editor);
    const presentation = references.map((reference) => [reference.versionId, reference.role,
      reference.label, reference.thumbnailUrl, reference.color].join(":")).join("|");
    if (current.prompt !== prompt || JSON.stringify(current.mentions) !== JSON.stringify(mentions)
        || presentationRef.current !== presentation) {
      renderPromptEditor(editor, prompt, mentions, references);
      presentationRef.current = presentation;
    }
  }, [prompt, mentions, references]);

  useLayoutEffect(() => {
    if (readOnly) {
      triggerRange.current = null;
      setMenu(null);
    }
  }, [readOnly]);

  function update(event: FormEvent<HTMLDivElement>) {
    if (readOnly) return;
    const editor = event.currentTarget;
    const value = readPromptEditor(editor);
    if (value.prompt.length > maxLength) {
      renderPromptEditor(editor, prompt, mentions, references);
      return;
    }
    onChange(value.prompt, value.mentions);
    const selection = window.getSelection();
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
    const container = range?.startContainer;
    const offset = range?.startOffset ?? 0;
    if (references.length && range?.collapsed && container?.nodeType === Node.TEXT_NODE
        && (container.textContent ?? "").slice(offset - 1, offset) === "@") {
      triggerRange.current = range.cloneRange();
      const bounds = editor.getBoundingClientRect();
      const caret = typeof range.getBoundingClientRect === "function"
        ? range.getBoundingClientRect() : bounds;
      setMenu({ left: Math.max(0, caret.left - bounds.left),
        top: Math.max(MENTION_MENU_MIN_TOP, caret.bottom - bounds.top + MENTION_MENU_OFFSET), selected: 0 });
    } else {
      triggerRange.current = null;
      setMenu(null);
    }
  }

  function insert(reference: PromptReference) {
    if (readOnly) return;
    const editor = editorRef.current;
    const range = triggerRange.current;
    if (!editor || !range) return;
    const container = range.startContainer;
    if (container.nodeType === Node.TEXT_NODE && range.startOffset > 0) {
      range.setStart(container, range.startOffset - 1);
      range.deleteContents();
    }
    const token = mentionToken(reference);
    range.insertNode(token);
    range.setStartAfter(token);
    range.collapse(true);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
    editor.focus();
    const value = readPromptEditor(editor);
    onChange(value.prompt, value.mentions);
    triggerRange.current = null;
    setMenu(null);
  }

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (readOnly) return;
    if (!menu) return;
    if (event.key === "Escape") {
      event.preventDefault();
      setMenu(null);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const delta = event.key === "ArrowDown" ? 1 : -1;
      setMenu({ ...menu, selected: (menu.selected + delta + references.length) % references.length });
      return;
    }
    if (event.key === "Enter") {
      event.preventDefault();
      const reference = references[menu.selected];
      if (reference) insert(reference);
    }
  }

  return <div className="media-draft-prompt-shell">
    <label className="media-draft-prompt-label" htmlFor={id}>{label}</label>
    <div ref={editorRef} id={id} className="media-draft-prompt ui-multiline" role="textbox"
      aria-label={label} aria-multiline="true" aria-readonly={readOnly} contentEditable={!readOnly} suppressContentEditableWarning
      data-placeholder={placeholder} onInput={update} onKeyDown={onKeyDown} />
    {menu && !readOnly ? <Command shouldFilter={false} value={references[menu.selected]?.versionId ?? ""} className="media-draft-mention-menu" aria-label={t("media.mentions.imageReferences")}
      style={{ left: menu.left, top: menu.top }}>
      <CommandList label={t("media.mentions.imageReferences")}><CommandGroup heading="Image">
      {references.map((reference, index) => <CommandItem key={reference.versionId} value={reference.versionId}
        role="option" aria-selected={index === menu.selected}
        aria-label={`${reference.label} ${reference.versionId}`}
        onMouseDown={(event) => event.preventDefault()} onSelect={() => insert(reference)}>
        {reference.thumbnailUrl ? <img src={reference.thumbnailUrl} alt="" /> : reference.role === "AUDIO_REFERENCE" ? <MusicNotes size={MENTION_ICON_SIZE} /> : <ImageSquare size={MENTION_ICON_SIZE} />}
        <span>{reference.label}</span>
      </CommandItem>)}
    </CommandGroup></CommandList></Command> : null}
  </div>;
}

