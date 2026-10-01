import { RunningHubForm, runningHubErrors, runningHubUsedVersions, type RunningHubValue } from "./RunningHubForm";
import { AudioPromptTools } from "./AudioPromptTools";
import { VOICES } from "./voiceCatalog";
import { VoiceLibrary } from "./VoiceLibrary";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { adapterModel } from "../settings/mediaAdapterCatalog";
import { DropdownMenu } from "../../shared/ui/DropdownMenu";
import { ArrowUp, BoundingBox, CaretDown, Check, Coins, Cube, ImagesSquare, ImageSquare,
  PaintBrush, Plus, SlidersHorizontal, UploadSimple, VideoCamera, MusicNotes, X } from "@phosphor-icons/react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import type { CSSProperties, FormEvent, KeyboardEvent as ReactKeyboardEvent } from "react";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { taskErrorDetail } from "./taskErrorMessages";
import { latestMediaTask, occupiesMediaCard, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { readContentText } from "./artifactContent";
import { ApiError, assetContentUrl, cancelQueuedDirectMediaTask, createArtifact, getDirectMediaQueueStatus,
  getMediaDraft, getMediaSettings,
  listArtifactVersions, listArtifacts, listCanvasItems, listDirectMediaTasks, runMediaDraft, saveMediaDraft,
  removeMediaDraftMediaInput,
  uploadImageAsset, uploadAudioAsset, uploadVideoAsset, type RunningHubField, type Artifact, type MediaCapability, type MediaDraft,
  type ImageGenerationParameters, type SaveMediaDraftRequest } from "../../shared/api/client";
import "./MediaDraftEditor.css";
import { useCanvasStore } from "./canvasStore";
import { saveClosedMediaDraft, type PendingMediaDraftSave } from "./mediaDraftCloseSave";

const AUTOSAVE_DELAY_MS = 650;
const REFERENCE_SOURCE_CLOSE_DELAY_MS = 120;
const MAX_PROMPT_LENGTH = 20000;
const MAX_AUDIO_PROMPT_LENGTH = 3000;
const MIN_VIDEO_SECONDS = 1;
const MAX_VIDEO_SECONDS = 30;
const CONFLICT_STATUS = 409;
const MAX_ARTIFACT_TITLE_LENGTH = 160;
const INPUT_COLORS = [
  "#F15CAF", "#67C7F3", "#F1B95C", "#8DD17E", "#A98AF7", "#F27979", "#56C8B5",
  "#D98BD9", "#E56B3F", "#4DB6E5", "#B8D84A", "#8C7AE6", "#E7A93D", "#4FC38D",
] as const;
const QUEUE_LABELS = {
  WAITING_WORKER: "等待执行器", NOT_QUEUED: "未排队",
} as const;
const QUALITY_LABELS = { low: "低", medium: "中", high: "高" } as const;
const ASPECT_RATIO_OPTIONS = ["1:1", "2:3", "3:2", "9:16", "16:9", "3:4", "4:3", "21:9", "AUTO"] as const;
const VIDEO_ASPECT_RATIO_OPTIONS = ["AUTO", "16:9", "9:16", "1:1"] as const;
const RESOLUTION_OPTIONS = ["1K", "2K", "4K"] as const;
const QUALITY_OPTIONS = ["low", "medium", "high"] as const;
const GENERATION_COUNT_OPTIONS = [1, 2, 4] as const;
const ASPECT_RATIO_LABELS: Readonly<Record<(typeof ASPECT_RATIO_OPTIONS)[number], string>> = {
  "1:1": "1:1", "2:3": "2:3", "3:2": "3:2", "9:16": "9:16", "16:9": "16:9",
  "3:4": "3:4", "4:3": "4:3", "21:9": "21:9", AUTO: "自动",
};
const VIDEO_MODE_OPTIONS = [
  { value: "TEXT", label: "文生视频", description: "只使用文字描述生成", needsImage: false },
  { value: "GENERAL_REFERENCE", label: "全能参考", description: "按顺序参考图片和音频", needsImage: true },
  { value: "START_END", label: "首尾帧", description: "固定首帧，可选尾帧", needsImage: true },
] as const;
// Each object-replacement character occupies one position in the prompt and maps to the
// structurally equivalent entry in mentions. Human-readable labels are a view of that pair.
const MENTION_MARKER = "\uFFFC";
type DraftFields = Omit<SaveMediaDraftRequest, "expectedVersion">;
type Popover = "models" | "modes" | "parameters" | "assetReferences" | "canvasReferences" | "voices";
type RunIntent = { key: string; expectedDraftVersion: number };
type UploadProgress = { assetId?: string; createKey: string };
type AssetReferenceCommit = {
  fieldsAtStart: DraftFields;
  nextFields: DraftFields;
  request: SaveMediaDraftRequest;
};
type PromptReference = DraftFields["mediaInputs"][number] & {
  label: string; thumbnailUrl?: string;
};
type ImageParameters = Required<Omit<ImageGenerationParameters, "speaker" | "speechRate" | "loudnessRate" | "pitchRate" | "dynamicValues">>;
type VideoInputMode = NonNullable<MediaDraft["videoInputMode"]>;
type VideoParameters = { aspectRatio: (typeof VIDEO_ASPECT_RATIO_OPTIONS)[number] };

function normalizedImageParameters(raw: ImageGenerationParameters | undefined,
  capability?: MediaCapability): ImageParameters {
  raw = { ...capability?.settings.defaultParameters, ...raw };
  const supportedRatios = capability?.supportedImageAspectRatios ?? ["AUTO"];
  const supportedResolutions = capability?.supportedImageResolutions ?? ["1K"];
  const supportedQualities = capability?.supportedImageQualities ?? [];
  const configuredQuality = capability?.settings.quality;
  const aspectRatio = raw?.aspectRatio && supportedRatios.includes(raw.aspectRatio)
    ? raw.aspectRatio : supportedRatios.includes("AUTO") ? "AUTO" : supportedRatios[0] ?? "AUTO";
  const resolution = raw?.resolution && supportedResolutions.includes(raw.resolution)
    ? raw.resolution : supportedResolutions[0] ?? "1K";
  const quality = raw?.quality && (supportedQualities.length === 0 || supportedQualities.includes(raw.quality))
    ? raw.quality : configuredQuality ?? supportedQualities[0] ?? "medium";
  return {
    aspectRatio, resolution, quality,
    transparentBackground: capability?.supportsTransparentBackground
      ? raw?.transparentBackground ?? false : false,
    generationCount: raw?.generationCount === 2 || raw?.generationCount === 4
      ? raw.generationCount : 1,
  };
}

function normalizedVideoParameters(raw: ImageGenerationParameters | undefined, capability?: MediaCapability): VideoParameters {
  raw = { ...capability?.settings.defaultParameters, ...raw };
  const aspectRatio = VIDEO_ASPECT_RATIO_OPTIONS.find((candidate) => candidate === raw?.aspectRatio);
  return { aspectRatio: aspectRatio ?? "AUTO" };
}

function preferredImageVideoMode(capability?: MediaCapability): VideoInputMode {
  if (capability?.supportedVideoInputModes.includes("GENERAL_REFERENCE")) return "GENERAL_REFERENCE";
  if (capability?.supportedVideoInputModes.includes("START_END")) return "START_END";
  return "GENERAL_REFERENCE";
}

function inputsForVideoMode(inputs: DraftFields["mediaInputs"], mode: VideoInputMode) {
  if (mode === "GENERAL_REFERENCE") {
    return inputs.map((input) => ({ ...input, role: input.role === "AUDIO_REFERENCE" ? "AUDIO_REFERENCE" as const : "REFERENCE" as const }));
  }
  if (mode === "START_END") {
    return inputs.filter((input) => input.role !== "AUDIO_REFERENCE").slice(0, 2).map((input, index) => ({ ...input,
      role: index === 0 ? "START_FRAME" as const : "END_FRAME" as const }));
  }
  return [];
}

function modelName(capability: MediaCapability) {
  return capability.settings.model || adapterModel(capability.adapterId) || capability.settings.checkpoint
    || capability.settings.diffusionModel || capability.adapterId;
}

function imageAssetId(content: unknown) {
  const value = readContentText(content, "assetId");
  return value.trim() ? value : null;
}

function uploadArtifactTitle(file: File) {
  const withoutExtension = file.name.replace(/\.[^.]+$/, "").trim();
  return (withoutExtension || "上传图片").slice(0, MAX_ARTIFACT_TITLE_LENGTH);
}

function fieldsFromDraft(draft: MediaDraft): DraftFields {
  return {
    prompt: draft.prompt, parameters: draft.parameters ?? {},
    durationSeconds: draft.durationSeconds, capabilityId: draft.capabilityId,
    videoInputMode: draft.videoInputMode,
    mediaInputs: (draft.mediaInputs ?? []).map(({ versionId, role, color }) => ({
      versionId, role, color,
    })),
    mentions: draft.mentions ?? [],
  };
}

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
  let text = "";
  const flush = () => {
    if (!text) return;
    root.append(document.createTextNode(text));
    text = "";
  };
  for (const character of prompt) {
    if (character === MENTION_MARKER) {
      flush();
      const mention = mentions[mentionIndex++];
      const reference = mention && references.find((candidate) =>
        candidate.versionId === mention.versionId && candidate.role === mention.role);
      if (reference) root.append(mentionToken(reference));
      continue;
    }
    if (character === "\n") {
      flush();
      root.append(document.createElement("br"));
    } else text += character;
  }
  flush();
}

function removePromptReferences(prompt: string, mentions: DraftFields["mentions"],
    versionId: string) {
  let mentionIndex = 0;
  let nextPrompt = "";
  const nextMentions: DraftFields["mentions"] = [];
  for (const character of prompt) {
    if (character !== MENTION_MARKER) {
      nextPrompt += character;
      continue;
    }
    const mention = mentions[mentionIndex++];
    if (mention && mention.versionId !== versionId) {
      nextPrompt += MENTION_MARKER;
      nextMentions.push(mention);
    }
  }
  return { prompt: nextPrompt, mentions: nextMentions };
}

