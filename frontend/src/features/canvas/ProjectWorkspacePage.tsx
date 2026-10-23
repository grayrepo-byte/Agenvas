import { VideoEditDialog } from "./VideoEditDialog";
import { AlignLeftSimple,ArrowLeft,CheckCircle,CircleNotch,Cube,DownloadSimple,FolderSimple,ImageSquare,MusicNotes,Plus,SelectionAll,Sparkle,TextT,VideoCamera,WarningCircle,X,type Icon } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import {
Background,
ConnectionLineType,
NodeResizer,
NodeToolbar,
Position,
ReactFlow,
type Connection,
type Edge,
type EdgeChange,
type Node,
type NodeChange,
type NodeProps,
type ReactFlowInstance,
type ResizeParams,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { memo,useCallback,useEffect,useMemo,useRef,useState } from "react";
import { Link,Navigate,useParams,useLocation } from "react-router";
import {
HTTP_STATUS,
ApiError,
applyCanvasCommands,
ensureProjectDirector,
createArtifact,
createCanvasConnection,
disconnectCanvasConnection,
duplicateCanvasItem,
getCurrentUser,
getMediaDraft,
getProject,
getProjectSnapshot,
listArtifacts,
listCanvasConnections,
listCanvasItems,
projectExportManifestUrl,
updateAgent,
uploadAudioAsset,
uploadImageAsset,
uploadVideoAsset,
type Agent,
type Artifact,
type Canvas,
type CanvasCommand,
type CanvasItem,
type ProjectSnapshot,
} from "../../shared/api/client";
import { isAudioFile,isVideoFile,MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Tooltip,TooltipContent,TooltipProvider,TooltipTrigger } from "../../shared/ui/primitives/tooltip";
import { Command,CommandGroup,CommandItem,CommandList } from "../../shared/ui/primitives/command";
import { Input } from "../../shared/ui/primitives/input";
import { Separator } from "../../shared/ui/primitives/separator";
import { Empty,EmptyContent,EmptyDescription,EmptyHeader,EmptyMedia,EmptyTitle } from "../../shared/ui/primitives/empty";
import { LibraryCanvasPicker } from "../library/LibraryCanvasPicker";
import { AgentChatDock } from "./AgentChatDock";
import type { AgentChatRunRequest } from "./AgentChatPanel";
import { CanvasErrorNotice } from "./CanvasErrorNotice";
import { CanvasEditorOverlay } from "./CanvasEditorOverlay";
import { calculateCanvasEditorViewport } from "./canvasEditorViewport";
import { CanvasHandle } from "./CanvasHandle";
import { CanvasPaneMenu } from "./CanvasPaneMenu";
import { CanvasRelationEdge } from "./CanvasRelationEdge";
import { CanvasSettingsMenu } from "./CanvasSettingsMenu";
import { CanvasSelectionCheckbox } from "./CanvasSelectionCheckbox";
import { CanvasToolMenu } from "./CanvasToolMenu";
import { CanvasViewportControls } from "./CanvasViewportControls";
import { ContentCanvasCard } from "./ContentCanvasCard";
import { MediaCanvasCard } from "./MediaCanvasCard";
import { DirectorWorkbench } from "./DirectorWorkbench";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { TextGenerationEditor } from "./TextGenerationEditor";
import { displayCanvasRelations } from "./canvasEdgeDisplay";
import { CANVAS_POINTER_THRESHOLD,useCanvasInteraction } from "./canvasInteraction";
import { arrangeCanvas,CANVAS_ARRANGE_GAP } from "./arrangeCanvas";
import {
canvasRelationRemoval,
isCanvasConnectionValid,mediaInputConnection,projectCanvasRelations,
type CanvasRelationRemoval
} from "./canvasRelations";
import { CANVAS_SELECTION_MODE,useCanvasStore } from "./canvasStore";
import { CANVAS_MAX_SIZE,imageNodeResizeBounds,projectImageNodeSize } from "./imageNodeLayout";
import { AUDIO_CARD_HEIGHT,AUDIO_CARD_WIDTH,prepareMediaNode,VISUAL_MEDIA_CARD_HEIGHT,VISUAL_MEDIA_CARD_WIDTH,type PreparedMediaNode } from "./mediaNodeActions";
import { subscribeProjectEvents,type EventSyncStatus } from "./projectEvents";
import { projectCacheCallbacks } from "./projectCache";
import { useCanvasDisplayPreferences } from "./useCanvasDisplayPreferences";
import { KIND_LABELS } from "../library/libraryLabels";
import { useMediaNodeRatios } from "./useMediaNodeRatios";
import { useCanvasLayoutSave } from "./useCanvasLayoutSave";
import { CharacterManagerDialog } from "../characters/CharacterManagerDialog";
import { characterBindingStale, characterQueryOptions } from "../characters/characterQueries";
import { Badge } from "../../shared/ui/primitives/badge";

type LayoutPatch = Pick<ResizeParams, "x" | "y" | "width" | "height">;
type CreationKind = "TEXT" | "IMAGE" | "VIDEO" | "AUDIO";
type DrawerKind = "ALIGN";
type CreationPoint = { x: number; y: number };
type UploadIntent = { file: File; point: CreationPoint; title: string; createKey: string; itemId: string;
  zIndex: number; assetId?: string; artifactId?: string };
type CreationMenu = { x: number; y: number; point: CreationPoint; centerAtPoint: boolean };
type RestorableResource = { subjectType: "ARTIFACT"; subjectId: string; kind: Artifact["kind"] };
/** Card under the pointer during a connection gesture; the drop lands on the card, not on an exact port. */
type ConnectionTarget = { itemId: string; targetHandle: "artifact-input"; valid: boolean };
const EDITOR_NODE_GAP = 32;
const TEXT_EDITOR_NODE_GAP = 16;
const CANVAS_MIN_ZOOM = 0.25;
const EDITOR_VIEWPORT_MARGIN = 16;
const DUPLICATE_ITEM_OFFSET = 32;
const MAX_CANVAS_Z_INDEX = 1000;
const MAX_ARTIFACT_TITLE_LENGTH = 160;
const CREATION_MENU_WIDTH = 208;
/** Match the menu's title, rows, gaps and padding in styles.css so edge clamping stays accurate. */
const CREATION_MENU_HEIGHT = 218;
const CREATION_MENU_MARGIN = 12;
const DEFAULT_ARTIFACT_CARD_SIZES: Record<Artifact["kind"], { width: number; height: number }> = {
  TEXT: { width: 420, height: 270 },
  IMAGE: { width: VISUAL_MEDIA_CARD_WIDTH, height: VISUAL_MEDIA_CARD_HEIGHT },
  VIDEO: { width: VISUAL_MEDIA_CARD_WIDTH, height: VISUAL_MEDIA_CARD_HEIGHT },
  AUDIO: { width: AUDIO_CARD_WIDTH, height: AUDIO_CARD_HEIGHT },
};
const DEFAULT_TEXT_CARD_TITLE = "canvas.text.defaultTitle";
const DEFAULT_CANVAS_ORIGIN = 80;
const AUDIO_RESULT_CARD_HEIGHT = 160;
// Existing 240px audio placeholders and the current default both become a compact player after selection.
const AUDIO_PLACEHOLDER_HEIGHTS = new Set([240, AUDIO_CARD_HEIGHT]);
const MIN_ARTIFACT_CARD_SIZE = 120;
/** Drop tolerance around a hidden target handle, in flow units: it keeps the same feel on screen at any zoom. */
const CANVAS_CONNECTION_RADIUS = 80;
/** Delete and Backspace both delete the selected cards and relation lines; React Flow ignores both while typing in a field. */
const CANVAS_DELETE_KEY_CODES = ["Backspace", "Delete"];
const ARTIFACT_LABELS: Record<Artifact["kind"], string> = {
  get TEXT() { return t("common.text"); }, get IMAGE() { return t("common.image"); }, get VIDEO() { return t("common.video"); }, get AUDIO() { return t("common.audio"); },
};

function focusArtifactEditor() {
  document.querySelector<HTMLElement>(".workspace-media-editor [data-content-editor-focus], .workspace-media-editor .media-draft-prompt")?.focus();
}
const CREATION_KINDS: ReadonlyArray<{ kind: CreationKind; label: string; icon: Icon }> = [
  { kind: "TEXT", get label() { return t("common.text"); }, icon: TextT },
  { kind: "IMAGE", get label() { return t("common.image"); }, icon: ImageSquare },
  { kind: "AUDIO", get label() { return t("common.audio"); }, icon: MusicNotes },
  { kind: "VIDEO", get label() { return t("common.video"); }, icon: VideoCamera },
];

type CanvasNodeData = {
  item: CanvasItem;
  projectId: string;
  onResizeEnd: (itemId: string, layout: LayoutPatch) => void;
  onRemove: (item: CanvasItem) => void;
  onToggleLocked: (item: CanvasItem) => void;
  onDuplicate: (item: CanvasItem) => void;
  onMakeMV: (item: CanvasItem) => void;
  onComposeVideo: (item: CanvasItem) => void;
  onEditVideo: (item: CanvasItem) => void;
  compositionDisabledReason: string | null;
  mediaAspectRatio: number | undefined;
  dragging: boolean;
  toolbarVisible: boolean;
  multiSelecting: boolean;
  onToggleSelection: (itemId: string) => void;
  /** Connection gesture feedback: this card is under the pointer and will accept, or reject, the line. */
  connectionTarget: "valid" | "invalid" | null;
  characterLabel: string | null;
  characterNeedsReview: boolean;
};

type CanvasNode = Node<CanvasNodeData, "canvasCard">;
const edgeTypes = { canvasRelation: CanvasRelationEdge };

/** Safe client-side validation message for unsupported canvas connection gestures. */
class CanvasConnectionError extends Error {}

/** React Flow workspace whose nodes are projections of query data plus transient layout drafts. */
export function ProjectWorkspacePage() {
  useLocale();
  const { projectId } = useParams();
  if (!projectId) return <Navigate to="/projects" replace />;
  return <ProjectWorkspace key={projectId} projectId={projectId} />;
}

function ProjectWorkspace({ projectId }: { projectId: string }) {
  const tryLocation = useLocation();
  const focusedTrySkill = useRef<string | null>(null);
  useLocale();
  const queryClient = useQueryClient();
  const uploadInput = useRef<HTMLInputElement>(null);
  const uploadPoint = useRef<CreationPoint | null>(null);
  const uploadProgress = useRef<UploadIntent | null>(null);
  const textProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; zIndex: number; artifactId?: string } | null>(null);
  const [eventStatus, setEventStatus] = useState<EventSyncStatus>("connecting");
  const flow = useRef<ReactFlowInstance<CanvasNode> | null>(null);
  const editorFocusFrame = useRef<number | null>(null);
  const boxSelectionActive = useRef(false);
  const canvasElement = useRef<HTMLElement>(null);
  useEffect(() => () => {
    if (editorFocusFrame.current !== null) window.cancelAnimationFrame(editorFocusFrame.current);
  }, []);
  const focusNodeForEditing = useCallback((node: CanvasNode) => {
    if (editorFocusFrame.current !== null) window.cancelAnimationFrame(editorFocusFrame.current);
    editorFocusFrame.current = null;
    const instance = flow.current;
    if (!instance) return;
    const width = node.measured?.width ?? node.initialWidth ?? 0;
    const height = node.measured?.height ?? node.initialHeight ?? 0;
    const centerX = node.position.x + width / 2;
    const centerY = node.position.y + height / 2;
    const currentZoom = instance.getZoom();
    if (node.data.item.artifact?.kind !== "TEXT") {
      void instance.setCenter(centerX, centerY, { zoom: currentZoom });
      return;
    }
    // Wait for the text editor's four-line minimum to be measured. Reserve real
    // screen space rather than making a taller prompt inside a clipped panel.
    editorFocusFrame.current = window.requestAnimationFrame(() => {
      editorFocusFrame.current = null;
      const selection = useCanvasStore.getState();
      if (selection.selectionMode !== CANVAS_SELECTION_MODE.SINGLE
          || selection.selectedIds.length !== 1 || selection.selectedIds[0] !== node.id) return;
      const canvas = canvasElement.current?.querySelector(".react-flow");
      const bounds = canvas?.getBoundingClientRect();
      const editor = canvas?.querySelector<HTMLElement>(".text-generation-editor");
      const anchor = [...canvas?.querySelectorAll<HTMLElement>(".react-flow__node") ?? []]
        .find(element => element.dataset.id === node.id);
      const actions = [...canvas?.querySelectorAll<HTMLElement>(".artifact-actions-toolbar") ?? []]
        .find(element => element.dataset.id === node.id);
      if (!bounds?.width || !bounds.height || !editor?.scrollHeight || !anchor) {
        void instance.setCenter(centerX, centerY, { zoom: currentZoom });
        return;
      }
      const target = calculateCanvasEditorViewport({ canvas: bounds, nodeSize: { width, height },
        currentZoom, minZoom: CANVAS_MIN_ZOOM,
        editorHeight: editor.scrollHeight + editor.offsetHeight - editor.clientHeight,
        editorGap: TEXT_EDITOR_NODE_GAP,
        topToolbarSpace: actions ? Math.max(0, anchor.getBoundingClientRect().top - actions.getBoundingClientRect().top) : 0,
        margin: EDITOR_VIEWPORT_MARGIN });
      void instance.setCenter(centerX, centerY + target.centerOffsetY, { zoom: target.zoom });
    });
  }, []);
  const { tool, setTool, spaceHeld, selecting } = useCanvasInteraction();
  const creationMenuElement = useRef<HTMLDivElement>(null);
  const creationMenuReturnFocus = useRef<HTMLElement | null>(null);
  const [creationMenu, setCreationMenu] = useState<CreationMenu | null>(null);
  const [paneMenu, setPaneMenu] = useState<Omit<CreationMenu, "centerAtPoint"> | null>(null);
  const [directorItem, setDirectorItem] = useState<CanvasItem | null>(null);
  const [toolsKind, setToolsKind] = useState<DrawerKind | null>(null);
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [charactersOpen, setCharactersOpen] = useState(false);
  const characters = useQuery(characterQueryOptions(projectId));
  const bindingsByItem = useMemo(() => new Map(characters.data?.bindings.map((binding) => [binding.canvasItemId, binding]) ?? []), [characters.data]);
  const [agentChatOpen, setAgentChatOpen] = useState(false);
  const [videoEditIds, setVideoEditIds] = useState<string[] | null>(null);
  const [agentRunRequest, setAgentRunRequest] = useState<AgentChatRunRequest | null>(null);
  const [resourceTab, setResourceTab] = useState<"PROJECT" | "LIBRARY">("PROJECT");
  const [resourceSearch, setResourceSearch] = useState("");
  const mediaProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; artifactId?: string } | null>(null);
  const drafts = useCanvasStore((state) => state.drafts);
  const [draggingIds, setDraggingIds] = useState<string[]>([]);
  const saveState = useCanvasStore((state) => state.saveState);
  const selectedIds = useCanvasStore((state) => state.selectedIds);
  const selectionMode = useCanvasStore((state) => state.selectionMode);
  const multiSelecting = selectionMode === CANVAS_SELECTION_MODE.MULTIPLE || selectedIds.length > 1;
  const updateDraft = useCanvasStore((state) => state.updateDraft);
  const clearDraft = useCanvasStore((state) => state.clearDraft);
  const setSaveState = useCanvasStore((state) => state.setSaveState);
  const setSaveError = (error: Error) => setSaveState(
    error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT ? "conflict" : "failed");
  const canvasMutationFeedback = {
    onMutate: () => setSaveState("saving"),
    onError: setSaveError,
  };
  const acceptCanvasSnapshot = (saved: Canvas) => {
    queryClient.setQueryData(["canvas", projectId], saved);
    setSaveState("saved");
  };
  const setSelectedIds = useCanvasStore((state) => state.setSelectedIds);
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const displaySettings = useCanvasDisplayPreferences(currentUser.data?.id, projectId);
  const snapshot = useQuery({
    queryKey: ["snapshot", projectId],
    queryFn: () => getProjectSnapshot(projectId),
    enabled: currentUser.isSuccess,
  });
  // Pin the SSE starting waterline once; subsequent snapshot updates must not reopen the stream.
  const initialEventSequence = useRef<number | null>(null);
  if (initialEventSequence.current === null && snapshot.data) {
    initialEventSequence.current = snapshot.data.snapshotSeq;
  }
  const project = useQuery({
    queryKey: ["projects", projectId],
    queryFn: () => getProject(projectId),
    enabled: snapshot.isSuccess,
  });
  const canvas = useQuery({
    queryKey: ["canvas", projectId],
    queryFn: () => listCanvasItems(projectId),
    enabled: snapshot.isSuccess,
  });
  useEffect(() => {
    if (!canvas.isSuccess) return;
    const visible = new Set(canvas.data.items.map((item) => item.id));
    const current = useCanvasStore.getState();
    const retained = current.selectedIds.filter((id) => visible.has(id));
    if (retained.length !== current.selectedIds.length) setSelectedIds(retained, current.selectionMode);
  }, [canvas.data, canvas.isSuccess, setSelectedIds]);
  const canvasConnections = useQuery({
    queryKey: ["canvas-connections", projectId],
    queryFn: () => listCanvasConnections(projectId),
    enabled: snapshot.isSuccess,
  });
  const mediaRatios = useMediaNodeRatios(canvas.data?.items);
  const effectiveNodeSize = useCallback((item: CanvasItem, patch?: Partial<LayoutPatch>) => {
    const draft = useCanvasStore.getState().drafts[item.id];
    const storedHeight = patch?.height ?? draft?.height ?? item.height;
    // A result has a compact player; preserve explicit user sizes and pending resize drafts.
    const compactAudio = item.artifact.kind === "AUDIO" && item.selectedVersion
      && patch?.height === undefined && draft?.height === undefined
      && AUDIO_PLACEHOLDER_HEIGHTS.has(item.height);
    return projectImageNodeSize({
      width: patch?.width ?? draft?.width ?? item.width,
      height: compactAudio ? AUDIO_RESULT_CARD_HEIGHT : storedHeight,
    }, mediaRatios[item.id]);
  }, [mediaRatios]);
  const resources = useQuery({
    queryKey: ["artifacts", projectId],
    queryFn: () => listArtifacts(projectId),
    enabled: resourcesOpen,
  });

  useEffect(() => {
    if (initialEventSequence.current === null || typeof EventSource === "undefined") return;
    return subscribeProjectEvents(projectId, initialEventSequence.current, {
      ...projectCacheCallbacks(queryClient, projectId),
      onStatus: setEventStatus,
    });
  }, [projectId, queryClient, snapshot.isSuccess]);

  const saveLayout = useCanvasLayoutSave(projectId, effectiveNodeSize);
  const removeItem = useMutation({
    mutationFn: (item: CanvasItem) =>
      applyCanvasCommands(projectId, [
        { type: "REMOVE", itemId: item.id, expectedVersion: item.version },
      ]),
    ...canvasMutationFeedback,
    onSuccess: (saved, item) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      clearDraft(item.id);
      setSaveState("saved");
    },
  });
  const duplicateMediaItem = useMutation({
    mutationFn: async (item: CanvasItem) => {
      const draft = await getMediaDraft(projectId, item.id);
      const targetItemId = crypto.randomUUID();
      return duplicateCanvasItem(projectId, item.id, {
        targetItemId,
        expectedSourceVersion: item.version,
        expectedSourceDraftVersion: draft.version,
        x: item.x + DUPLICATE_ITEM_OFFSET,
        y: item.y + DUPLICATE_ITEM_OFFSET,
        width: item.width,
        height: item.height,
        zIndex: Math.min(MAX_CANVAS_Z_INDEX, item.zIndex + 1),
      });
    },
    ...canvasMutationFeedback,
    onSuccess: async (result) => {
      queryClient.setQueryData(["media-draft", projectId, result.item.id], result.draft);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
      ]);
      setSelectedIds([result.item.id]);
      setSaveState("saved");
    },
  });
  const toggleLocked = useMutation({
    mutationFn: (item: CanvasItem) =>
      applyCanvasCommands(projectId, [
        {
          type: "SET_LOCKED",
          itemId: item.id,
          expectedVersion: item.version,
          locked: !item.locked,
        },
      ]),
    ...canvasMutationFeedback,
    onSuccess: acceptCanvasSnapshot,
  });
  const addTextCard = useMutation({
    mutationFn: async ({ point }: { point: CreationPoint }) => {
      const fingerprint = JSON.stringify({ projectId, point });
      if (textProgress.current?.fingerprint !== fingerprint) {
        textProgress.current = { fingerprint, createKey: crypto.randomUUID(),
          itemId: crypto.randomUUID(), zIndex: canvas.data?.items.length ?? 0 };
      }
      const pending = textProgress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, {
          kind: "TEXT", title: t(DEFAULT_TEXT_CARD_TITLE),
          content: { format: "PLAIN_TEXT", text: "" },
        }, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const saved = await applyCanvasCommands(projectId, [
        {
          type: "PLACE_ARTIFACT",
          itemId: pending.itemId,
          artifactId: pending.artifactId,
          x: point.x,
          y: point.y,
          ...DEFAULT_ARTIFACT_CARD_SIZES.TEXT,
          zIndex: pending.zIndex,
          locked: false,
        },
      ]);
      return { saved, itemId: pending.itemId };
    },
    ...canvasMutationFeedback,
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      setSelectedIds([itemId]);
      textProgress.current = null;
      setSaveState("saved");
    },
  });
  // Freeze the file, placement and command identities before uploading. Explicit retries
  // continue from confirmed stages even if the user moves the viewport or opens another menu.
  const uploadMedia = useMutation({
    mutationFn: async (intent: UploadIntent) => {
      const audio = isAudioFile(intent.file);
      const video = isVideoFile(intent.file);
      const kind = audio ? "AUDIO" : video ? "VIDEO" : "IMAGE";
      if (!intent.assetId) {
        const asset = await (audio ? uploadAudioAsset : video ? uploadVideoAsset : uploadImageAsset)(projectId, intent.file);
        intent.assetId = asset.id;
      }
      if (!intent.artifactId) {
        const artifact = await createArtifact(projectId, {
          kind, title: intent.title,
          content: { sourceType: "UPLOAD", assetId: intent.assetId },
        }, intent.createKey);
        intent.artifactId = artifact.id;
      }
      return applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT", itemId: intent.itemId, artifactId: intent.artifactId,
        ...intent.point, ...DEFAULT_ARTIFACT_CARD_SIZES[kind], zIndex: intent.zIndex, locked: false,
      }]);
    },
    ...canvasMutationFeedback,
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      uploadProgress.current = null;
      setSaveState("saved");
    },
  });
  const addBlankMedia = useMutation({
    mutationFn: async ({ kind, point, director = false }: { kind: "IMAGE" | "VIDEO" | "AUDIO"; point: CreationPoint; director?: boolean }) => {
      const fingerprint = JSON.stringify({ projectId, kind, point, director });
      if (mediaProgress.current?.fingerprint !== fingerprint) {
        mediaProgress.current = { fingerprint, createKey: crypto.randomUUID(),
          itemId: crypto.randomUUID() };
      }
      const pending = mediaProgress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, { kind,
          title: director ? t("director.title") : t("canvas.workspace.newMediaTitle", { "0": ARTIFACT_LABELS[kind] }), content: null }, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const index = canvas.data?.items.length ?? 0;
      const saved = await applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT", itemId: pending.itemId, artifactId: pending.artifactId,
        x: point.x, y: point.y, ...DEFAULT_ARTIFACT_CARD_SIZES[kind], zIndex: index, locked: false,
      }]);
      return { saved, itemId: pending.itemId, director };
    },
    ...canvasMutationFeedback,
    onSuccess: ({ saved, itemId, director }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      setSelectedIds([itemId]);
      if (director) setDirectorItem(saved.items.find((item) => item.id === itemId) ?? null);
      mediaProgress.current = null;
      setSaveState("saved");
    },
  });
  const mvProgress = useRef(new Map<string, PreparedMediaNode>());
  const makeMV = useMutation({ mutationFn: async (source: CanvasItem) => {
    if (!source.selectedVersionId) throw new Error(t("canvas.workspace.audioResultRequired"));
    const progress = mvProgress.current.get(source.id) ?? { createKey: crypto.randomUUID(), itemId: crypto.randomUUID() };
    mvProgress.current.set(source.id, progress);
    const prepared = await prepareMediaNode(projectId, source.id, "VIDEO", `${source.title} · MV`, {
      prompt: "根据参考音频的节奏和情绪制作视频。", parameters: { aspectRatio: "AUTO" },
      durationSeconds: 5, capabilityId: null, videoInputMode: "GENERAL_REFERENCE",
      mediaInputs: [{ versionId: source.selectedVersionId, role: "AUDIO_REFERENCE", color: "#67C7F3" }], mentions: [],
    }, progress);
    await queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
    return prepared;
  }, onSuccess: (prepared, source) => {
    setSelectedIds([prepared.canvasItemId]); mvProgress.current.delete(source.id); setSaveState("saved");
  }, onError: setSaveError });
  // Agent identity and conversation memory belong to the project, independently of canvas placement.
  const director = snapshot.data?.director ?? null;
  function rememberDirector(agent: Agent) {
    queryClient.setQueryData<ProjectSnapshot>(["snapshot", projectId], (previous) => previous ? {
      ...previous, director: agent,
    } : previous);
  }
  const ensureDirector = useMutation({
    mutationFn: () => ensureProjectDirector(projectId),
    onSuccess: rememberDirector,
  });
  const ensureDirectorMutate = ensureDirector.mutate;
  const ensureDirectorPending = ensureDirector.isPending;
  const openAgentChat = useCallback((open: boolean) => {
    setAgentChatOpen(open);
    if (open && !director && !snapshot.isError && !ensureDirectorPending) {
      ensureDirectorMutate();
    }
  }, [director, snapshot.isError, ensureDirectorPending, ensureDirectorMutate]);
  const compositionDisabledReason = snapshot.data?.activeRun
    && !["SUCCEEDED", "FAILED", "CANCELED"].includes(snapshot.data.activeRun.status)
    ? t("canvas.workspace.compositionBusy")
    : agentRunRequest ? t("canvas.workspace.compositionPending") : null;
  const requestVideoComposition = useCallback((items: readonly CanvasItem[]) => {
    if (compositionDisabledReason || !items.some((item) => item.artifact.kind === "VIDEO")
      || items.some((item) => !item.selectedVersionId)) return;
    setAgentRunRequest({ id: crypto.randomUUID(), instruction: t("agent.chat.compositionInstruction"),
      selectedItemIds: items.map((item) => item.id) });
    openAgentChat(true);
  }, [compositionDisabledReason, openAgentChat]);
  const openAudioConversation = useMutation({
    mutationFn: async (source: CanvasItem) => {
      if (source.artifact.kind !== "AUDIO" || !source.selectedVersionId)
        throw new Error(t("canvas.workspace.audioResultRequired"));
      setAgentChatOpen(true);
      if (director) return director;
      return ensureDirector.mutateAsync();
    },
    onSuccess: (agent, source) => { rememberDirector(agent); setSelectedIds([source.id]); },
  });
  const editAgent = useMutation({
    mutationFn: ({ agent, name, instruction }: { agent: Agent; name: string; instruction: string }) =>
      updateAgent(projectId, agent.id, { expectedVersion: agent.version, name, instruction }),
    onSuccess: async (agent) => {
      rememberDirector(agent);
      await Promise.all([queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas", projectId] })]);
    },
  });
  const connectInput = useMutation({
    mutationFn: async (connection: Connection) => {
      const media = mediaInputConnection(canvas.data?.items ?? [], connection);
      if (!media) throw new CanvasConnectionError(t("canvas.workspace.unsupportedConnection"));
      const targetDraft = await getMediaDraft(projectId, media.targetCanvasItemId);
      await createCanvasConnection(projectId, { ...media, relationType: "MEDIA_INPUT",
        expectedTargetDraftVersion: targetDraft.version });
    },
    ...canvasMutationFeedback,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] }),
      ]);
      setSaveState("saved");
    },
  });
  const removeMediaConnection = useMutation({
    mutationFn: async ({ connection }: Extract<CanvasRelationRemoval, { kind: "mediaConnection" }>) => {
      if (connection.relationType === "MEDIA_DERIVATION") {
        return disconnectCanvasConnection(projectId, connection.id, {});
      }
      const targetDraft = await getMediaDraft(projectId, connection.targetCanvasItemId);
      return disconnectCanvasConnection(projectId, connection.id, {
        expectedTargetDraftVersion: targetDraft.version,
      });
    },
    ...canvasMutationFeedback,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] }),
      ]);
      setSaveState("saved");
    },
  });
  const alignSelected = useMutation({
    mutationFn: async () => {
      const selected = (canvas.data?.items ?? []).filter((item) => selectedIds.includes(item.id));
      const layoutDrafts = useCanvasStore.getState().drafts;
      const layouts = selected.map((item) => ({ item, layout: {
        x: layoutDrafts[item.id]?.x ?? item.x, y: layoutDrafts[item.id]?.y ?? item.y,
        ...effectiveNodeSize(item),
      } })).sort((a, b) => a.layout.y - b.layout.y || a.layout.x - b.layout.x || a.item.id.localeCompare(b.item.id));
      const targetX = Math.min(...layouts.map(({ layout }) => layout.x));
      let nextY = Math.min(...layouts.map(({ layout }) => layout.y));
      const updates = layouts.map(({ item, layout: current }) => {
        // Keep existing vertical space; push overlapping cards down in spatial order.
        // Use projected heights so portrait media and resized cards remain clear.
        const layout = { ...current, x: targetX, y: Math.max(current.y, nextY) };
        nextY = layout.y + layout.height + CANVAS_ARRANGE_GAP.y;
        updateDraft(item.id, layout);
        return { item, patch: layout };
      });
      return saveLayout.mutateAsync(updates);
    },
  });
  function canvasCenter(): CreationPoint {
    const rect = canvasElement.current?.getBoundingClientRect();
    return rect && flow.current
      ? flow.current.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 })
      : { x: DEFAULT_CANVAS_ORIGIN, y: DEFAULT_CANVAS_ORIGIN };
  }

  const restoreResource = useMutation({
    mutationFn: async (resource: RestorableResource) => {
      const point = canvasCenter();
      const itemId = crypto.randomUUID();
      const placement = {
        itemId, x: point.x, y: point.y,
        zIndex: canvas.data?.items.length ?? 0, locked: false,
      };
      const command: CanvasCommand = { ...placement, type: "PLACE_ARTIFACT", artifactId: resource.subjectId,
        ...DEFAULT_ARTIFACT_CARD_SIZES[resource.kind] };
      const saved = await applyCanvasCommands(projectId, [command]);
      return { saved, itemId };
    },
    ...canvasMutationFeedback,
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSelectedIds([itemId]);
      setResourcesOpen(false);
      setSaveState("saved");
    },
  });

  const saveLayoutMutate = saveLayout.mutate;
  const removeItemMutate = removeItem.mutate;
  const toggleLockedMutate = toggleLocked.mutate;
  const editAgentMutate = editAgent.mutate;
  const removeMediaConnectionMutate = removeMediaConnection.mutate;
  const connectInputMutate = connectInput.mutate;

  const handleResizeEnd = useCallback(
    (itemId: string, layout: LayoutPatch) => {
      const item = canvas.data?.items.find((candidate) => candidate.id === itemId);
      if (!item) return;
      const effectiveLayout = { ...layout, ...effectiveNodeSize(item, layout) };
      updateDraft(itemId, effectiveLayout);
      saveLayoutMutate({ item, patch: effectiveLayout });
    },
    [canvas.data?.items, effectiveNodeSize, saveLayoutMutate, updateDraft],
  );
  const handleRemove = useCallback(
    (item: CanvasItem) => removeItemMutate(item),
    [removeItemMutate],
  );
  const handleDuplicate = useCallback(
    (item: CanvasItem) => duplicateMediaItem.mutate(item),
    [duplicateMediaItem],
  );
  const handleToggleLocked = useCallback(
    (item: CanvasItem) => toggleLockedMutate(item),
    [toggleLockedMutate],
  );
  const handleUpdateAgent = useCallback(
    (agent: Agent, name: string, instruction: string) =>
      editAgentMutate({ agent, name, instruction }),
    [editAgentMutate],
  );
  /**
   * Keyboard deletion hands every gesture to the application services and makes React Flow drop its own
   * local removal, so a card or line only disappears from the canvas once the server projection says so.
   */
  const handleBeforeDelete = useCallback(
    async ({ nodes: deletedNodes, edges: deletedEdges }: { nodes: CanvasNode[]; edges: Edge[] }) => {
      // 移除卡片不动 Artifact 内容；服务端会同步清理以该卡片为端点的画布拓扑关系。
      const deletedIds = new Set(deletedNodes.map((node) => node.id));
      const relations = deletedEdges.filter((edge) =>
        !deletedIds.has(edge.source) && !deletedIds.has(edge.target));
      for (const node of deletedNodes) removeItemMutate(node.data.item);
      for (const edge of relations) {
        const removal = canvasRelationRemoval(canvasConnections.data?.items ?? [], edge);
        if (removal?.kind === "mediaConnection") removeMediaConnectionMutate(removal);
      }
      return false;
    },
    [canvas.data?.items, canvasConnections.data?.items,
      removeItemMutate, removeMediaConnectionMutate],
  );
  /**
   * A drop lands on the card under the pointer, so a big card does not require aiming at its left port.
   * While a gesture runs the pointer is hit-tested directly (node enter/leave events miss the case where the
   * pointer already sits inside a card), and the card is handed to `onConnectEnd` when the pointer never
   * reached a port — in that case React Flow's own resolution has already committed the connection.
   */
  const [connectionTarget, setConnectionTarget] = useState<ConnectionTarget | null>(null);
  const [connectGesture, setConnectGesture] = useState(false);
  const nodeActionsVisible = draggingIds.length === 0 && !connectGesture;
  const connectionSource = useRef<{ nodeId: string; handleId: string | null } | null>(null);
  const connectionTargetRef = useRef<ConnectionTarget | null>(null);
  const trackConnectionTarget = useCallback((candidate: ConnectionTarget | null) => {
    const current = connectionTargetRef.current;
    connectionTargetRef.current = candidate;
    // Only a different card or a different verdict is worth a re-render.
    if (current?.itemId === candidate?.itemId && current?.valid === candidate?.valid) return;
    setConnectionTarget(candidate);
  }, []);
  const trackPointerTarget = useCallback((clientX: number, clientY: number) => {
    const source = connectionSource.current;
    if (!source) return;
    const items = canvas.data?.items ?? [];
    const nodeId = document.elementFromPoint(clientX, clientY)
      ?.closest(".react-flow__node")?.getAttribute("data-id");
    // 源卡片自身永远不是落点：开始拖动时指针就在它身上，否则会立刻闪出一次无效反馈。
    if (!nodeId || nodeId === source.nodeId) {
      trackConnectionTarget(null);
      return;
    }
    const target = items.find((item) => item.id === nodeId);
    const targetHandle = target ? "artifact-input" : null;
    trackConnectionTarget(target && targetHandle ? { itemId: target.id, targetHandle,
      valid: isCanvasConnectionValid(items, { source: source.nodeId, sourceHandle: source.handleId,
        target: target.id, targetHandle }) } : null);
  }, [canvas.data?.items, trackConnectionTarget]);
  useEffect(() => {
    if (!connectGesture) return;
    const onPointerMove = (event: MouseEvent) => trackPointerTarget(event.clientX, event.clientY);
    document.addEventListener("mousemove", onPointerMove);
    return () => document.removeEventListener("mousemove", onPointerMove);
  }, [connectGesture, trackPointerTarget]);
  const handleConnectStart = useCallback((_: unknown,
    params: { nodeId: string | null; handleId: string | null }) => {
    connectionSource.current = params.nodeId
      ? { nodeId: params.nodeId, handleId: params.handleId }
      : null;
    trackConnectionTarget(null);
    setConnectGesture(true);
  }, [trackConnectionTarget]);
  const handleConnectEnd = useCallback((_: unknown, state: { toHandle?: unknown }) => {
    const source = connectionSource.current;
    const target = connectionTargetRef.current;
    connectionSource.current = null;
    trackConnectionTarget(null);
    setConnectGesture(false);
    if (state.toHandle || !source || !target?.valid) return;
    connectInputMutate({ source: source.nodeId, sourceHandle: source.handleId,
      target: target.itemId, targetHandle: target.targetHandle });
  }, [connectInputMutate, trackConnectionTarget]);

  const handleToggleSelection = useCallback((itemId: string) => {
    const current = useCanvasStore.getState().selectedIds;
    setSelectedIds(current.includes(itemId) ? current.filter((id) => id !== itemId) : [...current, itemId],
      CANVAS_SELECTION_MODE.MULTIPLE);
  }, [setSelectedIds]);
  const nodes = useMemo<CanvasNode[]>(
    () =>
      (canvas.data?.items ?? [])
        .map((item) => {
          const draft = drafts[item.id];
          const { width, height } = effectiveNodeSize(item, draft);
          return {
            id: item.id,
            type: "canvasCard",
            position: { x: draft?.x ?? item.x, y: draft?.y ?? item.y },
            // React Flow needs node dimensions, not only CSS sizes, before it will show a card.
            initialWidth: width,
            initialHeight: height,
            // Preserve measured dimensions across controlled-node projections so React Flow
            // keeps its DOM-measured handle bounds for edges and drag-to-connect.
            measured: { width, height },
            style: { width, height },
            draggable: selecting && !item.locked,
            selected: selectedIds.includes(item.id),
            data: {
              item,
              projectId,
              characterLabel: bindingsByItem.get(item.id)?.snapshots.map((snapshot) => `${snapshot.name} v${snapshot.characterVersion}`).join(" · ") || null,
              characterNeedsReview: Boolean(bindingsByItem.get(item.id)?.needsReview || (bindingsByItem.get(item.id) && characterBindingStale(bindingsByItem.get(item.id)!, characters.data?.characters ?? []))),
              onResizeEnd: handleResizeEnd,
              onRemove: handleRemove,
              onToggleLocked: handleToggleLocked,
              onDuplicate: handleDuplicate,
              onMakeMV: (item) => makeMV.mutate(item),
              onComposeVideo: (item) => requestVideoComposition([item]),
              onEditVideo: (item) => setVideoEditIds([item.id]),
              compositionDisabledReason,
              mediaAspectRatio: mediaRatios[item.id],
              dragging: draggingIds.includes(item.id),
              toolbarVisible: nodeActionsVisible && !multiSelecting,
              multiSelecting,
              onToggleSelection: handleToggleSelection,
              connectionTarget: connectionTarget?.itemId === item.id
                ? (connectionTarget.valid ? "valid" : "invalid")
                : null,
            },
          };
        }),
    [
      canvas.data?.items,
      bindingsByItem,
      characters.data,
      drafts,
      draggingIds,
      nodeActionsVisible,
      multiSelecting,
      requestVideoComposition,
      compositionDisabledReason,
      handleToggleSelection,
      effectiveNodeSize,
      handleRemove,
      handleResizeEnd,
      handleToggleLocked,
      handleDuplicate,
      mediaRatios,
      projectId,
      selectedIds,
      selecting,
      connectionTarget,
    ],
  );
  /**
   * Relation lines are projected from server data, but React Flow only applies selection changes itself for
   * uncontrolled edges: with a controlled `edges` prop it reports them through `onEdgesChange` and expects
   * them back on the prop. Selection is therefore the only locally owned part of a line.
   */
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([]);
  const projectedRelations = useMemo(() => {
    const items = canvas.data?.items ?? [];
    return projectCanvasRelations(items, canvasConnections.data?.items ?? []);
  },
    [canvas.data?.items, canvasConnections.data?.items]);
  const organizeCanvas = useMutation({
    mutationFn: async () => {
      const items = canvas.data?.items ?? [];
      const positions = arrangeCanvas(items.map((item) => {
        const draft = useCanvasStore.getState().drafts[item.id];
        return { id: item.id, x: draft?.x ?? item.x, y: draft?.y ?? item.y,
          ...effectiveNodeSize(item, draft), locked: item.locked };
      }), projectedRelations);
      const updates = items.flatMap((item) => {
        const position = positions.get(item.id);
        if (!position) return [];
        const patch = { ...position, ...effectiveNodeSize(item) };
        updateDraft(item.id, patch);
        return [{ item, patch }];
      });
      await saveLayout.saveInBatches(updates);
      // Map insertion order is the arrangement order, independent of the API's item order.
      return positions.keys().next().value;
    },
    onSuccess: (firstItemId) => {
      const first = queryClient.getQueryData<Canvas>(["canvas", projectId])?.items
        .find((item) => item.id === firstItemId);
      if (!first) return;
      const { width, height } = effectiveNodeSize(first);
      const instance = flow.current;
      void instance?.setCenter(first.x + width / 2, first.y + height / 2,
        { zoom: instance.getZoom() });
    },
  });
  const relationEdges = useMemo(() => displayCanvasRelations(projectedRelations, selectedIds, selectedEdgeIds,
    displaySettings.preferences), [projectedRelations, selectedIds, selectedEdgeIds, displaySettings.preferences]);
  const handleEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    setSelectedEdgeIds((current) => {
      const next = new Set(current);
      for (const change of changes) {
        if (change.type !== "select") continue;
        if (change.selected) next.add(change.id);
        else next.delete(change.id);
      }
      return [...next];
    });
  }, []);

  /**
   * 应用状态是受控节点选中的权威源；单击、点空白、点连线、框选和追加选择通过 select 变更同步。
   * 按增量更新，避免再用 onSelectionChange 的整量结果重复写入同一状态。
   * 受控 nodes 更新后，React Flow 仍会同步内部选中标记；移除回写不代表内部标记不会更新。
   */
  const handleNodesChange = useCallback(
    (changes: NodeChange<CanvasNode>[]) => {
      const selectChanges = changes.filter((change) => change.type === "select");
      if (selectChanges.length) {
        const { selectedIds: current, selectionMode: mode } = useCanvasStore.getState();
        const next = new Set(current);
        for (const change of selectChanges) {
          if (change.selected) next.add(change.id);
          else next.delete(change.id);
        }
        const ids = [...next];
        // A box gesture becomes multi-select only after React Flow actually selects a node.
        // Keep checkbox removals outside the gesture in their existing multi-select mode.
        const nextMode = boxSelectionActive.current
          ? ids.length > 0 ? CANVAS_SELECTION_MODE.MULTIPLE : CANVAS_SELECTION_MODE.SINGLE
          : mode;
        if (nextMode !== mode || ids.length !== current.length || ids.some((id) => !current.includes(id))) {
          setSelectedIds(ids, nextMode);
        }
      }
      for (const change of changes) {
        if (change.type === "position" && change.position) {
          updateDraft(change.id, change.position);
        } else if (change.type === "dimensions" && change.dimensions && change.resizing) {
          // DOM measurements reflect the current projection; only a resize gesture is a draft.
          updateDraft(change.id, change.dimensions);
        }
      }
    },
    [setSelectedIds, updateDraft],
  );
  /** 移动或缩放画布即放弃当前焦点；程序化的 fitView（event 为 null）不参与，否则会上演选中后立刻被清掉。 */
  const clearSelection = useCallback(() => {
    boxSelectionActive.current = false;
    setSelectedIds([]);
    setSelectedEdgeIds([]);
  }, [setSelectedEdgeIds, setSelectedIds]);
  function openCreationMenu(clientX: number, clientY: number, placement?: CreationPoint) {
    const rect = canvasElement.current?.getBoundingClientRect();
    if (!rect) return;
    const point = placement ?? flow.current?.screenToFlowPosition({ x: clientX, y: clientY })
      ?? { x: clientX - rect.left, y: clientY - rect.top };
    const x = Math.max(CREATION_MENU_MARGIN, Math.min(clientX - rect.left,
      rect.width - CREATION_MENU_WIDTH - CREATION_MENU_MARGIN));
    const y = Math.max(CREATION_MENU_MARGIN, Math.min(clientY - rect.top,
      rect.height - CREATION_MENU_HEIGHT - CREATION_MENU_MARGIN));
    creationMenuReturnFocus.current = document.activeElement instanceof HTMLElement
      ? document.activeElement : null;
    setPaneMenu(null);
    setCreationMenu({ x, y, point, centerAtPoint: placement !== undefined });
  }

  useEffect(() => {
    if (creationMenu) creationMenuElement.current?.focus();
  }, [creationMenu]);

  // 关闭入口不依赖焦点：只有 div 上的 onKeyDown 时，用户一旦把焦点移出菜单就再也关不掉。
  useEffect(() => {
    if (!creationMenu) return;
    function closeOnOutsidePointer(event: PointerEvent) {
      const target = event.target;
      // 非 Node 目标（例如 Window）不可能落在菜单内，按外部点击处理。
      if (target instanceof Node && creationMenuElement.current?.contains(target)) return;
      setCreationMenu(null);
    }
    document.addEventListener("pointerdown", closeOnOutsidePointer, true);
    return () => document.removeEventListener("pointerdown", closeOnOutsidePointer, true);
  }, [creationMenu]);

  // Esc 先交给当前模态/弹出层，再关创建菜单，最后关底部编辑区。setSelectedIds 对相同值返回原 state，
  // 因此没有选中时按 Esc 不会引起重渲染。
  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape" || event.defaultPrevented) return;
      // Radix 的 document 监听器可能晚于画布注册；不能依赖它先 preventDefault。
      // 即使焦点落到 document，打开的模态仍拥有 Escape，避免取消选中卸载编辑草稿。
      if (document.querySelector('[role="dialog"]:not([hidden]):not([aria-modal="false"]), [role="alertdialog"]:not([hidden])')) return;
      if (creationMenu) {
        setCreationMenu(null);
        creationMenuReturnFocus.current?.focus();
        return;
      }
      if (document.querySelector('[role="listbox"], [role="menu"]')) return;
      setSelectedIds([]);
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [creationMenu, setSelectedIds]);

  function chooseCreationKind(kind: CreationKind, point = creationMenu?.point, centerAtPoint = creationMenu?.centerAtPoint ?? false) {
    if (!point) return;
    // Center toolbar/empty-state creations without moving the viewport. Pointer placements
    // retain their original top-left anchor so double-clicking remains spatially predictable.
    const size = DEFAULT_ARTIFACT_CARD_SIZES[kind];
    const origin = centerAtPoint ? { x: point.x - size.width / 2, y: point.y - size.height / 2 } : point;
    setCreationMenu(null);
    setPaneMenu(null);
    setResourcesOpen(false);
    if (kind === "TEXT") {
      setToolsKind(null);
      if (!addTextCard.isPending) addTextCard.mutate({ point: origin });
    } else if (kind === "IMAGE" || kind === "VIDEO" || kind === "AUDIO") {
      addBlankMedia.mutate({ kind, point: origin });
    }
  }

  const selectedItems = (canvas.data?.items ?? []).filter((item) => selectedIds.includes(item.id));
  const selectedVideos = selectedItems.filter((item) => item.artifact.kind === "VIDEO");
  const compositionUnavailable = !selectedVideos.length ? t("canvas.workspace.compositionVideoRequired")
    : selectedItems.some((item) => !item.selectedVersionId) ? t("canvas.workspace.compositionResultRequired")
    : compositionDisabledReason;
  useEffect(() => {
    const params = new URLSearchParams(tryLocation.search);
    const skillId = params.get("skillId"), skillVersionId = params.get("skillVersionId");
    const requestedSkill = skillId && skillVersionId ? `${projectId}:${skillId}:${skillVersionId}` : null;
    if (!requestedSkill || focusedTrySkill.current === requestedSkill || !snapshot.isSuccess) return;
    focusedTrySkill.current = requestedSkill;
    setAgentChatOpen(true);
    if (!director && !ensureDirector.isPending) ensureDirector.mutate();
  }, [projectId, tryLocation.search, snapshot.isSuccess, director, ensureDirector]);
  const projectResources = [
    ...(resources.data?.items ?? []).map((artifact) => ({
      subjectType: "ARTIFACT" as const, subjectId: artifact.id, kind: artifact.kind,
      label: `${artifact.title} · ${KIND_LABELS[artifact.kind]} · ${artifact.resourceDefaultVersionId ? t("canvas.workspace.defaultSelected") : t("canvas.workspace.noDefault")}`,
    })),
  ];

  function locateFeedbackNodes(ids: string[]) {
    const existing = ids.filter((id) => canvas.data?.items.some((item) => item.id === id));
    if (!existing.length) return;
    setSelectedIds(existing);
    void flow.current?.fitView({ nodes: existing.map((id) => ({ id })), padding: 0.2, maxZoom: 1 });
  }
  function feedbackTitle(action: string, ids: (string | null | undefined)[]) {
    const titles = ids.flatMap((id) => {
      const item = canvas.data?.items.find((candidate) => candidate.id === id);
      return item ? [item.title] : [];
    });
    return titles.length ? `${action} · ${titles.join("、")}` : action;
  }
  const layoutErrorIds = (Array.isArray(saveLayout.variables) ? saveLayout.variables : saveLayout.variables ? [saveLayout.variables] : []).map(({ item }) => item.id);
  const connectionErrorIds = [connectInput.variables?.source, connectInput.variables?.target].filter((id): id is string => Boolean(id));
  const openCenteredCreationMenu = () => {
    const rect = canvasElement.current?.getBoundingClientRect();
    if (rect) openCreationMenu(rect.left + rect.width / 2, rect.top + rect.height / 2, canvasCenter());
  };
  const openDockCreationMenu = () => {
    const trigger = canvasElement.current?.querySelector(".workspace-add-button")?.getBoundingClientRect();
    // The menu follows its trigger; the new node still lands in the visible canvas center.
    if (trigger) openCreationMenu(trigger.right + 12, trigger.top, canvasCenter());
    else openCenteredCreationMenu();
  };

  if (currentUser.isError) return <Navigate to="/login" replace />;

  return (
    <main className="workspace-shell text-[var(--ink)]">
      <input ref={uploadInput} type="file" hidden aria-label={t("canvas.context.upload")}
        accept={`${MEDIA_FILE_ACCEPT.IMAGE},${MEDIA_FILE_ACCEPT.VIDEO},${MEDIA_FILE_ACCEPT.AUDIO}`}
        onChange={(event) => {
          const file = event.currentTarget.files?.[0];
          event.currentTarget.value = "";
          if (!file || !uploadPoint.current || uploadMedia.isPending) return;
          const intent: UploadIntent = { file, point: uploadPoint.current,
            title: (file.name.replace(/\.[^.]+$/, "").trim() || file.name).slice(0, MAX_ARTIFACT_TITLE_LENGTH),
            createKey: crypto.randomUUID(), itemId: crypto.randomUUID(), zIndex: canvas.data?.items.length ?? 0 };
          uploadProgress.current = intent;
          uploadMedia.mutate(intent);
        }} />
      <header className="workspace-header" data-sync-state={eventStatus}>
        <div className="workspace-header-identity">
          <Button asChild variant="ghost" size="sm"><Link to="/projects"><ArrowLeft data-icon="inline-start" />{t("common.project")}</Link></Button>
          <Separator orientation="vertical" className="data-[orientation=vertical]:h-4" />
          <h1 className="workspace-project-name" title={project.data?.name}>{project.data?.name ?? t("common.projectLoading")}</h1>
          <SaveBadge state={saveState} />
        </div>
        <div className="workspace-header-actions">
          <Button variant="ghost" size="sm" onClick={() => setCharactersOpen(true)} type="button">{t("character.title")}</Button>
          <Button variant="ghost" size="sm" onClick={() => {
            setToolsKind(null); setResourcesOpen(true);
          }} type="button"><FolderSimple data-icon="inline-start" />{t("canvas.workspace.resources")}</Button>
          {/* 导出清单只含项目的非密钥配置、产物历史与媒体元数据，用于备份与迁移。 */}
          <Separator orientation="vertical" className="data-[orientation=vertical]:h-4" />
          <TooltipProvider><Tooltip><TooltipTrigger asChild>
            <Button asChild variant="ghost" size="icon-sm">
              <a aria-label={t("canvas.workspace.exportManifest")} download={`agenvas-project-${projectId}.json`}
                href={projectExportManifestUrl(projectId)}><DownloadSimple aria-hidden="true" /></a>
            </Button>
          </TooltipTrigger><TooltipContent>{t("canvas.workspace.exportManifest")}</TooltipContent></Tooltip></TooltipProvider>
          {eventStatus !== "live" ? <span className="workspace-sync-status" role="status">
            {eventStatus === "failed" ? t("canvas.workspace.syncRetrying") :
              eventStatus === "recovering" ? t("canvas.workspace.snapshotRestoring") : t("canvas.workspace.streamConnecting")}
          </span> : null}
        </div>
      </header>

      {(toolsKind || resourcesOpen) ? <aside className="workspace-drawer" aria-label={resourcesOpen ? t("canvas.workspace.resourceTitle") : t("canvas.workspace.creationTools")}>
        <div className="workspace-drawer-heading">
          <h2 className="font-semibold">{resourcesOpen ? t("canvas.workspace.resourceTitle") : t("canvas.workspace.creationTools")}</h2>
          <Button variant="ghost" aria-label={t("canvas.workspace.closeDrawer")} className="node-action" onClick={() => {
            setToolsKind(null); setResourcesOpen(false);
          }} type="button">{t("common.close")}</Button>
        </div>
        {resourcesOpen ? <>
          <div className="library-tabs"><Button variant="ghost" type="button" aria-pressed={resourceTab === "PROJECT"} onClick={() => setResourceTab("PROJECT")}>{t("canvas.workspace.resourceTitle")}</Button><Button variant="ghost" type="button" aria-pressed={resourceTab === "LIBRARY"} onClick={() => setResourceTab("LIBRARY")}>{t("library.title")}</Button></div>
          {resourceTab === "LIBRARY" ? <LibraryCanvasPicker projectId={projectId} position={canvasCenter} /> : <>
          <label className="mt-3 block text-sm">{t("canvas.workspace.searchResources")}<Input value={resourceSearch} onChange={(event) => setResourceSearch(event.target.value)}
              placeholder={t("canvas.workspace.searchPlaceholder")} /></label>
          {resources.isPending ? <p className="mt-3 text-sm">{t("canvas.workspace.resourcesLoading")}</p> : null}
          {resources.error ? <WorkspaceError error={resources.error} /> : null}
          <ul className="mt-3 space-y-2">
            {projectResources.filter((resource) => resource.label.toLowerCase()
              .includes(resourceSearch.trim().toLowerCase())).map((resource) => {
              const placed = canvas.data?.items.some((item) =>
                item.subjectType === resource.subjectType && item.subjectId === resource.subjectId);
              return <li className="resource-entry" key={`${resource.subjectType}:${resource.subjectId}`}>
                <span>{resource.label}</span>
                <Button variant="ghost" className="node-action" disabled={restoreResource.isPending}
                  onClick={() => restoreResource.mutate(resource)} type="button">
                  {placed ? t("canvas.workspace.placeAnother") : t("canvas.place")}</Button>
              </li>;
            })}
          </ul>
          {restoreResource.error ? <WorkspaceError error={restoreResource.error} /> : null}
          </>}
        </> : null}
        {editAgent.error ? <WorkspaceError error={editAgent.error} /> : null}
        {toolsKind === "ALIGN" ? <div className="mt-6 border-t border-[var(--line)] pt-5">
          <h2 className="text-sm font-semibold">{t("canvas.workspace.selectionTools")}</h2>
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">{t("canvas.workspace.multiSelectHint")}</p>
          <p className="mt-2 text-xs leading-5 text-[var(--muted)]">{t("canvas.workspace.connectionHint")}</p>
          <p className="mt-2 text-xs leading-5 text-[var(--muted)]">{t("canvas.workspace.deleteHint")}</p>
          <Button variant="outline" className="mt-3 w-full" disabled={selectedIds.length < 2 || alignSelected.isPending} onClick={() => alignSelected.mutate()} type="button">{t("canvas.workspace.alignSelection")}</Button>
          {alignSelected.error ? <WorkspaceError error={alignSelected.error} /> : null}
          </div> : null}
      </aside> : null}

      <section className={`workspace-canvas${selecting ? " is-select-tool" : " is-hand-tool"}${multiSelecting ? " is-multi-select" : ""}`} aria-label={t("canvas.workspace.canvasTitle")} ref={canvasElement}
        onDoubleClickCapture={(event) => {
          if (selecting && (event.target as HTMLElement).classList.contains("react-flow__pane")) {
            openCreationMenu(event.clientX, event.clientY);
          }
        }}>
        <div className="canvas-feedback">
          {snapshot.isPending || canvas.isPending ? <div className="canvas-message">{t("canvas.workspace.canvasRestoring")}</div> : null}
          {snapshot.error ? <CanvasErrorNotice error={snapshot.error} title={t("canvas.feedback.loadSnapshot")} message={workspaceErrorMessage(snapshot.error)}>
            <Button variant="ghost" size="xs" type="button" onClick={() => void snapshot.refetch()}>{t("common.retryRead")}</Button>
          </CanvasErrorNotice> : null}
          {canvas.error ? <CanvasErrorNotice error={canvas.error} title={t("canvas.feedback.loadCanvas")} message={workspaceErrorMessage(canvas.error)}>
            <Button variant="ghost" size="xs" type="button" onClick={() => void canvas.refetch()}>{t("common.retryRead")}</Button>
          </CanvasErrorNotice> : null}
          {displaySettings.persistenceError ? <CanvasErrorNotice error={displaySettings.persistenceError} title={t("canvas.feedback.saveSettings")} message={displaySettings.persistenceError}>
            <Button variant="ghost" size="xs" type="button" onClick={displaySettings.retrySave}>{t("canvas.settings.retrySave")}</Button>
          </CanvasErrorNotice> : null}
          {uploadMedia.isPending ? <div className="canvas-message" role="status">{t("media.upload.uploading")}</div> : null}
          {uploadMedia.error ? <CanvasErrorNotice error={uploadMedia.error}
            title={`${t("canvas.context.upload")} · ${uploadMedia.variables?.title ?? ""}`} message={workspaceErrorMessage(uploadMedia.error)}>
            <Button variant="ghost" size="xs" type="button" disabled={uploadMedia.isPending}
              onClick={() => { if (uploadProgress.current) uploadMedia.mutate(uploadProgress.current); }}>{t("common.retry")}</Button>
          </CanvasErrorNotice> : null}
          {addTextCard.isPending ? <div className="canvas-message" role="status">{t("canvas.workspace.creatingText")}</div> : null}
          {addTextCard.error ? <CanvasErrorNotice error={addTextCard.error} title={`${t("canvas.feedback.createCard")} · ${ARTIFACT_LABELS.TEXT}`} message={workspaceErrorMessage(addTextCard.error)}>
            <Button variant="ghost" size="xs" type="button" onClick={() => { if (addTextCard.variables) addTextCard.mutate(addTextCard.variables); }}>{t("canvas.workspace.retryCreateText")}</Button>
          </CanvasErrorNotice> : null}
          {openAudioConversation.error ? <CanvasErrorNotice error={openAudioConversation.error}
            title={feedbackTitle(t("media.editor.agentConversation"), [openAudioConversation.variables?.id])} message={workspaceErrorMessage(openAudioConversation.error)}
            onLocate={canvas.data?.items.some((item) => item.id === openAudioConversation.variables?.id) ? () => locateFeedbackNodes([openAudioConversation.variables!.id]) : undefined} /> : null}
          {addBlankMedia.error ? <CanvasErrorNotice error={addBlankMedia.error} title={`${t("canvas.feedback.createCard")} · ${addBlankMedia.variables ? ARTIFACT_LABELS[addBlankMedia.variables.kind] : ""}`} message={workspaceErrorMessage(addBlankMedia.error)} /> : null}
          {saveLayout.error ? <CanvasErrorNotice error={saveLayout.error} title={feedbackTitle(t("canvas.feedback.saveLayout"), layoutErrorIds)} message={workspaceErrorMessage(saveLayout.error)}
            onLocate={layoutErrorIds.some((id) => canvas.data?.items.some((item) => item.id === id)) ? () => locateFeedbackNodes(layoutErrorIds) : undefined} /> : null}
          {connectInput.error ? <CanvasErrorNotice error={connectInput.error} title={feedbackTitle(t("canvas.feedback.connect"), connectionErrorIds)} message={workspaceErrorMessage(connectInput.error)}
            onLocate={connectionErrorIds.some((id) => canvas.data?.items.some((item) => item.id === id)) ? () => locateFeedbackNodes(connectionErrorIds) : undefined} /> : null}
          {removeItem.error ? <CanvasErrorNotice error={removeItem.error} title={feedbackTitle(t("canvas.feedback.remove"), [removeItem.variables?.id])} message={workspaceErrorMessage(removeItem.error)}
            onLocate={canvas.data?.items.some((item) => item.id === removeItem.variables?.id) ? () => locateFeedbackNodes([removeItem.variables!.id]) : undefined} /> : null}
          {toggleLocked.error ? <CanvasErrorNotice error={toggleLocked.error} title={feedbackTitle(t("canvas.feedback.lock"), [toggleLocked.variables?.id])} message={workspaceErrorMessage(toggleLocked.error)}
            onLocate={canvas.data?.items.some((item) => item.id === toggleLocked.variables?.id) ? () => locateFeedbackNodes([toggleLocked.variables!.id]) : undefined} /> : null}
        </div>
        <ReactFlow<CanvasNode>
          colorMode="dark"
          connectOnClick={false}
          connectionLineType={ConnectionLineType.Bezier}
          connectionRadius={CANVAS_CONNECTION_RADIUS}
          deleteKeyCode={CANVAS_DELETE_KEY_CODES}
          edges={relationEdges}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
          isValidConnection={(connection) => isCanvasConnectionValid(canvas.data?.items ?? [], connection)}
          minZoom={CANVAS_MIN_ZOOM}
          nodes={nodes}
          nodeTypes={nodeTypes}
          nodesConnectable={selecting}
          elementsSelectable={selecting}
          panOnDrag={!selecting}
          panActivationKeyCode={null}
          selectionKeyCode={selecting ? "Shift" : null}
          nodeClickDistance={CANVAS_POINTER_THRESHOLD}
          nodeDragThreshold={CANVAS_POINTER_THRESHOLD}
          onBeforeDelete={handleBeforeDelete}
          onConnect={(connection) => connectInputMutate(connection)}
          onConnectEnd={handleConnectEnd}
          onConnectStart={handleConnectStart}
          selectNodesOnDrag={false}
          onNodeDragStart={(_, node, moving) => { setDraggingIds((moving?.length ? moving : [node]).map((item) => item.id)); }}
          onNodeDragStop={(_, node, moving) => {
            const updates = (moving?.length ? moving : [node]).flatMap((moved) => {
              const item = canvas.data?.items.find((candidate) => candidate.id === moved.id);
              if (!item) return [];
              const patch = { x: moved.position.x, y: moved.position.y, ...effectiveNodeSize(item) };
              updateDraft(item.id, patch);
              return [{ item, patch }];
            });
            setDraggingIds([]);
            clearSelection();
            if (updates.length) saveLayout.mutate(updates);
          }}
          onNodeClick={(event, node) => {
            if (!selecting || event.metaKey || event.ctrlKey || event.shiftKey) return;
            if (event.target instanceof Element && event.target.closest(
              "button, a, input, textarea, select, summary, [role='button'], [contenteditable]:not([contenteditable='false'])",
            )) return;
            // An ordinary click explicitly focuses a card, even if it was the only box-selected card.
            setSelectedIds([node.id]);
            focusNodeForEditing(node);
          }}
          onPaneClick={clearSelection}
          onSelectionStart={() => {
            clearSelection();
            boxSelectionActive.current = true;
          }}
          onSelectionEnd={() => { boxSelectionActive.current = false; }}
          onPointerCancel={() => { boxSelectionActive.current = false; }}
          onPaneContextMenu={(event) => {
            event.preventDefault();
            const rect = canvasElement.current?.getBoundingClientRect();
            if (!rect) return;
            const point = flow.current?.screenToFlowPosition({ x: event.clientX, y: event.clientY })
              ?? { x: event.clientX - rect.left, y: event.clientY - rect.top };
            setCreationMenu(null);
            setPaneMenu({ x: event.clientX, y: event.clientY, point });
          }}
          onNodesChange={handleNodesChange}
          onNodeDoubleClick={(event, node) => {
            if (!selecting || (event.target instanceof Element &&
              event.target.closest("button, a, input, textarea, select, summary"))) return;
            setSelectedIds([node.id]);
            window.requestAnimationFrame(focusArtifactEditor);
          }}
          onEdgesChange={handleEdgesChange}
          onInit={(instance) => { flow.current = instance; }}
          onMoveStart={(event) => { if (event) clearSelection(); }}
          selectionOnDrag={selecting}
          ariaLabelConfig={{
            "controls.ariaLabel": t("canvas.workspace.viewportControls"), "controls.zoomIn.ariaLabel": t("canvas.workspace.zoomIn"),
            "controls.zoomOut.ariaLabel": t("canvas.workspace.zoomOut"), "controls.fitView.ariaLabel": t("canvas.workspace.fitView"),
            "controls.interactive.ariaLabel": t("canvas.workspace.toggleInteraction"), "minimap.ariaLabel": t("canvas.workspace.minimap"),
          }}
          zoomOnDoubleClick={false}
        >
          {!multiSelecting && draggingIds.length === 0 && selectedItems.length === 1 && selectedItems[0]?.artifact ?
            // NodeToolbar keeps the editor in screen pixels below the focused node.
            <NodeToolbar nodeId={selectedItems[0].id} isVisible position={Position.Bottom}
              offset={selectedItems[0].artifact.kind === "TEXT" ? TEXT_EDITOR_NODE_GAP : EDITOR_NODE_GAP}
              style={{
                // Keep the editor mounted while connecting so unsaved prompts survive the gesture.
                display: connectGesture ? "none" : undefined,
              }}
              className="workspace-media-toolbar nodrag nowheel nopan">
              <CanvasEditorOverlay>
                <Button variant="ghost" aria-label={t("canvas.workspace.closeEditor")} className="workspace-bottom-close"
                  onClick={() => setSelectedIds([])} type="button"><X size={15} /></Button>
                {selectedItems[0].artifact.kind === "IMAGE" ||
                  selectedItems[0].artifact.kind === "VIDEO" || selectedItems[0].artifact.kind === "AUDIO" ?
                  <MediaDraftEditor key={selectedItems[0].id}
                    artifact={selectedItems[0].artifact} canvasItemId={selectedItems[0].id}
                    onOpenAgentConversation={selectedItems[0].artifact.kind === "AUDIO" && selectedItems[0].selectedVersionId
                      ? () => { const source = selectedItems[0]; if (source) openAudioConversation.mutate(source); } : undefined}
                    openingAgentConversation={openAudioConversation.isPending} /> : null}
                {selectedItems[0].artifact.kind === "TEXT" ?
                  <TextGenerationEditor key={selectedItems[0].artifact.id}
                    artifact={selectedItems[0].artifact} /> : null}
              </CanvasEditorOverlay>
            </NodeToolbar> : null}
          <Background color="var(--ui-canvas-dot)" gap={24} size={1} />
          <CanvasViewportControls hasNodes={nodes.length > 0} />
        </ReactFlow>
        {canvas.isSuccess && nodes.length === 0 && !snapshot.isPending && !creationMenu && !paneMenu ?
          <Empty className="canvas-empty-state">
            <EmptyHeader>
              <EmptyMedia variant="icon"><Sparkle /></EmptyMedia>
              <EmptyTitle>{t("canvas.empty.title")}</EmptyTitle>
              <EmptyDescription>{t("canvas.empty.description")}</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
              <Button onClick={openCenteredCreationMenu}><Plus data-icon="inline-start" />{t("canvas.empty.addFirst")}</Button>
              <div className="canvas-empty-types" role="group" aria-label={t("canvas.tools.addCard")}>
                {CREATION_KINDS.map(({ kind, label, icon: CreationIcon }) => <Button key={kind} variant="ghost" size="sm"
                  disabled={kind === "TEXT" ? addTextCard.isPending : addBlankMedia.isPending}
                  onClick={() => chooseCreationKind(kind, canvasCenter(), true)}><CreationIcon aria-hidden="true" />{label}</Button>)}
              </div>
              <p className="canvas-empty-hint">{t("canvas.workspace.emptyHint")}</p>
            </EmptyContent>
          </Empty> : null}
        <CanvasPaneMenu position={paneMenu} onClose={() => setPaneMenu(null)}
          onAdd={(kind) => chooseCreationKind(kind, paneMenu?.point, false)} uploading={uploadMedia.isPending} creatingText={addTextCard.isPending}
          onUpload={() => {
            uploadPoint.current = paneMenu?.point ?? canvasCenter();
            setPaneMenu(null);
            uploadInput.current?.click();
          }}
          onArrange={() => { setPaneMenu(null); clearSelection(); organizeCanvas.mutate(); }}
          arranging={organizeCanvas.isPending || saveLayout.isPending || alignSelected.isPending}
          canArrange={canvas.isSuccess && canvasConnections.isSuccess &&
            nodes.some((node) => !node.data.item.locked)} />
        <CanvasToolMenu tool={tool} spaceHeld={spaceHeld} onToolChange={setTool} onAdd={openDockCreationMenu}>
          <Button variant="ghost" size="icon-sm" aria-label={t("director.title")} title={t("director.title")}
            disabled={addBlankMedia.isPending || !canvas.isSuccess} onClick={() => {
              const center = canvasCenter();
              addBlankMedia.mutate({ kind: "IMAGE", point: { x: center.x - VISUAL_MEDIA_CARD_WIDTH / 2,
                y: center.y - VISUAL_MEDIA_CARD_HEIGHT / 2 }, director: true });
            }}><Cube /></Button>
          <CanvasSettingsMenu preferences={displaySettings.preferences} onPreferenceChange={displaySettings.setPreference}
            persistenceError={displaySettings.persistenceError} onRetrySave={displaySettings.retrySave}
            disabled={!displaySettings.ready} />
        </CanvasToolMenu>
        {directorItem ? <DirectorWorkbench key={directorItem.id} projectId={projectId} artifactId={directorItem.artifact.id}
          canvasItemId={directorItem.id} expectedVersion={directorItem.version} sourceVersionId={directorItem.selectedVersionId}
          sourceTitle={directorItem.title} initialScene={directorItem.selectedVersion?.frozenInput?.directorScene}
          onClose={() => setDirectorItem(null)} /> : null}
        <div className="canvas-gesture-hints" aria-hidden="true">
          <span><kbd>V</kbd>{t("canvas.tools.selectionTool")}</span>
          <span><kbd>Space</kbd>{t("canvas.tools.handTool")}</span>
          <span><kbd>Shift</kbd>{t("canvas.viewport.boxSelect")}</span>
        </div>
        {creationMenu ? <Command loop shouldFilter={false} tabIndex={-1} className="workspace-create-menu h-auto"
          ref={creationMenuElement}
          style={{ left: creationMenu.x, top: creationMenu.y, width: CREATION_MENU_WIDTH }}>
          <CommandList label={t("canvas.tools.addCard")}><CommandGroup heading={t("canvas.tools.addCard")}>
          {CREATION_KINDS.map(({ kind, label, icon: CreationIcon }) => <CommandItem key={kind} value={kind}
            disabled={kind === "TEXT" && addTextCard.isPending}
            onSelect={() => chooseCreationKind(kind, creationMenu?.point)}>
            <CreationIcon size={20} aria-hidden="true" /><span>{label}</span>
          </CommandItem>)}
        </CommandGroup></CommandList></Command> : null}
        {nodeActionsVisible && multiSelecting && selectedItems.length > 0 ? <div className="workspace-selection-toolbar nodrag nowheel nopan" role="group" aria-label={t("canvas.workspace.bulkActions")}>
          <span className="workspace-selection-count"><SelectionAll aria-hidden />{t("canvas.selection.count", { "0": selectedItems.length })}</span>
          <Separator orientation="vertical" className="data-[orientation=vertical]:h-5" />
          <div className="workspace-selection-actions">
            <Button variant="ghost" size="sm" className="rounded-full" disabled={Boolean(compositionUnavailable)}
              title={compositionUnavailable ?? t("canvas.workspace.compositionHint")}
              onClick={() => requestVideoComposition(selectedItems)} type="button"><Sparkle data-icon="inline-start" />{t("canvas.workspace.composeVideo")}</Button>
            <Button variant="ghost" size="sm" disabled={!selectedVideos.length || selectedVideos.some((item) => !item.selectedVersionId)}
              onClick={() => setVideoEditIds(selectedVideos.map((item) => item.id))} type="button">{t("video.edit.title")}</Button>
            <Button variant="ghost" size="sm" className="rounded-full" disabled={selectedItems.length < 2 || alignSelected.isPending}
              onClick={() => alignSelected.mutate()} type="button"><AlignLeftSimple data-icon="inline-start" />{t("canvas.workspace.alignLeft")}</Button>
          </div>
          <Separator orientation="vertical" className="data-[orientation=vertical]:h-5" />
          <Button variant="ghost" size="icon-sm" className="rounded-full" aria-label={t("canvas.workspace.closeEditor")}
            onClick={() => setSelectedIds([])} type="button"><X /></Button>
        </div> : null}
        <AgentChatDock open={agentChatOpen} onOpenChange={openAgentChat}
          data={director ? { agent: director, projectId, activeRun: snapshot.data?.activeRun ?? null,
            canvasNodeCount: nodes.length, selectedNodeCount: selectedItems.length,
            runRequest: agentRunRequest,
            onRunRequestHandled: (id) => setAgentRunRequest((request) => request?.id === id ? null : request),
            onUpdateAgent: handleUpdateAgent,
            updatingAgent: editAgent.isPending,
            updateAgentError: editAgent.variables?.agent.id === director.id ? editAgent.error : null } : null}
          loading={snapshot.isPending || ensureDirector.isPending}
          error={ensureDirector.error ?? snapshot.error}
          onRetry={() => { if (snapshot.isError) void snapshot.refetch(); else ensureDirector.mutate(); }} />
      </section>
      {charactersOpen ? <CharacterManagerDialog projectId={projectId} onClose={() => setCharactersOpen(false)} /> : null}
      {videoEditIds ? <VideoEditDialog projectId={projectId} items={canvas.data?.items ?? []} initialIds={videoEditIds} onClose={() => setVideoEditIds(null)} /> : null}
      <div className="workspace-narrow-warning">{t("canvas.workspace.viewportHint")}</div>
    </main>
  );
}

