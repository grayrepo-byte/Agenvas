import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { FieldSet, FieldLegend } from "../../shared/ui/primitives/field";
import { Switch } from "../../shared/ui/primitives/switch";
import {
ArrowUp,BoundingBox,CaretDown,Check,Coins,Cube,
ImageSquare,
ImagesSquare,
MusicNotes,
PaintBrush,Plus,SlidersHorizontal,UploadSimple,VideoCamera,
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
import { isAudioFile, MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { OptionContent } from "../../shared/ui/OptionContent";
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
import { RunningHubForm,runningHubErrors,runningHubUsedVersions,type RunningHubValue } from "./RunningHubForm";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { VoiceLibrary } from "./VoiceLibrary";
import { readContentText } from "./artifactContent";
import { useCanvasStore } from "./canvasStore";
import { saveClosedMediaDraft,type PendingMediaDraftSave } from "./mediaDraftCloseSave";
import { MEDIA_TASK_REFRESH_INTERVAL_MS,latestMediaTask,occupiesMediaCard } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import { VOICES } from "./voiceCatalog";
import { ASPECT_RATIO_OPTIONS, VIDEO_ASPECT_RATIO_OPTIONS, RESOLUTION_OPTIONS, QUALITY_OPTIONS,
  GENERATION_COUNT_OPTIONS, normalizedImageParameters, normalizedVideoParameters,
  preferredImageVideoMode, inputsForVideoMode, planMediaCapabilityChange,
  type MediaDraftFields as DraftFields } from "./mediaDraftCapability";
import { PromptMentionEditor, type PromptReference } from "./PromptMentionEditor";
import { promptForMediaInputs, removePromptReferences } from "./mediaPrompt";

const AUTOSAVE_DELAY_MS = 650;
const REFERENCE_SOURCE_CLOSE_DELAY_MS = 120;
const MAX_PROMPT_LENGTH = 20000;
const MAX_AUDIO_PROMPT_LENGTH = 3000;
const MAX_RUNNINGHUB_INPUT_BYTES = 30 * 1024 * 1024;
const MIN_VIDEO_SECONDS = 1;
const MAX_VIDEO_SECONDS = 30;
const MAX_ARTIFACT_TITLE_LENGTH = 160;
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
    prompt: draft.prompt, parameters: draft.parameters ?? {},
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
  const referenceSourcesCloseTimer = useRef<number | null>(null);
  const suppressReferenceSourceFocusOpen = useRef(false);
  const popoverRef = useRef<HTMLDivElement>(null);
  const focusModelMenu = useCallback((element: HTMLDivElement | null) => {
    popoverRef.current = element;
    if (element) queueMicrotask(() => element.querySelector<HTMLElement>("[aria-checked=true]")?.focus());
  }, []);
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
    if (!popover || popover === "models" || popover === "modes") return;
    const firstControl = popoverRef.current?.querySelector<HTMLElement>(popover === "voices"
      ? "input[type=search]" : "[aria-checked='true'], button, select, input");
    (firstControl ?? popoverRef.current)?.focus();
    function onPointerDown(event: PointerEvent) {
      if (popover === "libraryReferences" && libraryBusy) return;
      const listbox = event.target instanceof Element ? event.target.closest('[role="listbox"]') : null;
      // Select portals sit outside the picker; the trigger's ARIA link identifies only its own menu.
      if (popover === "libraryReferences" && listbox?.id
        && [...popoverRef.current?.querySelectorAll('[role="combobox"][aria-controls]') ?? []]
          .some((control) => control.getAttribute("aria-controls") === listbox.id)) return;
      if (event.target instanceof Node && !popoverRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setPopover(null);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        if (popover === "libraryReferences" && libraryBusy) return;
        setPopover(null);
        suppressReferenceSourceFocusOpen.current = true;
        triggerRef.current?.focus();
        suppressReferenceSourceFocusOpen.current = false;
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
      if (state.error || state.libraryBusy) {
        useCanvasStore.getState().setMediaDraftRecovery(ratioDraftKey,
          { request, saving: false, error: state.error ?? new Error(t("media.editor.transferPendingHint")) });
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
    if (!dirty || !fields || expectedVersion === null || save.isPending
        || commitAssetReferences.isPending
        || removeConnectedInput.isPending || libraryBusy || templateBusy || popover === "libraryReferences" || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, commitAssetReferences.isPending,
    removeConnectedInput.isPending, libraryBusy, templateBusy, popover, error]);

  useEffect(() => {
    if (runningHub || artifact.kind !== "VIDEO" || !fields || !chosenCapability || dirty || save.isPending
        || commitAssetReferences.isPending || removeConnectedInput.isPending || templateBusy) return;
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
    removeConnectedInput.isPending, save.isPending, templateBusy]);

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
    if (libraryBusy) return;
    triggerRef.current = trigger;
    setPopover((current) => current === next ? null : next);
  }

  function chooseCapability(capabilityId: string | null) {
    if (!fields) return;
    const resolvedCapabilityId = capabilityId ?? defaultCapabilityId ?? undefined;
    const next = availableCapabilities.find((candidate) => candidate.id === resolvedCapabilityId);
    const change = planMediaCapabilityChange({ kind: artifact.kind, fields, capabilityId,
      resolvedCapabilityId, previous: chosenCapability, next });
    if (change.confirmation && !window.confirm(change.confirmation)) return;
    edit(change.fields);
    setPopover(null);
    triggerRef.current?.focus();
  }

  if (!fields) return <div className="media-draft-editor media-draft-initial" aria-label={t("media.editor.title")}>
    {draft.error ? <div role="alert">{t("media.editor.draftFailed", { "0": draft.error.message })}<Button variant="ghost" className="media-draft-text-action" disabled={draft.isFetching}
        onClick={() => void draft.refetch()} type="button">{t("media.editor.retryDraft")}</Button></div>
      : <CanvasLoadingState compact label={recovery?.saving ? t("media.editor.saving") : t("media.editor.draftLoading")} />}
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
  const dynamicErrors = runningHub ? runningHubErrors(runningHub, fields.parameters.dynamicValues ?? {}, fields.prompt, duration, imageChoices) : [];
  const dynamicUsedVersions = runningHub ? runningHubUsedVersions(runningHub, fields.parameters.dynamicValues ?? {}, fields.prompt, duration) : new Set<string>();
  const canRun = !dirty && !save.isPending && !commitAssetReferences.isPending
    && !error && !run.isPending && !templateBusy
    && directTasks.isSuccess && settings.isSuccess && Boolean(chosenCapability)
    && !occupied && (runningHub ? dynamicErrors.length === 0 && fields.mediaInputs.length <= INPUT_COLORS.length && fields.mediaInputs.every((input) => dynamicUsedVersions.has(input.versionId)) && allInputsAvailable
      : fields.prompt.trim().length > 0 && semanticInputsValid && autodlInputsValid && autodlRatioValid && autodlTierValid && allInputsAvailable && imageParametersSupported && videoModeSupported
        && (artifact.kind !== "VIDEO" || validDuration));
  const dimensionLabel = artifact.kind === "IMAGE"
    ? `${ASPECT_RATIO_LABELS[imageParameters.aspectRatio]} · ${imageParameters.resolution}`
    : `${ASPECT_RATIO_LABELS[videoParameters.aspectRatio]}${autodlWorkflow ? ` · ${autodlTier}` : ""}`;
  const qualityLabel = artifact.kind === "IMAGE"
    ? supportedImageQualities.length
      ? QUALITY_LABELS[imageParameters.quality] : t("media.editor.modelDefault")
    : chosenCapability?.settings.quality ? t("media.editor.qualityLabel", { "0": QUALITY_LABELS[chosenCapability.settings.quality] }) : t("media.editor.defaultQuality");
  const historyError = imageHistories.find((history) => history.error)?.error;
  const historyPending = resources.isPending || imageHistories.some((history) => history.isPending);
  const normalizedAssetSearch = assetSearch.trim().toLocaleLowerCase();
  const filteredImageChoices = normalizedAssetSearch
    ? imageChoices.filter((choice) => choice.label.toLocaleLowerCase().includes(normalizedAssetSearch))
    : imageChoices;
  const remainingAssetCapacity = Math.max(0, mediaCapacity - fields.mediaInputs.length);
  const saveLabel = removeConnectedInput.isPending ? t("media.editor.removingInput")
    : failedRemovalVersionId ? t("media.editor.removeInputFailed")
      : commitAssetReferences.isPending ? t("media.editor.resourcesAdding")
      : save.isPending ? t("common.saving") : dirty ? error ? t("media.editor.saveFailed") : t("media.editor.unsaved") : t("common.saved");
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
      if (inputs.length >= INPUT_COLORS.length) { setError(new Error(t("media.editor.inputLimit"))); return; }
      const role = field.type === "VIDEO" ? "VIDEO_REFERENCE" : field.type === "AUDIO" ? "AUDIO_REFERENCE" : "REFERENCE";
      inputs.push({ versionId: value, role, color: INPUT_COLORS[inputs.length % INPUT_COLORS.length]! });
    }
    edit({ parameters: { dynamicValues }, mediaInputs: inputs, ...(artifact.kind === "VIDEO" ? { videoInputMode: inputs.length ? "GENERAL_REFERENCE" : "TEXT" } : {}) });
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

  async function uploadDynamicSlot(field: RunningHubField, file: File) {
    if (file.size > MAX_RUNNINGHUB_INPUT_BYTES) throw new Error(t("media.editor.runningHubSizeLimit"));
    const capabilityAtStart = chosenCapability?.id;
    const uploaded = await uploadReferenceArtifact(file, field.type === "VIDEO" ? "VIDEO" : field.type === "AUDIO" ? "AUDIO" : "IMAGE");
    await queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
    if (!uploaded.resourceDefaultVersionId) throw new Error(t("media.editor.uploadedVersionPending"));
    if (fieldsRef.current?.capabilityId && fieldsRef.current.capabilityId !== capabilityAtStart) throw new Error(t("media.editor.capabilityConflict"));
    changeDynamicField(field.key, uploaded.resourceDefaultVersionId);
    uploadProgress.current.delete(file);
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
      if (nextInputs.some((input) => input.versionId === versionId)) continue;
      const kind = referenceKind(versionId);
      if (!canAddReference(kind, nextInputs)) return baseFields;
      const audio = kind === "AUDIO";
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
      return canAddReference(referenceKind(versionId), pending) ? [...current, versionId] : current;
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
      setUploadError(new Error(t("media.editor.remainingImageLimit", { "0": remaining })));
      setFailedUploads([]);
      return;
    }
    const selectedAudioCount = files.filter(isAudioFile).length;
    const selectedImageCount = files.length - selectedAudioCount;
    if (selectedImageCount + imageCount > imageCapacity || selectedAudioCount + audioCount + (isAudio && audioSpeaker ? 1 : 0) > audioCapacity
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
        const uploadedArtifact = await uploadReferenceArtifact(file, audio ? "AUDIO" : "IMAGE");
        if (!uploadedArtifact.resourceDefaultVersionId) {
          throw new Error(t("media.editor.uploadedVersionUnavailable", { "0": file.name }));
        }
        uploadedKinds.current.set(uploadedArtifact.resourceDefaultVersionId, uploadedArtifact.kind === "AUDIO" ? "AUDIO" : "IMAGE");
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
        && !window.confirm(t("media.editor.startEndConversionConfirmation"))) return;
    edit({ videoInputMode: mode, mediaInputs: nextInputs, ...promptForMediaInputs(currentFields, nextInputs) });
    setPopover(null);
    triggerRef.current?.focus();
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
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      {(artifact.kind === "IMAGE" || artifact.kind === "VIDEO") ? <Button variant="ghost" className="media-draft-tab" type="button"
        disabled={templateBusy || save.isPending || run.isPending || commitAssetReferences.isPending || removeConnectedInput.isPending || libraryBusy || Boolean(error)}
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
    {!runningHub ? <div className="media-draft-reference-row" aria-label={audioCapacity > 0 ? t("media.editor.mixedInputs") : t("media.editor.imageInputs")}>
      <DropdownMenu open={referenceSourcesOpen && !popover} onOpenChange={setReferenceSourcesOpen} modal={false}><div className="media-draft-popover-anchor"
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
        <Input ref={uploadInputRef} className="media-draft-upload-input" type="file"
          accept={audioCapacity > 0 ? `${MEDIA_FILE_ACCEPT.IMAGE},${MEDIA_FILE_ACCEPT.AUDIO}` : MEDIA_FILE_ACCEPT.IMAGE} multiple aria-label={audioCapacity > 0 ? t("media.editor.chooseLocalMedia") : t("media.editor.chooseLocalImage")} onChange={handleUploadSelection} />
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-reference-add" type="button"
          disabled={!chosenCapability || referenceLimitReached || uploading
            || commitAssetReferences.isPending}
          aria-label={audioCapacity > 0 ? t("media.editor.addMixedInput") : t("media.editor.addImageInput")}
          title={uploading ? t("media.editor.assetUploading") : t("media.editor.referenceLimits", { "0": imageCapacity, "1": audioCapacity })}
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
        </Button></DropdownMenuTrigger>
        {referenceSourcesOpen && !popover ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-reference-sources"
          onPointerEnter={() => {
            if (referenceSourcesCloseTimer.current !== null) window.clearTimeout(referenceSourcesCloseTimer.current);
            referenceSourcesCloseTimer.current = null;
          }}
          onPointerLeave={() => {
            referenceSourcesCloseTimer.current = window.setTimeout(() => {
              setReferenceSourcesOpen(false);
              referenceSourcesCloseTimer.current = null;
            }, REFERENCE_SOURCE_CLOSE_DELAY_MS);
          }}
          ref={popoverRef} id={`${id}-reference-sources`} role="menu" aria-label={t("media.editor.imageSource")}><DropdownMenuGroup>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            uploadInputRef.current?.click();
           }}><UploadSimple size={17} /><span>{t("media.editor.upload")}</span></DropdownMenuItem>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            openAssetReferences();
           }}>
            <ImagesSquare size={17} /><span>{t("media.editor.chooseResources")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            setPopover("canvasReferences");
           }}>
            <BoundingBox size={17} /><span>{t("media.editor.chooseCanvas")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem role="menuitem" disabled={save.isPending || expectedVersion === null} onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false); setPopover("libraryReferences");
           }}><ImagesSquare size={17} /><span>{t("media.editor.chooseLibrary")}</span></DropdownMenuItem>
          <DropdownMenuItem role="menuitem" disabled aria-disabled="true" aria-label={t("media.editor.sketchUnavailableLabel")} title={t("media.editor.sketchUnavailable")} onSelect={(event) => event.preventDefault()}>
            <PaintBrush size={17} /><span>{t("media.editor.sketchReference")}</span><small>{t("media.editor.notAvailable")}</small>
          </DropdownMenuItem>
        </DropdownMenuGroup></DropdownMenuContent> : null}
        {popover === "libraryReferences" && expectedVersion !== null ? <div className="ui-popover-surface media-draft-popover media-draft-library-popover" ref={popoverRef} role="dialog" aria-label={t("media.editor.libraryReferences")}>
          <Button variant="ghost" type="button" disabled={libraryBusy} onClick={() => setPopover(null)}>{t("media.editor.closePicker")}</Button>
          <LibraryReferencePicker projectId={artifact.projectId} itemId={canvasItemId}
            kinds={audioCapacity > 0 ? ["IMAGE", "AUDIO"] : ["IMAGE"]} draft={{ ...fields, expectedVersion }} onBusy={setLibraryBusy}
            plan={(entry) => {
              if (!canAddReference(entry.kind, fields.mediaInputs)) return null;
              const audio = entry.kind === "AUDIO";
              const mode = artifact.kind === "VIDEO" && fields.mediaInputs.length === 0 ? preferredImageVideoMode(chosenCapability) : effectiveMode;
              const role = audio ? (isAudio || mode === "GENERAL_REFERENCE" ? "AUDIO_REFERENCE" as const : null) : nextRole(fields.mediaInputs, mode);
              if (!role || role === "END_FRAME" && !chosenCapability?.supportsEndFrame) return null;
              const color = INPUT_COLORS.find((candidate) => !fields.mediaInputs.some((input) => input.color === candidate)) ?? INPUT_COLORS[0];
              return { role, color, videoInputMode: mode };
            }} onApplied={(saved, submitted) => {
              const latest = fieldsRef.current;
              const changed = latest !== null && JSON.stringify(latest) !== JSON.stringify(Object.fromEntries(Object.entries(submitted).filter(([name]) => name !== "expectedVersion")));
              const savedFields = fieldsFromDraft(saved);
              const oldVersions = new Set(submitted.mediaInputs.map((input) => input.versionId));
              const next = changed ? { ...latest, videoInputMode: saved.videoInputMode,
                mediaInputs: [...latest.mediaInputs, ...savedFields.mediaInputs.filter((input) => !oldVersions.has(input.versionId))] } : savedFields;
              fieldsRef.current = next; setFields(next); setExpectedVersion(saved.version); setDirty(changed); setError(null);
              queryClient.setQueryData(key, saved); setPopover(null);
              void queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
            }} />
        </div> : null}
        {popover === "assetReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-asset-references`} role="dialog" aria-label={audioCapacity > 0 ? t("media.editor.inputMediaVersions") : t("media.editor.inputImageVersions")}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? t("media.editor.chooseMixedVersion") : t("media.editor.chooseImageVersion")}</p>
          <label htmlFor={`${id}-asset-search`}>{audioCapacity > 0 ? t("media.editor.searchMedia") : t("media.editor.searchImages")}</label>
          <Input id={`${id}-asset-search`} type="search" value={assetSearch}
            placeholder={t("media.editor.searchPlaceholder")}
            onChange={(event) => setAssetSearch(event.target.value)} />
          <div className="media-draft-reference-options" role="group" aria-label={audioCapacity > 0 ? t("media.editor.mediaVersions") : t("media.editor.imageVersions")}>
            {filteredImageChoices.map((choice) => {
              const alreadyAdded = fields.mediaInputs.some((input) => input.versionId === choice.id);
              const selected = assetSelection.includes(choice.id);
              const selectionFull = !selected && !canAddReference(referenceKind(choice.id), fieldsWithReferences(assetSelection).mediaInputs);
              return <button key={choice.id} type="button" role="checkbox"
              className="media-draft-reference-option" aria-label={t("media.editor.selectNamed", { "0": choice.label })}
              aria-checked={alreadyAdded || selected}
              disabled={!choice.available || alreadyAdded || selectionFull || commitAssetReferences.isPending}
              onClick={() => toggleAssetReference(choice.id)}>
              {/* Reference pixels are shown from the archived original, not the 480px preview. */}
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>v{choice.versionNo} · {choice.current ? t("media.editor.selectedVersion") : t("media.editor.historicalVersions")}</small></span>
              {alreadyAdded || selected ? <Check size={15} /> : null}
            </button>;
            })}
          </div>
          {historyPending ? <CanvasLoadingState compact label={t("media.editor.imageVersionsLoading")} /> : null}
          {!historyPending && !resources.error && !historyError && !imageChoices.length ? <p>{t("media.editor.imagesEmpty")}</p> : null}
          {!historyPending && !resources.error && !historyError && imageChoices.length > 0
            && !filteredImageChoices.length ? <p>{t("media.editor.imageVersionsEmpty")}</p> : null}
          {resources.error || historyError ? <div role="alert">{t("media.editor.imageVersionsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => {
              void resources.refetch();
              imageHistories.forEach((history) => { void history.refetch(); });
            }} type="button">{t("media.editor.retryImages")}</Button></div> : null}
          {assetSelectionError ? <div className="media-draft-reference-error" role="alert">
            {t("media.editor.batchSelectionFailed")}</div> : null}
          <p>{t("media.editor.selectionOrderHint", { "0": Math.max(0,
            remainingAssetCapacity - assetSelection.length) })}</p>
          <div className="media-draft-reference-actions">
            <Button variant="ghost" type="button" disabled={commitAssetReferences.isPending} onClick={() => setPopover(null)}>{t("common.cancel")}</Button>
            <Button variant="ghost" type="button" className="is-primary"
              disabled={!assetSelection.length || save.isPending || commitAssetReferences.isPending}
              onClick={confirmAssetReferences}>
              {commitAssetReferences.isPending ? t("common.adding") : t("media.editor.addSelected", { "0": audioCapacity > 0 ? t("media.editor.assets") : t("common.image"), "1": assetSelection.length })}
            </Button>
          </div>
        </div> : null}
        {popover === "canvasReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-canvas-references`} role="dialog" aria-label={audioCapacity > 0 ? t("media.editor.chooseCanvasMedia") : t("media.editor.chooseCanvasImage")}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? t("media.editor.canvasMedia") : t("media.editor.otherCanvasImages")}</p>
          <div className="media-draft-reference-options">
            {canvasChoices.map((choice) => <Button variant="ghost" key={choice.canvasItemId} type="button"
              className="media-draft-reference-option" aria-label={t("media.editor.useCanvasMedia", { "0": choice.kind === "AUDIO" ? t("common.audio") : t("common.image"), "1": choice.title })}
              aria-pressed={fields.mediaInputs.some((input) => input.versionId === choice.versionId)}
              disabled={fields.mediaInputs.some((input) => input.versionId === choice.versionId) || !canAddReference(referenceKind(choice.versionId), fields.mediaInputs)}
              onClick={() => appendReferences([choice.versionId])}>
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>{t("media.editor.canvasSelectedVersion", { "0": choice.versionNo })}</small></span>
              {fields.mediaInputs.some((input) => input.versionId === choice.versionId) ? <Check size={15} /> : null}
            </Button>)}
          </div>
          {canvas.isPending ? <CanvasLoadingState compact label={t("media.editor.canvasImagesLoading")} /> : null}
          {canvas.isSuccess && !canvasChoices.length ? <p>{t("media.editor.canvasImagesEmpty")}</p> : null}
          {canvas.error ? <div role="alert">{t("media.editor.canvasImagesFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void canvas.refetch()}
              type="button">{t("media.editor.retryCanvas")}</Button></div> : null}
          <p>{t("media.editor.selectedCanvasVersionsHint")}</p>
        </div> : null}
      </div></DropdownMenu>
      <div className="media-draft-reference-list" role="list" aria-label={audioCapacity > 0 ? t("media.editor.selectedReferences") : t("media.editor.selectedImages")}>
        {selectedReferences.map(({ input, choice }, index) => <MediaReferenceThumbnail
          key={input.versionId} index={index} audio={input.role === "AUDIO_REFERENCE"} color={input.color}
          accessibleLabel={choice?.label ?? t("media.editor.numberedImageInput", { "0": index + 1 })}
          {...(choice && choice.kind === "IMAGE" ? { thumbnailUrl: assetContentUrl(artifact.projectId, choice.assetId) } : {})}
          connected={hasConnectionSource(input.versionId)}
          busy={removeConnectedInput.isPending || commitAssetReferences.isPending || dirty || save.isPending}
          reorderable={effectiveMode !== "START_END"}
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
    : <RunningHubForm definition={runningHub} values={fields.parameters.dynamicValues ?? {}} prompt={fields.prompt}
      durationSeconds={fields.durationSeconds} choices={imageChoices} disabled={run.isPending} onChange={changeDynamicField} onUpload={uploadDynamicSlot} />}
    {!runningHub ? <PromptMentionEditor id={`${id}-prompt`} maxLength={MAX_PROMPT_LENGTH}
      label={isAudio ? t("media.editor.audioPrompt") : artifact.kind === "IMAGE" ? t("media.editor.imagePrompt") : t("media.editor.videoPrompt")}
      placeholder={isAudio ? t("media.editor.audioPromptPlaceholder") : artifact.kind === "IMAGE" ? t("media.editor.imagePromptPlaceholder") : t("media.editor.videoPromptPlaceholder")}
      prompt={fields.prompt} mentions={fields.mentions} references={promptReferences}
      onChange={(prompt, mentions) => edit({ prompt, mentions })} /> : null}
    <div className="media-draft-toolbar">
      {!runningHub && artifact.kind === "VIDEO" ? <DropdownMenu open={popover === "modes"} onOpenChange={(open) => { if (!libraryBusy) setPopover(open ? "modes" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-mode-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-mode-trigger" type="button"
          aria-label={t("media.editor.chooseVideoMode")} aria-haspopup="menu" aria-expanded={popover === "modes"}
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
            const disabled = missingImage || hasImagesForText || unsupported;
            const reason = missingImage ? t("media.editor.requiresImage") : hasImagesForText ? t("media.editor.automaticModeHint")
              : unsupported ? t("media.editor.unsupportedModel") : option.value === "START_END" && autodlWorkflow ? t("media.editor.startEndRequired") : option.description;
            return <DropdownMenuItem className="media-draft-model-option" role="menuitemradio" aria-checked={effectiveMode === option.value} disabled={disabled} title={reason} key={option.value} onSelect={(event) => { event.preventDefault(); chooseVideoMode(option.value); }}>
              <span><strong>{option.label}</strong><small>{reason}</small></span>
              {effectiveMode === option.value ? <Check size={16} /> : null}
            </DropdownMenuItem>;
          })}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu> : null}
      <DropdownMenu open={popover === "models"} onOpenChange={(open) => { if (!libraryBusy) setPopover(open ? "models" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-model-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-model-trigger" type="button"
          aria-label={t("media.editor.chooseModel")} aria-haspopup="menu" aria-expanded={popover === "models"}
          aria-controls={`${id}-models`} onPointerDown={(event) => { triggerRef.current = event.currentTarget; }}>
          <Cube size={17} /><span>{settings.isPending ? t("models.loading") : settings.error ? t("models.loadFailed") : chosenCapability?.name
            ?? (fields.capabilityId ? t("media.editor.modelUnavailable") : t("media.editor.defaultNotConfigured"))}</span><CaretDown size={12} />
        </Button></DropdownMenuTrigger>
        {popover === "models" ? <DropdownMenuContent aria-labelledby={undefined} side="top" align="start" onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-models p-3" ref={focusModelMenu}
          id={`${id}-models`} role="menu" aria-label={t("media.editor.model")}><DropdownMenuGroup>
          <p className="media-draft-popover-title">{artifact.kind === "IMAGE" ? t("media.editor.imageModel") : isAudio ? t("media.editor.audioModel") : t("media.editor.videoModel")}</p>
          <DropdownMenuItem variant="rich" className="media-draft-model-option" role="menuitemradio" aria-checked={!fields.capabilityId} onSelect={(event) => { event.preventDefault(); chooseCapability(null); }}>
            <OptionContent icon={<Cube />} title={t("media.editor.projectDefaultCapability")}
              description={defaultCapabilityId ? t("media.editor.followDefaultModel") : t("media.editor.defaultModelMissing")} />
            {!fields.capabilityId ? <Check size={16} /> : null}
          </DropdownMenuItem>
          {availableCapabilities.map((capability) => <DropdownMenuItem variant="rich" className="media-draft-model-option" role="menuitemradio" aria-checked={fields.capabilityId === capability.id} key={capability.id} onSelect={(event) => { event.preventDefault(); chooseCapability(capability.id); }}>
            <OptionContent {...mediaModelDetails(capability, capability.connectionName)} title={capability.name}
              note={capability.mock ? t("media.editor.mock") : undefined} />
            {fields.capabilityId === capability.id ? <Check size={16} /> : null}
          </DropdownMenuItem>)}
          {settings.isPending ? <p role="status">{t("media.editor.availableModelsLoading")}</p> : null}
          {settings.error ? <div role="alert">{t("media.editor.modelsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("media.editor.retryModels")}</Button></div> : null}
          {settings.isSuccess && !availableCapabilities.length ? <p>{t("media.editor.noModelsHint")}</p> : null}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu>
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
              if (next !== undefined) edit({ parameters: { ...imageParameters, aspectRatio: next } });
            }}>
              {ASPECT_RATIO_OPTIONS.filter((value) => supportedImageAspectRatios.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>
                  <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                  <small>{ASPECT_RATIO_LABELS[value]}</small>
                </ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <FieldSet><FieldLegend>{t("media.editor.resolution")}</FieldLegend><ToggleGroup type="single" value={imageParameters.resolution} className="media-draft-segmented" onValueChange={(selected) => {
              const next = RESOLUTION_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, resolution: next } });
            }}>
              {RESOLUTION_OPTIONS.filter((value) => supportedImageResolutions.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <div className="media-draft-switch-row"><span>{t("media.editor.transparentBackground")}</span><Switch
              aria-label={t("media.editor.transparentBackground")}
              checked={imageParameters.transparentBackground}
              disabled={!chosenCapability?.supportsTransparentBackground}
              title={chosenCapability?.supportsTransparentBackground ? undefined : t("media.editor.transparencyUnsupported")}
              onCheckedChange={(checked) => edit({ parameters: { ...imageParameters,
                transparentBackground: checked } })} /></div>
            <FieldSet><FieldLegend>{t("media.editor.quality")}</FieldLegend>{supportedImageQualities.length
              ? <ToggleGroup type="single" value={imageParameters.quality} className="media-draft-segmented" onValueChange={(selected) => {
              const next = QUALITY_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, quality: next } });
            }}>{QUALITY_OPTIONS
                .filter((value) => supportedImageQualities.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{QUALITY_LABELS[value]}</ToggleGroupItem>)}</ToggleGroup>
              : <p className="media-draft-fixed-parameter">{t("media.editor.fixedByModel")}</p>}</FieldSet>
            <FieldSet><FieldLegend>{t("media.editor.batchSize")}</FieldLegend><ToggleGroup type="single" value={String(imageParameters.generationCount)} className="media-draft-segmented" onValueChange={(selected) => {
              const next = GENERATION_COUNT_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, generationCount: next } });
            }}>
              {GENERATION_COUNT_OPTIONS.map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
          </div> : <div className="media-draft-video-parameters">
            <FieldSet><FieldLegend>{t("media.editor.aspectRatio")}</FieldLegend><ToggleGroup type="single" value={videoParameters.aspectRatio} className="media-draft-choice-grid media-draft-video-aspect-grid" onValueChange={(selected) => {
              const next = VIDEO_ASPECT_RATIO_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...videoParameters, aspectRatio: next } });
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
                if (next) edit({ parameters: { ...videoParameters, videoResolution: next } });
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
      <Button variant="ghost" className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? t("media.editor.submittingRun") : t("media.editor.run")} title={occupied ? t("media.editor.activeTaskHint") : t("media.editor.run")}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></Button>
    </div>
    <div className="media-draft-feedback">
      {runningHub ? <>
        {dynamicErrors.map((message) => <p role="status" key={message}>{message}</p>)}
        {fields.mediaInputs.filter((input) => !dynamicUsedVersions.has(input.versionId)).map((input) => <p key={input.versionId} role="status">{t("media.editor.unassignedSlots")}<Button variant="ghost" type="button" disabled={dirty || save.isPending || removeConnectedInput.isPending} onClick={() => removeReference(input.versionId)}>{t("media.editor.removeUnusedReferences")}</Button></p>)}
        {runningHub.retainSeconds ? <p>{t("media.editor.instanceRetentionCostHint", { "0": runningHub.retainSeconds })}</p> : null}
      </> : null}
      {uploading ? <CanvasLoadingState compact label={t("media.editor.referenceUploading")} /> : null}
      {uploadError ? <div role="alert">{t("media.editor.referenceUploadFailed", { "0": uploadError.message })}{failedUploads.length ? <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={uploading} onClick={() => void uploadFiles(failedUploads)}>{t("media.editor.retryFailedImages")}</Button> : null}
      </div> : null}
      {run.isPending ? <CanvasLoadingState compact label={t("media.editor.submittingTask")} /> : null}
      {run.error ? <p role="alert">{t("media.editor.runFailed", { "0": run.error.message })}</p> : null}
      {directTasks.isPending ? <p role="status">{t("media.editor.taskChecking")}</p> : null}
      {directTasks.error ? <div role="alert">{t("media.editor.taskStatusFailed", { "0": directTasks.error.message })}<Button variant="ghost" className="media-draft-text-action" type="button" onClick={() => void directTasks.refetch()}>{t("media.editor.retryTaskCheck")}</Button></div> : null}
      {settings.isSuccess && fields.capabilityId && !chosenCapability ? <p role="status">{t("media.editor.modelUnavailableHint")}</p> : null}
      {settings.isSuccess && !fields.capabilityId && !chosenCapability ? <p role="status">{t("media.editor.defaultModelMissingHint")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && effectiveMode && !videoModeSupported
        ? <p role="alert">{t("media.editor.unsupportedModeHint", { "0": VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label })}</p> : null}
      {settings.error ? <div role="alert">{t("media.editor.modelSettingsFailed")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("media.editor.retryModels")}</Button></div> : null}
      {autodlWorkflow && !autodlInputsValid ? <p role="alert">{t("media.editor.workflowLimitsHint", { "0": autodlWorkflow.minimumImages, "1": autodlWorkflow.minimumAudios, "2": autodlWorkflow.mode === "START_END" ? t("media.editor.startEndRequiredHint") : "", "3": autodlWorkflow.promptLimit })}</p> : null}
      {!autodlTierValid ? <p role="alert">{t("media.editor.unsupportedResolution")}</p> : null}
      {autodlWorkflow && !autodlRatioValid ? <p role="alert">{t("media.editor.workflowAspectRatioUnsupported")}</p> : null}
      {!runningHub && isAudio && !semanticInputsValid ? <p role="alert">{t("media.editor.audioInputLimitsHint")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && duration != null && !validDuration ? <p role="alert">{t("media.editor.durationValidation")}</p> : null}
      {fields.mediaInputs.length > 0 && !historyPending && (!resources.isSuccess || !allInputsAvailable)
        ? <p role="alert">{t("media.editor.fixedVersionUnavailable")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && !semanticInputsValid
        ? <p role="alert">{t("media.editor.invalidVideoInputs")}</p> : null}
      {latestTask && (latestTask.status === "FAILED" || latestTask.status === "BLOCKED")
        ? <p role="alert">{t("media.editor.generationIncomplete", { "0": taskErrorDetail(latestTask.errorCode) })}</p> : null}
      {latestTask?.status === "READY" ? <div className="media-draft-task-status">
        {queue.data ? <span>{t("media.editor.queuePosition", { "0": queue.data.waitingAhead, "1": QUEUE_LABELS[queue.data.reason] })}</span> : null}
        {queue.error ? <span role="alert">{t("media.editor.queuePositionUnavailable")}</span> : null}
        <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>{cancel.isPending ? t("media.editor.canceling") : t("media.editor.cancelQueue")}</Button>
      </div> : null}
      {cancel.error ? <p role="alert">{t("media.editor.cancelFailed", { "0": cancel.error.message })}</p> : null}
      {latestTask?.status === "UNKNOWN" ? <UnknownTaskRetryPanel errorCode={latestTask.errorCode}
        projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version} /> : null}
      {error ? <div role="alert"><span>{error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT
        ? t("media.editor.draftConflict") : error.message}</span>
        <Button variant="ghost" className="media-draft-text-action" onClick={() => void retry()} type="button">
          {error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? t("media.editor.refreshVersion")
            : failedRemovalVersionId ? t("media.editor.retryRemoval") : t("common.retrySave")}</Button></div> : null}
    </div>
  </div>;
}