function promptForMediaInputs(fields: DraftFields, inputs: DraftFields["mediaInputs"]) {
  let result = { prompt: fields.prompt, mentions: fields.mentions };
  for (const input of fields.mediaInputs) {
    if (!inputs.some((next) => next.versionId === input.versionId))
      result = removePromptReferences(result.prompt, result.mentions, input.versionId);
  }
  return { ...result, mentions: result.mentions.map((mention) => ({ ...mention,
    role: inputs.find((input) => input.versionId === mention.versionId)?.role ?? mention.role })) };
}

function PromptMentionEditor({ id, label, placeholder, prompt, mentions, references, onChange }: {
  id: string; label: string; placeholder: string; prompt: string;
  mentions: DraftFields["mentions"]; references: PromptReference[];
  onChange: (prompt: string, mentions: DraftFields["mentions"]) => void;
}) {
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

  function update(event: FormEvent<HTMLDivElement>) {
    const editor = event.currentTarget;
    const value = readPromptEditor(editor);
    if (value.prompt.length > MAX_PROMPT_LENGTH) {
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
        top: Math.max(30, caret.bottom - bounds.top + 8), selected: 0 });
    } else {
      triggerRange.current = null;
      setMenu(null);
    }
  }

  function insert(reference: PromptReference) {
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
    <div ref={editorRef} id={id} className="media-draft-prompt" role="textbox"
      aria-label={label} aria-multiline="true" contentEditable suppressContentEditableWarning
      data-placeholder={placeholder} onInput={update} onKeyDown={onKeyDown} />
    {menu ? <DropdownMenu className="media-draft-mention-menu" role="listbox" aria-label="图片引用"
      style={{ left: menu.left, top: menu.top }}>
      <span className="media-draft-mention-menu-title">Image</span>
      {references.map((reference, index) => <button key={reference.versionId} type="button"
        role="option" aria-selected={index === menu.selected}
        aria-label={`${reference.label} ${reference.versionId}`}
        onMouseDown={(event) => event.preventDefault()} onClick={() => insert(reference)}>
        {reference.thumbnailUrl ? <img src={reference.thumbnailUrl} alt="" /> : reference.role === "AUDIO_REFERENCE" ? <MusicNotes size={28} /> : <ImageSquare size={28} />}
        <span>{reference.label}</span>
      </button>)}
    </DropdownMenu> : null}
  </div>;
}

function MediaReferenceThumbnail({ index, color, thumbnailUrl, accessibleLabel, connected, audio = false,
    busy, reorderable, onMove, onDragStart, onDragEnd, onDrop, onRemove }: {
  index: number; color: string; thumbnailUrl?: string; accessibleLabel: string; audio?: boolean;
  connected: boolean; busy: boolean; reorderable: boolean;
  onMove: (delta: -1 | 1) => void; onDragStart: () => void;
  onDragEnd: () => void; onDrop: () => void; onRemove: () => void;
}) {
  return <div className="media-draft-reference-chip"
    style={{ "--reference-color": color } as CSSProperties}
    role="listitem"
    aria-label={`${accessibleLabel}，序号 ${index + 1}`}
    data-reorderable={reorderable ? "true" : undefined}
    {...(reorderable ? { tabIndex: 0, "aria-keyshortcuts": "ArrowLeft ArrowRight" } : {})}
    onPointerDown={(event) => {
      if (!reorderable || event.button !== 0
          || event.target instanceof Element && event.target.closest("button")) return;
      onDragStart();
    }}
    onPointerUp={(event) => {
      if (!reorderable || event.button !== 0
          || event.target instanceof Element && event.target.closest("button")) return;
      onDrop();
      onDragEnd();
    }}
    onPointerCancel={onDragEnd}
    onKeyDown={(event) => {
      if (!reorderable || event.target !== event.currentTarget) return;
      if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        onMove(event.key === "ArrowLeft" ? -1 : 1);
      }
    }}>
    {thumbnailUrl ? <img src={thumbnailUrl} alt="" draggable={false} />
      : audio ? <MusicNotes className="media-draft-reference-fallback" size={25} aria-hidden="true" /> : <ImageSquare className="media-draft-reference-fallback" size={25} aria-hidden="true" />}
    <span className="media-draft-reference-index" aria-hidden="true">{index + 1}</span>
    <button className="media-draft-reference-remove" type="button"
      aria-label={`取消引入 ${accessibleLabel}`} disabled={busy}
      title={connected ? "取消引入并断开画布连线" : "取消引入"}
      onClick={onRemove}><X size={13} /></button>
  </div>;
}

/**
 * Attachment chips, raised pickers and compact task rows adapt Beautiful UI's PromptBar / TaskRows.
 * https://github.com/slev12397/beautiful-ui (MIT, Shane Levine; see beautiful-ui-LICENSE.txt).
 * All states come from persisted drafts/tasks; the source's scripted demo sequences are not used.
 */