const CanvasCardNode = memo(function CanvasCardNode({ data, selected }: NodeProps<CanvasNode>) {
  useLocale();
  const halo = data.connectionTarget
    ? <span className={`canvas-connection-halo canvas-connection-halo--${data.connectionTarget}`} />
    : null;
  const selectionControl = data.multiSelecting ? <CanvasSelectionCheckbox title={data.item.title}
    selected={selected} onToggle={() => data.onToggleSelection(data.item.id)} /> : null;
  const artifact = data.item.artifact;
  const cardProps = {
    artifact, item: data.item, selected: selected || data.dragging, locked: data.item.locked,
    toolbarVisible: data.toolbarVisible,
    children: <NodeResizer isVisible={selected && data.toolbarVisible && !data.item.locked}
      {...(data.mediaAspectRatio === undefined
        ? { minHeight: MIN_ARTIFACT_CARD_SIZE, minWidth: MIN_ARTIFACT_CARD_SIZE,
          maxWidth: CANVAS_MAX_SIZE, maxHeight: CANVAS_MAX_SIZE }
        : imageNodeResizeBounds(data.mediaAspectRatio))}
      keepAspectRatio={data.mediaAspectRatio !== undefined}
      onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />,
  };
  return (
    <>
      <CanvasHandle id="artifact-input" />
      <CanvasHandle id="artifact-output" />
      {halo}
      {selectionControl}
      {artifact.kind === "IMAGE" || artifact.kind === "VIDEO" || artifact.kind === "AUDIO"
        ? <MediaCanvasCard {...cardProps} onEdit={focusArtifactEditor}
          onDuplicate={() => data.onDuplicate(data.item)} onMakeMV={() => data.onMakeMV(data.item)}
          onComposeVideo={() => data.onComposeVideo(data.item)} onEditVideo={() => data.onEditVideo(data.item)} compositionDisabledReason={data.compositionDisabledReason} />
        : <ContentCanvasCard {...cardProps} />}
      {data.characterLabel ? <div className="pointer-events-none absolute bottom-1 left-1 flex max-w-full flex-wrap gap-1"><Badge variant="secondary" className="max-w-full truncate">{data.characterLabel}</Badge>{data.characterNeedsReview ? <Badge variant="outline">{t("character.needsReview")}</Badge> : null}</div> : null}
    </>
  );
});


const nodeTypes = { canvasCard: CanvasCardNode };

function SaveBadge({ state }: { state: "saved" | "saving" | "failed" | "conflict" }) {
  useLocale();
  const labels = { saved: t("common.saved"), saving: t("common.saving"), failed: t("canvas.workspace.saveFailed"),
    conflict: t("canvas.workspace.contentConflict") };
  const StatusIcon = state === "saved" ? CheckCircle : state === "saving" ? CircleNotch : WarningCircle;
  return <span className={`save-badge save-badge-${state}`} aria-live="polite"><StatusIcon aria-hidden />{labels[state]}</span>;
}

function WorkspaceError({ error }: { error: Error }) {
  useLocale();
  return <p className="mt-3 rounded-xl bg-red-50 p-3 text-xs text-red-800" role="alert">{workspaceErrorMessage(error)}</p>;
}

function workspaceErrorMessage(error: Error): string {
  return error instanceof ApiError || error instanceof CanvasConnectionError
    ? error.message : t("canvas.workspace.actionFailed");
}
