import { Popover as PopoverPrimitive } from "radix-ui";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { FieldSet, FieldLegend } from "../../shared/ui/primitives/field";
import { Switch } from "../../shared/ui/primitives/switch";
import {
ArrowUp,CaretDown,Check,Coins,Cube,
ImageSquare,
MusicNotes,
PaintBrush,SlidersHorizontal,VideoCamera,
X
} from "@phosphor-icons/react";
import { useMutation,useQueries,useQuery,useQueryClient } from "@tanstack/react-query";
import type { CSSProperties,FormEvent } from "react";
import { useCallback, useEffect,useId,useLayoutEffect,useRef,useState } from "react";
import {
HTTP_STATUS,ApiError,assetContentUrl,cancelQueuedDirectMediaTask,createArtifact,getDirectMediaQueueStatus,
assetThumbnailUrl,
getMediaDraft,getMediaSettings,
listArtifactVersions,listArtifacts,listCanvasItems,listDirectMediaTasks,
removeMediaDraftMediaInput,
replaceMediaDraftInputs,
runMediaDraft,saveMediaDraft,
uploadAudioAsset,
uploadImageAsset,
uploadVideoAsset,
type Artifact,
type MediaDraft,
type RunningHubField,
type SaveMediaDraftRequest
} from "../../shared/api/client";
import type { MediaTemplateImport } from "../../shared/api/client";
import { AUTODL_ADAPTER,publishedAutoDlResolutions,autoDlRatioSupported,resolveAutoDlWorkflow } from "../../shared/autodlWorkflows";
import { t,useLocale } from "../../shared/i18n";
import { estimatedMediaCost } from "../../shared/mediaPricing";
import { isAudioFile, isVideoFile, MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { OptionContent } from "../../shared/ui/OptionContent";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { Input } from "../../shared/ui/primitives/input";
import { LibraryReferencePicker } from "../library/LibraryReferencePicker";
import { MediaTemplatePicker } from "../templates/MediaTemplatePicker";
import { templateDraftChanges, type TemplateApplyOptions } from "../templates/templateApplication";
import { mediaModelDetails } from "./mediaModelPresentation";
import { mediaDraftQueryOptions } from "./mediaDisplay";
import { AudioPromptTools } from "./AudioPromptTools";
import "./MediaDraftEditor.css";
import { runningHubErrors,runningHubUsedVersions,runningHubFieldValue,type RunningHubValue } from "./RunningHubForm";
import { WorkflowMediaInputs, WorkflowParametersDialog } from "./WorkflowDraftControls";
import { MediaReferenceSourceMenu } from "./MediaReferenceSourceMenu";
import { activeWorkflowMediaFields, workflowDefinition, workflowDraftValues } from "./workflowDraft";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { VoiceLibrary } from "./VoiceLibrary";
import { readContentText } from "./artifactContent";
import { useCanvasStore } from "./canvasStore";
import { saveClosedMediaDraft,type PendingMediaDraftSave } from "./mediaDraftCloseSave";
import { MEDIA_TASK_REFRESH_INTERVAL_MS,latestMediaTask,occupiesMediaCard,isMediaTaskRunning } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import { VOICES } from "./voiceCatalog";
import { ASPECT_RATIO_OPTIONS, VIDEO_ASPECT_RATIO_OPTIONS, RESOLUTION_OPTIONS, QUALITY_OPTIONS,
  GENERATION_COUNT_OPTIONS, normalizedImageParameters, normalizedVideoParameters,
  preferredImageVideoMode, preferredVideoMode, inputsForVideoMode, planMediaCapabilityChange,
  type MediaDraftFields as DraftFields } from "./mediaDraftCapability";
import { PromptMentionEditor, type PromptReference } from "./PromptMentionEditor";
import { promptForMediaInputs, removePromptReferences } from "./mediaPrompt";
import { mediaStylesQueryOptions } from "../../shared/mediaStyles";
import { MediaStylePicker } from "./MediaStylePicker";

const AUTOSAVE_DELAY_MS = 650;
const MAX_PROMPT_LENGTH = 20000;
const MAX_AUDIO_PROMPT_LENGTH = 3000;
const MAX_MEDIA_INPUTS = 14;
const MAX_RUNNINGHUB_INPUT_BYTES = 30 * 1024 * 1024;
const MIN_VIDEO_SECONDS = 1;
const MAX_VIDEO_SECONDS = 30;
const MAX_ARTIFACT_TITLE_LENGTH = 160;
const REFERENCE_PICKER_GAP_PX = 10;
const PICKER_VIEWPORT_MARGIN_PX = 12;
const INPUT_COLORS = [
  "#F15CAF", "#67C7F3", "#F1B95C", "#8DD17E", "#A98AF7", "#F27979", "#56C8B5",
  "#D98BD9", "#E56B3F", "#4DB6E5", "#B8D84A", "#8C7AE6", "#E7A93D", "#4FC38D",
] as const;
const QUEUE_LABELS = {
  get WAITING_WORKER() { return t("media.editor.waitingForWorker"); }, get NOT_QUEUED() { return t("media.editor.notQueued"); },
} as const;
const QUALITY_LABELS = { get low() { return t("media.editor.lowQuality"); }, get medium() { return t("media.editor.mediumQuality"); }, get high() { return t("media.editor.highQuality"); } } as const;
const ASPECT_RATIO_LABELS: Readonly<Record<(typeof ASPECT_RATIO_OPTIONS)[number], string>> = {
  "1:1": "1:1", "2:3": "2:3", "3:2": "3:2", "9:16": "9:16", "16:9": "16:9",
  "3:4": "3:4", "4:3": "4:3", "21:9": "21:9", get AUTO() { return t("common.automatic"); },
};
const VIDEO_MODE_OPTIONS = [
  { value: "TEXT", get label() { return t("media.editor.textToVideo"); }, get description() { return t("media.editor.textOnlyHint"); }, needsImage: false },
  { value: "GENERAL_REFERENCE", get label() { return t("common.mixedReference"); }, get description() { return t("media.editor.mixedReferencesHint"); }, needsImage: true },
  { value: "START_END", get label() { return t("common.startEndFrames"); }, get description() { return t("media.editor.startFrameHint"); }, needsImage: true },
] as const;
type Popover = "models" | "modes" | "parameters" | "assetReferences" | "canvasReferences" | "libraryReferences" | "voices";
type RunIntent = { key: string; expectedDraftVersion: number };
type UploadProgress = { assetId?: string; createKey: string };
type AssetReferenceCommit = {
  fieldsAtStart: DraftFields;
  nextFields: DraftFields;
  request: SaveMediaDraftRequest;
};
type VideoInputMode = NonNullable<MediaDraft["videoInputMode"]>;

function imageAssetId(content: unknown) {
  const value = readContentText(content, "assetId");
  return value.trim() ? value : null;
}

function uploadArtifactTitle(file: File) {
  const withoutExtension = file.name.replace(/\.[^.]+$/, "").trim();
  return (withoutExtension || t("media.uploadImage")).slice(0, MAX_ARTIFACT_TITLE_LENGTH);
}

function fieldsFromDraft(draft: MediaDraft): DraftFields {
  return {
    prompt: draft.prompt, parameters: draft.parameters ?? {}, styleId: draft.styleId ?? null,
    durationSeconds: draft.durationSeconds, capabilityId: draft.capabilityId,
    videoInputMode: draft.videoInputMode,
    mediaInputs: (draft.mediaInputs ?? []).map(({ versionId, role, color }) => ({
      versionId, role, color,
    })),
    mentions: draft.mentions ?? [],
  };
}

function MediaReferenceThumbnail({ index, color, thumbnailUrl, accessibleLabel, connected, audio = false,
    busy, reorderable, onMove, onDragStart, onDragEnd, onDrop, onRemove }: {
  index: number; color: string; thumbnailUrl?: string; accessibleLabel: string; audio?: boolean;
  connected: boolean; busy: boolean; reorderable: boolean;
  onMove: (delta: -1 | 1) => void; onDragStart: () => void;
  onDragEnd: () => void; onDrop: () => void; onRemove: () => void;
}) {
  useLocale();
  return <div className="media-draft-reference-chip"
    style={{ "--reference-color": color } as CSSProperties}
    role="listitem"
    aria-label={t("media.editor.inputLabel", { "0": accessibleLabel, "1": index + 1 })}
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
    <Button variant="ghost" className="media-draft-reference-remove" type="button"
      aria-label={t("media.editor.removeNamedInput", { "0": accessibleLabel })} disabled={busy}
      title={connected ? t("media.editor.removeInputAndRelations") : t("media.editor.removeInput")}
      onClick={onRemove}><X size={13} /></Button>
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
  useLocale();
  const isAudio = artifact.kind === "AUDIO";
  const queryClient = useQueryClient();
  const draftOptions = mediaDraftQueryOptions(artifact.projectId, canvasItemId);
  const key = draftOptions.queryKey;
  const draft = useQuery(draftOptions);
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
    enabled: latestTask?.status === "READY" && latestTask.runId === null,
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS,
  });
  const [fields, setFields] = useState<DraftFields | null>(null);
  const [stylePickerOpen, setStylePickerOpen] = useState(false);
  const [extendedParametersOpen, setExtendedParametersOpen] = useState(false);
  const [workflowUploading, setWorkflowUploading] = useState(false);
  const workflowUploads = useRef(new Set<string>());
  const styles = useQuery({ ...mediaStylesQueryOptions(), enabled: !isAudio && (stylePickerOpen || Boolean(fields?.styleId)) });
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
  const expectedVersionRef = useRef<number | null>(null);
  useLayoutEffect(() => { expectedVersionRef.current = expectedVersion; }, [expectedVersion]);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [failedRemovalVersionId, setFailedRemovalVersionId] = useState<string | null>(null);
  const draggedReferenceIndex = useRef<number | null>(null);
  const runIntent = useRef<RunIntent | null>(null);
  const [libraryBusy, setLibraryBusy] = useState(false);
  const [templateOpen, setTemplateOpen] = useState(false);
  const [templateBusy, setTemplateBusy] = useState(false);
  const [popover, setPopover] = useState<Popover | null>(null);
  const [referenceSourcesOpen, setReferenceSourcesOpen] = useState(false);
  const [submissionTipOpen, setSubmissionTipOpen] = useState(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const referencePickerAnchorRef = useRef<HTMLDivElement>(null);
  const focusModelMenu = useCallback((element: HTMLDivElement | null) => {
    popoverRef.current = element;
    if (element) queueMicrotask(() => element.querySelector<HTMLElement>("[aria-checked=true]")?.focus());
  }, []);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const uploadInputRef = useRef<HTMLInputElement>(null);
  const uploadedKinds = useRef(new Map<string, "IMAGE" | "AUDIO" | "VIDEO">());
  const uploadProgress = useRef(new Map<File, UploadProgress>());
  const [uploading, setUploading] = useState(false);
  const [failedUploads, setFailedUploads] = useState<File[]>([]);
  const [uploadError, setUploadError] = useState<Error | null>(null);
  const [workflowPickerField, setWorkflowPickerField] = useState<RunningHubField | null>(null);
  const [assetSearch, setAssetSearch] = useState("");
  const [assetSelection, setAssetSelection] = useState<string[]>([]);
  const [assetSelectionError, setAssetSelectionError] = useState<Error | null>(null);
  const id = useId();
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
  const workflow = workflowDefinition(chosenCapability);
  const chosenCapabilityRef = useRef(chosenCapability);
  useLayoutEffect(() => { chosenCapabilityRef.current = chosenCapability; }, [chosenCapability]);
  const canvas = useQuery({
    queryKey: ["canvas", artifact.projectId],
    queryFn: () => listCanvasItems(artifact.projectId),
    enabled: popover === "canvasReferences" || Boolean(workflow),
  });

  useEffect(() => {
    if (popover) setReferenceSourcesOpen(false);
  }, [popover]);

  useLayoutEffect(() => {
    if (popover !== "assetReferences" && popover !== "canvasReferences" && popover !== "libraryReferences") return;
    function fitPicker() {
      const anchor = referencePickerAnchorRef.current;
      if (!anchor) return;
      const space = anchor.getBoundingClientRect().top - REFERENCE_PICKER_GAP_PX - PICKER_VIEWPORT_MARGIN_PX;
      if (space > 0) anchor.style.setProperty("--media-reference-picker-space", `${space}px`);
    }
    fitPicker();
    window.addEventListener("resize", fitPicker);
    return () => window.removeEventListener("resize", fitPicker);
  }, [popover]);

  useEffect(() => {
    if (!popover || popover === "models" || popover === "modes") return;
    const firstControl = popoverRef.current?.querySelector<HTMLElement>(popover === "voices"
      ? "input[type=search]" : "[aria-checked='true'], button, select, input");
    (firstControl ?? popoverRef.current)?.focus();
    function onPointerDown(event: PointerEvent) {
      if (popover === "libraryReferences" && libraryBusy) return;
      const listbox = event.target instanceof Element ? event.target.closest('[role="listbox"]') : null;
      // Select portals sit outside the picker; the trigger's ARIA link identifies only its own menu.
      if ((popover === "libraryReferences" || popover === "voices") && listbox?.id
        && [...popoverRef.current?.querySelectorAll('[role="combobox"][aria-controls]') ?? []]
          .some((control) => control.getAttribute("aria-controls") === listbox.id)) return;
      if (event.target instanceof Node && !popoverRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setPopover(null);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        // The voice filter owns the first Escape; keep the library open while
        // Radix dismisses its menu and restores focus to the filter trigger.
        if (popover === "voices" && popoverRef.current?.querySelector('[role="combobox"][aria-expanded="true"]')) return;
        event.preventDefault();
        event.stopPropagation();
        if (popover === "libraryReferences" && libraryBusy) return;
        setPopover(null);
        triggerRef.current?.focus();
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown, true);
    };
  }, [popover, libraryBusy]);

  const save = useMutation({
    mutationFn: (input: SaveMediaDraftRequest) => {
      if (editorReadOnlyRef.current) throw new Error(t("media.editor.editingLocked"));
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
    mutationFn: ({ request }: AssetReferenceCommit) => {
      if (editorReadOnlyRef.current) throw new Error(t("media.editor.editingLocked"));
      return saveMediaDraft(artifact.projectId, canvasItemId, request);
    },
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
      triggerRef.current?.focus();
    },
    onError: async (failure) => {
      setAssetSelection([]);
      setAssetSelectionError(failure instanceof Error ? failure : new Error(t("media.editor.selectionSaveFailed")));
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
      if (editorReadOnlyRef.current) throw new Error(t("media.editor.editingLocked"));
      if (!draft.data || version === null || (dirty || save.isPending) && !allowDirty) {
        throw new Error(t("media.editor.waitForSaveBeforeRemoval"));
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
      if (artifact.kind === "VIDEO" && !runningHub && next.mediaInputs.length === 0
          && context?.fieldsAtStart?.mediaInputs.length && next.videoInputMode === "TEXT"
          && chosenCapability && !chosenCapability.supportedVideoInputModes.includes("TEXT")) {
        next = { ...next, videoInputMode: preferredVideoMode(chosenCapability, false) };
        changedWhileRemoving = true;
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
      if (expectedVersion === null) throw new Error(t("media.editor.waitForDraft"));
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
  // Card-scoped history includes approved Agent work. Unknown outcomes keep their
  // explicit retry controls; active requests lock edits through local archival.
  const editorReadOnly = !isAudio && (run.isPending || !directTasks.isSuccess
    || Boolean(directTasks.data?.some(isMediaTaskRunning)));
  const editorReadOnlyRef = useRef(editorReadOnly);
  useLayoutEffect(() => { editorReadOnlyRef.current = editorReadOnly; }, [editorReadOnly]);
  useEffect(() => {
    if (!editorReadOnly) return;
    setPopover(null);
    setWorkflowPickerField(null);
    setReferenceSourcesOpen(false);
    setStylePickerOpen(false);
    setTemplateOpen(false);
    setSubmissionTipOpen(false);
    draggedReferenceIndex.current = null;
  }, [editorReadOnly]);

  const cancel = useMutation({
    mutationFn: (taskId: string) => cancelQueuedDirectMediaTask(artifact.projectId, taskId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tasksKey }),
  });

  const closeState = useRef<{ fields: DraftFields | null; dirty: boolean;
    expectedVersion: number | null; error: Error | null; libraryBusy: boolean }>({ fields: null,
    dirty: false, expectedVersion: null, error: null, libraryBusy: false });
  useLayoutEffect(() => {
    closeState.current = { fields, dirty, expectedVersion, error, libraryBusy: libraryBusy || templateBusy };
  }, [fields, dirty, expectedVersion, error, libraryBusy, templateBusy]);
  useEffect(() => () => {
    const state = closeState.current;
    if (state.dirty && state.fields && state.expectedVersion !== null) {
      const request = { ...state.fields, expectedVersion: state.expectedVersion };
      // An accepted reference owns this CAS version; closing must retain edits without racing it.
      if (editorReadOnlyRef.current || state.error || state.libraryBusy) {
        useCanvasStore.getState().setMediaDraftRecovery(ratioDraftKey,
          { request, saving: false, error: state.error ?? new Error(t(editorReadOnlyRef.current ? "media.editor.editingLocked" : "media.editor.transferPendingHint")) });
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
        || libraryBusy || templateBusy || run.isPending || error) return;
    // An earlier GET can finish after a successful save wrote its newer result to the cache.
    // The last acknowledged CAS version is monotonic even if query responses arrive out of order.
    if (expectedVersion !== null && draft.data.version < expectedVersion) return;
    const initial = fieldsFromDraft(draft.data);
    fieldsRef.current = initial;
    setFields(initial);
    setExpectedVersion(draft.data.version);
  }, [draft.data, dirty, save.isPending, commitAssetReferences.isPending,
    run.isPending, libraryBusy, templateBusy, error, expectedVersion, recovery]);

  useEffect(() => {
    if (editorReadOnly || !dirty || !fields || expectedVersion === null || save.isPending
        || commitAssetReferences.isPending
        || removeConnectedInput.isPending || libraryBusy || templateBusy || popover === "libraryReferences" || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, commitAssetReferences.isPending,
    removeConnectedInput.isPending, libraryBusy, templateBusy, popover, error, editorReadOnly]);

  useEffect(() => {
    if (editorReadOnly || !fields || !chosenCapability || dirty || save.isPending || error || run.isPending || runIntent.current
        || commitAssetReferences.isPending || removeConnectedInput.isPending || templateBusy) return;
    const changes: Partial<DraftFields> = {};
    if (!runningHub && artifact.kind === "VIDEO") {
      const hasInputs = fields.mediaInputs.length > 0;
      const useDefault = fields.videoInputMode === null || fields.videoInputMode === "TEXT"
        && (hasInputs || !chosenCapability.supportedVideoInputModes.includes("TEXT"));
      const desiredMode = useDefault ? preferredVideoMode(chosenCapability, hasInputs) : fields.videoInputMode;
      const parameters = { ...normalizedVideoParameters(fields.parameters, chosenCapability),
        ...(workflow ? { dynamicValues: fields.parameters.dynamicValues } : {}) };
      if (desiredMode && fields.videoInputMode !== desiredMode) {
        changes.videoInputMode = desiredMode;
        changes.mediaInputs = inputsForVideoMode(fields.mediaInputs, desiredMode);
      }
      if (JSON.stringify(fields.parameters) !== JSON.stringify(parameters)) changes.parameters = parameters;
    }
    if (Object.keys(changes).length > 0) edit(changes);
  }, [artifact.kind, chosenCapability, runningHub, workflow, commitAssetReferences.isPending, dirty, fields,
    removeConnectedInput.isPending, save.isPending, templateBusy, error, run.isPending, editorReadOnly]);

  function edit(changes: Partial<DraftFields>) {
    if (editorReadOnlyRef.current) return;
    setSubmissionTipOpen(false);
    runIntent.current = null;
    if (!run.isPending) run.reset();
    setFields((current) => {
      if (!current) return current;
      const next = { ...current, ...(workflow && chosenCapability ? { capabilityId: chosenCapability.id } : {}), ...changes };
      fieldsRef.current = next;
      return next;
    });
    setDirty(true);
    setFailedRemovalVersionId(null);
    if (!(error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT)) setError(null);
  }

  async function retry() {
    if (!fields || expectedVersion === null) return;
    if (error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT) {
      try {
        const fresh = await getMediaDraft(artifact.projectId, canvasItemId);
        queryClient.setQueryData(key, fresh);
        setExpectedVersion(fresh.version);
        setFailedRemovalVersionId(null);
        setError(null);
      } catch (failure) {
        setError(failure instanceof Error ? failure : new Error(t("media.editor.draftRefreshFailed")));
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
    if (editorReadOnlyRef.current || libraryBusy) return;
    triggerRef.current = trigger;
    setPopover((current) => current === next ? null : next);
  }

  function chooseCapability(capabilityId: string) {
    if (editorReadOnlyRef.current) return;
    if (!fields) return;
    const resolvedCapabilityId = capabilityId;
    const next = availableCapabilities.find((candidate) => candidate.id === resolvedCapabilityId);
    const change = planMediaCapabilityChange({ kind: artifact.kind, fields, capabilityId,
      resolvedCapabilityId, previous: chosenCapability, next });
    edit(change.fields);
    setWorkflowPickerField(null);
    setPopover(null);
    triggerRef.current?.focus();
  }

  if (!fields) return <div className="media-draft-editor media-draft-initial" aria-label={t("media.editor.title")}>
    {draft.error ? <div role="alert">{t("media.editor.draftFailed", { "0": draft.error.message })}<Button variant="ghost" className="media-draft-text-action" disabled={draft.isFetching}
        onClick={() => void draft.refetch()} type="button">{t("media.editor.retryDraft")}</Button></div>
      : <CanvasLoadingState compact label={recovery?.saving ? t("media.editor.saving") : t("media.editor.draftLoading")} />}
  </div>;

  const videoCapacity = chosenCapability?.maxReferenceVideos ?? 0;
  const imageChoices = imageResources.flatMap((candidate, index) =>
    (imageHistories[index]?.data?.items ?? []).flatMap((version) => {
      const assetId = imageAssetId(version.content);
      return assetId && (workflow || candidate.kind === "IMAGE" || (candidate.kind === "AUDIO" && artifact.kind !== "IMAGE" || candidate.kind === "VIDEO" && videoCapacity > 0)) ? [{ id: version.id, label: `${candidate.title} · v${version.versionNo}`,
        title: candidate.title, kind: candidate.kind, versionNo: version.versionNo, assetId,
        available: imageHistories[index]?.isSuccess === true,
        current: version.id === candidate.resourceDefaultVersionId }] : [];
    }));
  const canvasChoices = (canvas.data?.items ?? []).flatMap((item) => {
    if (item.id === canvasItemId || item.subjectType !== "ARTIFACT"
        || !item.artifact || !["IMAGE", "AUDIO", ...(workflow || videoCapacity > 0 ? ["VIDEO"] : [])].includes(item.artifact.kind) || !item.selectedVersion) return [];
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
      : input.role === "END_FRAME" ? "End Frame" : `${input.role === "VIDEO_REFERENCE" ? "Video" : input.role === "AUDIO_REFERENCE" ? "Audio" : "Image"} ${fields.mediaInputs.slice(0, index + 1).filter((ref) => ref.role === input.role).length}`,
    ...(choice && choice.kind !== "AUDIO" ? { thumbnailUrl: choice.kind === "VIDEO" ? assetThumbnailUrl(artifact.projectId, choice.assetId) : assetContentUrl(artifact.projectId, choice.assetId) } : {}),
  }));
  const imageCapacity = chosenCapability?.maxReferenceImages ?? 0;
  const audioCapacity = chosenCapability?.maxReferenceAudios ?? 0;
  const audioCount = fields.mediaInputs.filter((input) => input.role === "AUDIO_REFERENCE").length;
  const videoCount = fields.mediaInputs.filter((input) => input.role === "VIDEO_REFERENCE").length;
  const imageCount = fields.mediaInputs.length - audioCount - videoCount;
  const mediaCapacity = imageCapacity + audioCapacity + videoCapacity;
  const referenceLimitReached = fields.mediaInputs.length >= MAX_MEDIA_INPUTS || imageCount >= imageCapacity && audioCount >= audioCapacity && videoCount >= videoCapacity;
  const allInputsAvailable = selectedReferences.every(({ choice }) => choice?.available);
  const startFrame = fields.mediaInputs.find((input) => input.role === "START_FRAME");
  const endFrame = fields.mediaInputs.find((input) => input.role === "END_FRAME");
  const audioSpeaker = fields.parameters.speaker ?? chosenCapability?.settings.defaultParameters?.speaker ?? "";
  const audioMixValid = imageCount === 0 || audioCount === 0 && !audioSpeaker;
  const withinCapacity = fields.mediaInputs.length <= MAX_MEDIA_INPUTS && imageCount <= imageCapacity && audioCount <= audioCapacity && videoCount <= videoCapacity;
  const semanticInputsValid = isAudio ? withinCapacity && audioMixValid
      && audioCount + (audioSpeaker ? 1 : 0) <= audioCapacity && fields.prompt.length <= MAX_AUDIO_PROMPT_LENGTH
    : artifact.kind === "IMAGE" ? withinCapacity && audioCount === 0 && videoCount === 0
    : effectiveMode === "TEXT" ? fields.mediaInputs.length === 0
      : effectiveMode === "START_END" ? Boolean(startFrame) && withinCapacity && audioCount === 0 && videoCount === 0
        && (chosenCapability?.supportsEndFrame || !endFrame)
      : effectiveMode === "GENERAL_REFERENCE" ? fields.mediaInputs.length > 0 && withinCapacity
        && (chosenCapability?.adapterId !== "ARK_SEEDANCE_2_I2V" || imageCount + videoCount > 0) : false;
  const occupied = latestTask ? occupiesMediaCard(latestTask) : false;
  const workflowDuration = workflow?.fields.find((field) => field.source === "DURATION_SECONDS")?.defaultValue;
  const duration = fields.durationSeconds ?? chosenCapability?.settings.defaultDurationSeconds
    ?? (typeof workflowDuration === "number" ? workflowDuration : null);
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
  const autodlWorkflow = chosenCapability?.adapterId === AUTODL_ADAPTER
    ? resolveAutoDlWorkflow(chosenCapability.settings) : undefined;
  const autodlTier = videoParameters.videoResolution ?? chosenCapability?.settings.videoResolution ?? autodlWorkflow?.defaultResolution ?? "";
  const autodlTiers = chosenCapability?.adapterId === AUTODL_ADAPTER ? publishedAutoDlResolutions(chosenCapability.settings) : [];
  const autodlTierValid = !videoParameters.videoResolution || Boolean(autodlWorkflow) && autodlTiers.some((tier) => tier === autodlTier);
  const autodlInputsValid = !autodlWorkflow || imageCount >= autodlWorkflow.minimumImages
    && audioCount >= autodlWorkflow.minimumAudios
    && (autodlWorkflow.mode !== "START_END" || Boolean(endFrame))
    && Array.from(fields.prompt).length <= autodlWorkflow.promptLimit;
  const autodlRatioValid = !autodlWorkflow || autoDlRatioSupported(autodlWorkflow, autodlTier, videoParameters.aspectRatio);
  const videoModeSupported = artifact.kind !== "VIDEO" || effectiveMode !== null
    && (chosenCapability?.supportedVideoInputModes.includes(effectiveMode) ?? false);
  const workflowValues = workflowDraftValues(chosenCapability, fields);
  const dynamicErrors = workflow ? runningHubErrors(workflow, workflowValues, fields.prompt, duration, imageChoices) : [];
  const dynamicUsedVersions = workflow ? runningHubUsedVersions(workflow, workflowValues, fields.prompt, duration) : new Set<string>();
  const promptField = workflow?.fields.find((field) => field.source === "PROMPT");
  const promptCondition = workflow?.fields.find((field) => field.key === promptField?.enabledWhen?.field);
  const promptEnabled = !workflow || Boolean(promptField && (!promptField.enabledWhen
    || promptCondition && runningHubFieldValue(promptCondition, workflowValues, fields.prompt, duration) === promptField.enabledWhen.value));
  const promptValue = promptField ? runningHubFieldValue(promptField, workflowValues, fields.prompt, duration) : fields.prompt;
  const displayedPrompt = promptEnabled && typeof promptValue === "string" ? promptValue : fields.prompt;
  const supportsStyle = !isAudio && promptEnabled;
  const selectedStyle = styles.data?.find((style) => style.id === fields.styleId);
  const styleAvailable = !fields.styleId || supportsStyle && styles.isSuccess && Boolean(selectedStyle?.enabled);
  const historyPending = resources.isPending || imageHistories.some((history) => history.isPending);
  const validationMessages: string[] = [];
  if (workflow) {
    validationMessages.push(...dynamicErrors);
    if (fields.mediaInputs.length > INPUT_COLORS.length || fields.mediaInputs.some((input) => !dynamicUsedVersions.has(input.versionId))) validationMessages.push(t("media.editor.unassignedSlots"));
  }
  if (!runningHub) {
    if (promptEnabled && !fields.prompt.trim()) validationMessages.push(t("media.editor.promptRequired"));
    if (artifact.kind === "IMAGE" && !semanticInputsValid) validationMessages.push(t("media.editor.referenceLimits", { "0": imageCapacity, "1": audioCapacity }));
    if (!imageParametersSupported) validationMessages.push(t("media.editor.imageParametersUnsupported"));
    if (artifact.kind === "VIDEO" && !videoModeSupported) validationMessages.push(t("media.editor.unsupportedModeHint", { "0": VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label }));
    if (autodlWorkflow && !autodlInputsValid) validationMessages.push(t("media.editor.workflowLimitsHint", { "0": autodlWorkflow.minimumImages, "1": autodlWorkflow.minimumAudios, "2": autodlWorkflow.mode === "START_END" ? t("media.editor.startEndRequiredHint") : "", "3": autodlWorkflow.promptLimit }));
    if (!autodlTierValid) validationMessages.push(t("media.editor.unsupportedResolution"));
    if (autodlWorkflow && !autodlRatioValid) validationMessages.push(t("media.editor.workflowAspectRatioUnsupported"));
    if (isAudio && !semanticInputsValid) validationMessages.push(t("media.editor.audioInputLimitsHint"));
    if (artifact.kind === "VIDEO" && !validDuration) validationMessages.push(t("media.editor.durationValidation"));
    if (artifact.kind === "VIDEO" && !semanticInputsValid && !workflow) validationMessages.push(t("media.editor.invalidVideoInputs"));
  }
  if (fields.mediaInputs.length > 0 && !historyPending && (!resources.isSuccess || !allInputsAvailable)) validationMessages.push(t("media.editor.fixedVersionUnavailable"));
  // Draft validation is shown on submit; operational locks still disable the button.
  const canSubmit = !dirty && !save.isPending && !commitAssetReferences.isPending
    && !removeConnectedInput.isPending && !libraryBusy
    && !error && !run.isPending && !templateBusy && !uploading && !workflowUploading
    && directTasks.isSuccess && settings.isSuccess && Boolean(chosenCapability)
    && !occupied && styleAvailable && !historyPending;
  const dimensionLabel = artifact.kind === "IMAGE"
    ? `${ASPECT_RATIO_LABELS[imageParameters.aspectRatio]} · ${imageParameters.resolution}`
    : `${ASPECT_RATIO_LABELS[videoParameters.aspectRatio]}${autodlWorkflow ? ` · ${autodlTier}` : ""}`;
  const qualityLabel = artifact.kind === "IMAGE"
    ? supportedImageQualities.length
      ? QUALITY_LABELS[imageParameters.quality] : t("media.editor.modelDefault")
    : chosenCapability?.settings.quality ? t("media.editor.qualityLabel", { "0": QUALITY_LABELS[chosenCapability.settings.quality] }) : t("media.editor.defaultQuality");
  const historyError = imageHistories.find((history) => history.error)?.error;
  const normalizedAssetSearch = assetSearch.trim().toLocaleLowerCase();
  const workflowMediaFields = workflow ? activeWorkflowMediaFields(workflow, workflowValues, fields.prompt, duration) : [];
  const pickerField = workflowMediaFields.find((field) => field.key === workflowPickerField?.key);
  const emptyWorkflowFields = workflowMediaFields.filter((field) => {
    const value = runningHubFieldValue(field, workflowValues, fields.prompt, duration);
    return value === undefined || value === "";
  });
  const pickerKinds: Artifact["kind"][] = workflow
    ? [...new Set((pickerField ? [pickerField] : emptyWorkflowFields).map((field) => field.type as Artifact["kind"]))]
    : ["IMAGE", ...(audioCapacity > 0 ? ["AUDIO" as const] : []), ...(videoCapacity > 0 ? ["VIDEO" as const] : [])];
  const pickerChoices = workflow ? imageChoices.filter((choice) => pickerKinds.includes(choice.kind)) : imageChoices;
  const pickerCanvasChoices = workflow ? canvasChoices.filter((choice) => pickerKinds.includes(choice.kind)) : canvasChoices;
  const mixedPicker = pickerKinds.some((kind) => kind !== "IMAGE");
  const filteredImageChoices = normalizedAssetSearch
    ? pickerChoices.filter((choice) => choice.label.toLocaleLowerCase().includes(normalizedAssetSearch))
    : pickerChoices;
  const remainingAssetCapacity = Math.max(0, mediaCapacity - fields.mediaInputs.length);
  const saveLabel = removeConnectedInput.isPending ? t("media.editor.removingInput")
    : failedRemovalVersionId ? t("media.editor.removeInputFailed")
      : commitAssetReferences.isPending ? t("media.editor.resourcesAdding")
      : save.isPending ? t("common.saving") : dirty ? error ? t("media.editor.saveFailed") : t("media.editor.unsaved") : t("common.saved");
  const currentFields = fields;

  // The shared add entry assigns by media type; existing thumbnails target their named slot.
  function workflowTargetField(kind: Artifact["kind"] | undefined) {
    if (workflowPickerField) return pickerField?.type === kind ? pickerField : undefined;
    return emptyWorkflowFields.find((field) => field.type === kind);
  }

  function changeDynamicField(fieldKey: string, value: RunningHubValue | undefined) {
    if (!workflow || editorReadOnlyRef.current) return;
    const field = workflow.fields.find((item) => item.key === fieldKey);
    const latest = fieldsRef.current ?? currentFields;
    if (!field) return;
    if (field.source === "PROMPT") { edit({ prompt: typeof value === "string" ? value : "", mentions: [] }); return; }
    if (field.source === "DURATION_SECONDS") { edit({ durationSeconds: typeof value === "number" ? value : null }); return; }
    const dynamicValues = workflowDraftValues(chosenCapability, latest);
    const previousValue = dynamicValues[fieldKey];
    if (value === undefined) delete dynamicValues[fieldKey]; else dynamicValues[fieldKey] = value;
    const mediaField = ["IMAGE", "AUDIO", "VIDEO"].includes(field.type);
    const stillAssigned = workflow.fields.some((slot) => ["IMAGE", "AUDIO", "VIDEO"].includes(slot.type)
      && dynamicValues[slot.key] === previousValue);
    const removedVersion = mediaField && typeof previousValue === "string" && previousValue !== value && !stillAssigned
      ? previousValue : null;
    const inputs = latest.mediaInputs.filter((input) => input.versionId !== removedVersion);
    if (mediaField && typeof value === "string" && !inputs.some((input) => input.versionId === value)) {
      if (inputs.length >= INPUT_COLORS.length) { setError(new Error(t("media.editor.inputLimit"))); return; }
      const role = field.type === "VIDEO" ? "VIDEO_REFERENCE" : field.type === "AUDIO" ? "AUDIO_REFERENCE" : "REFERENCE";
      const color = INPUT_COLORS.find((candidate) => !inputs.some((input) => input.color === candidate)) ?? INPUT_COLORS[0];
      inputs.push({ versionId: value, role, color });
    }
    edit({ parameters: { ...latest.parameters, dynamicValues }, mediaInputs: inputs,
      ...promptForMediaInputs(latest, inputs),
      ...(artifact.kind === "VIDEO" ? { videoInputMode: inputs.length ? "GENERAL_REFERENCE" : preferredVideoMode(chosenCapability, false) } : {}) });
    // A linked input must also remove its persistent sources; retain the new local assignment
    // while that CAS operation runs, then save it against the acknowledged draft version.
    if (removedVersion && hasConnectionSource(removedVersion)) {
      removeConnectedInput.mutate({ versionId: removedVersion, allowDirty: true, preserveLocalChanges: true });
    }
  }

  async function uploadReferenceArtifact(file: File, kind: "IMAGE" | "AUDIO" | "VIDEO") {
    const progress = uploadProgress.current.get(file) ?? { createKey: crypto.randomUUID() };
    uploadProgress.current.set(file, progress);
    if (!progress.assetId) {
      const asset = await (kind === "VIDEO" ? uploadVideoAsset : kind === "AUDIO" ? uploadAudioAsset : uploadImageAsset)(artifact.projectId, file);
      progress.assetId = asset.id;
    }
    // Keep the archived bytes and command key until the caller has accepted the exact version.
    return createArtifact(artifact.projectId, { kind,
      title: uploadArtifactTitle(file), content: { sourceType: "UPLOAD", assetId: progress.assetId } }, progress.createKey);
  }

  async function uploadDynamicSlot(field: RunningHubField, file: File, options?: { requireEmptySlot: boolean }) {
    if (runningHub && file.size > MAX_RUNNINGHUB_INPUT_BYTES) throw new Error(t("media.editor.runningHubSizeLimit"));
    const scope = chosenCapabilityRef.current;
    const uploadToken = crypto.randomUUID();
    workflowUploads.current.add(uploadToken); setWorkflowUploading(true);
    try {
      const uploaded = await uploadReferenceArtifact(file, field.type === "VIDEO" ? "VIDEO" : field.type === "AUDIO" ? "AUDIO" : "IMAGE");
      await queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
      if (!uploaded.resourceDefaultVersionId) throw new Error(t("media.editor.uploadedVersionPending"));
      if (editorReadOnlyRef.current) throw new Error(t("media.editor.editingLocked"));
      const current = chosenCapabilityRef.current;
      if (!scope || current?.id !== scope.id || current.capabilityVersion !== scope.capabilityVersion
        || current.mappingSha256 !== scope.mappingSha256) throw new Error(t("media.editor.capabilityConflict"));
      let targetField = field;
      if (options?.requireEmptySlot) {
        // A canvas connection can fill the original slot while the upload is archiving.
        // Add to the next available slot instead of replacing that newer user input.
        const latest = fieldsRef.current;
        const definition = workflowDefinition(current);
        const values = latest ? workflowDraftValues(current, latest) : {};
        const active = latest && definition ? activeWorkflowMediaFields(definition, values, latest.prompt,
          latest.durationSeconds ?? current.settings.defaultDurationSeconds) : [];
        const empty = active.find((candidate) => {
          const value = runningHubFieldValue(candidate, values, latest?.prompt ?? "", latest?.durationSeconds ?? current.settings.defaultDurationSeconds);
          return candidate.type === field.type && (value === undefined || value === "");
        });
        if (!empty) throw new Error(t("media.workflow.noEmptySlot", { "0": field.type === "VIDEO" ? t("common.video") : field.type === "AUDIO" ? t("common.audio") : t("common.image") }));
        targetField = empty;
      }
      changeDynamicField(targetField.key, uploaded.resourceDefaultVersionId);
      uploadProgress.current.delete(file);
    } finally {
      workflowUploads.current.delete(uploadToken);
      setWorkflowUploading(workflowUploads.current.size > 0);
    }
  }

  function nextRole(inputs = currentFields.mediaInputs, mode = effectiveMode) {
    if (artifact.kind === "IMAGE" || isAudio || mode === "GENERAL_REFERENCE") return "REFERENCE" as const;
    if (mode === "START_END") return inputs.some((input) => input.role === "START_FRAME")
      ? "END_FRAME" as const : "START_FRAME" as const;
    return null;
  }

  function referenceKind(versionId: string) {
    return imageChoices.find((choice) => choice.id === versionId)?.kind
      ?? canvasChoices.find((choice) => choice.versionId === versionId)?.kind ?? uploadedKinds.current.get(versionId);
  }

  function canAddReference(kind: Artifact["kind"] | undefined, inputs: DraftFields["mediaInputs"]) {
    const audio = kind === "AUDIO";
    const video = kind === "VIDEO";
    const role = video ? "VIDEO_REFERENCE" : audio ? "AUDIO_REFERENCE" : "REFERENCE";
    const count = inputs.filter((input) => video || audio ? input.role === role
      : input.role !== "AUDIO_REFERENCE" && input.role !== "VIDEO_REFERENCE").length;
    if (!kind || inputs.length >= MAX_MEDIA_INPUTS || count >= (video ? videoCapacity : audio ? audioCapacity - (isAudio && audioSpeaker ? 1 : 0) : imageCapacity)) return false;
    if ((video || audio && artifact.kind === "VIDEO") && effectiveMode === "START_END") return false;
    if (isAudio && (audio ? inputs.some((input) => input.role !== "AUDIO_REFERENCE")
      : Boolean(audioSpeaker) || inputs.some((input) => input.role === "AUDIO_REFERENCE"))) return false;
    return true;
  }

  function fieldsWithReferences(versionIds: string[], baseFields = currentFields) {
    const nextInputs = [...baseFields.mediaInputs];
    const nextMode = modeForAddedReference(baseFields);
    for (const versionId of versionIds) {
      if (nextInputs.some((input) => input.versionId === versionId)) continue;
      const kind = referenceKind(versionId);
      if (!canAddReference(kind, nextInputs)) return baseFields;
      const audio = kind === "AUDIO";
      const role = kind === "VIDEO" && nextMode === "GENERAL_REFERENCE" ? "VIDEO_REFERENCE" : audio && (isAudio || nextMode === "GENERAL_REFERENCE") ? "AUDIO_REFERENCE" : nextRole(nextInputs, nextMode);
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

  function modeForAddedReference(baseFields: DraftFields) {
    if (artifact.kind !== "VIDEO") return effectiveMode;
    return baseFields.videoInputMode === null || baseFields.videoInputMode === "TEXT"
      ? preferredImageVideoMode(chosenCapability) : baseFields.videoInputMode;
  }

  function appendReferences(versionIds: string[], baseFields = currentFields) {
    if (editorReadOnlyRef.current) return;
    const next = fieldsWithReferences(versionIds, baseFields);
    if (next !== baseFields) edit({ mediaInputs: next.mediaInputs,
      ...(artifact.kind === "VIDEO" ? { videoInputMode: next.videoInputMode } : {}) });
  }

  function openAssetReferences() {
    setAssetSearch("");
    setAssetSelection([]);
    setAssetSelectionError(null);
    setPopover("assetReferences");
  }

  function chooseWorkflowReference(versionId: string) {
    const field = workflowTargetField(referenceKind(versionId));
    if (!field || editorReadOnlyRef.current || libraryBusy) return;
    changeDynamicField(field.key, versionId);
    setPopover(null);
    triggerRef.current?.focus();
  }

  function toggleAssetReference(versionId: string) {
    setAssetSelectionError(null);
    setAssetSelection((current) => {
      if (current.includes(versionId)) return current.filter((candidate) => candidate !== versionId);
      const pending = fieldsWithReferences(current).mediaInputs;
      return canAddReference(referenceKind(versionId), pending) ? [...current, versionId] : current;
    });
  }

  function confirmAssetReferences() {
    if (editorReadOnlyRef.current) return;
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
    if (editorReadOnlyRef.current) return;
    const remaining = Math.max(0, mediaCapacity - currentFields.mediaInputs.length);
    if (files.length > remaining) {
      setUploadError(new Error(t("media.editor.remainingImageLimit", { "0": remaining })));
      setFailedUploads([]);
      return;
    }
    const selectedAudioCount = files.filter(isAudioFile).length;
    const selectedVideoCount = files.filter(isVideoFile).length;
    const selectedImageCount = files.length - selectedAudioCount - selectedVideoCount;
    if (files.length + currentFields.mediaInputs.length > MAX_MEDIA_INPUTS || selectedVideoCount + videoCount > videoCapacity || (selectedVideoCount > 0 || selectedAudioCount > 0 && !isAudio) && effectiveMode === "START_END"
        || selectedImageCount + imageCount > imageCapacity || selectedAudioCount + audioCount + (isAudio && audioSpeaker ? 1 : 0) > audioCapacity
        || isAudio && selectedImageCount + imageCount > 0 && (selectedAudioCount + audioCount > 0 || Boolean(audioSpeaker))) {
      setUploadError(new Error(t("media.editor.invalidMixedInputs"))); setFailedUploads([]); return;
    }
    setUploading(true);
    setUploadError(null);
    const successfulVersions: string[] = [];
    const failures: File[] = [];
    let firstFailure: Error | null = null;
    for (const file of files) {
      const audio = isAudioFile(file);
      try {
        const uploadedArtifact = await uploadReferenceArtifact(file, isVideoFile(file) ? "VIDEO" : audio ? "AUDIO" : "IMAGE");
        if (!uploadedArtifact.resourceDefaultVersionId) {
          throw new Error(t("media.editor.uploadedVersionUnavailable", { "0": file.name }));
        }
        uploadedKinds.current.set(uploadedArtifact.resourceDefaultVersionId, uploadedArtifact.kind === "VIDEO" ? "VIDEO" : uploadedArtifact.kind === "AUDIO" ? "AUDIO" : "IMAGE");
        successfulVersions.push(uploadedArtifact.resourceDefaultVersionId);
        uploadProgress.current.delete(file);
      } catch (failure) {
        failures.push(file);
        firstFailure ??= failure instanceof Error ? failure : new Error(t("api.errors.imageUploadFailed"));
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
    if (editorReadOnlyRef.current) return;
    const persisted = draft.data?.mediaInputs.find((input) => input.versionId === versionId);
    if (persisted?.sources.some((source) => source.type === "CONNECTION"
        && source.connectionId)) {
      removeConnectedInput.mutate({ versionId });
      return;
    }
    const dynamicValues = workflowDraftValues(chosenCapability, currentFields);
    if (workflow) for (const field of workflow.fields) {
      if (["IMAGE", "AUDIO", "VIDEO"].includes(field.type) && dynamicValues[field.key] === versionId) delete dynamicValues[field.key];
    }
    const nextPrompt = removePromptReferences(currentFields.prompt, currentFields.mentions, versionId);
    const remaining = currentFields.mediaInputs.filter((input) => input.versionId !== versionId);
    const nextMode = artifact.kind === "VIDEO" && remaining.length === 0 ? preferredVideoMode(chosenCapability, false) : effectiveMode;
    edit({ mediaInputs: remaining,
      ...(workflow ? { parameters: { ...currentFields.parameters, dynamicValues } } : {}),
      ...(artifact.kind === "VIDEO" ? { videoInputMode: nextMode } : {}), ...nextPrompt });
  }

  function chooseVideoMode(mode: VideoInputMode) {
    if (editorReadOnlyRef.current) return;
    if (artifact.kind !== "VIDEO" || mode === "TEXT" && currentFields.mediaInputs.length > 0) return;
    const nextInputs = inputsForVideoMode(currentFields.mediaInputs, mode);
    if (nextInputs.length < currentFields.mediaInputs.length
        && !window.confirm(t("media.editor.startEndConversionConfirmation"))) return;
    edit({ videoInputMode: mode, mediaInputs: nextInputs, ...promptForMediaInputs(currentFields, nextInputs) });
    setPopover(null);
    triggerRef.current?.focus();
  }

  function moveReferenceTo(index: number, target: number) {
    if (editorReadOnlyRef.current) return;
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

  async function applyTemplate(imported: MediaTemplateImport, options: TemplateApplyOptions) {
    if (artifact.kind !== "IMAGE" && artifact.kind !== "VIDEO" || imported.targetKind !== artifact.kind) {
      throw new Error(t("templates.kindMismatch"));
    }
    const pending = pendingSaveRef.current;
    const settledSave = pending ? await pending.result : undefined;
    const latest = fieldsRef.current;
    const acknowledgedVersion = settledSave?.version ?? expectedVersionRef.current;
    if (!latest || acknowledgedVersion === null) throw new Error(t("media.editor.waitForDraft"));
    if (JSON.stringify(latest) !== JSON.stringify(currentFields)) throw new Error(t("templates.draftChanged"));
    // Calculate the complete replacement before any current reference or line is removed.
    const changes = templateDraftChanges(artifact.kind, latest, chosenCapability, imported, options, INPUT_COLORS);
    const freshResources = await queryClient.fetchQuery({ queryKey: ["artifacts", artifact.projectId],
      queryFn: () => listArtifacts(artifact.projectId), staleTime: 0 });
    await Promise.all(freshResources.items.filter((item) => item.kind === "IMAGE").map((item) =>
      queryClient.fetchQuery({ queryKey: ["artifact-versions", artifact.projectId, item.id],
        queryFn: () => listArtifactVersions(artifact.projectId, item.id), staleTime: 0 })));
    if (editorReadOnlyRef.current) throw new Error(t("media.editor.editingLocked"));
    if (imported.images.length > 0) {
      // Reference replacement and connected-line removal either commit together or leave the old draft intact.
      const acknowledged = await replaceMediaDraftInputs(artifact.projectId, canvasItemId,
        { ...latest, ...changes, expectedVersion: acknowledgedVersion });
      const retained = fieldsFromDraft(acknowledged);
      fieldsRef.current = retained; setFields(retained); setExpectedVersion(acknowledged.version);
      setDirty(false); setError(null); queryClient.setQueryData(key, acknowledged);
      runIntent.current = null; if (!run.isPending) run.reset();
      await Promise.all([queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] })]);
      return;
    }
    edit(changes);
  }

  return <div className="media-draft-editor" aria-label={t("media.editor.title")}>
    {stylePickerOpen ? <MediaStylePicker selected={fields.styleId ?? null} onClose={() => setStylePickerOpen(false)}
      onSelect={(styleId) => edit({ styleId })} /> : null}
    {workflow ? <WorkflowParametersDialog open={extendedParametersOpen} onOpenChange={setExtendedParametersOpen}
      definition={workflow} values={workflowValues} prompt={fields.prompt} durationSeconds={duration}
      disabled={editorReadOnly || run.isPending} onChange={changeDynamicField} /> : null}
    <fieldset className="media-draft-controls" disabled={editorReadOnly}>
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      {(artifact.kind === "IMAGE" || artifact.kind === "VIDEO") ? <Button variant="ghost" className="media-draft-tab" type="button"
        disabled={!promptEnabled || templateBusy || save.isPending || run.isPending || commitAssetReferences.isPending || removeConnectedInput.isPending || libraryBusy || Boolean(error)}
        onClick={() => { setPopover(null); setReferenceSourcesOpen(false); setTemplateOpen(true); }}>{t("templates.entry")}</Button> : null}
      {isAudio && onOpenAgentConversation ? <Button variant="ghost" className="media-draft-tab"
        type="button" onClick={onOpenAgentConversation} disabled={openingAgentConversation}
        title={t("media.editor.openAudioAgent")}>{openingAgentConversation ? t("media.editor.opening") : t("media.editor.agentConversation")}</Button> : null}
      {!runningHub && isAudio ? <AudioPromptTools projectId={artifact.projectId} canvasItemId={canvasItemId}
        prompt={fields.prompt} hasMentions={fields.mentions.length > 0} onApply={(prompt) => edit({ prompt, mentions: [] })} /> : null}
      <span className={`media-draft-save-state${error ? " is-error" : ""}`} role="status">{saveLabel}</span>
    </div>
    {templateOpen && (artifact.kind === "IMAGE" || artifact.kind === "VIDEO") ? <MediaTemplatePicker projectId={artifact.projectId}
      targetKind={artifact.kind} fields={fields} capability={chosenCapability}
      seedImages={selectedReferences.flatMap(({ input, choice }) => choice?.kind === "IMAGE" ? [{ versionId: input.versionId,
        title: choice.title, thumbnailUrl: assetThumbnailUrl(artifact.projectId, choice.assetId) }] : [])}
      onApply={applyTemplate} onBusy={setTemplateBusy} onClose={() => setTemplateOpen(false)} /> : null}
    <div ref={referencePickerAnchorRef} className="media-draft-reference-picker-anchor">
    {!workflow ? <div className="media-draft-reference-row" aria-label={audioCapacity > 0 ? t("media.editor.mixedInputs") : t("media.editor.imageInputs")}>
      <div className="media-draft-popover-anchor">
        <Input ref={uploadInputRef} className="media-draft-upload-input" type="file"
          accept={[MEDIA_FILE_ACCEPT.IMAGE, ...(audioCapacity > 0 ? [MEDIA_FILE_ACCEPT.AUDIO] : []), ...(videoCapacity > 0 ? [MEDIA_FILE_ACCEPT.VIDEO] : [])].join(",")} multiple aria-label={videoCapacity > 0 ? t("media.editor.chooseLocalAllMedia") : audioCapacity > 0 ? t("media.editor.chooseLocalMedia") : t("media.editor.chooseLocalImage")} onChange={handleUploadSelection} />
        <MediaReferenceSourceMenu open={referenceSourcesOpen && !popover} onOpenChange={setReferenceSourcesOpen} suspended={Boolean(popover)}
          disabled={editorReadOnly || !chosenCapability || referenceLimitReached || uploading || commitAssetReferences.isPending}
          libraryDisabled={save.isPending || expectedVersion === null}
          label={videoCapacity > 0 ? t("media.editor.addVideoMediaInput") : audioCapacity > 0 ? t("media.editor.addMixedInput") : t("media.editor.addImageInput")}
          title={uploading ? t("media.editor.assetUploading") : videoCapacity > 0 ? t("media.editor.videoReferenceLimits", { "0": imageCapacity, "1": videoCapacity, "2": audioCapacity }) : t("media.editor.referenceLimits", { "0": imageCapacity, "1": audioCapacity })}
          onTrigger={(trigger) => { triggerRef.current = trigger; }}
          onChoose={(source) => {
            setWorkflowPickerField(null);
            if (source === "upload") uploadInputRef.current?.click();
            else if (source === "resources") openAssetReferences();
            else setPopover(source === "canvas" ? "canvasReferences" : "libraryReferences");
          }} />
      </div>

      <div className="media-draft-reference-list" role="list" aria-label={audioCapacity > 0 ? t("media.editor.selectedReferences") : t("media.editor.selectedImages")}>
        {selectedReferences.map(({ input, choice }, index) => <MediaReferenceThumbnail
          key={input.versionId} index={index} audio={input.role === "AUDIO_REFERENCE"} color={input.color}
          accessibleLabel={choice?.label ?? t("media.editor.numberedImageInput", { "0": index + 1 })}
          {...(choice && choice.kind !== "AUDIO" ? { thumbnailUrl: choice.kind === "VIDEO" ? assetThumbnailUrl(artifact.projectId, choice.assetId) : assetContentUrl(artifact.projectId, choice.assetId) } : {})}
          connected={hasConnectionSource(input.versionId)}
          busy={editorReadOnly || removeConnectedInput.isPending || commitAssetReferences.isPending || dirty || save.isPending}
          reorderable={!editorReadOnly && effectiveMode !== "START_END"}
          onMove={(delta) => moveReferenceTo(index, index + delta)}
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
    : <WorkflowMediaInputs definition={workflow} values={workflowValues} prompt={fields.prompt}
      durationSeconds={duration} choices={imageChoices.map((choice) => ({ ...choice,
        thumbnailUrl: choice.kind === "AUDIO" ? undefined : assetThumbnailUrl(artifact.projectId, choice.assetId) }))}
      canvasChoices={canvasChoices.map((choice) => ({ id: choice.versionId, label: `${choice.title} · v${choice.versionNo}`,
        kind: choice.kind, available: true, thumbnailUrl: choice.kind === "AUDIO" ? undefined : assetThumbnailUrl(artifact.projectId, choice.assetId) }))}
      disabled={editorReadOnly || run.isPending || removeConnectedInput.isPending || libraryBusy}
      libraryDisabled={save.isPending || expectedVersion === null || workflowUploading} pickerOpen={Boolean(popover)}
      onChooseSource={(field, source, trigger) => {
        if (source === "library" && workflowUploads.current.size > 0) return;
        triggerRef.current = trigger; setWorkflowPickerField(field);
        if (source === "resources") openAssetReferences();
        else setPopover(source === "canvas" ? "canvasReferences" : "libraryReferences");
      }} onChange={changeDynamicField} onUpload={uploadDynamicSlot} />}
        {popover === "libraryReferences" && expectedVersion !== null ? <div className="ui-popover-surface media-draft-popover media-draft-library-popover" ref={popoverRef} role="dialog" aria-label={t("media.editor.libraryReferences")}>
          <Button variant="ghost" type="button" disabled={libraryBusy} onClick={() => setPopover(null)}>{t("media.editor.closePicker")}</Button>
          <LibraryReferencePicker key={pickerField?.key ?? "references"} projectId={artifact.projectId} itemId={canvasItemId}
            slotKey={pickerField?.key}
            kinds={pickerKinds} draft={{ ...fields, expectedVersion }} onBusy={setLibraryBusy}
            plan={(entry) => {
              if (workflow) {
                const field = workflowTargetField(entry.kind);
                if (!field) return null;
                const color = INPUT_COLORS.find((candidate) => !fields.mediaInputs.some((input) => input.color === candidate)) ?? INPUT_COLORS[0];
                return { role: entry.kind === "VIDEO" ? "VIDEO_REFERENCE" : entry.kind === "AUDIO" ? "AUDIO_REFERENCE" : "REFERENCE",
                  color, videoInputMode: fields.videoInputMode, slotKey: field.key };
              }
              if (!canAddReference(entry.kind, fields.mediaInputs)) return null;
              const audio = entry.kind === "AUDIO";
              const mode = modeForAddedReference(fields);
              const role = entry.kind === "VIDEO" ? (mode === "GENERAL_REFERENCE" ? "VIDEO_REFERENCE" as const : null) : audio ? (isAudio || mode === "GENERAL_REFERENCE" ? "AUDIO_REFERENCE" as const : null) : nextRole(fields.mediaInputs, mode);
              if (!role || role === "END_FRAME" && !chosenCapability?.supportsEndFrame) return null;
              const color = INPUT_COLORS.find((candidate) => !fields.mediaInputs.some((input) => input.color === candidate)) ?? INPUT_COLORS[0];
              return { role, color, videoInputMode: mode };
            }} onApplied={(saved, submitted, assignedSlotKey) => {
              const latest = fieldsRef.current;
              const changed = latest !== null && JSON.stringify(latest) !== JSON.stringify(Object.fromEntries(Object.entries(submitted).filter(([name]) => name !== "expectedVersion")));
              const savedFields = fieldsFromDraft(saved);
              const oldVersions = new Set(submitted.mediaInputs.map((input) => input.versionId));
              let next = savedFields;
              if (changed && latest) {
                if (assignedSlotKey) {
                  const values = workflowDraftValues(chosenCapability, latest);
                  const savedValue = workflowDraftValues(chosenCapability, savedFields)[assignedSlotKey];
                  if (savedValue === undefined) delete values[assignedSlotKey]; else values[assignedSlotKey] = savedValue;
                  next = { ...latest, videoInputMode: saved.videoInputMode,
                    parameters: { ...latest.parameters, dynamicValues: values }, mediaInputs: savedFields.mediaInputs };
                } else next = { ...latest, videoInputMode: saved.videoInputMode,
                  mediaInputs: [...latest.mediaInputs, ...savedFields.mediaInputs.filter((input) => !oldVersions.has(input.versionId))] };
              }
              fieldsRef.current = next; setFields(next); setExpectedVersion(saved.version); setDirty(changed); setError(null);
              queryClient.setQueryData(key, saved); setPopover(null);
              void queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
            }} />
        </div> : null}
        {popover === "assetReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-asset-references`} role="dialog" aria-label={mixedPicker ? t("media.editor.inputMediaVersions") : t("media.editor.inputImageVersions")}>
          <p className="media-draft-popover-title">{pickerField?.label ?? (mixedPicker ? t("media.editor.chooseMixedVersion") : t("media.editor.chooseImageVersion"))}</p>
          <label htmlFor={`${id}-asset-search`}>{mixedPicker ? t("media.editor.searchMedia") : t("media.editor.searchImages")}</label>
          <Input id={`${id}-asset-search`} type="search" value={assetSearch}
            placeholder={t("media.editor.searchPlaceholder")}
            onChange={(event) => setAssetSearch(event.target.value)} />
          <div className="media-draft-reference-options" role="group" aria-label={mixedPicker ? t("media.editor.mediaVersions") : t("media.editor.imageVersions")}>
            {filteredImageChoices.map((choice) => {
              const alreadyAdded = workflow ? Boolean(pickerField && workflowValues[pickerField.key] === choice.id) : fields.mediaInputs.some((input) => input.versionId === choice.id);
              const selected = assetSelection.includes(choice.id);
              const selectionFull = !workflow && !selected && !canAddReference(referenceKind(choice.id), fieldsWithReferences(assetSelection).mediaInputs);
              return <button key={choice.id} type="button" role={workflow ? "button" : "checkbox"}
              className="media-draft-reference-option" aria-label={t("media.editor.selectNamed", { "0": choice.label })}
              {...(workflow ? { "aria-pressed": alreadyAdded } : { "aria-checked": alreadyAdded || selected })}
              disabled={editorReadOnly || !choice.available || (!workflow && alreadyAdded) || selectionFull || commitAssetReferences.isPending}
              onClick={() => workflow ? chooseWorkflowReference(choice.id) : toggleAssetReference(choice.id)}>
              {/* Video references use archived covers; images preserve their existing preview. */}
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={(choice.kind === "VIDEO" ? assetThumbnailUrl : assetContentUrl)(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>v{choice.versionNo} · {choice.current ? t("media.editor.selectedVersion") : t("media.editor.historicalVersions")}</small></span>
              {alreadyAdded || selected ? <Check size={15} /> : null}
            </button>;
            })}
          </div>
          {historyPending ? <CanvasLoadingState compact label={t("media.editor.imageVersionsLoading")} /> : null}
          {!historyPending && !resources.error && !historyError && !pickerChoices.length ? <p>{t("media.editor.imagesEmpty")}</p> : null}
          {!historyPending && !resources.error && !historyError && pickerChoices.length > 0
            && !filteredImageChoices.length ? <p>{t("media.editor.imageVersionsEmpty")}</p> : null}
          {resources.error || historyError ? <div role="alert">{t("media.editor.imageVersionsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => {
              void resources.refetch();
              imageHistories.forEach((history) => { void history.refetch(); });
            }} type="button">{t("media.editor.retryImages")}</Button></div> : null}
          {assetSelectionError ? <div className="media-draft-reference-error" role="alert">
            {t("media.editor.batchSelectionFailed")}</div> : null}
          {!workflow ? <p>{t("media.editor.selectionOrderHint", { "0": Math.max(0,
            remainingAssetCapacity - assetSelection.length) })}</p> : null}
          <div className="media-draft-reference-actions">
            <Button variant="ghost" type="button" disabled={commitAssetReferences.isPending} onClick={() => setPopover(null)}>{t("common.cancel")}</Button>
            {!workflow ? <Button variant="ghost" type="button" className="is-primary"
              disabled={!assetSelection.length || save.isPending || commitAssetReferences.isPending}
              onClick={confirmAssetReferences}>
              {commitAssetReferences.isPending ? t("common.adding") : t("media.editor.addSelected", { "0": mixedPicker ? t("media.editor.assets") : t("common.image"), "1": assetSelection.length })}
            </Button> : null}
          </div>
        </div> : null}
        {popover === "canvasReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-canvas-references`} role="dialog" aria-label={mixedPicker ? t("media.editor.chooseCanvasMedia") : t("media.editor.chooseCanvasImage")}>
          <p className="media-draft-popover-title">{pickerField?.label ?? (mixedPicker ? t("media.editor.canvasMedia") : t("media.editor.otherCanvasImages"))}</p>
          <div className="media-draft-reference-options">
            {pickerCanvasChoices.map((choice) => <Button variant="ghost" key={choice.canvasItemId} type="button"
              className="media-draft-reference-option" aria-label={t("media.editor.useCanvasMedia", { "0": choice.kind === "VIDEO" ? t("common.video") : choice.kind === "AUDIO" ? t("common.audio") : t("common.image"), "1": choice.title })}
              aria-pressed={workflow ? Boolean(pickerField && workflowValues[pickerField.key] === choice.versionId) : fields.mediaInputs.some((input) => input.versionId === choice.versionId)}
              disabled={editorReadOnly || !workflow && (fields.mediaInputs.some((input) => input.versionId === choice.versionId) || !canAddReference(referenceKind(choice.versionId), fields.mediaInputs))}
              onClick={() => workflow ? chooseWorkflowReference(choice.versionId) : appendReferences([choice.versionId])}>
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={(choice.kind === "VIDEO" ? assetThumbnailUrl : assetContentUrl)(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>{t("media.editor.canvasSelectedVersion", { "0": choice.versionNo })}</small></span>
              {(workflow ? pickerField && workflowValues[pickerField.key] === choice.versionId : fields.mediaInputs.some((input) => input.versionId === choice.versionId)) ? <Check size={15} /> : null}
            </Button>)}
          </div>
          {canvas.isPending ? <CanvasLoadingState compact label={t("media.editor.canvasImagesLoading")} /> : null}
          {canvas.isSuccess && !pickerCanvasChoices.length ? <p>{t("media.editor.canvasImagesEmpty")}</p> : null}
          {canvas.error ? <div role="alert">{t("media.editor.canvasImagesFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void canvas.refetch()}
              type="button">{t("media.editor.retryCanvas")}</Button></div> : null}
          <p>{t("media.editor.selectedCanvasVersionsHint")}</p>
        </div> : null}
    </div>
    <PromptMentionEditor id={`${id}-prompt`}
      label={isAudio ? t("media.editor.audioPrompt") : artifact.kind === "IMAGE" ? t("media.editor.imagePrompt") : t("media.editor.videoPrompt")}
      placeholder={!promptEnabled ? t("media.workflow.noPrompt") : promptField?.description || (isAudio ? t("media.editor.audioPromptPlaceholder") : artifact.kind === "IMAGE" ? t("media.editor.imagePromptPlaceholder") : t("media.editor.videoPromptPlaceholder"))}
      prompt={displayedPrompt} mentions={displayedPrompt === fields.prompt ? fields.mentions : []} references={promptReferences} readOnly={editorReadOnly || !promptEnabled}
      maxLength={promptField?.maxLength ?? MAX_PROMPT_LENGTH}
      onChange={(prompt, mentions) => edit({ prompt, mentions })} />
    <div className="media-draft-toolbar">
      {!workflow && artifact.kind === "VIDEO" ? <DropdownMenu open={popover === "modes"} onOpenChange={(open) => { if (!editorReadOnly && !libraryBusy) setPopover(open ? "modes" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-mode-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-mode-trigger" type="button"
          disabled={editorReadOnly} aria-label={t("media.editor.chooseVideoMode")} aria-haspopup="menu" aria-expanded={popover === "modes"}
          aria-controls={`${id}-modes`} onPointerDown={(event) => { triggerRef.current = event.currentTarget; }}>
          <VideoCamera size={17} /><span>{VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label
            ?? t("media.editor.chooseInputMode")}</span><CaretDown size={12} />
        </Button></DropdownMenuTrigger>
        {popover === "modes" ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-modes" ref={popoverRef}
          id={`${id}-modes`} role="menu" aria-label={t("media.editor.videoInputMode")}><DropdownMenuGroup>
          <p className="media-draft-popover-title">{t("media.editor.videoMode")}</p>
          {VIDEO_MODE_OPTIONS.map((option) => {
            const missingImage = option.value === "START_END" ? imageCount === 0
              : option.needsImage && fields.mediaInputs.length === 0;
            const hasImagesForText = option.value === "TEXT" && fields.mediaInputs.length > 0;
            const unsupported = !chosenCapability?.supportedVideoInputModes.includes(option.value);
            const disabled = hasImagesForText || unsupported;
            const reason = unsupported ? t("media.editor.unsupportedModel") : hasImagesForText ? t("media.editor.automaticModeHint")
              : missingImage ? t("media.editor.requiresImage") : option.value === "START_END" && autodlWorkflow ? t("media.editor.startEndRequired") : option.description;
            return <DropdownMenuItem className="media-draft-model-option" role="menuitemradio" aria-checked={effectiveMode === option.value} disabled={disabled} title={reason} key={option.value} onSelect={(event) => { event.preventDefault(); chooseVideoMode(option.value); }}>
              <span><strong>{option.label}</strong><small>{reason}</small></span>
              {effectiveMode === option.value ? <Check size={16} /> : null}
            </DropdownMenuItem>;
          })}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu> : null}
      <DropdownMenu open={popover === "models"} onOpenChange={(open) => { if (!editorReadOnly && !libraryBusy) setPopover(open ? "models" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-model-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-model-trigger" type="button"
          disabled={editorReadOnly} aria-label={t("media.editor.chooseModel")} aria-haspopup="menu" aria-expanded={popover === "models"}
          aria-controls={`${id}-models`} onPointerDown={(event) => { triggerRef.current = event.currentTarget; }}>
          <Cube size={17} /><span>{settings.isPending ? t("models.loading") : settings.error ? t("models.loadFailed") : chosenCapability?.name
            ?? (fields.capabilityId ? t("media.editor.modelUnavailable") : t("media.editor.defaultNotConfigured"))}</span><CaretDown size={12} />
        </Button></DropdownMenuTrigger>
        {popover === "models" ? <DropdownMenuContent aria-labelledby={undefined} side="top" align="start" onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-models p-3" ref={focusModelMenu}
          id={`${id}-models`} role="menu" aria-label={t("media.editor.model")}><DropdownMenuGroup>
          <p className="media-draft-popover-title">{artifact.kind === "IMAGE" ? t("media.editor.imageModel") : isAudio ? t("media.editor.audioModel") : t("media.editor.videoModel")}</p>
          {availableCapabilities.map((capability) => <DropdownMenuItem variant="rich" className="media-draft-model-option" role="menuitemradio" aria-checked={chosenCapability?.id === capability.id} key={capability.id} onSelect={(event) => { event.preventDefault(); chooseCapability(capability.id); }}>
            <OptionContent {...mediaModelDetails(capability, capability.connectionName)} title={capability.name}
              note={capability.mock ? t("media.editor.mock") : undefined} />
            {chosenCapability?.id === capability.id ? <Check size={16} /> : null}
          </DropdownMenuItem>)}
          {settings.isPending ? <p role="status">{t("media.editor.availableModelsLoading")}</p> : null}
          {settings.error ? <div role="alert">{t("media.editor.modelsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("media.editor.retryModels")}</Button></div> : null}
          {settings.isSuccess && !availableCapabilities.length ? <p>{t("media.editor.noModelsHint")}</p> : null}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu>
      {supportsStyle || fields.styleId ? <Button variant="ghost" className="media-draft-toolbar-button" type="button" aria-label={t("styles.choose")}
        disabled={!supportsStyle}
        aria-haspopup="dialog" onClick={() => { setPopover(null); setReferenceSourcesOpen(false); setStylePickerOpen(true); }}>
        <PaintBrush data-icon="inline-start" /><span>{fields.styleId ? selectedStyle?.name ?? t("styles.unavailable") : t("styles.title")}</span>
      </Button> : null}
      {fields.styleId ? <Button variant="ghost" className="media-draft-toolbar-button" size="icon-sm" type="button"
        aria-label={t("styles.clear")} onClick={() => edit({ styleId: null })}><X /></Button> : null}
      {workflow ? <Button variant="ghost" className="media-draft-toolbar-button" type="button"
        aria-label={t("media.workflow.extendedParameters")} aria-haspopup="dialog"
        aria-expanded={extendedParametersOpen} onClick={() => { setPopover(null); setExtendedParametersOpen(true); }}>
        <SlidersHorizontal size={16} /><span>{t("media.workflow.extendedParameters")}</span><CaretDown size={12} />
      </Button> : null}
      {!runningHub ? <div className="media-draft-popover-anchor media-draft-parameters-anchor">
        <Button variant="ghost" className="media-draft-toolbar-button" type="button" aria-label={isAudio ? t("media.editor.audioParameters") : t("media.editor.sizeQuality")}
          aria-expanded={popover === "parameters"} aria-controls={`${id}-parameters`}
          onClick={(event) => togglePopover("parameters", event.currentTarget)}>
          <SlidersHorizontal size={16} /><span>{isAudio ? t("media.editor.audioControls") : `${artifact.kind === "VIDEO" ? t("media.editor.durationPrefix", { "0": duration ?? "—" }) : ""}${dimensionLabel} · ${qualityLabel}${artifact.kind === "IMAGE" ? t("media.editor.imageCountSuffix", { "0": imageParameters.generationCount }) : ""}`}</span><CaretDown size={12} />
        </Button>
        {popover === "parameters" ? <div className="ui-popover-surface media-draft-popover media-draft-parameters" ref={popoverRef}
          tabIndex={-1} id={`${id}-parameters`} role="dialog" aria-label={t("media.editor.sizeQualitySettings")}>
          <p className="media-draft-popover-title">{t("media.editor.parameters")}</p>
          {isAudio ? <div className="media-draft-audio-parameters">
            {([{ key: "speechRate", label: t("media.editor.speechRate"), min: -50, max: 100 },
              { key: "loudnessRate", label: t("media.editor.loudnessRate"), min: -50, max: 100 },
              { key: "pitchRate", label: t("media.editor.pitchRate"), min: -12, max: 12 }] as const).map((control) =>
              <label key={control.key}>{control.label}<Input type="number" min={control.min} max={control.max} step={1}
                value={fields.parameters[control.key] ?? 0} onChange={(event) => edit({ parameters: {
                  ...fields.parameters, [control.key]: Number(event.target.value) } })} /></label>)}
            <p className="ui-muted">{t("media.editor.audioDurationHint")}</p>
          </div> : artifact.kind === "IMAGE" ? <div className="media-draft-image-parameters">
            <FieldSet><FieldLegend>{t("media.editor.aspectRatio")}</FieldLegend><ToggleGroup type="single" value={imageParameters.aspectRatio} className="media-draft-choice-grid media-draft-aspect-grid" onValueChange={(selected) => {
              const next = ASPECT_RATIO_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...fields.parameters, ...imageParameters, aspectRatio: next } });
            }}>
              {ASPECT_RATIO_OPTIONS.filter((value) => supportedImageAspectRatios.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>
                  <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                  <small>{ASPECT_RATIO_LABELS[value]}</small>
                </ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <FieldSet><FieldLegend>{t("media.editor.resolution")}</FieldLegend><ToggleGroup type="single" value={imageParameters.resolution} className="media-draft-segmented" onValueChange={(selected) => {
              const next = RESOLUTION_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...fields.parameters, ...imageParameters, resolution: next } });
            }}>
              {RESOLUTION_OPTIONS.filter((value) => supportedImageResolutions.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <div className="media-draft-switch-row"><span>{t("media.editor.transparentBackground")}</span><Switch
              aria-label={t("media.editor.transparentBackground")}
              checked={imageParameters.transparentBackground}
              disabled={!chosenCapability?.supportsTransparentBackground}
              title={chosenCapability?.supportsTransparentBackground ? undefined : t("media.editor.transparencyUnsupported")}
              onCheckedChange={(checked) => edit({ parameters: { ...fields.parameters, ...imageParameters,
                transparentBackground: checked } })} /></div>
            <FieldSet><FieldLegend>{t("media.editor.quality")}</FieldLegend>{supportedImageQualities.length
              ? <ToggleGroup type="single" value={imageParameters.quality} className="media-draft-segmented" onValueChange={(selected) => {
              const next = QUALITY_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...fields.parameters, ...imageParameters, quality: next } });
            }}>{QUALITY_OPTIONS
                .filter((value) => supportedImageQualities.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{QUALITY_LABELS[value]}</ToggleGroupItem>)}</ToggleGroup>
              : <p className="media-draft-fixed-parameter">{t("media.editor.fixedByModel")}</p>}</FieldSet>
            <FieldSet><FieldLegend>{t("media.editor.batchSize")}</FieldLegend><ToggleGroup type="single" value={String(imageParameters.generationCount)} className="media-draft-segmented" onValueChange={(selected) => {
              const next = GENERATION_COUNT_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...fields.parameters, ...imageParameters, generationCount: next } });
            }}>
              {GENERATION_COUNT_OPTIONS.map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
          </div> : <div className="media-draft-video-parameters">
            <FieldSet><FieldLegend>{t("media.editor.aspectRatio")}</FieldLegend><ToggleGroup type="single" value={videoParameters.aspectRatio} className="media-draft-choice-grid media-draft-video-aspect-grid" onValueChange={(selected) => {
              const next = VIDEO_ASPECT_RATIO_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...fields.parameters, ...videoParameters, aspectRatio: next } });
            }}>
              {VIDEO_ASPECT_RATIO_OPTIONS.filter((value) => !autodlWorkflow || autoDlRatioSupported(autodlWorkflow, autodlTier, value)).map((value) => <ToggleGroupItem key={value} value={String(value)}
                aria-label={ASPECT_RATIO_LABELS[value]}>
                <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                <small>{ASPECT_RATIO_LABELS[value]}</small>
              </ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            {autodlWorkflow ? <FieldSet><FieldLegend>{t("media.editor.resolution")}</FieldLegend>
              <ToggleGroup type="single" value={autodlTier} className="media-draft-segmented" onValueChange={(selected) => {
                const next = autodlTiers.find((tier) => tier === selected);
                if (next) edit({ parameters: { ...fields.parameters, ...videoParameters, videoResolution: next } });
              }}>{autodlTiers.map((tier) => <ToggleGroupItem key={tier} value={tier}>{tier}</ToggleGroupItem>)}</ToggleGroup>
            </FieldSet> : null}
            <p className="media-draft-fixed-parameter">{t("media.editor.fixedVideoQuality")}</p>
          </div>}
          {artifact.kind === "VIDEO" ? <div className="media-draft-duration"><label htmlFor={`${id}-duration`}>{t("media.editor.durationSeconds")}</label>
            <Input id={`${id}-duration`} aria-describedby={chosenCapability ? `${id}-duration-help` : undefined}
              min={Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)}
              max={Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS)} step={1} type="number"
              value={duration ?? ""} onChange={(event) => edit({ durationSeconds: event.target.value ? Number(event.target.value) : null })} />
            {chosenCapability ? <span id={`${id}-duration-help`}>{t("media.editor.durationRange", { "0": chosenCapability.minimumSeconds, "1": chosenCapability.maximumSeconds })}</span> : null}
          </div> : null}
        </div> : null}
      </div>
      : null}
      {!runningHub && isAudio ? <div className="media-draft-popover-anchor">
        <Button variant="ghost" type="button" className="media-draft-toolbar-button" aria-label={t("media.editor.chooseVoice")} aria-expanded={popover === "voices"}
          onClick={(event) => togglePopover("voices", event.currentTarget)}><MusicNotes size={17} />{VOICES.find((voice) => voice.id === audioSpeaker)?.name ?? t("media.editor.voiceLibrary")}<CaretDown size={12} /></Button>
        {popover === "voices" ? <VoiceLibrary containerRef={popoverRef} projectId={artifact.projectId} canvasItemId={canvasItemId} capabilityId={chosenCapability?.id} mock={chosenCapability?.mock ?? true} selected={audioSpeaker} onSelect={(speaker) => {
          edit({ parameters: { ...fields.parameters, speaker } }); setPopover(null); triggerRef.current?.focus();
        }} onClose={() => setPopover(null)} /> : null}
      </div> : null}
      <span className="media-draft-cost" title={t("media.editor.estimateHint")}><Coins size={16} /><span>{estimatedMediaCost(chosenCapability, runningHub ? 1 : imageParameters.generationCount, isAudio && !runningHub ? 120 : duration, autodlTier)}</span></span>
      <PopoverPrimitive.Root open={submissionTipOpen} onOpenChange={setSubmissionTipOpen}>
        <PopoverPrimitive.Anchor asChild><Button variant="ghost" className="media-draft-run" type="button" disabled={!canSubmit}
          aria-label={run.isPending ? t("media.editor.submittingRun") : t("media.editor.run")}
          title={occupied ? t("media.editor.activeTaskHint") : t("media.editor.run")}
          onClick={() => {
            if (!canSubmit) return;
            if (validationMessages.length > 0) { setSubmissionTipOpen(true); return; }
            setSubmissionTipOpen(false); run.mutate();
          }}><ArrowUp size={21} weight="bold" /></Button></PopoverPrimitive.Anchor>
        <PopoverPrimitive.Portal><PopoverPrimitive.Content side="top" align="end" sideOffset={10}
          className="media-draft-submit-tip z-[var(--ui-z-popup)] nodrag nowheel nopan" aria-label={t("media.editor.checkInputs")}
          onOpenAutoFocus={(event) => event.preventDefault()} onCloseAutoFocus={(event) => event.preventDefault()}>
          <Alert role="status"><AlertDescription><div className="media-draft-submit-tip-heading"><strong>{t("media.editor.checkInputs")}</strong>
            <PopoverPrimitive.Close asChild><Button variant="ghost" size="icon-xs" type="button" aria-label={t("common.close")}><X /></Button></PopoverPrimitive.Close></div>
            <ul>{validationMessages.map((message) => <li key={message}>{message}</li>)}</ul>
          </AlertDescription></Alert>
        </PopoverPrimitive.Content></PopoverPrimitive.Portal>
      </PopoverPrimitive.Root>
    </div>
    </fieldset>
    <div className="media-draft-feedback">
      {editorReadOnly && directTasks.isSuccess ? <p role="status">{t("media.editor.editingLocked")}</p> : null}
      {fields.styleId && !supportsStyle ? <p role="alert">{t("styles.promptRequired")}</p> : null}
      {fields.styleId && supportsStyle && styles.isPending ? <p role="status">{t("styles.loading")}</p> : null}
      {fields.styleId && styles.error ? <div role="alert">{t("styles.loadFailed")}<Button variant="ghost" type="button"
        onClick={() => void styles.refetch()}>{t("common.retry")}</Button></div> : null}
      {fields.styleId && styles.isSuccess && !selectedStyle?.enabled ? <p role="alert">{t("styles.unavailableHint")}</p> : null}
      {workflow ? <>
        {fields.mediaInputs.filter((input) => !dynamicUsedVersions.has(input.versionId)).map((input) => <p key={input.versionId} role="status">{t("media.editor.unassignedSlots")}<Button variant="ghost" type="button" disabled={editorReadOnly || dirty || save.isPending || removeConnectedInput.isPending} onClick={() => removeReference(input.versionId)}>{t("media.editor.removeUnusedReferences")}</Button></p>)}
        {runningHub?.retainSeconds ? <p>{t("media.editor.instanceRetentionCostHint", { "0": runningHub.retainSeconds })}</p> : null}
      </> : null}
      {uploading ? <CanvasLoadingState compact label={t("media.editor.referenceUploading")} /> : null}
      {uploadError ? <div role="alert">{t("media.editor.referenceUploadFailed", { "0": uploadError.message })}{failedUploads.length ? <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={editorReadOnly || uploading} onClick={() => void uploadFiles(failedUploads)}>{t("media.editor.retryFailedImages")}</Button> : null}
      </div> : null}
      {run.isPending ? <CanvasLoadingState compact label={t("media.editor.submittingTask")} /> : null}
      {run.error ? <p role="alert">{t("media.editor.runFailed", { "0": run.error.message })}</p> : null}
      {directTasks.isPending ? <p role="status">{t("media.editor.taskChecking")}</p> : null}
      {directTasks.error ? <div role="alert">{t("media.editor.taskStatusFailed", { "0": directTasks.error.message })}<Button variant="ghost" className="media-draft-text-action" type="button" onClick={() => void directTasks.refetch()}>{t("media.editor.retryTaskCheck")}</Button></div> : null}
      {settings.isSuccess && fields.capabilityId && !chosenCapability ? <p role="status">{t("media.editor.modelUnavailableHint")}</p> : null}
      {settings.isSuccess && !fields.capabilityId && !chosenCapability ? <p role="status">{t("media.editor.defaultModelMissingHint")}</p> : null}
      {settings.error ? <div role="alert">{t("media.editor.modelSettingsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("media.editor.retryModels")}</Button></div> : null}
      {chosenCapability?.adapterId === "ARK_SEEDANCE_2_I2V" && videoCount > 0 ? <p className="ui-muted">{t("media.editor.videoRelayHint")}</p> : null}
      {latestTask && (latestTask.status === "FAILED" || latestTask.status === "BLOCKED")
        ? <p role="alert">{t("media.editor.generationIncomplete", { "0": taskErrorDetail(latestTask.errorCode) })}</p> : null}
      {latestTask?.status === "READY" ? <div className="media-draft-task-status">
        {queue.data ? <span>{t("media.editor.queuePosition", { "0": queue.data.waitingAhead, "1": QUEUE_LABELS[queue.data.reason] })}</span> : null}
        {queue.error ? <span role="alert">{t("media.editor.queuePositionUnavailable")}</span> : null}
        {latestTask.runId === null ? <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>{cancel.isPending ? t("media.editor.canceling") : t("media.editor.cancelQueue")}</Button> : null}
      </div> : null}
      {cancel.error ? <p role="alert">{t("media.editor.cancelFailed", { "0": cancel.error.message })}</p> : null}
      {latestTask?.status === "UNKNOWN" ? latestTask.runId === null ? <UnknownTaskRetryPanel errorCode={latestTask.errorCode}
        projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version} />
        : <p role="status">{t("tasks.status.unknown")}</p> : null}
      {error ? <div role="alert"><span>{error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT
        ? t("media.editor.draftConflict") : error.message}</span>
        <Button variant="ghost" className="media-draft-text-action" disabled={editorReadOnly} onClick={() => void retry()} type="button">
          {error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? t("media.editor.refreshVersion")
            : failedRemovalVersionId ? t("media.editor.retryRemoval") : t("common.retrySave")}</Button></div> : null}
    </div>
  </div>;
}