export function MediaDraftEditor({ artifact, canvasItemId, onOpenAgentConversation, openingAgentConversation = false }: {
  artifact: Artifact; canvasItemId: string; onOpenAgentConversation?: () => void; openingAgentConversation?: boolean;
}) {
  const isAudio = artifact.kind === "AUDIO";
  const queryClient = useQueryClient();
  const key = ["media-draft", artifact.projectId, canvasItemId] as const;
  const draft = useQuery({ queryKey: key,
    queryFn: () => getMediaDraft(artifact.projectId, canvasItemId) });
  const resources = useQuery({
    queryKey: ["artifacts", artifact.projectId],
    queryFn: () => listArtifacts(artifact.projectId),
  });
  const imageResources = (resources.data?.items ?? []).filter((candidate) =>
    ["IMAGE", "AUDIO", "VIDEO"].includes(candidate.kind));
  const imageHistories = useQueries({ queries: imageResources.map((candidate) => ({
      queryKey: ["artifact-versions", artifact.projectId, candidate.id],
      queryFn: () => listArtifactVersions(artifact.projectId, candidate.id),
    })) });
  const settings = useQuery({ queryKey: ["media-settings"], queryFn: getMediaSettings });
  const tasksKey = ["direct-media-tasks", artifact.projectId, canvasItemId] as const;
  const directTasks = useQuery({ queryKey: tasksKey,
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id, canvasItemId),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS });
  const latestTask = latestMediaTask(directTasks.data);
  const queue = useQuery({
    queryKey: ["direct-media-queue", artifact.projectId, latestTask?.id],
    queryFn: () => getDirectMediaQueueStatus(artifact.projectId, latestTask!.id),
    enabled: latestTask?.status === "READY", refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS,
  });
  const [fields, setFields] = useState<DraftFields | null>(null);
  const fieldsRef = useRef<DraftFields | null>(null);
  const ratioDraftKey = `${artifact.projectId}:${canvasItemId}`;
  const recovery = useCanvasStore((state) => state.mediaDraftRecoveries[ratioDraftKey]);
  const pendingSaveRef = useRef<PendingMediaDraftSave | undefined>(undefined);
  const localAspectRatio = fields?.parameters.aspectRatio;
  useEffect(() => {
    if (artifact.kind === "IMAGE" && localAspectRatio !== undefined) {
      useCanvasStore.getState().setImageRatioDraft(ratioDraftKey, localAspectRatio);
    } else if (!useCanvasStore.getState().mediaDraftRecoveries[ratioDraftKey]) {
      useCanvasStore.getState().clearImageRatioDraft(ratioDraftKey);
    }
  }, [artifact.kind, localAspectRatio, ratioDraftKey]);
  const [expectedVersion, setExpectedVersion] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [failedRemovalVersionId, setFailedRemovalVersionId] = useState<string | null>(null);
  const draggedReferenceIndex = useRef<number | null>(null);
  const runIntent = useRef<RunIntent | null>(null);
  const [popover, setPopover] = useState<Popover | null>(null);
  const [referenceSourcesOpen, setReferenceSourcesOpen] = useState(false);
  const referenceSourcesCloseTimer = useRef<number | null>(null);
  const suppressReferenceSourceFocusOpen = useRef(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const uploadInputRef = useRef<HTMLInputElement>(null);
  const uploadedKinds = useRef(new Map<string, "IMAGE" | "AUDIO">());
  const uploadProgress = useRef(new Map<File, UploadProgress>());
  const [uploading, setUploading] = useState(false);
  const [failedUploads, setFailedUploads] = useState<File[]>([]);
  const [uploadError, setUploadError] = useState<Error | null>(null);
  const [assetSearch, setAssetSearch] = useState("");
  const [assetSelection, setAssetSelection] = useState<string[]>([]);
  const [assetSelectionError, setAssetSelectionError] = useState<Error | null>(null);
  const id = useId();
  const canvas = useQuery({
    queryKey: ["canvas", artifact.projectId],
    queryFn: () => listCanvasItems(artifact.projectId),
    enabled: popover === "canvasReferences",
  });
  const mediaKind = artifact.kind === "IMAGE" ? "IMAGE_GENERATION" : isAudio ? "AUDIO_GENERATION" : "VIDEO_GENERATION";
  const availableCapabilities = (settings.data?.connections ?? [])
    .filter((connection) => connection.enabled && connection.platform !== "LOCAL")
    .flatMap((connection) => connection.capabilities.filter((capability) =>
      capability.enabled && capability.kind === mediaKind).map((capability) => ({
        ...capability, connectionName: connection.name,
        mock: connection.platform === "MOCK",
      })));
  const defaultCapabilityId = settings.data?.defaults.find((item) => item.kind === mediaKind)?.capabilityId;
  const chosenCapability = availableCapabilities.find((item) =>
    item.id === (fields?.capabilityId ?? defaultCapabilityId));

  const runningHub = chosenCapability?.settings.runningHub;

  useEffect(() => () => {
    if (referenceSourcesCloseTimer.current !== null) {
      window.clearTimeout(referenceSourcesCloseTimer.current);
    }
  }, []);

  useEffect(() => {
    if (popover) setReferenceSourcesOpen(false);
  }, [popover]);

  useEffect(() => {
    if (!referenceSourcesOpen || popover) return;
    function closeAndRestoreFocus() {
      setReferenceSourcesOpen(false);
      suppressReferenceSourceFocusOpen.current = true;
      triggerRef.current?.focus();
      suppressReferenceSourceFocusOpen.current = false;
    }
    function onPointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !popoverRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setReferenceSourcesOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      closeAndRestoreFocus();
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown, true);
    };
  }, [popover, referenceSourcesOpen]);

  useEffect(() => {
    if (!popover) return;
    const firstControl = popoverRef.current?.querySelector<HTMLElement>(popover === "voices"
      ? "input[type=search]" : "[aria-checked='true'], button, select, input");
    (firstControl ?? popoverRef.current)?.focus();
    function onPointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !popoverRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setPopover(null);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setPopover(null);
        suppressReferenceSourceFocusOpen.current = true;
        triggerRef.current?.focus();
        suppressReferenceSourceFocusOpen.current = false;
      }
      if (popover !== "models" || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      const choices = Array.from(popoverRef.current?.querySelectorAll<HTMLButtonElement>("[role='menuitemradio']") ?? []);
      if (!choices.length) return;
      event.preventDefault();
      const current = choices.findIndex((choice) => choice === document.activeElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? choices.length - 1
        : (current + (event.key === "ArrowDown" ? 1 : -1) + choices.length) % choices.length;
      choices[next]?.focus();
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown, true);
    };
  }, [popover]);

  const save = useMutation({
    mutationFn: (input: SaveMediaDraftRequest) => {
      const result = saveMediaDraft(artifact.projectId, canvasItemId, input);
      pendingSaveRef.current = { request: input, result };
      return result;
    },
    onSuccess: (saved, input) => {
      pendingSaveRef.current = undefined;
      setExpectedVersion(saved.version);
      queryClient.setQueryData(key, saved);
      const latest = fieldsRef.current;
      const submitted = Object.fromEntries(Object.entries(input)
        .filter(([name]) => name !== "expectedVersion")) as DraftFields;
      if (latest && JSON.stringify(latest) === JSON.stringify(submitted)) setDirty(false);
      setError(null);
    },
    onError: (failure) => { pendingSaveRef.current = undefined; setError(failure); },
  });
  const commitAssetReferences = useMutation({
    mutationFn: ({ request }: AssetReferenceCommit) =>
      saveMediaDraft(artifact.projectId, canvasItemId, request),
    onMutate: () => setAssetSelectionError(null),
    onSuccess: (saved, input) => {
      const latest = fieldsRef.current;
      const changedWhileSaving = latest !== null && latest !== input.fieldsAtStart;
      const next = changedWhileSaving
        ? { ...latest, mediaInputs: input.nextFields.mediaInputs }
        : input.nextFields;
      fieldsRef.current = next;
      setFields(next);
      setExpectedVersion(saved.version);
      setDirty(changedWhileSaving);
      setError(null);
      setAssetSelection([]);
      setAssetSelectionError(null);
      setPopover(null);
      queryClient.setQueryData(key, saved);
      suppressReferenceSourceFocusOpen.current = true;
      triggerRef.current?.focus();
      suppressReferenceSourceFocusOpen.current = false;
    },
    onError: async (failure) => {
      setAssetSelection([]);
      setAssetSelectionError(failure instanceof Error ? failure : new Error("资源选择保存失败"));
      await Promise.all([
        resources.refetch(),
        ...imageHistories.map((history) => history.refetch()),
      ]);
    },
  });
  const removeConnectedInput = useMutation({
    mutationFn: async ({ versionId, version = expectedVersion, allowDirty = false }: {
      versionId: string; version?: number | null; allowDirty?: boolean;
      preserveLocalChanges?: boolean;
    }) => {
      if (!draft.data || version === null || (dirty || save.isPending) && !allowDirty) {
        throw new Error("请等待当前草稿保存完成后再取消图片输入。");
      }
      return removeMediaDraftMediaInput(artifact.projectId, canvasItemId, versionId, {
        expectedVersion: version,
      });
    },
    onMutate: ({ versionId, preserveLocalChanges = false }) => {
      setFailedRemovalVersionId(null);
      setError(null);
      return { versionId, fieldsAtStart: fieldsRef.current, preserveLocalChanges };
    },
    onSuccess: async (saved, _versionId, context) => {
      const latest = fieldsRef.current;
      let changedWhileRemoving = false;
      let next = fieldsFromDraft(saved);
      if (latest !== null && (context?.preserveLocalChanges === true
        || latest !== context?.fieldsAtStart)) {
        changedWhileRemoving = true;
        next = { ...latest,
          mediaInputs: latest.mediaInputs.filter((input) => input.versionId !== context.versionId),
          ...removePromptReferences(latest.prompt, latest.mentions, context.versionId) };
      }
      fieldsRef.current = next;
      setFields(next);
      setExpectedVersion(saved.version);
      setDirty(changedWhileRemoving);
      setFailedRemovalVersionId(null);
      queryClient.setQueryData(key, saved);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
      ]);
    },
    onError: (failure, versionId) => {
      setFailedRemovalVersionId(versionId.versionId);
      setError(failure);
    },
  });
  const run = useMutation({
    mutationFn: () => {
      if (expectedVersion === null) throw new Error("请等待草稿读取完成");
      // A lost response may still have advanced the server draft. Retrying that submission
      // must replay its exact payload, even when SSE/refetch has supplied a newer CAS version.
      runIntent.current ??= { key: crypto.randomUUID(), expectedDraftVersion: expectedVersion };
      return runMediaDraft(artifact.projectId, artifact.id,
        { canvasItemId, expectedDraftVersion: runIntent.current.expectedDraftVersion },
        runIntent.current.key);
    },
    onSuccess: async () => {
      runIntent.current = null;
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: tasksKey }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: key }),
      ]);
    },
  });
  const cancel = useMutation({
    mutationFn: (taskId: string) => cancelQueuedDirectMediaTask(artifact.projectId, taskId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tasksKey }),
  });

  const closeState = useRef<{ fields: DraftFields | null; dirty: boolean;
    expectedVersion: number | null; error: Error | null }>({ fields: null,
    dirty: false, expectedVersion: null, error: null });
  useLayoutEffect(() => {
    closeState.current = { fields, dirty, expectedVersion, error };
  }, [fields, dirty, expectedVersion, error]);
  useEffect(() => () => {
    const state = closeState.current;
    if (state.dirty && state.fields && state.expectedVersion !== null) {
      const request = { ...state.fields, expectedVersion: state.expectedVersion };
      if (state.error) {
        useCanvasStore.getState().setMediaDraftRecovery(ratioDraftKey,
          { request, saving: false, error: state.error });
      } else {
        void saveClosedMediaDraft(queryClient, artifact.projectId, canvasItemId,
          request, pendingSaveRef.current);
      }
    } else if (!useCanvasStore.getState().mediaDraftRecoveries[ratioDraftKey]) {
      useCanvasStore.getState().clearImageRatioDraft(ratioDraftKey);
    }
  }, [queryClient, artifact.projectId, canvasItemId, ratioDraftKey]);

  useEffect(() => {
    if (fields || !recovery || recovery.saving) return;
    const { expectedVersion: recoveredVersion, ...recoveredFields } = recovery.request;
    fieldsRef.current = recoveredFields;
    setFields(recoveredFields);
    setExpectedVersion(recoveredVersion);
    setDirty(true);
    setError(recovery.error);
    useCanvasStore.getState().clearMediaDraftRecovery(ratioDraftKey);
  }, [fields, recovery, ratioDraftKey]);

  useEffect(() => {
    // Run submission and result selection may advance the draft CAS version. Refresh only clean
    // fields; an in-flight save, local edit or conflict must keep its current input intact.
    if (recovery || !draft.data || dirty || save.isPending || commitAssetReferences.isPending
        || run.isPending || error) return;
    // An earlier GET can finish after a successful save wrote its newer result to the cache.
    // The last acknowledged CAS version is monotonic even if query responses arrive out of order.
    if (expectedVersion !== null && draft.data.version < expectedVersion) return;
    const initial = fieldsFromDraft(draft.data);
    fieldsRef.current = initial;
    setFields(initial);
    setExpectedVersion(draft.data.version);
  }, [draft.data, dirty, save.isPending, commitAssetReferences.isPending,
    run.isPending, error, expectedVersion, recovery]);

  useEffect(() => {
    if (!dirty || !fields || expectedVersion === null || save.isPending
        || commitAssetReferences.isPending
        || removeConnectedInput.isPending || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, commitAssetReferences.isPending,
    removeConnectedInput.isPending, error]);

  useEffect(() => {
    if (runningHub || artifact.kind !== "VIDEO" || !fields || !chosenCapability || dirty || save.isPending
        || commitAssetReferences.isPending || removeConnectedInput.isPending) return;
    const hasImages = fields.mediaInputs.length > 0;
    const desiredMode = hasImages
      ? fields.videoInputMode === null || fields.videoInputMode === "TEXT"
        ? preferredImageVideoMode(chosenCapability) : fields.videoInputMode
      : "TEXT";
    const parameters = normalizedVideoParameters(fields.parameters, chosenCapability);
    if (fields.videoInputMode !== desiredMode
        || JSON.stringify(fields.parameters) !== JSON.stringify(parameters)) {
      edit({ videoInputMode: desiredMode,
        mediaInputs: inputsForVideoMode(fields.mediaInputs, desiredMode), parameters });
    }
  }, [artifact.kind, chosenCapability, runningHub, commitAssetReferences.isPending, dirty, fields,
    removeConnectedInput.isPending, save.isPending]);

  function edit(changes: Partial<DraftFields>) {
    runIntent.current = null;
    if (!run.isPending) run.reset();
    setFields((current) => {
      if (!current) return current;
      const next = { ...current, ...(runningHub && chosenCapability ? { capabilityId: chosenCapability.id } : {}), ...changes };
      fieldsRef.current = next;
      return next;
    });
    setDirty(true);
    setFailedRemovalVersionId(null);
    if (!(error instanceof ApiError && error.status === CONFLICT_STATUS)) setError(null);
  }

  async function retry() {
    if (!fields || expectedVersion === null) return;
    if (error instanceof ApiError && error.status === CONFLICT_STATUS) {
      try {
        const fresh = await getMediaDraft(artifact.projectId, canvasItemId);
        queryClient.setQueryData(key, fresh);
        setExpectedVersion(fresh.version);
        setFailedRemovalVersionId(null);
        setError(null);
      } catch (failure) {
        setError(failure instanceof Error ? failure : new Error("无法重新读取草稿"));
      }
      return;
    }
    setError(null);
    if (failedRemovalVersionId) {
      if (dirty) {
        try {
          const fieldsBeingSaved = fields;
          const saved = await save.mutateAsync({ ...fieldsBeingSaved, expectedVersion });
          removeConnectedInput.mutate({ versionId: failedRemovalVersionId,
            version: saved.version, allowDirty: true,
            preserveLocalChanges: fieldsRef.current !== fieldsBeingSaved });
        } catch {
          // The save mutation exposes its own actionable error and retains local fields.
        }
        return;
      }
      removeConnectedInput.mutate({ versionId: failedRemovalVersionId });
      return;
    }
    save.mutate({ ...fields, expectedVersion });
  }

  function togglePopover(next: Popover, trigger: HTMLButtonElement) {
    triggerRef.current = trigger;
    setPopover((current) => current === next ? null : next);
  }

  function chooseCapability(capabilityId: string | null) {
    if (!fields) return;
    const nextCapabilityId = capabilityId ?? defaultCapabilityId;
    const nextCapability = availableCapabilities.find((candidate) => candidate.id === nextCapabilityId);
    const nextDefinition = nextCapability?.settings.runningHub;
    if (nextDefinition || runningHub) {
      const oldValues = fields.parameters.dynamicValues ?? {};
      const compatible: Record<string, RunningHubValue> = {};
      if (nextDefinition && runningHub) for (const candidate of nextDefinition.fields) {
        const before = runningHub.fields.find((item) => item.key === candidate.key);
        const value = oldValues[candidate.key];
        if (value !== undefined && before?.type === candidate.type && before.source === candidate.source
          && before.nodeId === candidate.nodeId && before.fieldName === candidate.fieldName
          && (typeof value !== "number" || (candidate.minimum == null || value >= candidate.minimum) && (candidate.maximum == null || value <= candidate.maximum))
          && (candidate.type !== "SELECT" || candidate.options?.some((option) => option.value === value))) compatible[candidate.key] = value;
      }
      const used = new Set(Object.values(compatible).filter((value) => typeof value === "string"));
      const retained = nextDefinition ? fields.mediaInputs.filter((input) => used.has(input.versionId)) : [];
      const removed = Object.keys(oldValues).filter((key) => !(key in compatible));
      if ((removed.length || retained.length !== fields.mediaInputs.length || !runningHub && Object.keys(fields.parameters).length)
        && !window.confirm(`切换能力将移除不兼容参数${removed.length ? `（${removed.join("、")}）` : ""}与未匹配素材引用。是否继续？`)) return;
      edit({ capabilityId: nextCapabilityId ?? null, parameters: nextDefinition ? { dynamicValues: compatible } : {},
        mediaInputs: retained, ...promptForMediaInputs(fields, retained),
        durationSeconds: nextDefinition?.fields.some((field) => field.source === "DURATION_SECONDS") ? fields.durationSeconds : null,
        ...(artifact.kind === "VIDEO" ? { videoInputMode: retained.length ? "GENERAL_REFERENCE" : "TEXT" } : {}) });
      setPopover(null); triggerRef.current?.focus(); return;
    }
    const nextImageParameters = artifact.kind === "IMAGE"
      ? normalizedImageParameters(fields.parameters, nextCapability) : null;
    if (nextImageParameters && Object.keys(fields.parameters).length > 0
        && JSON.stringify(nextImageParameters) !== JSON.stringify(normalizedImageParameters(fields.parameters, chosenCapability))
        && !window.confirm("切换模型会将不受支持的图片参数调整为该模型的默认值。是否继续？")) return;
    const nextVideoMode = artifact.kind === "VIDEO" && fields.mediaInputs.length > 0
      && (!fields.videoInputMode || fields.videoInputMode === "TEXT"
        || !nextCapability?.supportedVideoInputModes.includes(fields.videoInputMode))
      ? preferredImageVideoMode(nextCapability) : fields.videoInputMode;
    const nextVideoInputs = nextVideoMode && artifact.kind === "VIDEO"
      ? inputsForVideoMode(fields.mediaInputs, nextVideoMode) : fields.mediaInputs;
    if (nextVideoInputs.length < fields.mediaInputs.length
        && !window.confirm("切换模型会移除不兼容的图片/音频参考及其连线、提示词标签。是否继续？")) return;
    edit({ capabilityId, ...(nextImageParameters ? { parameters: nextImageParameters } : {}),
      ...(artifact.kind === "VIDEO" ? { parameters: normalizedVideoParameters(fields.parameters, chosenCapability),
        videoInputMode: nextVideoMode, mediaInputs: nextVideoInputs, ...promptForMediaInputs(fields, nextVideoInputs) } : {}) });
    setPopover(null);
    triggerRef.current?.focus();
  }

  if (!fields) return <div className="media-draft-editor media-draft-initial" aria-label="媒体生成编辑器">
    {draft.error ? <div role="alert">无法读取工作草稿：{draft.error.message}
      <button className="media-draft-text-action" disabled={draft.isFetching}
        onClick={() => void draft.refetch()} type="button">重试读取草稿</button></div>
      : <CanvasLoadingState compact label={recovery?.saving ? "正在保存工作草稿" : "正在读取工作草稿"} />}
  </div>;

  const imageChoices = imageResources.flatMap((candidate, index) =>
    (imageHistories[index]?.data?.items ?? []).flatMap((version) => {
      const assetId = imageAssetId(version.content);
      return assetId && (runningHub || candidate.kind === "IMAGE" || candidate.kind === "AUDIO" && artifact.kind !== "IMAGE") ? [{ id: version.id, label: `${candidate.title} · v${version.versionNo}`,
        title: candidate.title, kind: candidate.kind, versionNo: version.versionNo, assetId,
        available: imageHistories[index]?.isSuccess === true,
        current: version.id === candidate.resourceDefaultVersionId }] : [];
    }));
  const canvasChoices = (canvas.data?.items ?? []).flatMap((item) => {
    if (item.id === canvasItemId || item.subjectType !== "ARTIFACT"
        || !item.artifact || !["IMAGE", "AUDIO"].includes(item.artifact.kind) || !item.selectedVersion) return [];
    const assetId = imageAssetId(item.selectedVersion.content);
    return assetId ? [{ canvasItemId: item.id, versionId: item.selectedVersion.id,
      title: item.title, kind: item.artifact.kind, versionNo: item.selectedVersion.versionNo, assetId }] : [];
  });
  const effectiveMode = artifact.kind === "VIDEO"
    ? fields.videoInputMode ?? chosenCapability?.defaultVideoInputMode ?? null : null;
  const selectedReferences = fields.mediaInputs.map((input) => ({ input,
    choice: imageChoices.find((choice) => choice.id === input.versionId) }));
  const promptReferences: PromptReference[] = selectedReferences.map(({ input, choice }, index) => ({
    ...input,
    label: input.role === "START_FRAME" ? "Start Frame"
      : input.role === "END_FRAME" ? "End Frame" : `${input.role === "AUDIO_REFERENCE" ? "Audio" : "Image"} ${fields.mediaInputs.slice(0, index + 1).filter((ref) => ref.role === input.role).length}`,
    ...(choice && choice.kind === "IMAGE" ? { thumbnailUrl: assetContentUrl(artifact.projectId, choice.assetId) } : {}),
  }));
  const imageCapacity = chosenCapability?.maxReferenceImages ?? 0;
  const audioCapacity = chosenCapability?.maxReferenceAudios ?? 0;
  const audioCount = fields.mediaInputs.filter((input) => input.role === "AUDIO_REFERENCE").length;
  const imageCount = fields.mediaInputs.length - audioCount;
  const mediaCapacity = imageCapacity + audioCapacity;
  const referenceLimitReached = imageCount >= imageCapacity && audioCount >= audioCapacity;
  const allInputsAvailable = selectedReferences.every(({ choice }) => choice?.available);
  const startFrame = fields.mediaInputs.find((input) => input.role === "START_FRAME");
  const endFrame = fields.mediaInputs.find((input) => input.role === "END_FRAME");
  const audioSpeaker = fields.parameters.speaker ?? chosenCapability?.settings.defaultParameters?.speaker ?? "";
  const audioMixValid = imageCount === 0 || audioCount === 0 && !audioSpeaker;
  const withinCapacity = imageCount <= imageCapacity && audioCount <= audioCapacity;
  const semanticInputsValid = isAudio ? withinCapacity && audioMixValid
      && audioCount + (audioSpeaker ? 1 : 0) <= audioCapacity && fields.prompt.length <= MAX_AUDIO_PROMPT_LENGTH
    : artifact.kind === "IMAGE" ? withinCapacity && audioCount === 0
    : effectiveMode === "TEXT" ? fields.mediaInputs.length === 0
      : effectiveMode === "START_END" ? Boolean(startFrame) && withinCapacity && audioCount === 0
        && (chosenCapability?.supportsEndFrame || !endFrame)
      : effectiveMode === "GENERAL_REFERENCE" ? fields.mediaInputs.length > 0 && withinCapacity
        && (chosenCapability?.adapterId !== "ARK_SEEDANCE_2_I2V" || imageCount > 0) : false;
  const occupied = latestTask ? occupiesMediaCard(latestTask) : false;
  const duration = fields.durationSeconds ?? chosenCapability?.settings.defaultDurationSeconds ?? null;
  const validDuration = duration != null && Number.isInteger(duration)
    && duration >= Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)
    && duration <= Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS);
  const imageParameters = normalizedImageParameters(fields.parameters, chosenCapability);
  const videoParameters = normalizedVideoParameters(fields.parameters, chosenCapability);
  const supportedImageAspectRatios = chosenCapability?.supportedImageAspectRatios ?? ["AUTO"];
  const supportedImageResolutions = chosenCapability?.supportedImageResolutions ?? ["1K"];
  const supportedImageQualities = chosenCapability?.supportedImageQualities ?? [];
  const imageParametersSupported = artifact.kind !== "IMAGE" || Boolean(chosenCapability)
    && supportedImageAspectRatios.includes(imageParameters.aspectRatio)
    && supportedImageResolutions.includes(imageParameters.resolution)
    && (supportedImageQualities.length === 0
      || supportedImageQualities.includes(imageParameters.quality))
    && (!imageParameters.transparentBackground || chosenCapability!.supportsTransparentBackground);
  const videoModeSupported = artifact.kind !== "VIDEO" || effectiveMode !== null
    && (chosenCapability?.supportedVideoInputModes.includes(effectiveMode) ?? false);
  const dynamicErrors = runningHub ? runningHubErrors(runningHub, fields.parameters.dynamicValues ?? {}, fields.prompt, duration, imageChoices) : [];
  const dynamicUsedVersions = runningHub ? runningHubUsedVersions(runningHub, fields.parameters.dynamicValues ?? {}, fields.prompt, duration) : new Set<string>();
  const canRun = !dirty && !save.isPending && !commitAssetReferences.isPending
    && !error && !run.isPending
    && directTasks.isSuccess && settings.isSuccess && Boolean(chosenCapability)
    && !occupied && (runningHub ? dynamicErrors.length === 0 && fields.mediaInputs.length <= INPUT_COLORS.length && fields.mediaInputs.every((input) => dynamicUsedVersions.has(input.versionId)) && allInputsAvailable
      : fields.prompt.trim().length > 0 && semanticInputsValid && allInputsAvailable && imageParametersSupported && videoModeSupported
        && (artifact.kind !== "VIDEO" || validDuration));
  const dimensionLabel = artifact.kind === "IMAGE"
    ? `${ASPECT_RATIO_LABELS[imageParameters.aspectRatio]} · ${imageParameters.resolution}`
    : ASPECT_RATIO_LABELS[videoParameters.aspectRatio];
  const qualityLabel = artifact.kind === "IMAGE"
    ? supportedImageQualities.length
      ? QUALITY_LABELS[imageParameters.quality] : "模型默认"
    : chosenCapability?.settings.quality ? `${QUALITY_LABELS[chosenCapability.settings.quality]}画质` : "默认画质";
  const historyError = imageHistories.find((history) => history.error)?.error;
  const historyPending = resources.isPending || imageHistories.some((history) => history.isPending);
  const normalizedAssetSearch = assetSearch.trim().toLocaleLowerCase();
  const filteredImageChoices = normalizedAssetSearch
    ? imageChoices.filter((choice) => choice.label.toLocaleLowerCase().includes(normalizedAssetSearch))
    : imageChoices;
  const remainingAssetCapacity = Math.max(0, mediaCapacity - fields.mediaInputs.length);
  const saveLabel = removeConnectedInput.isPending ? "正在取消引入…"
    : failedRemovalVersionId ? "取消引入失败"
      : commitAssetReferences.isPending ? "正在添加资源…"
      : save.isPending ? "保存中…" : dirty ? error ? "保存失败，本地输入已保留" : "待保存…" : "已保存";
  const currentFields = fields;

  function changeDynamicField(fieldKey: string, value: RunningHubValue | undefined) {
    if (!runningHub) return;
    const field = runningHub.fields.find((item) => item.key === fieldKey);
    if (!field) return;
    if (field.source === "PROMPT") { edit({ prompt: typeof value === "string" ? value : "", mentions: [] }); return; }
    if (field.source === "DURATION_SECONDS") { edit({ durationSeconds: typeof value === "number" ? value : null }); return; }
    const dynamicValues = { ...(fieldsRef.current?.parameters.dynamicValues ?? {}) };
    if (value === undefined) delete dynamicValues[fieldKey]; else dynamicValues[fieldKey] = value;
    const inputs = [...(fieldsRef.current?.mediaInputs ?? currentFields.mediaInputs)];
    if (["IMAGE", "AUDIO", "VIDEO"].includes(field.type) && typeof value === "string" && !inputs.some((input) => input.versionId === value)) {
      if (inputs.length >= INPUT_COLORS.length) { setError(new Error("单张卡片最多 14 个精确素材版本，请先移除不使用的输入。")); return; }
      const role = field.type === "VIDEO" ? "VIDEO_REFERENCE" : field.type === "AUDIO" ? "AUDIO_REFERENCE" : "REFERENCE";
      inputs.push({ versionId: value, role, color: INPUT_COLORS[inputs.length % INPUT_COLORS.length]! });
    }
    edit({ parameters: { dynamicValues }, mediaInputs: inputs, ...(artifact.kind === "VIDEO" ? { videoInputMode: inputs.length ? "GENERAL_REFERENCE" : "TEXT" } : {}) });
  }

  async function uploadDynamicSlot(field: RunningHubField, file: File) {
    if (file.size > 30 * 1024 * 1024) throw new Error("RunningHub 输入素材不能超过 30 MB。");
    const capabilityAtStart = chosenCapability?.id;
    const progress = uploadProgress.current.get(file) ?? { createKey: crypto.randomUUID() };
    uploadProgress.current.set(file, progress);
    if (!progress.assetId) {
      const asset = await (field.type === "VIDEO" ? uploadVideoAsset : field.type === "AUDIO" ? uploadAudioAsset : uploadImageAsset)(artifact.projectId, file);
      progress.assetId = asset.id;
    }
    const uploaded = await createArtifact(artifact.projectId, { kind: field.type === "VIDEO" ? "VIDEO" : field.type === "AUDIO" ? "AUDIO" : "IMAGE",
      title: uploadArtifactTitle(file), content: { sourceType: "UPLOAD", assetId: progress.assetId } }, progress.createKey);
    await queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
    if (!uploaded.resourceDefaultVersionId) throw new Error("上传成功，精确版本暂不可用，请重试。");
    if (fieldsRef.current?.capabilityId && fieldsRef.current.capabilityId !== capabilityAtStart) throw new Error("能力已变化；素材已保存到资源库，请重新选择。");
    changeDynamicField(field.key, uploaded.resourceDefaultVersionId);
    uploadProgress.current.delete(file);
  }

  function nextRole(inputs = currentFields.mediaInputs, mode = effectiveMode) {
    if (artifact.kind === "IMAGE" || isAudio || mode === "GENERAL_REFERENCE") return "REFERENCE" as const;
    if (mode === "START_END") return inputs.some((input) => input.role === "START_FRAME")
      ? "END_FRAME" as const : "START_FRAME" as const;
    return null;
  }

  function canAddReference(versionId: string, inputs: DraftFields["mediaInputs"]) {
    const kind = imageChoices.find((choice) => choice.id === versionId)?.kind
      ?? canvasChoices.find((choice) => choice.versionId === versionId)?.kind ?? uploadedKinds.current.get(versionId);
    const audio = kind === "AUDIO";
    const count = inputs.filter((input) => (input.role === "AUDIO_REFERENCE") === audio).length;
    if (!kind || count >= (audio ? audioCapacity - (isAudio && audioSpeaker ? 1 : 0) : imageCapacity)) return false;
    if (isAudio && (audio ? inputs.some((input) => input.role !== "AUDIO_REFERENCE")
      : Boolean(audioSpeaker) || inputs.some((input) => input.role === "AUDIO_REFERENCE"))) return false;
    return true;
  }

  function fieldsWithReferences(versionIds: string[], baseFields = currentFields) {
    const nextInputs = [...baseFields.mediaInputs];
    const nextMode = artifact.kind === "VIDEO" && nextInputs.length === 0
      ? preferredImageVideoMode(chosenCapability) : effectiveMode;
    for (const versionId of versionIds) {
      const kind = imageChoices.find((choice) => choice.id === versionId)?.kind
        ?? canvasChoices.find((choice) => choice.versionId === versionId)?.kind ?? uploadedKinds.current.get(versionId);
      const audio = kind === "AUDIO";
      const usedCapacity = nextInputs.filter((input) => (input.role === "AUDIO_REFERENCE") === audio).length;
      if (nextInputs.some((input) => input.versionId === versionId)) continue;
      if (!canAddReference(versionId, nextInputs) || usedCapacity >= (audio ? audioCapacity : imageCapacity)) return baseFields;
      const role = audio && (isAudio || nextMode === "GENERAL_REFERENCE") ? "AUDIO_REFERENCE" : nextRole(nextInputs, nextMode);
      if (!role || role === "END_FRAME" && !chosenCapability?.supportsEndFrame) continue;
      const used = new Set(nextInputs.map((input) => input.color));
      const color = INPUT_COLORS.find((candidate) => !used.has(candidate))
        ?? INPUT_COLORS[nextInputs.length % INPUT_COLORS.length]!;
      nextInputs.push({ versionId, role, color });
    }
    return nextInputs.length === baseFields.mediaInputs.length
      ? baseFields : { ...baseFields, mediaInputs: nextInputs,
        ...(artifact.kind === "VIDEO" ? { videoInputMode: nextMode } : {}) };
  }

  function appendReferences(versionIds: string[], baseFields = currentFields) {
    const next = fieldsWithReferences(versionIds, baseFields);
    if (next !== baseFields) edit({ mediaInputs: next.mediaInputs,
      ...(artifact.kind === "VIDEO" ? { videoInputMode: next.videoInputMode } : {}) });
  }

  function chooseReference(versionId: string) {
    appendReferences([versionId]);
  }

  function openAssetReferences() {
    setAssetSearch("");
    setAssetSelection([]);
    setAssetSelectionError(null);
    setPopover("assetReferences");
  }

  function toggleAssetReference(versionId: string) {
    setAssetSelectionError(null);
    setAssetSelection((current) => {
      if (current.includes(versionId)) return current.filter((candidate) => candidate !== versionId);
      const pending = fieldsWithReferences(current).mediaInputs;
      return canAddReference(versionId, pending) ? [...current, versionId] : current;
    });
  }

  function confirmAssetReferences() {
    if (!assetSelection.length || expectedVersion === null || save.isPending
        || commitAssetReferences.isPending) return;
    const nextFields = fieldsWithReferences(assetSelection);
    if (nextFields === currentFields) return;
    commitAssetReferences.mutate({
      fieldsAtStart: currentFields,
      nextFields,
      request: { ...nextFields, expectedVersion },
    });
  }

  async function uploadFiles(files: File[]) {
    const remaining = Math.max(0, mediaCapacity - currentFields.mediaInputs.length);
    if (files.length > remaining) {
      setUploadError(new Error(`还可添加 ${remaining} 张图片，请减少本次选择。`));
      setFailedUploads([]);
      return;
    }
    const selectedAudioCount = files.filter((file) => file.type.startsWith("audio/") || /\.(mp3|wav|ogg)$/i.test(file.name)).length;
    const selectedImageCount = files.length - selectedAudioCount;
    if (selectedImageCount + imageCount > imageCapacity || selectedAudioCount + audioCount + (isAudio && audioSpeaker ? 1 : 0) > audioCapacity
        || isAudio && selectedImageCount + imageCount > 0 && (selectedAudioCount + audioCount > 0 || Boolean(audioSpeaker))) {
      setUploadError(new Error("所选素材超出图片/音频数量限制，或包含不能混用的参考素材。")); setFailedUploads([]); return;
    }
    setUploading(true);
    setUploadError(null);
    const successfulVersions: string[] = [];
    const failures: File[] = [];
    let firstFailure: Error | null = null;
    for (const file of files) {
      const progress = uploadProgress.current.get(file) ?? { createKey: crypto.randomUUID() };
      uploadProgress.current.set(file, progress);
      try {
        if (!progress.assetId) {
          const audio = file.type.startsWith("audio/") || /\.(mp3|wav|ogg)$/i.test(file.name);
          const asset = await (audio ? uploadAudioAsset : uploadImageAsset)(artifact.projectId, file);
          progress.assetId = asset.id;
        }
        const uploadedArtifact = await createArtifact(artifact.projectId, {
          kind: file.type.startsWith("audio/") || /\.(mp3|wav|ogg)$/i.test(file.name) ? "AUDIO" : "IMAGE", title: uploadArtifactTitle(file),
          content: { sourceType: "UPLOAD", assetId: progress.assetId },
        }, progress.createKey);
        if (!uploadedArtifact.resourceDefaultVersionId) {
          throw new Error(`“${file.name}”已上传，但图片版本尚不可用。`);
        }
        uploadedKinds.current.set(uploadedArtifact.resourceDefaultVersionId, uploadedArtifact.kind === "AUDIO" ? "AUDIO" : "IMAGE");
        successfulVersions.push(uploadedArtifact.resourceDefaultVersionId);
        uploadProgress.current.delete(file);
      } catch (failure) {
        failures.push(file);
        firstFailure ??= failure instanceof Error ? failure : new Error("图片上传未完成");
      }
    }
    if (successfulVersions.length) {
      appendReferences(successfulVersions, fieldsRef.current ?? currentFields);
      await queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
    }
    setFailedUploads(failures);
    setUploadError(firstFailure);
    setUploading(false);
  }

  function handleUploadSelection(event: FormEvent<HTMLInputElement>) {
    const files = Array.from(event.currentTarget.files ?? []);
    event.currentTarget.value = "";
    if (files.length) void uploadFiles(files);
  }

  function removeReference(versionId: string) {
    const persisted = draft.data?.mediaInputs.find((input) => input.versionId === versionId);
    if (persisted?.sources.some((source) => source.type === "CONNECTION"
        && source.connectionId)) {
      removeConnectedInput.mutate({ versionId });
      return;
    }
    const nextPrompt = removePromptReferences(currentFields.prompt, currentFields.mentions, versionId);
    const remaining = currentFields.mediaInputs.filter((input) => input.versionId !== versionId);
    const nextMode = artifact.kind === "VIDEO" && remaining.length === 0 ? "TEXT" : effectiveMode;
    edit({ mediaInputs: remaining,
      ...(artifact.kind === "VIDEO" ? { videoInputMode: nextMode } : {}), ...nextPrompt });
  }

  function chooseVideoMode(mode: VideoInputMode) {
    if (artifact.kind !== "VIDEO" || mode === "TEXT" && currentFields.mediaInputs.length > 0) return;
    const nextInputs = inputsForVideoMode(currentFields.mediaInputs, mode);
    if (nextInputs.length < currentFields.mediaInputs.length
        && !window.confirm("首尾帧模式只保留前两张图片，并移除音频及多余图片的连线、提示词标签。是否继续？")) return;
    edit({ videoInputMode: mode, mediaInputs: nextInputs, ...promptForMediaInputs(currentFields, nextInputs) });
    setPopover(null);
    triggerRef.current?.focus();
  }

  function moveReference(index: number, delta: -1 | 1) {
    const target = index + delta;
    moveReferenceTo(index, target);
  }

  function moveReferenceTo(index: number, target: number) {
    if (target < 0 || target >= currentFields.mediaInputs.length || index === target
        || effectiveMode === "START_END") return;
    const next = [...currentFields.mediaInputs];
    const [moving] = next.splice(index, 1);
    if (!moving) return;
    next.splice(target, 0, moving);
    edit({ mediaInputs: next });
  }

  function hasConnectionSource(versionId: string) {
    const persisted = draft.data?.mediaInputs.find((input) => input.versionId === versionId);
    return persisted?.sources.some((source) => source.type === "CONNECTION"
      && source.connectionId) ?? false;
  }

  return <div className="media-draft-editor" aria-label="媒体生成编辑器">
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      {isAudio && onOpenAgentConversation ? <button className="media-draft-tab"
        type="button" onClick={onOpenAgentConversation} disabled={openingAgentConversation}
        title="打开绑定当前音频版本的 Agent 对话">{openingAgentConversation ? "打开中…" : "Agent 对话"}</button> : null}
      {!runningHub && isAudio ? <AudioPromptTools projectId={artifact.projectId} canvasItemId={canvasItemId}
        prompt={fields.prompt} hasMentions={fields.mentions.length > 0} onApply={(prompt) => edit({ prompt, mentions: [] })} /> : null}
      <span className={`media-draft-save-state${error ? " is-error" : ""}`} role="status">{saveLabel}</span>
    </div>
    {!runningHub ? <div className="media-draft-reference-row" aria-label={audioCapacity > 0 ? "图片与音频输入" : "图片输入"}>
      <div className="media-draft-popover-anchor"
        onPointerEnter={(event) => {
          if (referenceSourcesCloseTimer.current !== null) {
            window.clearTimeout(referenceSourcesCloseTimer.current);
            referenceSourcesCloseTimer.current = null;
          }
          if (popover) return;
          if (event.currentTarget.querySelector("button")?.hasAttribute("disabled")) return;
          triggerRef.current = event.currentTarget.querySelector("button");
          setReferenceSourcesOpen(true);
        }}
        onPointerLeave={() => {
          referenceSourcesCloseTimer.current = window.setTimeout(() => {
            setReferenceSourcesOpen(false);
            referenceSourcesCloseTimer.current = null;
          }, REFERENCE_SOURCE_CLOSE_DELAY_MS);
        }}>
        <input ref={uploadInputRef} className="media-draft-upload-input" type="file"
          accept={audioCapacity > 0 ? "image/png,image/jpeg,image/webp,audio/mpeg,audio/wav,audio/ogg" : "image/png,image/jpeg,image/webp"} multiple aria-label={audioCapacity > 0 ? "选择本地图片或音频" : "选择本地图片"} onChange={handleUploadSelection} />
        <button className="media-draft-reference-add" type="button"
          disabled={!chosenCapability || referenceLimitReached || uploading
            || commitAssetReferences.isPending}
          aria-label={audioCapacity > 0 ? "添加图片或音频输入" : "添加图片输入"}
          title={uploading ? "正在上传素材" : `所选模型最多支持 ${imageCapacity} 张图片、${audioCapacity} 条音频参考`}
          aria-haspopup="menu"
          aria-expanded={referenceSourcesOpen && !popover}
          aria-controls={`${id}-reference-sources`}
          onFocus={(event) => {
            triggerRef.current = event.currentTarget;
            if (suppressReferenceSourceFocusOpen.current) return;
            setReferenceSourcesOpen(true);
          }}
          onKeyDown={(event) => { if (event.key === "Escape") setReferenceSourcesOpen(false); }}
          onClick={(event) => {
            triggerRef.current = event.currentTarget;
            setReferenceSourcesOpen(true);
          }}>
          <Plus size={20} />
        </button>
        {referenceSourcesOpen && !popover ? <DropdownMenu className="media-draft-popover media-draft-reference-sources"
          ref={popoverRef} id={`${id}-reference-sources`} role="menu" aria-label="图片来源">
          <button type="button" role="menuitem" onClick={() => {
            setReferenceSourcesOpen(false);
            uploadInputRef.current?.click();
          }}><UploadSimple size={17} /><span>从设备上传</span></button>
          <button type="button" role="menuitem" onClick={() => {
            setReferenceSourcesOpen(false);
            openAssetReferences();
          }}>
            <ImagesSquare size={17} /><span>从资源库选择</span>
          </button>
          <button type="button" role="menuitem" onClick={() => {
            setReferenceSourcesOpen(false);
            setPopover("canvasReferences");
          }}>
            <BoundingBox size={17} /><span>从画布选择</span>
          </button>
          <button type="button" role="menuitem" disabled aria-disabled="true"
            aria-label="绘制引用图（暂未接入）" title="绘制引用图暂未接入">
            <PaintBrush size={17} /><span>绘制引用图</span><small>暂未接入</small>
          </button>
        </DropdownMenu> : null}
        {popover === "assetReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-asset-references`} role="dialog" aria-label={audioCapacity > 0 ? "输入媒体版本" : "输入图片版本"}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? "选择精确图片或音频版本" : "选择精确图片版本"}</p>
          <label htmlFor={`${id}-asset-search`}>{audioCapacity > 0 ? "搜索媒体资源" : "搜索资源图片"}</label>
          <input id={`${id}-asset-search`} type="search" value={assetSearch}
            placeholder="搜索名称或版本"
            onChange={(event) => setAssetSearch(event.target.value)} />
          <div className="media-draft-reference-options" role="group" aria-label={audioCapacity > 0 ? "可选媒体版本" : "可选图片版本"}>
            {filteredImageChoices.map((choice) => {
              const alreadyAdded = fields.mediaInputs.some((input) => input.versionId === choice.id);
              const selected = assetSelection.includes(choice.id);
              const selectionFull = !selected && !canAddReference(choice.id, fieldsWithReferences(assetSelection).mediaInputs);
              return <button key={choice.id} type="button" role="checkbox"
              className="media-draft-reference-option" aria-label={`选择 ${choice.label}`}
              aria-checked={alreadyAdded || selected}
              disabled={!choice.available || alreadyAdded || selectionFull || commitAssetReferences.isPending}
              onClick={() => toggleAssetReference(choice.id)}>
              {/* Reference pixels are shown from the archived original, not the 480px preview. */}
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>v{choice.versionNo} · {choice.current ? "当前选用版本" : "历史版本"}</small></span>
              {alreadyAdded || selected ? <Check size={15} /> : null}
            </button>;
            })}
          </div>
          {historyPending ? <CanvasLoadingState compact label="正在读取图片版本" /> : null}
          {!historyPending && !resources.error && !historyError && !imageChoices.length ? <p>暂无已生成或上传的图片，请先添加图片。</p> : null}
          {!historyPending && !resources.error && !historyError && imageChoices.length > 0
            && !filteredImageChoices.length ? <p>没有匹配的图片版本。</p> : null}
          {resources.error || historyError ? <div role="alert">无法读取图片版本。
            <button className="media-draft-text-action" onClick={() => {
              void resources.refetch();
              imageHistories.forEach((history) => { void history.refetch(); });
            }} type="button">重试读取图片</button></div> : null}
          {assetSelectionError ? <div className="media-draft-reference-error" role="alert">
            整批未添加；资源已刷新，请重新选择。
          </div> : null}
          <p>按勾选顺序添加，运行时固定精确版本。本次还可选择 {Math.max(0,
            remainingAssetCapacity - assetSelection.length)} 张。</p>
          <div className="media-draft-reference-actions">
            <button type="button" disabled={commitAssetReferences.isPending} onClick={() => setPopover(null)}>取消</button>
            <button type="button" className="is-primary"
              disabled={!assetSelection.length || save.isPending || commitAssetReferences.isPending}
              onClick={confirmAssetReferences}>
              {commitAssetReferences.isPending ? "正在添加…" : `添加所选${audioCapacity > 0 ? "素材" : "图片"}（${assetSelection.length}）`}
            </button>
          </div>
        </div> : null}
        {popover === "canvasReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-canvas-references`} role="dialog" aria-label={audioCapacity > 0 ? "从画布选择媒体" : "从画布选择图片"}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? "画布中的图片和音频" : "画布中的其他图片"}</p>
          <div className="media-draft-reference-options">
            {canvasChoices.map((choice) => <button key={choice.canvasItemId} type="button"
              className="media-draft-reference-option" aria-label={`使用画布${choice.kind === "AUDIO" ? "音频" : "图片"} ${choice.title}`}
              aria-pressed={fields.mediaInputs.some((input) => input.versionId === choice.versionId)}
              disabled={fields.mediaInputs.some((input) => input.versionId === choice.versionId) || !canAddReference(choice.versionId, fields.mediaInputs)}
              onClick={() => chooseReference(choice.versionId)}>
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>画布当前选用 · v{choice.versionNo}</small></span>
              {fields.mediaInputs.some((input) => input.versionId === choice.versionId) ? <Check size={15} /> : null}
            </button>)}
          </div>
          {canvas.isPending ? <CanvasLoadingState compact label="正在读取画布图片" /> : null}
          {canvas.isSuccess && !canvasChoices.length ? <p>画布中没有其他可用图片。</p> : null}
          {canvas.error ? <div role="alert">无法读取画布图片。
            <button className="media-draft-text-action" onClick={() => void canvas.refetch()}
              type="button">重试读取画布</button></div> : null}
          <p>这里只显示其他媒体卡片当前选用的已归档版本，不包含当前卡片。</p>
        </div> : null}
      </div>
      <div className="media-draft-reference-list" role="list" aria-label={audioCapacity > 0 ? "已选择的媒体参考" : "已选择的图片"}>
        {selectedReferences.map(({ input, choice }, index) => <MediaReferenceThumbnail
          key={input.versionId} index={index} audio={input.role === "AUDIO_REFERENCE"} color={input.color}
          accessibleLabel={choice?.label ?? `图片输入 ${index + 1}`}
          {...(choice && choice.kind === "IMAGE" ? { thumbnailUrl: assetContentUrl(artifact.projectId, choice.assetId) } : {})}
          connected={hasConnectionSource(input.versionId)}
          busy={removeConnectedInput.isPending || commitAssetReferences.isPending || dirty || save.isPending}
          reorderable={effectiveMode !== "START_END"}
          onMove={(delta) => moveReference(index, delta)}
          onDragStart={() => { draggedReferenceIndex.current = index; }}
          onDragEnd={() => { draggedReferenceIndex.current = null; }}
          onDrop={() => {
            if (draggedReferenceIndex.current !== null) {
              moveReferenceTo(draggedReferenceIndex.current, index);
              draggedReferenceIndex.current = null;
            }
          }}
          onRemove={() => removeReference(input.versionId)} />)}
      </div>
    </div>
    : <RunningHubForm definition={runningHub} values={fields.parameters.dynamicValues ?? {}} prompt={fields.prompt}
      durationSeconds={fields.durationSeconds} choices={imageChoices} disabled={run.isPending} onChange={changeDynamicField} onUpload={uploadDynamicSlot} />}
    {!runningHub ? <PromptMentionEditor id={`${id}-prompt`}
      label={isAudio ? "音频提示词" : artifact.kind === "IMAGE" ? "图片提示词" : "视频提示词"}
      placeholder={isAudio ? "描述声音、对白、情绪和环境音；输入 @ 引用音频…" : artifact.kind === "IMAGE" ? "描述你想创作的画面，让想象发生…" : "描述镜头、动作和运镜，让画面动起来…"}
      prompt={fields.prompt} mentions={fields.mentions} references={promptReferences}
      onChange={(prompt, mentions) => edit({ prompt, mentions })} /> : null}
    <div className="media-draft-toolbar">
      {!runningHub && artifact.kind === "VIDEO" ? <div className="media-draft-popover-anchor media-draft-mode-anchor">
        <button className="media-draft-toolbar-button media-draft-mode-trigger" type="button"
          aria-label="选择视频输入模式" aria-haspopup="menu" aria-expanded={popover === "modes"}
          aria-controls={`${id}-modes`} onClick={(event) => togglePopover("modes", event.currentTarget)}>
          <VideoCamera size={17} /><span>{VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label
            ?? "选择输入模式"}</span><CaretDown size={12} />
        </button>
        {popover === "modes" ? <DropdownMenu className="media-draft-popover media-draft-modes" ref={popoverRef}
          id={`${id}-modes`} role="menu" aria-label="视频输入模式">
          <p className="media-draft-popover-title">视频生成模式</p>
          {VIDEO_MODE_OPTIONS.map((option) => {
            const missingImage = option.value === "START_END" ? imageCount === 0
              : option.needsImage && fields.mediaInputs.length === 0;
            const hasImagesForText = option.value === "TEXT" && fields.mediaInputs.length > 0;
            const unsupported = !chosenCapability?.supportedVideoInputModes.includes(option.value);
            const disabled = missingImage || hasImagesForText || unsupported;
            const reason = missingImage ? "添加图片后可用" : hasImagesForText ? "移除参考素材后自动切换"
              : unsupported ? "当前模型不支持" : option.description;
            return <button key={option.value} className="media-draft-model-option" role="menuitemradio"
              type="button" aria-checked={effectiveMode === option.value} disabled={disabled}
              title={reason} onClick={() => chooseVideoMode(option.value)}>
              <span><strong>{option.label}</strong><small>{reason}</small></span>
              {effectiveMode === option.value ? <Check size={16} /> : null}
            </button>;
          })}
        </DropdownMenu> : null}
      </div> : null}
      <div className="media-draft-popover-anchor media-draft-model-anchor">
        <button className="media-draft-toolbar-button media-draft-model-trigger" type="button"
          aria-label="选择生成模型" aria-haspopup="menu" aria-expanded={popover === "models"}
          aria-controls={`${id}-models`} onClick={(event) => togglePopover("models", event.currentTarget)}>
          <Cube size={17} /><span>{settings.isPending ? "加载模型…" : settings.error ? "模型配置读取失败" : chosenCapability?.name
            ?? (fields.capabilityId ? "所选模型不可用" : "未配置默认模型")}</span><CaretDown size={12} />
        </button>
        {popover === "models" ? <DropdownMenu className="media-draft-popover media-draft-models" ref={popoverRef}
          id={`${id}-models`} role="menu" aria-label="生成模型">
          <p className="media-draft-popover-title">{artifact.kind === "IMAGE" ? "图片模型" : isAudio ? "音频模型" : "视频模型"}</p>
          <button className="media-draft-model-option" role="menuitemradio" aria-checked={!fields.capabilityId}
            onClick={() => chooseCapability(null)} type="button">
            <span><strong>项目默认能力</strong><small>{defaultCapabilityId ? "跟随当前默认模型" : "尚未配置默认模型"}</small></span>{!fields.capabilityId ? <Check size={16} /> : null}
          </button>
          {availableCapabilities.map((capability) => <button key={capability.id} type="button"
            className="media-draft-model-option" role="menuitemradio" aria-checked={fields.capabilityId === capability.id}
            onClick={() => chooseCapability(capability.id)}>
            <span><strong>{capability.name}</strong><small>{capability.connectionName} · {modelName(capability)}</small>
              {capability.mock ? <small className="media-draft-model-mock">Mock 演示</small> : null}</span>
            {fields.capabilityId === capability.id ? <Check size={16} /> : null}
          </button>)}
          {settings.isPending ? <p role="status">正在读取可用模型…</p> : null}
          {settings.error ? <div role="alert">无法读取模型。
            <button className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">重试读取模型</button></div> : null}
          {settings.isSuccess && !availableCapabilities.length ? <p>尚无可用模型，请在媒体设置中启用对应能力。</p> : null}
        </DropdownMenu> : null}
      </div>
      {!runningHub ? <div className="media-draft-popover-anchor media-draft-parameters-anchor">
        <button className="media-draft-toolbar-button" type="button" aria-label={isAudio ? "音频参数" : "尺寸与画质"}
          aria-expanded={popover === "parameters"} aria-controls={`${id}-parameters`}
          onClick={(event) => togglePopover("parameters", event.currentTarget)}>
          <SlidersHorizontal size={16} /><span>{isAudio ? "语速 · 音量 · 音调" : `${artifact.kind === "VIDEO" ? `${duration ?? "—"} 秒 · ` : ""}${dimensionLabel} · ${qualityLabel}${artifact.kind === "IMAGE" ? ` · ${imageParameters.generationCount} 张` : ""}`}</span><CaretDown size={12} />
        </button>
        {popover === "parameters" ? <div className="ui-popover-surface media-draft-popover media-draft-parameters" ref={popoverRef}
          tabIndex={-1} id={`${id}-parameters`} role="dialog" aria-label="尺寸与画质设置">
          <p className="media-draft-popover-title">生成参数</p>
          {isAudio ? <div className="media-draft-audio-parameters">
            {([{ key: "speechRate", label: "语速", min: -50, max: 100 },
              { key: "loudnessRate", label: "音量", min: -50, max: 100 },
              { key: "pitchRate", label: "音调", min: -12, max: 12 }] as const).map((control) =>
              <label key={control.key}>{control.label}<input type="number" min={control.min} max={control.max} step={1}
                value={fields.parameters[control.key] ?? 0} onChange={(event) => edit({ parameters: {
                  ...fields.parameters, [control.key]: Number(event.target.value) } })} /></label>)}
            <p className="ui-muted">时长、语言和情绪可在提示词中描述，最长生成 120 秒。</p>
          </div> : artifact.kind === "IMAGE" ? <div className="media-draft-image-parameters">
            <fieldset><legend>比例</legend><div className="media-draft-choice-grid media-draft-aspect-grid">
              {ASPECT_RATIO_OPTIONS.filter((value) => supportedImageAspectRatios.includes(value))
                .map((value) => <button key={value} type="button" aria-pressed={imageParameters.aspectRatio === value}
                  onClick={() => edit({ parameters: { ...imageParameters, aspectRatio: value } })}>
                  <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                  <small>{ASPECT_RATIO_LABELS[value]}</small>
                </button>)}
            </div></fieldset>
            <fieldset><legend>分辨率</legend><div className="media-draft-segmented">
              {RESOLUTION_OPTIONS.filter((value) => supportedImageResolutions.includes(value))
                .map((value) => <button key={value} type="button" aria-pressed={imageParameters.resolution === value}
                  onClick={() => edit({ parameters: { ...imageParameters, resolution: value } })}>{value}</button>)}
            </div></fieldset>
            <div className="media-draft-switch-row"><span>透明背景</span><button type="button" role="switch"
              aria-label="透明背景"
              aria-checked={imageParameters.transparentBackground}
              disabled={!chosenCapability?.supportsTransparentBackground}
              title={chosenCapability?.supportsTransparentBackground ? undefined : "所选模型不支持透明背景"}
              onClick={() => edit({ parameters: { ...imageParameters,
                transparentBackground: !imageParameters.transparentBackground } })}><span /></button></div>
            <fieldset><legend>画质</legend>{supportedImageQualities.length
              ? <div className="media-draft-segmented">{QUALITY_OPTIONS
                .filter((value) => supportedImageQualities.includes(value))
                .map((value) => <button key={value} type="button" aria-pressed={imageParameters.quality === value}
                  onClick={() => edit({ parameters: { ...imageParameters, quality: value } })}>{QUALITY_LABELS[value]}</button>)}</div>
              : <p className="media-draft-fixed-parameter">由所选模型固定</p>}</fieldset>
            <fieldset><legend>生成数量</legend><div className="media-draft-segmented">
              {GENERATION_COUNT_OPTIONS.map((value) => <button key={value} type="button"
                aria-pressed={imageParameters.generationCount === value}
                onClick={() => edit({ parameters: { ...imageParameters, generationCount: value } })}>{value}</button>)}
            </div></fieldset>
            <p className="media-draft-fixed-parameter">空节点首个结果留在当前节点，其余结果创建独立节点</p>
          </div> : <div className="media-draft-video-parameters">
            <fieldset><legend>比例</legend><div className="media-draft-choice-grid media-draft-video-aspect-grid">
              {VIDEO_ASPECT_RATIO_OPTIONS.map((value) => <button key={value} type="button"
                aria-pressed={videoParameters.aspectRatio === value}
                aria-label={ASPECT_RATIO_LABELS[value]}
                onClick={() => edit({ parameters: { aspectRatio: value } })}>
                <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                <small>{ASPECT_RATIO_LABELS[value]}</small>
              </button>)}
            </div></fieldset>
            <p className="media-draft-fixed-parameter">画质由所选视频模型固定</p>
          </div>}
          {artifact.kind === "VIDEO" ? <div className="media-draft-duration"><label htmlFor={`${id}-duration`}>时长（秒）</label>
            <input id={`${id}-duration`} aria-describedby={chosenCapability ? `${id}-duration-help` : undefined}
              min={Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)}
              max={Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS)} step={1} type="number"
              value={duration ?? ""} onChange={(event) => edit({ durationSeconds: event.target.value ? Number(event.target.value) : null })} />
            {chosenCapability ? <span id={`${id}-duration-help`}>所选模型支持 {chosenCapability.minimumSeconds}–{chosenCapability.maximumSeconds} 秒</span> : null}
          </div> : null}
        </div> : null}
      </div>
      : null}
      {!runningHub && isAudio ? <div className="media-draft-popover-anchor">
        <button type="button" className="media-draft-toolbar-button" aria-label="选择音色" aria-expanded={popover === "voices"}
          onClick={(event) => togglePopover("voices", event.currentTarget)}><MusicNotes size={17} />{VOICES.find((voice) => voice.id === audioSpeaker)?.name ?? "音色库"}<CaretDown size={12} /></button>
        {popover === "voices" ? <VoiceLibrary containerRef={popoverRef} projectId={artifact.projectId} canvasItemId={canvasItemId} capabilityId={chosenCapability?.id} mock={chosenCapability?.mock ?? true} selected={audioSpeaker} onSelect={(speaker) => {
          edit({ parameters: { ...fields.parameters, speaker } }); setPopover(null); triggerRef.current?.focus();
        }} onClose={() => setPopover(null)} /> : null}
      </div> : null}
      <span className="media-draft-cost" title="按管理员配置估算，实际费用以平台账单为准"><Coins size={16} /><span>{estimatedMediaCost(chosenCapability, runningHub ? 1 : imageParameters.generationCount, isAudio && !runningHub ? 120 : duration)}</span></span>
      <button className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? "正在提交运行" : "运行"} title={occupied ? "此卡片已有任务，请等待完成或先重试" : "运行"}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></button>
    </div>
    <div className="media-draft-feedback">
      {runningHub ? <>
        {dynamicErrors.map((message) => <p role="status" key={message}>{message}</p>)}
        {fields.mediaInputs.filter((input) => !dynamicUsedVersions.has(input.versionId)).map((input) => <p key={input.versionId} role="status">素材未分配到具名槽位。<button type="button" disabled={dirty || save.isPending || removeConnectedInput.isPending} onClick={() => removeReference(input.versionId)}>移除未使用素材引用</button></p>)}
        {runningHub.retainSeconds ? <p>实例保留 {runningHub.retainSeconds} 秒会额外计费。</p> : null}
      </> : null}
      {uploading ? <CanvasLoadingState compact label="正在上传引用图片" /> : null}
      {uploadError ? <div role="alert">上传引用图片失败：{uploadError.message}
        {failedUploads.length ? <button className="media-draft-text-action" type="button"
          disabled={uploading} onClick={() => void uploadFiles(failedUploads)}>重试失败图片</button> : null}
      </div> : null}
      {run.isPending ? <CanvasLoadingState compact label="正在提交任务" /> : null}
      {run.error ? <p role="alert">运行失败：{run.error.message}</p> : null}
      {directTasks.isPending ? <p role="status">正在检查卡片任务…</p> : null}
      {directTasks.error ? <div role="alert">无法确认卡片任务状态：{directTasks.error.message}
        <button className="media-draft-text-action" type="button" onClick={() => void directTasks.refetch()}>重试检查任务</button></div> : null}
      {settings.isSuccess && fields.capabilityId && !chosenCapability ? <p role="status">所选模型不可用，请选择其他模型。</p> : null}
      {settings.isSuccess && !fields.capabilityId && !chosenCapability ? <p role="status">尚未配置默认模型，请选择可用模型或先在媒体设置中配置。</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && effectiveMode && !videoModeSupported
        ? <p role="alert">当前模型不支持{VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label}，请切换模型或添加/移除图片。</p> : null}
      {settings.error ? <div role="alert">无法读取模型配置。
        <button className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">重试读取模型</button></div> : null}
      {!runningHub && isAudio && !semanticInputsValid ? <p role="alert">音频生成最多参考 1 张图片或 3 个音频/音色；图片不能与音频或指定音色混用，提示词最多 3000 字符。</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && duration != null && !validDuration ? <p role="alert">请填写所选模型支持的整数秒时长。</p> : null}
      {fields.mediaInputs.length > 0 && !historyPending && (!resources.isSuccess || !allInputsAvailable)
        ? <p role="alert">无法确认一个或多个已固定媒体版本。原选择已保留，请重试读取素材或替换输入。</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && !semanticInputsValid
        ? <p role="alert">当前媒体输入不满足所选视频模式或模型能力，请调整后再运行。</p> : null}
      {latestTask && (latestTask.status === "FAILED" || latestTask.status === "BLOCKED")
        ? <p role="alert">生成未完成{taskErrorDetail(latestTask.errorCode)}</p> : null}
      {latestTask?.status === "READY" ? <div className="media-draft-task-status">
        {queue.data && latestTask.status === "READY" ? <span>前方 {queue.data.waitingAhead} 项 · {QUEUE_LABELS[queue.data.reason]}（排位可能变化）</span> : null}
        {queue.error && latestTask.status === "READY" ? <span role="alert">暂时无法读取排位，任务仍在排队。</span> : null}
        {latestTask.status === "READY" ? <button className="media-draft-text-action" type="button"
          disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>{cancel.isPending ? "取消中…" : "取消排队"}</button> : null}
      </div> : null}
      {cancel.error ? <p role="alert">取消失败：{cancel.error.message}</p> : null}
      {latestTask?.status === "UNKNOWN" ? <UnknownTaskRetryPanel errorCode={latestTask.errorCode}
        projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version} /> : null}
      {error ? <div role="alert"><span>{error instanceof ApiError && error.status === CONFLICT_STATUS
        ? "草稿有冲突；本地输入已保留。重新读取版本后可再保存。" : error.message}</span>
        <button className="media-draft-text-action" onClick={() => void retry()} type="button">
          {error instanceof ApiError && error.status === CONFLICT_STATUS ? "重新读取版本"
            : failedRemovalVersionId ? "重试取消引入" : "重试保存"}</button></div> : null}
    </div>
  </div>;
}
