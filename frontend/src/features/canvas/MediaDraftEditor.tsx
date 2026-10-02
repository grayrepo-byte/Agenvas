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
getMediaDraft,getMediaSettings,
listArtifactVersions,listArtifacts,listCanvasItems,listDirectMediaTasks,
removeMediaDraftMediaInput,
runMediaDraft,saveMediaDraft,
uploadAudioAsset,
uploadImageAsset,
uploadVideoAsset,
type Artifact,
type MediaDraft,
type RunningHubField,
type SaveMediaDraftRequest
} from "../../shared/api/client";
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
  get WAITING_WORKER() { return t("等待执行器"); }, get NOT_QUEUED() { return t("未排队"); },
} as const;
const QUALITY_LABELS = { get low() { return t("低"); }, get medium() { return t("中"); }, get high() { return t("高"); } } as const;
const ASPECT_RATIO_LABELS: Readonly<Record<(typeof ASPECT_RATIO_OPTIONS)[number], string>> = {
  "1:1": "1:1", "2:3": "2:3", "3:2": "3:2", "9:16": "9:16", "16:9": "16:9",
  "3:4": "3:4", "4:3": "4:3", "21:9": "21:9", get AUTO() { return t("自动"); },
};
const VIDEO_MODE_OPTIONS = [
  { value: "TEXT", get label() { return t("文生视频"); }, get description() { return t("只使用文字描述生成"); }, needsImage: false },
  { value: "GENERAL_REFERENCE", get label() { return t("全能参考"); }, get description() { return t("按顺序参考图片和音频"); }, needsImage: true },
  { value: "START_END", get label() { return t("首尾帧"); }, get description() { return t("固定首帧，可选尾帧"); }, needsImage: true },
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
  return (withoutExtension || t("上传图片")).slice(0, MAX_ARTIFACT_TITLE_LENGTH);
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
    aria-label={t("{0}，序号 {1}", { "0": accessibleLabel, "1": index + 1 })}
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
      aria-label={t("取消引入 {0}", { "0": accessibleLabel })} disabled={busy}
      title={connected ? t("取消引入并断开画布连线") : t("取消引入")}
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
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [failedRemovalVersionId, setFailedRemovalVersionId] = useState<string | null>(null);
  const draggedReferenceIndex = useRef<number | null>(null);
  const runIntent = useRef<RunIntent | null>(null);
  const [libraryBusy, setLibraryBusy] = useState(false);
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
      setAssetSelectionError(failure instanceof Error ? failure : new Error(t("资源选择保存失败")));
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
        throw new Error(t("请等待当前草稿保存完成后再取消图片输入。"));
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
      if (expectedVersion === null) throw new Error(t("请等待草稿读取完成"));
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
    closeState.current = { fields, dirty, expectedVersion, error, libraryBusy };
  }, [fields, dirty, expectedVersion, error, libraryBusy]);
  useEffect(() => () => {
    const state = closeState.current;
    if (state.dirty && state.fields && state.expectedVersion !== null) {
      const request = { ...state.fields, expectedVersion: state.expectedVersion };
      // An accepted reference owns this CAS version; closing must retain edits without racing it.
      if (state.error || state.libraryBusy) {
        useCanvasStore.getState().setMediaDraftRecovery(ratioDraftKey,
          { request, saving: false, error: state.error ?? new Error(t("参考转存仍在进行，本地输入已保留。请重新打开并核对最新草稿后保存。")) });
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
        || libraryBusy || run.isPending || error) return;
    // An earlier GET can finish after a successful save wrote its newer result to the cache.
    // The last acknowledged CAS version is monotonic even if query responses arrive out of order.
    if (expectedVersion !== null && draft.data.version < expectedVersion) return;
    const initial = fieldsFromDraft(draft.data);
    fieldsRef.current = initial;
    setFields(initial);
    setExpectedVersion(draft.data.version);
  }, [draft.data, dirty, save.isPending, commitAssetReferences.isPending,
    run.isPending, libraryBusy, error, expectedVersion, recovery]);

  useEffect(() => {
    if (!dirty || !fields || expectedVersion === null || save.isPending
        || commitAssetReferences.isPending
        || removeConnectedInput.isPending || libraryBusy || popover === "libraryReferences" || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, commitAssetReferences.isPending,
    removeConnectedInput.isPending, libraryBusy, popover, error]);

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
        setError(failure instanceof Error ? failure : new Error(t("无法重新读取草稿")));
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
    const resolvedCapabilityId = capabilityId ?? defaultCapabilityId;
    const next = availableCapabilities.find((candidate) => candidate.id === resolvedCapabilityId);
    const change = planMediaCapabilityChange({ kind: artifact.kind, fields, capabilityId,
      resolvedCapabilityId, previous: chosenCapability, next });
    if (change.confirmation && !window.confirm(change.confirmation)) return;
    edit(change.fields);
    setPopover(null);
    triggerRef.current?.focus();
  }

  if (!fields) return <div className="media-draft-editor media-draft-initial" aria-label={t("媒体生成编辑器")}>
    {draft.error ? <div role="alert">{t("无法读取工作草稿：{0}", { "0": draft.error.message })}<Button variant="ghost" className="media-draft-text-action" disabled={draft.isFetching}
        onClick={() => void draft.refetch()} type="button">{t("重试读取草稿")}</Button></div>
      : <CanvasLoadingState compact label={recovery?.saving ? t("正在保存工作草稿") : t("正在读取工作草稿")} />}
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
    && !error && !run.isPending
    && directTasks.isSuccess && settings.isSuccess && Boolean(chosenCapability)
    && !occupied && (runningHub ? dynamicErrors.length === 0 && fields.mediaInputs.length <= INPUT_COLORS.length && fields.mediaInputs.every((input) => dynamicUsedVersions.has(input.versionId)) && allInputsAvailable
      : fields.prompt.trim().length > 0 && semanticInputsValid && autodlInputsValid && autodlRatioValid && autodlTierValid && allInputsAvailable && imageParametersSupported && videoModeSupported
        && (artifact.kind !== "VIDEO" || validDuration));
  const dimensionLabel = artifact.kind === "IMAGE"
    ? `${ASPECT_RATIO_LABELS[imageParameters.aspectRatio]} · ${imageParameters.resolution}`
    : `${ASPECT_RATIO_LABELS[videoParameters.aspectRatio]}${autodlWorkflow ? ` · ${autodlTier}` : ""}`;
  const qualityLabel = artifact.kind === "IMAGE"
    ? supportedImageQualities.length
      ? QUALITY_LABELS[imageParameters.quality] : t("模型默认")
    : chosenCapability?.settings.quality ? t("{0}画质", { "0": QUALITY_LABELS[chosenCapability.settings.quality] }) : t("默认画质");
  const historyError = imageHistories.find((history) => history.error)?.error;
  const historyPending = resources.isPending || imageHistories.some((history) => history.isPending);
  const normalizedAssetSearch = assetSearch.trim().toLocaleLowerCase();
  const filteredImageChoices = normalizedAssetSearch
    ? imageChoices.filter((choice) => choice.label.toLocaleLowerCase().includes(normalizedAssetSearch))
    : imageChoices;
  const remainingAssetCapacity = Math.max(0, mediaCapacity - fields.mediaInputs.length);
  const saveLabel = removeConnectedInput.isPending ? t("正在取消引入…")
    : failedRemovalVersionId ? t("取消引入失败")
      : commitAssetReferences.isPending ? t("正在添加资源…")
      : save.isPending ? t("保存中…") : dirty ? error ? t("保存失败，本地输入已保留") : t("待保存…") : t("已保存");
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
      if (inputs.length >= INPUT_COLORS.length) { setError(new Error(t("单张卡片最多 14 个精确素材版本，请先移除不使用的输入。"))); return; }
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
    if (file.size > MAX_RUNNINGHUB_INPUT_BYTES) throw new Error(t("RunningHub 输入素材不能超过 30 MB。"));
    const capabilityAtStart = chosenCapability?.id;
    const uploaded = await uploadReferenceArtifact(file, field.type === "VIDEO" ? "VIDEO" : field.type === "AUDIO" ? "AUDIO" : "IMAGE");
    await queryClient.invalidateQueries({ queryKey: ["artifacts", artifact.projectId] });
    if (!uploaded.resourceDefaultVersionId) throw new Error(t("上传成功，精确版本暂不可用，请重试。"));
    if (fieldsRef.current?.capabilityId && fieldsRef.current.capabilityId !== capabilityAtStart) throw new Error(t("能力已变化；素材已保存到资源库，请重新选择。"));
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
      setUploadError(new Error(t("还可添加 {0} 张图片，请减少本次选择。", { "0": remaining })));
      setFailedUploads([]);
      return;
    }
    const selectedAudioCount = files.filter(isAudioFile).length;
    const selectedImageCount = files.length - selectedAudioCount;
    if (selectedImageCount + imageCount > imageCapacity || selectedAudioCount + audioCount + (isAudio && audioSpeaker ? 1 : 0) > audioCapacity
        || isAudio && selectedImageCount + imageCount > 0 && (selectedAudioCount + audioCount > 0 || Boolean(audioSpeaker))) {
      setUploadError(new Error(t("所选素材超出图片/音频数量限制，或包含不能混用的参考素材。"))); setFailedUploads([]); return;
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
          throw new Error(t("“{0}”已上传，但图片版本尚不可用。", { "0": file.name }));
        }
        uploadedKinds.current.set(uploadedArtifact.resourceDefaultVersionId, uploadedArtifact.kind === "AUDIO" ? "AUDIO" : "IMAGE");
        successfulVersions.push(uploadedArtifact.resourceDefaultVersionId);
        uploadProgress.current.delete(file);
      } catch (failure) {
        failures.push(file);
        firstFailure ??= failure instanceof Error ? failure : new Error(t("图片上传未完成"));
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
        && !window.confirm(t("首尾帧模式只保留前两张图片，并移除音频及多余图片的连线、提示词标签。是否继续？"))) return;
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

  return <div className="media-draft-editor" aria-label={t("媒体生成编辑器")}>
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      {isAudio && onOpenAgentConversation ? <Button variant="ghost" className="media-draft-tab"
        type="button" onClick={onOpenAgentConversation} disabled={openingAgentConversation}
        title={t("打开绑定当前音频版本的 Agent 对话")}>{openingAgentConversation ? t("打开中…") : t("Agent 对话")}</Button> : null}
      {!runningHub && isAudio ? <AudioPromptTools projectId={artifact.projectId} canvasItemId={canvasItemId}
        prompt={fields.prompt} hasMentions={fields.mentions.length > 0} onApply={(prompt) => edit({ prompt, mentions: [] })} /> : null}
      <span className={`media-draft-save-state${error ? " is-error" : ""}`} role="status">{saveLabel}</span>
    </div>
    {!runningHub ? <div className="media-draft-reference-row" aria-label={audioCapacity > 0 ? t("图片与音频输入") : t("图片输入")}>
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
          accept={audioCapacity > 0 ? `${MEDIA_FILE_ACCEPT.IMAGE},${MEDIA_FILE_ACCEPT.AUDIO}` : MEDIA_FILE_ACCEPT.IMAGE} multiple aria-label={audioCapacity > 0 ? t("选择本地图片或音频") : t("选择本地图片")} onChange={handleUploadSelection} />
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-reference-add" type="button"
          disabled={!chosenCapability || referenceLimitReached || uploading
            || commitAssetReferences.isPending}
          aria-label={audioCapacity > 0 ? t("添加图片或音频输入") : t("添加图片输入")}
          title={uploading ? t("正在上传素材") : t("所选模型最多支持 {0} 张图片、{1} 条音频参考", { "0": imageCapacity, "1": audioCapacity })}
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
          ref={popoverRef} id={`${id}-reference-sources`} role="menu" aria-label={t("图片来源")}><DropdownMenuGroup>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            uploadInputRef.current?.click();
           }}><UploadSimple size={17} /><span>{t("从设备上传")}</span></DropdownMenuItem>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            openAssetReferences();
           }}>
            <ImagesSquare size={17} /><span>{t("从资源库选择")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem role="menuitem" onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false);
            setPopover("canvasReferences");
           }}>
            <BoundingBox size={17} /><span>{t("从画布选择")}</span>
          </DropdownMenuItem>
          <DropdownMenuItem role="menuitem" disabled={save.isPending || expectedVersion === null} onSelect={(event) => { event.preventDefault();
            setReferenceSourcesOpen(false); setPopover("libraryReferences");
           }}><ImagesSquare size={17} /><span>{t("从我的资产选择")}</span></DropdownMenuItem>
          <DropdownMenuItem role="menuitem" disabled aria-disabled="true" aria-label={t("绘制引用图（暂未接入）")} title={t("绘制引用图暂未接入")} onSelect={(event) => event.preventDefault()}>
            <PaintBrush size={17} /><span>{t("绘制引用图")}</span><small>{t("暂未接入")}</small>
          </DropdownMenuItem>
        </DropdownMenuGroup></DropdownMenuContent> : null}
        {popover === "libraryReferences" && expectedVersion !== null ? <div className="ui-popover-surface media-draft-popover media-draft-library-popover" ref={popoverRef} role="dialog" aria-label={t("我的资产参考")}>
          <Button variant="ghost" type="button" disabled={libraryBusy} onClick={() => setPopover(null)}>{t("关闭资产选择")}</Button>
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
          id={`${id}-asset-references`} role="dialog" aria-label={audioCapacity > 0 ? t("输入媒体版本") : t("输入图片版本")}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? t("选择精确图片或音频版本") : t("选择精确图片版本")}</p>
          <label htmlFor={`${id}-asset-search`}>{audioCapacity > 0 ? t("搜索媒体资源") : t("搜索资源图片")}</label>
          <Input id={`${id}-asset-search`} type="search" value={assetSearch}
            placeholder={t("搜索名称或版本")}
            onChange={(event) => setAssetSearch(event.target.value)} />
          <div className="media-draft-reference-options" role="group" aria-label={audioCapacity > 0 ? t("可选媒体版本") : t("可选图片版本")}>
            {filteredImageChoices.map((choice) => {
              const alreadyAdded = fields.mediaInputs.some((input) => input.versionId === choice.id);
              const selected = assetSelection.includes(choice.id);
              const selectionFull = !selected && !canAddReference(referenceKind(choice.id), fieldsWithReferences(assetSelection).mediaInputs);
              return <button key={choice.id} type="button" role="checkbox"
              className="media-draft-reference-option" aria-label={t("选择 {0}", { "0": choice.label })}
              aria-checked={alreadyAdded || selected}
              disabled={!choice.available || alreadyAdded || selectionFull || commitAssetReferences.isPending}
              onClick={() => toggleAssetReference(choice.id)}>
              {/* Reference pixels are shown from the archived original, not the 480px preview. */}
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>v{choice.versionNo} · {choice.current ? t("当前选用版本") : t("历史版本")}</small></span>
              {alreadyAdded || selected ? <Check size={15} /> : null}
            </button>;
            })}
          </div>
          {historyPending ? <CanvasLoadingState compact label={t("正在读取图片版本")} /> : null}
          {!historyPending && !resources.error && !historyError && !imageChoices.length ? <p>{t("暂无已生成或上传的图片，请先添加图片。")}</p> : null}
          {!historyPending && !resources.error && !historyError && imageChoices.length > 0
            && !filteredImageChoices.length ? <p>{t("没有匹配的图片版本。")}</p> : null}
          {resources.error || historyError ? <div role="alert">{t("无法读取图片版本。")}<Button variant="ghost" className="media-draft-text-action" onClick={() => {
              void resources.refetch();
              imageHistories.forEach((history) => { void history.refetch(); });
            }} type="button">{t("重试读取图片")}</Button></div> : null}
          {assetSelectionError ? <div className="media-draft-reference-error" role="alert">
            {t("整批未添加；资源已刷新，请重新选择。")}</div> : null}
          <p>{t("按勾选顺序添加，运行时固定精确版本。本次还可选择 {0} 张。", { "0": Math.max(0,
            remainingAssetCapacity - assetSelection.length) })}</p>
          <div className="media-draft-reference-actions">
            <Button variant="ghost" type="button" disabled={commitAssetReferences.isPending} onClick={() => setPopover(null)}>{t("取消")}</Button>
            <Button variant="ghost" type="button" className="is-primary"
              disabled={!assetSelection.length || save.isPending || commitAssetReferences.isPending}
              onClick={confirmAssetReferences}>
              {commitAssetReferences.isPending ? t("正在添加…") : t("添加所选{0}（{1}）", { "0": audioCapacity > 0 ? t("素材") : t("图片"), "1": assetSelection.length })}
            </Button>
          </div>
        </div> : null}
        {popover === "canvasReferences" ? <div className="ui-popover-surface media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-canvas-references`} role="dialog" aria-label={audioCapacity > 0 ? t("从画布选择媒体") : t("从画布选择图片")}>
          <p className="media-draft-popover-title">{audioCapacity > 0 ? t("画布中的图片和音频") : t("画布中的其他图片")}</p>
          <div className="media-draft-reference-options">
            {canvasChoices.map((choice) => <Button variant="ghost" key={choice.canvasItemId} type="button"
              className="media-draft-reference-option" aria-label={t("使用画布{0} {1}", { "0": choice.kind === "AUDIO" ? t("音频") : t("图片"), "1": choice.title })}
              aria-pressed={fields.mediaInputs.some((input) => input.versionId === choice.versionId)}
              disabled={fields.mediaInputs.some((input) => input.versionId === choice.versionId) || !canAddReference(referenceKind(choice.versionId), fields.mediaInputs)}
              onClick={() => appendReferences([choice.versionId])}>
              {choice.kind === "AUDIO" ? <MusicNotes size={24} /> : <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />}
              <span><strong>{choice.title}</strong><small>{t("画布当前选用 · v{0}", { "0": choice.versionNo })}</small></span>
              {fields.mediaInputs.some((input) => input.versionId === choice.versionId) ? <Check size={15} /> : null}
            </Button>)}
          </div>
          {canvas.isPending ? <CanvasLoadingState compact label={t("正在读取画布图片")} /> : null}
          {canvas.isSuccess && !canvasChoices.length ? <p>{t("画布中没有其他可用图片。")}</p> : null}
          {canvas.error ? <div role="alert">{t("无法读取画布图片。")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void canvas.refetch()}
              type="button">{t("重试读取画布")}</Button></div> : null}
          <p>{t("这里只显示其他媒体卡片当前选用的已归档版本，不包含当前卡片。")}</p>
        </div> : null}
      </div></DropdownMenu>
      <div className="media-draft-reference-list" role="list" aria-label={audioCapacity > 0 ? t("已选择的媒体参考") : t("已选择的图片")}>
        {selectedReferences.map(({ input, choice }, index) => <MediaReferenceThumbnail
          key={input.versionId} index={index} audio={input.role === "AUDIO_REFERENCE"} color={input.color}
          accessibleLabel={choice?.label ?? t("图片输入 {0}", { "0": index + 1 })}
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
      label={isAudio ? t("音频提示词") : artifact.kind === "IMAGE" ? t("图片提示词") : t("视频提示词")}
      placeholder={isAudio ? t("描述声音、对白、情绪和环境音；输入 @ 引用音频…") : artifact.kind === "IMAGE" ? t("描述你想创作的画面，让想象发生…") : t("描述镜头、动作和运镜，让画面动起来…")}
      prompt={fields.prompt} mentions={fields.mentions} references={promptReferences}
      onChange={(prompt, mentions) => edit({ prompt, mentions })} /> : null}
    <div className="media-draft-toolbar">
      {!runningHub && artifact.kind === "VIDEO" ? <DropdownMenu open={popover === "modes"} onOpenChange={(open) => { if (!libraryBusy) setPopover(open ? "modes" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-mode-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-mode-trigger" type="button"
          aria-label={t("选择视频输入模式")} aria-haspopup="menu" aria-expanded={popover === "modes"}
          aria-controls={`${id}-modes`} onPointerDown={(event) => { triggerRef.current = event.currentTarget; }}>
          <VideoCamera size={17} /><span>{VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label
            ?? t("选择输入模式")}</span><CaretDown size={12} />
        </Button></DropdownMenuTrigger>
        {popover === "modes" ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-modes" ref={popoverRef}
          id={`${id}-modes`} role="menu" aria-label={t("视频输入模式")}><DropdownMenuGroup>
          <p className="media-draft-popover-title">{t("视频生成模式")}</p>
          {VIDEO_MODE_OPTIONS.map((option) => {
            const missingImage = option.value === "START_END" ? imageCount === 0
              : option.needsImage && fields.mediaInputs.length === 0;
            const hasImagesForText = option.value === "TEXT" && fields.mediaInputs.length > 0;
            const unsupported = !chosenCapability?.supportedVideoInputModes.includes(option.value);
            const disabled = missingImage || hasImagesForText || unsupported;
            const reason = missingImage ? t("添加图片后可用") : hasImagesForText ? t("移除参考素材后自动切换")
              : unsupported ? t("当前模型不支持") : option.value === "START_END" && autodlWorkflow ? t("首帧和尾帧均为必填") : option.description;
            return <DropdownMenuItem className="media-draft-model-option" role="menuitemradio" aria-checked={effectiveMode === option.value} disabled={disabled} title={reason} key={option.value} onSelect={(event) => { event.preventDefault(); chooseVideoMode(option.value); }}>
              <span><strong>{option.label}</strong><small>{reason}</small></span>
              {effectiveMode === option.value ? <Check size={16} /> : null}
            </DropdownMenuItem>;
          })}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu> : null}
      <DropdownMenu open={popover === "models"} onOpenChange={(open) => { if (!libraryBusy) setPopover(open ? "models" : null); }} modal={false}><div className="media-draft-popover-anchor media-draft-model-anchor">
        <DropdownMenuTrigger asChild><Button variant="ghost" className="media-draft-toolbar-button media-draft-model-trigger" type="button"
          aria-label={t("选择生成模型")} aria-haspopup="menu" aria-expanded={popover === "models"}
          aria-controls={`${id}-models`} onPointerDown={(event) => { triggerRef.current = event.currentTarget; }}>
          <Cube size={17} /><span>{settings.isPending ? t("加载模型…") : settings.error ? t("模型配置读取失败") : chosenCapability?.name
            ?? (fields.capabilityId ? t("所选模型不可用") : t("未配置默认模型"))}</span><CaretDown size={12} />
        </Button></DropdownMenuTrigger>
        {popover === "models" ? <DropdownMenuContent aria-labelledby={undefined} side="top" align="start" onEscapeKeyDown={(event) => event.stopPropagation()} className="media-draft-popover media-draft-models p-3" ref={focusModelMenu}
          id={`${id}-models`} role="menu" aria-label={t("生成模型")}><DropdownMenuGroup>
          <p className="media-draft-popover-title">{artifact.kind === "IMAGE" ? t("图片模型") : isAudio ? t("音频模型") : t("视频模型")}</p>
          <DropdownMenuItem variant="rich" className="media-draft-model-option" role="menuitemradio" aria-checked={!fields.capabilityId} onSelect={(event) => { event.preventDefault(); chooseCapability(null); }}>
            <OptionContent icon={<Cube />} title={t("项目默认能力")}
              description={defaultCapabilityId ? t("跟随当前默认模型") : t("尚未配置默认模型")} />
            {!fields.capabilityId ? <Check size={16} /> : null}
          </DropdownMenuItem>
          {availableCapabilities.map((capability) => <DropdownMenuItem variant="rich" className="media-draft-model-option" role="menuitemradio" aria-checked={fields.capabilityId === capability.id} key={capability.id} onSelect={(event) => { event.preventDefault(); chooseCapability(capability.id); }}>
            <OptionContent {...mediaModelDetails(capability, capability.connectionName)} title={capability.name}
              note={capability.mock ? t("Mock 演示") : undefined} />
            {fields.capabilityId === capability.id ? <Check size={16} /> : null}
          </DropdownMenuItem>)}
          {settings.isPending ? <p role="status">{t("正在读取可用模型…")}</p> : null}
          {settings.error ? <div role="alert">{t("无法读取模型。")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("重试读取模型")}</Button></div> : null}
          {settings.isSuccess && !availableCapabilities.length ? <p>{t("尚无可用模型，请在媒体设置中启用对应能力。")}</p> : null}
        </DropdownMenuGroup></DropdownMenuContent> : null}
      </div></DropdownMenu>
      {!runningHub ? <div className="media-draft-popover-anchor media-draft-parameters-anchor">
        <Button variant="ghost" className="media-draft-toolbar-button" type="button" aria-label={isAudio ? t("音频参数") : t("尺寸与画质")}
          aria-expanded={popover === "parameters"} aria-controls={`${id}-parameters`}
          onClick={(event) => togglePopover("parameters", event.currentTarget)}>
          <SlidersHorizontal size={16} /><span>{isAudio ? t("语速 · 音量 · 音调") : `${artifact.kind === "VIDEO" ? t("{0} 秒 · ", { "0": duration ?? "—" }) : ""}${dimensionLabel} · ${qualityLabel}${artifact.kind === "IMAGE" ? t(" · {0} 张", { "0": imageParameters.generationCount }) : ""}`}</span><CaretDown size={12} />
        </Button>
        {popover === "parameters" ? <div className="ui-popover-surface media-draft-popover media-draft-parameters" ref={popoverRef}
          tabIndex={-1} id={`${id}-parameters`} role="dialog" aria-label={t("尺寸与画质设置")}>
          <p className="media-draft-popover-title">{t("生成参数")}</p>
          {isAudio ? <div className="media-draft-audio-parameters">
            {([{ key: "speechRate", label: t("语速"), min: -50, max: 100 },
              { key: "loudnessRate", label: t("音量"), min: -50, max: 100 },
              { key: "pitchRate", label: t("音调"), min: -12, max: 12 }] as const).map((control) =>
              <label key={control.key}>{control.label}<Input type="number" min={control.min} max={control.max} step={1}
                value={fields.parameters[control.key] ?? 0} onChange={(event) => edit({ parameters: {
                  ...fields.parameters, [control.key]: Number(event.target.value) } })} /></label>)}
            <p className="ui-muted">{t("时长、语言和情绪可在提示词中描述，最长生成 120 秒。")}</p>
          </div> : artifact.kind === "IMAGE" ? <div className="media-draft-image-parameters">
            <FieldSet><FieldLegend>{t("比例")}</FieldLegend><ToggleGroup type="single" value={imageParameters.aspectRatio} className="media-draft-choice-grid media-draft-aspect-grid" onValueChange={(selected) => {
              const next = ASPECT_RATIO_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, aspectRatio: next } });
            }}>
              {ASPECT_RATIO_OPTIONS.filter((value) => supportedImageAspectRatios.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>
                  <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                  <small>{ASPECT_RATIO_LABELS[value]}</small>
                </ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <FieldSet><FieldLegend>{t("分辨率")}</FieldLegend><ToggleGroup type="single" value={imageParameters.resolution} className="media-draft-segmented" onValueChange={(selected) => {
              const next = RESOLUTION_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, resolution: next } });
            }}>
              {RESOLUTION_OPTIONS.filter((value) => supportedImageResolutions.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            <div className="media-draft-switch-row"><span>{t("透明背景")}</span><Switch
              aria-label={t("透明背景")}
              checked={imageParameters.transparentBackground}
              disabled={!chosenCapability?.supportsTransparentBackground}
              title={chosenCapability?.supportsTransparentBackground ? undefined : t("所选模型不支持透明背景")}
              onCheckedChange={(checked) => edit({ parameters: { ...imageParameters,
                transparentBackground: checked } })} /></div>
            <FieldSet><FieldLegend>{t("画质")}</FieldLegend>{supportedImageQualities.length
              ? <ToggleGroup type="single" value={imageParameters.quality} className="media-draft-segmented" onValueChange={(selected) => {
              const next = QUALITY_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, quality: next } });
            }}>{QUALITY_OPTIONS
                .filter((value) => supportedImageQualities.includes(value))
                .map((value) => <ToggleGroupItem key={value} value={String(value)}>{QUALITY_LABELS[value]}</ToggleGroupItem>)}</ToggleGroup>
              : <p className="media-draft-fixed-parameter">{t("由所选模型固定")}</p>}</FieldSet>
            <FieldSet><FieldLegend>{t("生成数量")}</FieldLegend><ToggleGroup type="single" value={String(imageParameters.generationCount)} className="media-draft-segmented" onValueChange={(selected) => {
              const next = GENERATION_COUNT_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...imageParameters, generationCount: next } });
            }}>
              {GENERATION_COUNT_OPTIONS.map((value) => <ToggleGroupItem key={value} value={String(value)}>{value}</ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
          </div> : <div className="media-draft-video-parameters">
            <FieldSet><FieldLegend>{t("比例")}</FieldLegend><ToggleGroup type="single" value={videoParameters.aspectRatio} className="media-draft-choice-grid media-draft-video-aspect-grid" onValueChange={(selected) => {
              const next = VIDEO_ASPECT_RATIO_OPTIONS.find((option) => String(option) === selected);
              if (next !== undefined) edit({ parameters: { ...videoParameters, aspectRatio: next } });
            }}>
              {VIDEO_ASPECT_RATIO_OPTIONS.filter((value) => !autodlWorkflow || autoDlRatioSupported(autodlWorkflow, autodlTier, value)).map((value) => <ToggleGroupItem key={value} value={String(value)}
                aria-label={ASPECT_RATIO_LABELS[value]}>
                <span className={`media-draft-ratio-icon ratio-${value.replace(":", "-").toLowerCase()}`} aria-hidden="true" />
                <small>{ASPECT_RATIO_LABELS[value]}</small>
              </ToggleGroupItem>)}
            </ToggleGroup></FieldSet>
            {autodlWorkflow ? <FieldSet><FieldLegend>{t("分辨率")}</FieldLegend>
              <ToggleGroup type="single" value={autodlTier} className="media-draft-segmented" onValueChange={(selected) => {
                const next = autodlTiers.find((tier) => tier === selected);
                if (next) edit({ parameters: { ...videoParameters, videoResolution: next } });
              }}>{autodlTiers.map((tier) => <ToggleGroupItem key={tier} value={tier}>{tier}</ToggleGroupItem>)}</ToggleGroup>
            </FieldSet> : null}
            <p className="media-draft-fixed-parameter">{t("画质由所选视频模型固定")}</p>
          </div>}
          {artifact.kind === "VIDEO" ? <div className="media-draft-duration"><label htmlFor={`${id}-duration`}>{t("时长（秒）")}</label>
            <Input id={`${id}-duration`} aria-describedby={chosenCapability ? `${id}-duration-help` : undefined}
              min={Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)}
              max={Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS)} step={1} type="number"
              value={duration ?? ""} onChange={(event) => edit({ durationSeconds: event.target.value ? Number(event.target.value) : null })} />
            {chosenCapability ? <span id={`${id}-duration-help`}>{t("所选模型支持 {0}–{1} 秒", { "0": chosenCapability.minimumSeconds, "1": chosenCapability.maximumSeconds })}</span> : null}
          </div> : null}
        </div> : null}
      </div>
      : null}
      {!runningHub && isAudio ? <div className="media-draft-popover-anchor">
        <Button variant="ghost" type="button" className="media-draft-toolbar-button" aria-label={t("选择音色")} aria-expanded={popover === "voices"}
          onClick={(event) => togglePopover("voices", event.currentTarget)}><MusicNotes size={17} />{VOICES.find((voice) => voice.id === audioSpeaker)?.name ?? t("音色库")}<CaretDown size={12} /></Button>
        {popover === "voices" ? <VoiceLibrary containerRef={popoverRef} projectId={artifact.projectId} canvasItemId={canvasItemId} capabilityId={chosenCapability?.id} mock={chosenCapability?.mock ?? true} selected={audioSpeaker} onSelect={(speaker) => {
          edit({ parameters: { ...fields.parameters, speaker } }); setPopover(null); triggerRef.current?.focus();
        }} onClose={() => setPopover(null)} /> : null}
      </div> : null}
      <span className="media-draft-cost" title={t("按管理员配置估算，实际费用以平台账单为准")}><Coins size={16} /><span>{estimatedMediaCost(chosenCapability, runningHub ? 1 : imageParameters.generationCount, isAudio && !runningHub ? 120 : duration, autodlTier)}</span></span>
      <Button variant="ghost" className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? t("正在提交运行") : t("运行")} title={occupied ? t("此卡片已有任务，请等待完成或先重试") : t("运行")}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></Button>
    </div>
    <div className="media-draft-feedback">
      {runningHub ? <>
        {dynamicErrors.map((message) => <p role="status" key={message}>{message}</p>)}
        {fields.mediaInputs.filter((input) => !dynamicUsedVersions.has(input.versionId)).map((input) => <p key={input.versionId} role="status">{t("素材未分配到具名槽位。")}<Button variant="ghost" type="button" disabled={dirty || save.isPending || removeConnectedInput.isPending} onClick={() => removeReference(input.versionId)}>{t("移除未使用素材引用")}</Button></p>)}
        {runningHub.retainSeconds ? <p>{t("实例保留 {0} 秒会额外计费。", { "0": runningHub.retainSeconds })}</p> : null}
      </> : null}
      {uploading ? <CanvasLoadingState compact label={t("正在上传引用图片")} /> : null}
      {uploadError ? <div role="alert">{t("上传引用图片失败：{0}", { "0": uploadError.message })}{failedUploads.length ? <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={uploading} onClick={() => void uploadFiles(failedUploads)}>{t("重试失败图片")}</Button> : null}
      </div> : null}
      {run.isPending ? <CanvasLoadingState compact label={t("正在提交任务")} /> : null}
      {run.error ? <p role="alert">{t("运行失败：{0}", { "0": run.error.message })}</p> : null}
      {directTasks.isPending ? <p role="status">{t("正在检查卡片任务…")}</p> : null}
      {directTasks.error ? <div role="alert">{t("无法确认卡片任务状态：{0}", { "0": directTasks.error.message })}<Button variant="ghost" className="media-draft-text-action" type="button" onClick={() => void directTasks.refetch()}>{t("重试检查任务")}</Button></div> : null}
      {settings.isSuccess && fields.capabilityId && !chosenCapability ? <p role="status">{t("所选模型不可用，请选择其他模型。")}</p> : null}
      {settings.isSuccess && !fields.capabilityId && !chosenCapability ? <p role="status">{t("尚未配置默认模型，请选择可用模型或先在媒体设置中配置。")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && effectiveMode && !videoModeSupported
        ? <p role="alert">{t("当前模型不支持{0}，请切换模型或添加/移除图片。", { "0": VIDEO_MODE_OPTIONS.find((option) => option.value === effectiveMode)?.label })}</p> : null}
      {settings.error ? <div role="alert">{t("无法读取模型配置。")}<Button variant="ghost" className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">{t("重试读取模型")}</Button></div> : null}
      {autodlWorkflow && !autodlInputsValid ? <p role="alert">{t("此 AutoDL 工作流至少需要 {0} 张图片、{1} 条音频{2}，提示词最多 {3} 字符。", { "0": autodlWorkflow.minimumImages, "1": autodlWorkflow.minimumAudios, "2": autodlWorkflow.mode === "START_END" ? t("（首帧和尾帧均需提供）") : "", "3": autodlWorkflow.promptLimit })}</p> : null}
      {!autodlTierValid ? <p role="alert">{t("当前能力不支持已选分辨率，请重新选择。")}</p> : null}
      {autodlWorkflow && !autodlRatioValid ? <p role="alert">{t("当前 AutoDL 工作流不支持此画幅，请选择支持的比例。")}</p> : null}
      {!runningHub && isAudio && !semanticInputsValid ? <p role="alert">{t("音频生成最多参考 1 张图片或 3 个音频/音色；图片不能与音频或指定音色混用，提示词最多 3000 字符。")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && duration != null && !validDuration ? <p role="alert">{t("请填写所选模型支持的整数秒时长。")}</p> : null}
      {fields.mediaInputs.length > 0 && !historyPending && (!resources.isSuccess || !allInputsAvailable)
        ? <p role="alert">{t("无法确认一个或多个已固定媒体版本。原选择已保留，请重试读取素材或替换输入。")}</p> : null}
      {!runningHub && artifact.kind === "VIDEO" && chosenCapability && !semanticInputsValid
        ? <p role="alert">{t("当前媒体输入不满足所选视频模式或模型能力，请调整后再运行。")}</p> : null}
      {latestTask && (latestTask.status === "FAILED" || latestTask.status === "BLOCKED")
        ? <p role="alert">{t("生成未完成{0}", { "0": taskErrorDetail(latestTask.errorCode) })}</p> : null}
      {latestTask?.status === "READY" ? <div className="media-draft-task-status">
        {queue.data ? <span>{t("前方 {0} 项 · {1}（排位可能变化）", { "0": queue.data.waitingAhead, "1": QUEUE_LABELS[queue.data.reason] })}</span> : null}
        {queue.error ? <span role="alert">{t("暂时无法读取排位，任务仍在排队。")}</span> : null}
        <Button variant="ghost" className="media-draft-text-action" type="button"
          disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>{cancel.isPending ? t("取消中…") : t("取消排队")}</Button>
      </div> : null}
      {cancel.error ? <p role="alert">{t("取消失败：{0}", { "0": cancel.error.message })}</p> : null}
      {latestTask?.status === "UNKNOWN" ? <UnknownTaskRetryPanel errorCode={latestTask.errorCode}
        projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version} /> : null}
      {error ? <div role="alert"><span>{error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT
        ? t("草稿有冲突；本地输入已保留。重新读取版本后可再保存。") : error.message}</span>
        <Button variant="ghost" className="media-draft-text-action" onClick={() => void retry()} type="button">
          {error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? t("重新读取版本")
            : failedRemovalVersionId ? t("重试取消引入") : t("重试保存")}</Button></div> : null}
    </div>
  </div>;
}
