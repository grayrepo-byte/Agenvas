import { prepareMediaNode, AUDIO_CARD_WIDTH, AUDIO_CARD_HEIGHT, type PreparedMediaNode } from "./mediaNodeActions";
import { DropdownMenu } from "../../shared/ui/DropdownMenu";
import {
  Background,
  ConnectionLineType,
  Controls,
  MiniMap,
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
  type ResizeParams,
  type ReactFlowInstance,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { memo, useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link, Navigate, useParams } from "react-router";
import {
  ApiError,
  applyCanvasCommands,
  createCanvasConnection,
  createAgent,
  createArtifact,
  getCurrentUser,
  getMediaDraft,
  getProject,
  getProjectSnapshot,
  listCanvasItems,
  listAgents,
  listCanvasConnections,
  listArtifacts,
  projectExportManifestUrl,
  uploadImageAsset, uploadAudioAsset, uploadVideoAsset,
  updateAgent,
  disconnectCanvasConnection,
  duplicateCanvasItem,
  type Agent,
  type AgentRun,
  type Artifact,
  type CanvasCommand,
  type CanvasItem,
  type ProjectSnapshot,
} from "../../shared/api/client";
import { useCanvasStore } from "./canvasStore";
import { subscribeProjectEvents, type EventSyncStatus } from "./projectEvents";
import { AgentChatCard, AGENT_CHAT_WIDTH, AGENT_CHAT_HEIGHT, AGENT_CHAT_MIN_WIDTH, AGENT_CHAT_MIN_HEIGHT } from "./AgentChatCard";
import { ArtifactVersionHistory } from "./ArtifactVersionHistory";
import { canvasItemVersion } from "./versionedArtifact";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { TextGenerationEditor } from "./TextGenerationEditor";
import { MediaCanvasCard } from "./MediaCanvasCard";
import { ContentCanvasCard } from "./ContentCanvasCard";
import { ImageSquare, Sparkle, TextT, VideoCamera, MusicNotes, X, type Icon } from "@phosphor-icons/react";
import { CanvasToolMenu } from "./CanvasToolMenu";
import { CANVAS_POINTER_THRESHOLD, useCanvasInteraction } from "./canvasInteraction";
import { CanvasHandle } from "./CanvasHandle";
import { agentImageConnection, canvasRelationRemoval, canvasTargetHandleId, inputConnectionUpdate,
  isCanvasConnectionValid, mediaInputConnection, projectCanvasRelations,
  type CanvasRelationRemoval } from "./canvasRelations";
import { CANVAS_MAX_SIZE, imageNodeResizeBounds, persistableNodeSize, projectImageNodeSize } from "./imageNodeLayout";
import { useImageNodeRatios } from "./useImageNodeRatios";

type LayoutPatch = Pick<ResizeParams, "x" | "y" | "width" | "height">;
type CreationKind = "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "AGENT";
type DrawerKind = "AGENT" | "UPLOAD" | "ALIGN";
type CreationPoint = { x: number; y: number };
type CreationMenu = { x: number; y: number; point: CreationPoint };
type RestorableResource = { subjectType: "ARTIFACT" | "AGENT"; subjectId: string };
/** Card under the pointer during a connection gesture; the drop lands on the card, not on an exact port. */
type ConnectionTarget = { itemId: string; targetHandle: "agent-input" | "artifact-input"; valid: boolean };
const EDITOR_NODE_GAP = 32;
const MEDIA_EDITOR_VIEW_HEIGHT = 320;
const MEDIA_TOOLBAR_VIEW_HEIGHT = 70;
const MEDIA_VIEW_MARGIN = 24;
const MEDIA_EDITOR_VIEW_WIDTH = 680;
const MEDIA_MAX_INITIAL_ZOOM = 1;
const MEDIA_FOCUS_DELAY_MS = 150;
const MAX_AGENT_TITLE_LENGTH = 120;
const AUDIO_AGENT_INSTRUCTION = "协助用户创作音频提示词、对白与 MV 方案。绑定的音频只提供归档元数据和生成描述，不代表你已听到或分析了声音。不能调用媒体生成；需要生成音频或视频时，请引导用户在对应卡片中运行。";
const CREATION_MENU_WIDTH = 208;
/** Match the menu's title, rows, gaps and padding in styles.css so edge clamping stays accurate. */
const CREATION_MENU_HEIGHT = 218;
const CREATION_MENU_MARGIN = 12;
const DEFAULT_CARD_WIDTH = 280;
const DEFAULT_TEXT_CARD_HEIGHT = 180;
const DEFAULT_TEXT_CARD_TITLE = "新文字";
const DEFAULT_MEDIA_CARD_HEIGHT = 300;
const AUDIO_RESULT_CARD_HEIGHT = 160;
const DEFAULT_IMAGE_CARD_WIDTH = 225;
const DEFAULT_VIDEO_CARD_WIDTH = 534;
const MIN_ARTIFACT_CARD_SIZE = 120;
/** Drop tolerance around a hidden target handle, in flow units: it keeps the same feel on screen at any zoom. */
const CANVAS_CONNECTION_RADIUS = 80;
/** Delete and Backspace both delete the selected cards and relation lines; React Flow ignores both while typing in a field. */
const CANVAS_DELETE_KEY_CODES = ["Backspace", "Delete"];
const ARTIFACT_LABELS: Record<Artifact["kind"], string> = {
  TEXT: "文字", IMAGE: "图片", VIDEO: "视频", AUDIO: "音频",
};

function focusArtifactEditor() {
  document.querySelector<HTMLElement>(".workspace-media-editor [data-content-editor-focus], .workspace-media-editor .media-draft-prompt")?.focus();
}
const CREATION_KINDS: ReadonlyArray<{ kind: CreationKind; label: string; icon: Icon }> = [
  { kind: "TEXT", label: "文字", icon: TextT },
  { kind: "IMAGE", label: "图片", icon: ImageSquare },
  { kind: "AUDIO", label: "音频", icon: MusicNotes },
  { kind: "VIDEO", label: "视频", icon: VideoCamera },
  { kind: "AGENT", label: "Agent", icon: Sparkle },
];

type CanvasNodeData = {
  item: CanvasItem;
  projectId: string;
  activeRun: AgentRun | null;
  outputCount: number;
  onShowOutputs: (agent: Agent) => void;
  onResizeEnd: (itemId: string, layout: LayoutPatch) => void;
  onRemove: (item: CanvasItem) => void;
  onToggleLocked: (item: CanvasItem) => void;
  onInspect: (item: CanvasItem) => void;
  onDuplicate: (item: CanvasItem) => void;
  onMakeMV: (item: CanvasItem) => void;
  onUpdateAgent: (agent: Agent, name: string, instruction: string) => void;
  updatingAgent: boolean;
  updateAgentError: Error | null;
  imageAspectRatio: number | undefined;
  dragging: boolean;
  toolbarVisible: boolean;
  /** Connection gesture feedback: this card is under the pointer and will accept, or reject, the line. */
  connectionTarget: "valid" | "invalid" | null;
};

type CanvasNode = Node<CanvasNodeData, "canvasCard">;

/** Safe client-side validation message for unsupported canvas connection gestures. */
class CanvasConnectionError extends Error {}

/** React Flow workspace whose nodes are projections of query data plus transient layout drafts. */
export function ProjectWorkspacePage() {
  const { projectId } = useParams();
  if (!projectId) return <Navigate to="/projects" replace />;
  return <ProjectWorkspace key={projectId} projectId={projectId} />;
}

function ProjectWorkspace({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const textProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; zIndex: number; artifactId?: string } | null>(null);
  const [imageTitle, setImageTitle] = useState("");
  const [imageFile, setImageFile] = useState<File | null>(null);
  const imageInput = useRef<HTMLInputElement>(null);
  const imageProgress = useRef<{ projectId: string; file: File; title: string;
    assetId?: string; artifactId?: string; itemId?: string; createKey?: string } | null>(null);
  const [imagePartialStage, setImagePartialStage] = useState<"asset" | "artifact" | null>(null);
  const [agentName, setAgentName] = useState("Creator Agent");
  const [agentInstruction, setAgentInstruction] = useState("根据明确绑定的输入创作内容。");
  const [eventStatus, setEventStatus] = useState<EventSyncStatus>("connecting");
  const flow = useRef<ReactFlowInstance<CanvasNode> | null>(null);
  const canvasElement = useRef<HTMLElement>(null);
  const { tool, setTool, spaceHeld, selecting } = useCanvasInteraction();
  const creationMenuElement = useRef<HTMLDivElement>(null);
  const creationMenuReturnFocus = useRef<HTMLElement | null>(null);
  const [creationMenu, setCreationMenu] = useState<CreationMenu | null>(null);
  const [creationPoint, setCreationPoint] = useState<CreationPoint | null>(null);
  const [toolsKind, setToolsKind] = useState<DrawerKind | null>(null);
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [inspectingId, setInspectingId] = useState<string | null>(null);
  const [resourceSearch, setResourceSearch] = useState("");
  const mediaProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; artifactId?: string } | null>(null);
  const drafts = useCanvasStore((state) => state.drafts);
  const [draggingIds, setDraggingIds] = useState<string[]>([]);
  const saveState = useCanvasStore((state) => state.saveState);
  const selectedIds = useCanvasStore((state) => state.selectedIds);
  const updateDraft = useCanvasStore((state) => state.updateDraft);
  const clearDraft = useCanvasStore((state) => state.clearDraft);
  const setSaveState = useCanvasStore((state) => state.setSaveState);
  const setSaveError = (error: Error) => setSaveState(
    error instanceof ApiError && error.status === 409 ? "conflict" : "failed");
  const setSelectedIds = useCanvasStore((state) => state.setSelectedIds);
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
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
  const canvasConnections = useQuery({
    queryKey: ["canvas-connections", projectId],
    queryFn: () => listCanvasConnections(projectId),
    enabled: snapshot.isSuccess,
  });
  const imageRatios = useImageNodeRatios(canvas.data?.items);
  const effectiveNodeSize = useCallback((item: CanvasItem, patch?: Partial<LayoutPatch>) => {
    const draft = useCanvasStore.getState().drafts[item.id];
    const storedHeight = patch?.height ?? draft?.height ?? item.height;
    // A result has a compact player; preserve explicit user sizes and pending resize drafts.
    const compactAudio = item.artifact?.kind === "AUDIO" && item.selectedVersion
      && patch?.height === undefined && draft?.height === undefined
      && (item.height === AUDIO_CARD_HEIGHT || item.height === DEFAULT_MEDIA_CARD_HEIGHT);
    return projectImageNodeSize({
      width: Math.max(patch?.width ?? draft?.width ?? item.width, item.agent ? AGENT_CHAT_MIN_WIDTH : 0),
      height: Math.max(compactAudio ? AUDIO_RESULT_CARD_HEIGHT : storedHeight, item.agent ? AGENT_CHAT_MIN_HEIGHT : 0),
    }, imageRatios[item.id]);
  }, [imageRatios]);
  const resources = useQuery({
    queryKey: ["artifacts", projectId],
    queryFn: () => listArtifacts(projectId),
    enabled: resourcesOpen,
  });

  useEffect(() => {
    if (initialEventSequence.current === null || typeof EventSource === "undefined") return;
    return subscribeProjectEvents(projectId, initialEventSequence.current, {
      onChange: (event) => {
        if (event.type.startsWith("canvas.") || event.type === "task.status.changed") {
          void queryClient.invalidateQueries({ queryKey: ["canvas-media-versions", projectId] });
        }
        if (event.type.startsWith("artifact.") || event.type.startsWith("canvas.") ||
            event.type === "agent.instance.changed") {
          void queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
          if (event.type.startsWith("canvas.connection.")) {
            void queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
            void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
          }
          if (event.type === "canvas.items.changed") {
            void queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
            void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
          }
          if (event.type === "agent.instance.changed") {
            void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
          }
          if (event.type.startsWith("artifact.")) {
            void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
          }
          if (event.type === "canvas.item.selected_version.changed"
              && typeof event.payload.canvasItemId === "string") {
            void queryClient.invalidateQueries({
              queryKey: ["media-draft", projectId, event.payload.canvasItemId],
            });
            if (typeof event.payload.artifactId === "string") {
              void queryClient.invalidateQueries({
                queryKey: ["artifact-versions", projectId, event.payload.artifactId],
              });
            }
          }
        }
        if (event.type === "project.changed") {
          void queryClient.invalidateQueries({ queryKey: ["projects", projectId] });
        }
        if (event.type === "task.status.changed" || event.type === "agent.run.changed" || event.type === "agent.conversation.changed") {
          void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-history", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["agent-conversations", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["conversation-runs", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-actions", projectId] });
        }
        const activeRunId = queryClient.getQueryData<ProjectSnapshot>(["snapshot", projectId])?.activeRun?.id;
        if (event.type === "task.status.changed" && event.payload.artifactId) {
          void queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
        }
        if (event.type.startsWith("task.") && activeRunId) {
          void queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId, activeRunId] });
        }
        if (event.type === "task.status.changed") {
          void queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["direct-media-queue", projectId] });
        }
        if (event.type === "media.draft.changed") {
          void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
        }
        if (event.type === "usage.changed") {
          void queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] });
        }
      },
      onSnapshot: (fresh) => {
        queryClient.setQueryData(["snapshot", projectId], fresh);
        queryClient.setQueryData(["projects", projectId], fresh.project);
        queryClient.setQueryData(["canvas", projectId], fresh.canvas);
        queryClient.setQueryData(["canvas-connections", projectId], { items: fresh.connections });
        // The snapshot contains the current workspace, but not historical panels or lists.
        // A missed event may have changed any of them while the stream was unavailable.
        void queryClient.invalidateQueries({ queryKey: ["run-history", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["agent-conversations", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["conversation-runs", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-actions", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["canvas-media-versions", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] });
      },
      onStatus: setEventStatus,
    });
  }, [projectId, queryClient, snapshot.isSuccess]);

  const saveLayout = useMutation({
    mutationFn: (input: { item: CanvasItem; patch: Partial<LayoutPatch> } | { item: CanvasItem; patch: Partial<LayoutPatch> }[]) => {
      const updates = Array.isArray(input) ? input : [input];
      const commands = updates.map(({ item, patch }): CanvasCommand => {
        const draft = useCanvasStore.getState().drafts[item.id];
        const command: CanvasCommand = {
          type: "UPDATE_LAYOUT",
          itemId: item.id,
          expectedVersion: item.version,
          x: patch.x ?? draft?.x ?? item.x,
          y: patch.y ?? draft?.y ?? item.y,
          ...persistableNodeSize(effectiveNodeSize(item, patch)),
          zIndex: item.zIndex,
          groupId: item.groupId,
        };
        return command;
      });
      return applyCanvasCommands(projectId, commands);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved, variables) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      (Array.isArray(variables) ? variables : [variables]).forEach(({ item }) => clearDraft(item.id));
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const removeItem = useMutation({
    mutationFn: (item: CanvasItem) =>
      applyCanvasCommands(projectId, [
        { type: "REMOVE", itemId: item.id, expectedVersion: item.version },
      ]),
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved, item) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      clearDraft(item.id);
      setInspectingId(null);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const duplicateMediaItem = useMutation({
    mutationFn: async (item: CanvasItem) => {
      const draft = await getMediaDraft(projectId, item.id);
      const targetItemId = crypto.randomUUID();
      const result = await duplicateCanvasItem(projectId, item.id, {
        targetItemId,
        expectedSourceVersion: item.version,
        expectedSourceDraftVersion: draft.version,
        x: item.x + 32,
        y: item.y + 32,
        width: item.width,
        height: item.height,
        zIndex: Math.min(1000, item.zIndex + 1),
      });
      return result;
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: async (result) => {
      queryClient.setQueryData(["media-draft", projectId, result.item.id], result.draft);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
      ]);
      setSelectedIds([result.item.id]);
      setSaveState("saved");
    },
    onError: setSaveError,
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
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSaveState("saved");
    },
    onError: setSaveError,
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
          kind: "TEXT", title: DEFAULT_TEXT_CARD_TITLE,
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
          width: DEFAULT_CARD_WIDTH,
          height: DEFAULT_TEXT_CARD_HEIGHT,
          zIndex: pending.zIndex,
          locked: false,
        },
      ]);
      return { saved, itemId: pending.itemId };
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      setSelectedIds([itemId]);
      textProgress.current = null;
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const addImageCard = useMutation({
    mutationFn: async ({ cardTitle, file }: { cardTitle: string; file: File }) => {
      const audio = file.type.startsWith("audio/") || /\.(mp3|wav|ogg)$/i.test(file.name);
      const video = file.type === "video/mp4" || /\.mp4$/i.test(file.name);
      const progress = imageProgress.current?.projectId === projectId &&
        imageProgress.current.file === file && imageProgress.current.title === cardTitle
        ? imageProgress.current : { projectId, file, title: cardTitle };
      imageProgress.current = progress;
      if (!progress.assetId) {
        const asset = await (audio ? uploadAudioAsset : video ? uploadVideoAsset : uploadImageAsset)(projectId, file);
        progress.assetId = asset.id;
      }
      if (!progress.artifactId) {
        progress.createKey ??= crypto.randomUUID();
        const artifact = await createArtifact(projectId, {
          kind: audio ? "AUDIO" : video ? "VIDEO" : "IMAGE",
          title: cardTitle,
          content: { sourceType: "UPLOAD", assetId: progress.assetId },
        }, progress.createKey);
        progress.artifactId = artifact.id;
      }
      progress.itemId ??= crypto.randomUUID();
      const index = canvas.data?.items.length ?? 0;
      return applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT",
        itemId: progress.itemId,
        artifactId: progress.artifactId,
        x: 80 + (index % 3) * 320,
        y: 80 + Math.floor(index / 3) * 220,
        width: audio ? AUDIO_CARD_WIDTH : DEFAULT_CARD_WIDTH,
        height: AUDIO_CARD_HEIGHT,
        zIndex: index,
        locked: false,
      }]);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      imageProgress.current = null;
      setImagePartialStage(null);
      setImageTitle("");
      setImageFile(null);
      if (imageInput.current) imageInput.current.value = "";
      setSaveState("saved");
    },
    onError: (error) => {
      setImagePartialStage(imageProgress.current?.artifactId ? "artifact"
        : imageProgress.current?.assetId ? "asset" : null);
      setSaveError(error);
    },
  });
  const addBlankMedia = useMutation({
    mutationFn: async ({ kind, point }: { kind: "IMAGE" | "VIDEO" | "AUDIO"; point: CreationPoint }) => {
      const fingerprint = JSON.stringify({ projectId, kind, point });
      if (mediaProgress.current?.fingerprint !== fingerprint) {
        mediaProgress.current = { fingerprint, createKey: crypto.randomUUID(),
          itemId: crypto.randomUUID() };
      }
      const pending = mediaProgress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, { kind,
          title: `新${ARTIFACT_LABELS[kind]}`, content: null }, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const index = canvas.data?.items.length ?? 0;
      const saved = await applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT", itemId: pending.itemId, artifactId: pending.artifactId,
        x: point.x, y: point.y, width: kind === "IMAGE" ? DEFAULT_IMAGE_CARD_WIDTH : kind === "AUDIO" ? AUDIO_CARD_WIDTH : DEFAULT_VIDEO_CARD_WIDTH,
        height: kind === "AUDIO" ? AUDIO_CARD_HEIGHT : DEFAULT_MEDIA_CARD_HEIGHT, zIndex: index, locked: false,
      }]);
      return { saved, itemId: pending.itemId };
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["artifacts", projectId] });
      setSelectedIds([itemId]);
      mediaProgress.current = null;
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const mvProgress = useRef(new Map<string, PreparedMediaNode>());
  const makeMV = useMutation({ mutationFn: async (source: CanvasItem) => {
    if (!source.selectedVersionId) throw new Error("请先选用音频结果。");
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
  const audioConversationProgress = useRef(new Map<string, { itemId: string; agent?: Agent }>());
  const openAudioConversation = useMutation({
    mutationFn: async (source: CanvasItem) => {
      if (!source.artifact || source.artifact.kind !== "AUDIO" || !source.selectedVersionId)
        throw new Error("请先选用音频结果。");
      const intentId = `${source.id}:${source.selectedVersionId}`;
      const progress: { itemId: string; agent?: Agent } = audioConversationProgress.current.get(intentId) ?? { itemId: crypto.randomUUID() };
      audioConversationProgress.current.set(intentId, progress);
      // Re-read persisted agents before creating, including after a lost create response.
      // Conversation entry only creates/binds an idle card; sending a message remains explicit.
      progress.agent ??= (await listAgents(projectId)).items.find((agent) =>
        agent.instruction === AUDIO_AGENT_INSTRUCTION && agent.bindings.some((binding) =>
          binding.artifactId === source.artifact?.id && binding.selectedVersionId === source.selectedVersionId));
      progress.agent ??= await createAgent(projectId, {
        name: `${source.title} · 对话`.slice(0, MAX_AGENT_TITLE_LENGTH), instruction: AUDIO_AGENT_INSTRUCTION,
        bindings: [{ artifactId: source.artifact.id, selectedVersionId: source.selectedVersionId }],
      });
      const current = await listCanvasItems(projectId);
      const placed = current.items.find((item) => item.agent?.id === progress.agent?.id);
      if (placed) return { saved: current, itemId: placed.id };
      const saved = await applyCanvasCommands(projectId, [{ type: "PLACE_AGENT", itemId: progress.itemId,
        agentId: progress.agent.id, x: source.x + source.width + EDITOR_NODE_GAP, y: source.y,
        width: AGENT_CHAT_WIDTH, height: AGENT_CHAT_HEIGHT, zIndex: current.items.length, locked: false }]);
      return { saved, itemId: progress.itemId };
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
      setSelectedIds([itemId]);
      window.setTimeout(() => { void flow.current?.fitView({ nodes: [{ id: itemId }], padding: 0.15, maxZoom: 1 }); }, MEDIA_FOCUS_DELAY_MS);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const addAgentCard = useMutation({
    mutationFn: async ({ name, instruction }: { name: string; instruction: string }) => {
      const bindings = selectedArtifactBindings(canvas.data?.items ?? [], selectedIds);
      const agent = await createAgent(projectId, { name, instruction, bindings });
      const index = canvas.data?.items.length ?? 0;
      return applyCanvasCommands(projectId, [
        {
          type: "PLACE_AGENT",
          itemId: crypto.randomUUID(),
          agentId: agent.id,
          x: creationPoint?.x ?? 100 + (index % 3) * 360,
          y: creationPoint?.y ?? 100 + Math.floor(index / 3) * 360,
          width: AGENT_CHAT_WIDTH,
          height: AGENT_CHAT_HEIGHT,
          zIndex: index,
          locked: false,
        },
      ]);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setToolsKind(null);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const editAgent = useMutation({
    mutationFn: async ({ agent, name, instruction }: { agent: Agent; name: string; instruction: string }) => {
      await updateAgent(projectId, agent.id, {
        expectedVersion: agent.version,
        name,
        instruction,
        bindings: agent.bindings.map(({ artifactId, selectedVersionId }) => ({
          artifactId,
          selectedVersionId,
        })),
      });
      return listCanvasItems(projectId);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const bindSelection = useMutation({
    mutationFn: async () => {
      const items = canvas.data?.items ?? [];
      const selectedAgent = items.find(
        (item) => selectedIds.includes(item.id) && item.agent !== null,
      )?.agent;
      if (!selectedAgent) throw new Error("请选择一张 Agent 卡片。");
      const selected = selectedArtifactBindings(items, selectedIds);
      const bindings = new Map(
        selectedAgent.bindings.map((binding) => [
          binding.artifactId,
          { artifactId: binding.artifactId, selectedVersionId: binding.selectedVersionId },
        ]),
      );
      selected.forEach((binding) => bindings.set(binding.artifactId, binding));
      await updateAgent(projectId, selectedAgent.id, {
        expectedVersion: selectedAgent.version,
        name: selectedAgent.name,
        instruction: selectedAgent.instruction,
        bindings: [...bindings.values()],
      });
      return listCanvasItems(projectId);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const clearBindings = useMutation({
    mutationFn: async () => {
      const selectedAgent = (canvas.data?.items ?? []).find(
        (item) => selectedIds.includes(item.id) && item.agent !== null,
      )?.agent;
      if (!selectedAgent) throw new Error("请选择一张 Agent 卡片。");
      await updateAgent(projectId, selectedAgent.id, {
        expectedVersion: selectedAgent.version,
        name: selectedAgent.name,
        instruction: selectedAgent.instruction,
        bindings: [],
      });
      return listCanvasItems(projectId);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const connectInput = useMutation({
    mutationFn: async (connection: Connection) => {
      const agentImage = agentImageConnection(canvas.data?.items ?? [], connection);
      if (agentImage) {
        await createCanvasConnection(projectId, {
          sourceCanvasItemId: agentImage.sourceCanvasItemId,
          targetCanvasItemId: agentImage.targetCanvasItemId,
          sourceVersionId: agentImage.sourceVersionId,
          relationType: "AGENT_IMAGE_INPUT",
          expectedTargetAgentVersion: agentImage.agent.version,
        });
        return;
      }
      const update = inputConnectionUpdate(canvas.data?.items ?? [], connection);
      if (update) {
        await updateAgent(projectId, update.agent.id, {
          expectedVersion: update.agent.version,
          name: update.agent.name,
          instruction: update.agent.instruction,
          bindings: update.bindings,
        });
        return;
      }
      const media = mediaInputConnection(canvas.data?.items ?? [], connection);
      if (!media) throw new CanvasConnectionError("仅支持把图片卡片连到媒体或 Agent 输入。");
      const targetDraft = await getMediaDraft(projectId, media.targetCanvasItemId);
      await createCanvasConnection(projectId, { ...media, relationType: "MEDIA_INPUT",
        expectedTargetDraftVersion: targetDraft.version });
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] }),
      ]);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const removeMediaConnection = useMutation({
    mutationFn: async ({ connection }: Extract<CanvasRelationRemoval, { kind: "mediaConnection" }>) => {
      if (connection.relationType === "AGENT_IMAGE_INPUT") {
        const target = canvas.data?.items.find((item) => item.id === connection.targetCanvasItemId);
        if (!target?.agent) throw new CanvasConnectionError("Agent 卡片已不存在，请刷新画布。");
        return disconnectCanvasConnection(projectId, connection.id, {
          expectedTargetAgentVersion: target.agent.version,
        });
      }
      if (connection.relationType === "MEDIA_DERIVATION") {
        return disconnectCanvasConnection(projectId, connection.id, {});
      }
      const targetDraft = await getMediaDraft(projectId, connection.targetCanvasItemId);
      return disconnectCanvasConnection(projectId, connection.id, {
        expectedTargetDraftVersion: targetDraft.version,
      });
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] }),
      ]);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const removeInputBinding = useMutation({
    mutationFn: async ({ agent, bindingId }: Extract<CanvasRelationRemoval, { kind: "inputBinding" }>) => {
      await updateAgent(projectId, agent.id, {
        expectedVersion: agent.version,
        name: agent.name,
        instruction: agent.instruction,
        bindings: agent.bindings.filter((binding) => binding.id !== bindingId)
          .map(({ artifactId, selectedVersionId }) => ({ artifactId, selectedVersionId })),
      });
      return listCanvasItems(projectId);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const alignSelected = useMutation({
    mutationFn: async () => {
      const selected = (canvas.data?.items ?? []).filter((item) => selectedIds.includes(item.id));
      const layoutDrafts = useCanvasStore.getState().drafts;
      const targetX = Math.min(...selected.map((item) => layoutDrafts[item.id]?.x ?? item.x));
      const commands: CanvasCommand[] = selected.map((item) => {
        const layout = { x: targetX, y: layoutDrafts[item.id]?.y ?? item.y, ...effectiveNodeSize(item) };
        updateDraft(item.id, layout);
        return { type: "UPDATE_LAYOUT", itemId: item.id, expectedVersion: item.version,
          ...layout, ...persistableNodeSize(layout), zIndex: item.zIndex, groupId: item.groupId };
      });
      return { saved: await applyCanvasCommands(projectId, commands), itemIds: selected.map((item) => item.id) };
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: ({ saved, itemIds }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      itemIds.forEach(clearDraft);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const restoreResource = useMutation({
    mutationFn: async (resource: RestorableResource) => {
      const rect = canvasElement.current?.getBoundingClientRect();
      const point = rect && flow.current
        ? flow.current.screenToFlowPosition({ x: rect.left + rect.width / 2,
          y: rect.top + rect.height / 2 }) : { x: 80, y: 80 };
      const itemId = crypto.randomUUID();
      const placement = {
        itemId, x: point.x, y: point.y,
        zIndex: canvas.data?.items.length ?? 0, locked: false,
      };
      const command: CanvasCommand = resource.subjectType === "AGENT"
        ? { ...placement, type: "PLACE_AGENT", agentId: resource.subjectId,
          width: AGENT_CHAT_WIDTH, height: AGENT_CHAT_HEIGHT }
        : { ...placement, type: "PLACE_ARTIFACT", artifactId: resource.subjectId,
          width: DEFAULT_CARD_WIDTH, height: DEFAULT_MEDIA_CARD_HEIGHT };
      const saved = await applyCanvasCommands(projectId, [command]);
      return { saved, itemId };
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: ({ saved, itemId }) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      setSelectedIds([itemId]);
      setResourcesOpen(false);
      setSaveState("saved");
    },
    onError: setSaveError,
  });

  const saveLayoutMutate = saveLayout.mutate;
  const removeItemMutate = removeItem.mutate;
  const toggleLockedMutate = toggleLocked.mutate;
  const editAgentMutate = editAgent.mutate;
  const removeInputBindingMutate = removeInputBinding.mutate;
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
        const removal = canvasRelationRemoval(canvas.data?.items ?? [], canvasConnections.data?.items ?? [], edge);
        if (removal?.kind === "inputBinding") removeInputBindingMutate(removal);
        if (removal?.kind === "mediaConnection") removeMediaConnectionMutate(removal);
      }
      return false;
    },
    [canvas.data?.items, canvasConnections.data?.items, removeInputBindingMutate,
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
    const targetHandle = target ? canvasTargetHandleId(target) : null;
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

  const handleInspect = useCallback((item: CanvasItem) => {
    setToolsKind(null); setResourcesOpen(false); setInspectingId(item.id);
  }, []);

  const handleShowOutputs = useCallback((agent: Agent) => {
    const outputs = (canvas.data?.items ?? []).filter((item) => item.artifact && item.groupId === agent.outputGroupId);
    setSelectedIds(outputs.map((item) => item.id));
    if (outputs.length) void flow.current?.fitView({ nodes: outputs, padding: 0.2 });
  }, [canvas.data?.items, setSelectedIds]);
  const nodes = useMemo<CanvasNode[]>(
    () =>
      (canvas.data?.items ?? [])
        .filter((item) => item.artifact !== null || item.agent !== null)
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
              activeRun: snapshot.data?.activeRun ?? null,
              outputCount: item.agent ? (canvas.data?.items ?? []).filter((candidate) => candidate.artifact && candidate.groupId === item.agent?.outputGroupId).length : 0,
              onShowOutputs: handleShowOutputs,
              onResizeEnd: handleResizeEnd,
              onRemove: handleRemove,
              onToggleLocked: handleToggleLocked,
              onInspect: handleInspect,
              onDuplicate: handleDuplicate,
              onMakeMV: (item) => makeMV.mutate(item),
              onUpdateAgent: handleUpdateAgent,
              updatingAgent: editAgent.isPending,
              updateAgentError: editAgent.error,
              imageAspectRatio: imageRatios[item.id],
              dragging: draggingIds.includes(item.id),
              toolbarVisible: draggingIds.length === 0,
              connectionTarget: connectionTarget?.itemId === item.id
                ? (connectionTarget.valid ? "valid" : "invalid")
                : null,
            },
          };
        }),
    [
      canvas.data?.items,
      drafts,
      draggingIds,
      editAgent.isPending,
      editAgent.error,
      effectiveNodeSize,
      handleRemove,
      handleResizeEnd,
      handleShowOutputs,
      handleToggleLocked,
      handleInspect,
      handleDuplicate,
      handleUpdateAgent,
      imageRatios,
      projectId,
      selectedIds,
      selecting,
      connectionTarget,
      snapshot.data?.activeRun,
    ],
  );
  /**
   * Relation lines are projected from server data, but React Flow only applies selection changes itself for
   * uncontrolled edges: with a controlled `edges` prop it reports them through `onEdgesChange` and expects
   * them back on the prop. Selection is therefore the only locally owned part of a line.
   */
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([]);
  const relationEdges = useMemo(() => projectCanvasRelations(canvas.data?.items ?? [], canvasConnections.data?.items ?? [])
    .map((edge) => selectedEdgeIds.includes(edge.id) ? { ...edge, selected: true } : edge),
    [canvas.data?.items, canvasConnections.data?.items, selectedEdgeIds]);
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
        const current = useCanvasStore.getState().selectedIds;
        const next = new Set(current);
        for (const change of selectChanges) {
          if (change.selected) next.add(change.id);
          else next.delete(change.id);
        }
        const ids = [...next];
        if (ids.length !== current.length || ids.some((id) => !current.includes(id))) {
          setSelectedIds(ids);
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
    setSelectedIds([]);
    setSelectedEdgeIds([]);
  }, [setSelectedEdgeIds, setSelectedIds]);
  function submitAgent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    addAgentCard.mutate({ name: agentName, instruction: agentInstruction });
  }

  function submitImage(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (imageFile) addImageCard.mutate({ cardTitle: imageTitle, file: imageFile });
  }

  function openCreationMenu(clientX: number, clientY: number) {
    const rect = canvasElement.current?.getBoundingClientRect();
    if (!rect) return;
    const point = flow.current?.screenToFlowPosition({ x: clientX, y: clientY })
      ?? { x: clientX - rect.left, y: clientY - rect.top };
    const x = Math.max(CREATION_MENU_MARGIN, Math.min(clientX - rect.left,
      rect.width - CREATION_MENU_WIDTH - CREATION_MENU_MARGIN));
    const y = Math.max(CREATION_MENU_MARGIN, Math.min(clientY - rect.top,
      rect.height - CREATION_MENU_HEIGHT - CREATION_MENU_MARGIN));
    creationMenuReturnFocus.current = document.activeElement instanceof HTMLElement
      ? document.activeElement : null;
    setCreationMenu({ x, y, point });
  }

  useEffect(() => {
    if (creationMenu) creationMenuElement.current?.querySelector("button")?.focus();
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

  // Esc 由外向内收拢：先关创建菜单，再关底部编辑区。setSelectedIds 对相同值返回原 state，
  // 因此没有选中时按 Esc 不会引起重渲染。
  useEffect(() => {
    function closeOnEscape(event: KeyboardEvent) {
      if (event.key !== "Escape") return;
      if (creationMenu) {
        setCreationMenu(null);
        creationMenuReturnFocus.current?.focus();
        return;
      }
      setSelectedIds([]);
    }
    document.addEventListener("keydown", closeOnEscape);
    return () => document.removeEventListener("keydown", closeOnEscape);
  }, [creationMenu, setSelectedIds]);

  function chooseCreationKind(kind: CreationKind) {
    const point = creationMenu?.point;
    if (!point) return;
    setInspectingId(null);
    setCreationMenu(null);
    setResourcesOpen(false);
    setCreationPoint(point);
    if (kind === "TEXT") {
      setToolsKind(null);
      if (!addTextCard.isPending) addTextCard.mutate({ point });
    } else if (kind === "IMAGE" || kind === "VIDEO" || kind === "AUDIO") {
      addBlankMedia.mutate({ kind, point });
    } else {
      setToolsKind(kind);
    }
  }

  const selectedItems = (canvas.data?.items ?? []).filter((item) => selectedIds.includes(item.id));
  const selectedMedia = draggingIds.length === 0 && selectedItems.length === 1 && selectedItems[0]?.artifact && selectedItems[0].artifact.kind !== "TEXT"
    ? selectedItems[0] : undefined;
  const mediaFocus = useRef<string | null>(null);
  useEffect(() => {
    if (!selectedMedia) { mediaFocus.current = null; return; }
    if (mediaFocus.current === selectedMedia.id || !flow.current || !canvasElement.current) return;
    const timer = window.setTimeout(() => {
      if (!flow.current || !canvasElement.current) return;
      mediaFocus.current = selectedMedia.id;
      const bounds = canvasElement.current.getBoundingClientRect();
      const zoom = Math.min(MEDIA_MAX_INITIAL_ZOOM,
        (bounds.width - MEDIA_VIEW_MARGIN * 2) / Math.max(MEDIA_EDITOR_VIEW_WIDTH, selectedMedia.width),
        (bounds.height - MEDIA_EDITOR_VIEW_HEIGHT - MEDIA_TOOLBAR_VIEW_HEIGHT - MEDIA_VIEW_MARGIN * 2) / effectiveNodeSize(selectedMedia).height);
      if (zoom <= 0) return;
      // NodeToolbars stay in CSS pixels while the card zooms. Reserve the editor's space.
      void flow.current.setCenter(selectedMedia.x + selectedMedia.width / 2,
        selectedMedia.y + effectiveNodeSize(selectedMedia).height / 2
          + (MEDIA_EDITOR_VIEW_HEIGHT - MEDIA_TOOLBAR_VIEW_HEIGHT) / (2 * zoom), { zoom });
    }, MEDIA_FOCUS_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [selectedMedia, effectiveNodeSize]);

  const canBindSelection =
    selectedItems.filter((item) => item.agent !== null).length === 1 &&
    selectedItems.some((item) => item.artifact !== null);
  const canClearBindings = selectedItems.filter((item) => item.agent !== null).length === 1;
  const projectResources = [
    ...(resources.data?.items ?? []).map((artifact) => ({
      subjectType: "ARTIFACT" as const, subjectId: artifact.id,
      label: `${artifact.title} · ${artifact.kind} · ${artifact.resourceDefaultVersionId ? "有结果" : "草稿"}`,
    })),
    ...(snapshot.data?.agents ?? []).map((agent) => ({
      subjectType: "AGENT" as const, subjectId: agent.id, label: `${agent.name} · Agent`,
    })),
  ];

  if (currentUser.isError) return <Navigate to="/login" replace />;

  return (
    <main className="workspace-shell text-[var(--ink)]">
      <header className="workspace-header">
        <div className="flex items-center gap-4">
          <Link className="secondary-button" to="/projects">项目</Link>
          <div>
            <p className="text-xs text-[var(--muted)]">创作画布</p>
            <h1 className="text-lg font-semibold">{project.data?.name ?? "正在读取项目…"}</h1>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <button className="secondary-button" onClick={() => {
            setToolsKind(null); setInspectingId(null); setResourcesOpen(true);
          }} type="button">资源</button>
          <button className="secondary-button" onClick={() => {
            setResourcesOpen(false); setInspectingId(null); setToolsKind("UPLOAD");
          }} type="button">导入素材</button>
          {/* 导出清单只含项目的非密钥配置、产物历史与媒体元数据，用于备份与迁移。 */}
          <a className="secondary-button" download={`agenvas-project-${projectId}.json`}
            href={projectExportManifestUrl(projectId)}>导出清单</a>
          <span className="text-xs text-[var(--muted)]" role="status">
            {eventStatus === "live" ? "实时同步" :
              eventStatus === "failed" ? "同步失败，正在重试" :
                eventStatus === "recovering" ? "正在恢复项目快照…" : "正在连接事件流…"}
          </span>
          <SaveBadge state={saveState} />
        </div>
      </header>

      {(toolsKind || resourcesOpen) ? <aside className="workspace-drawer" aria-label={resourcesOpen ? "项目资源" : "创建与工具"}>
        <div className="workspace-drawer-heading">
          <h2 className="font-semibold">{resourcesOpen ? "项目资源" : "创建与工具"}</h2>
          <button aria-label="关闭抽屉" className="node-action" onClick={() => {
            setToolsKind(null); setResourcesOpen(false);
          }} type="button">关闭</button>
        </div>
        {resourcesOpen ? <>
          <label className="mt-3 block text-sm">搜索资源
            <input value={resourceSearch} onChange={(event) => setResourceSearch(event.target.value)}
              placeholder="标题或类型" /></label>
          {resources.isPending ? <p className="mt-3 text-sm">正在读取资源…</p> : null}
          {resources.error ? <WorkspaceError error={resources.error} /> : null}
          <ul className="mt-3 space-y-2">
            {projectResources.filter((resource) => resource.label.toLowerCase()
              .includes(resourceSearch.trim().toLowerCase())).map((resource) => {
              const placed = canvas.data?.items.some((item) =>
                item.subjectType === resource.subjectType && item.subjectId === resource.subjectId);
              const duplicateAllowed = resource.subjectType === "ARTIFACT";
              return <li className="resource-entry" key={`${resource.subjectType}:${resource.subjectId}`}>
                <span>{resource.label}</span>
                <button className="node-action" disabled={(!duplicateAllowed && placed) || restoreResource.isPending}
                  onClick={() => restoreResource.mutate(resource)} type="button">
                  {duplicateAllowed ? placed ? "再放一张" : "放到画布"
                    : placed ? "已在画布" : "放回画布"}</button>
              </li>;
            })}
          </ul>
          {restoreResource.error ? <WorkspaceError error={restoreResource.error} /> : null}
        </> : null}
        {toolsKind === "UPLOAD" ? <div className="mt-6 border-t border-[var(--line)] pt-5">
          <h2 className="text-base font-semibold">上传图片、视频或音频</h2>
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">图片支持 PNG、JPEG、WebP，最大 20 MiB/40 MP；视频支持 MP4，最大 500 MiB；音频支持 MP3、WAV、OGG Opus，最大 50 MiB/10 分钟。上传后创建对应媒体节点，可作为精确版本参考。</p>
          <form className="mt-4" onSubmit={submitImage}>
            <label className="text-sm font-medium">素材标题<input maxLength={160} required value={imageTitle} onChange={(event) => { setImageTitle(event.target.value); setImagePartialStage(null); }} /></label>
            <label className="mt-3 block text-sm font-medium">图片、视频或音频<input accept="image/png,image/jpeg,image/webp,video/mp4,audio/mpeg,audio/wav,audio/ogg" className="mt-2 block w-full" ref={imageInput} required type="file" onChange={(event) => { setImageFile(event.target.files?.[0] ?? null); setImagePartialStage(null); }} /></label>
            <button className="secondary-button mt-4 w-full" disabled={!imageFile || addImageCard.isPending} type="submit">{addImageCard.isPending ? "正在上传并放置…" : "上传并放到画布"}</button>
          </form>
          {addImageCard.error ? <WorkspaceError error={addImageCard.error} /> : null}
          {imagePartialStage ? <p className="mt-2 text-xs text-amber-900" role="status">{imagePartialStage === "artifact"
            ? "素材和产物已创建，但画布放置未完成；保留当前标题与文件重试会继续放置。"
            : "素材已归档，但产物创建未完成；保留当前标题与文件重试会复用已确认的上传。"}若请求结果不明，请先刷新确认，避免重复创建。</p> : null}
        </div> : null}
        {toolsKind === "AGENT" ? <div className="mt-6 border-t border-[var(--line)] pt-5">
          <h2 className="text-base font-semibold">添加 Creator Agent</h2>
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">当前选中的 Artifact 会以精确版本绑定；创建 Agent 不会自动运行。</p>
          <form className="mt-4" onSubmit={submitAgent}>
            <label className="text-sm font-medium">名称<input maxLength={120} required value={agentName} onChange={(event) => setAgentName(event.target.value)} /></label>
            <label className="mt-3 block text-sm font-medium">指令<textarea className="mt-2 min-h-24 w-full rounded-xl border border-[var(--line)] bg-white p-3" maxLength={8000} required value={agentInstruction} onChange={(event) => setAgentInstruction(event.target.value)} /></label>
            <button className="primary-button mt-4 w-full" disabled={addAgentCard.isPending} type="submit">{addAgentCard.isPending ? "正在添加…" : "添加 Agent 到画布"}</button>
          </form>
          {addAgentCard.error ? <WorkspaceError error={addAgentCard.error} /> : null}
        </div> : null}
        {saveLayout.error ? <WorkspaceError error={saveLayout.error} /> : null}
        {removeItem.error ? <WorkspaceError error={removeItem.error} /> : null}
        {toggleLocked.error ? <WorkspaceError error={toggleLocked.error} /> : null}
        {editAgent.error ? <WorkspaceError error={editAgent.error} /> : null}
        {toolsKind === "ALIGN" ? <div className="mt-6 border-t border-[var(--line)] pt-5">
          <h2 className="text-sm font-semibold">选择与对齐</h2>
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">按住 Cmd（macOS）或 Ctrl（其他系统）点击追加选择；按住 Shift 拖出选框可选择多张卡片。</p>
          <p className="mt-2 text-xs leading-5 text-[var(--muted)]">选中卡片后从右侧连接点拖出：落在 Agent 卡片上可保存输入绑定；图片落在图片或视频卡片上会把当前展示的精确版本加入目标草稿，但不会触发生成。靠近可用落点时落点会浮现并显示为强调色，不能建立的关系显示为红色且松手不生效。蓝线是 Agent 输入，灰线是媒体输入，指向历史版本时使用虚线，绿线是输出组。</p>
          <p className="mt-2 text-xs leading-5 text-[var(--muted)]">选中卡片按 Delete 或退格移除卡片，内容与历史仍保留在项目资源中；选中 Agent 输入线或媒体输入线可删除对应关系。输出组和生成版本内冻结的来源记录不能单独删除。</p>
          <button className="secondary-button mt-3 w-full" disabled={selectedIds.length < 2 || alignSelected.isPending} onClick={() => alignSelected.mutate()} type="button">左对齐已选卡片</button>
          <button className="secondary-button mt-3 w-full" disabled={!canBindSelection || bindSelection.isPending} onClick={() => bindSelection.mutate()} type="button">把已选 Artifact 绑定到 Agent</button>
          <button className="secondary-button mt-3 w-full" disabled={!canClearBindings || clearBindings.isPending} onClick={() => clearBindings.mutate()} type="button">清空已选 Agent 输入</button>
          {alignSelected.error ? <WorkspaceError error={alignSelected.error} /> : null}
          {bindSelection.error ? <WorkspaceError error={bindSelection.error} /> : null}
          {clearBindings.error ? <WorkspaceError error={clearBindings.error} /> : null}
          {connectInput.error ? <WorkspaceError error={connectInput.error} /> : null}
        </div> : null}
      </aside> : null}

      <section className={`workspace-canvas${selecting ? " is-select-tool" : " is-hand-tool"}`} aria-label="项目画布" ref={canvasElement}
        onDoubleClickCapture={(event) => {
          if (selecting && (event.target as HTMLElement).classList.contains("react-flow__pane")) {
            openCreationMenu(event.clientX, event.clientY);
          }
        }}>
        {snapshot.isPending || canvas.isPending ? <div className="canvas-message">正在恢复画布…</div> : null}
        {snapshot.error ? <div className="canvas-message"><WorkspaceError error={snapshot.error} /></div> : null}
        {canvas.error ? <div className="canvas-message"><WorkspaceError error={canvas.error} /></div> : null}
        {canvas.data && canvas.data.items.length === 0 ? <div className="canvas-message">双击空白画布或点击“+”添加第一张卡片。</div> : null}
        <ReactFlow<CanvasNode>
          colorMode="dark"
          connectOnClick={false}
          connectionLineType={ConnectionLineType.Bezier}
          connectionRadius={CANVAS_CONNECTION_RADIUS}
          deleteKeyCode={CANVAS_DELETE_KEY_CODES}
          edges={relationEdges}
          fitView
          isValidConnection={(connection) => isCanvasConnectionValid(canvas.data?.items ?? [], connection)}
          minZoom={0.25}
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
            const current = useCanvasStore.getState().selectedIds;
            // React Flow retains the group when clicking an already-selected node.
            // Only this case needs extra deselection; ordinary selection comes from onNodesChange.
            if (current.length > 1 && current.includes(node.id)) {
              handleNodesChange(current.filter((id) => id !== node.id)
                .map((id) => ({ id, type: "select", selected: false })));
            }
          }}
          onNodesChange={handleNodesChange}
          onNodeDoubleClick={(event, node) => {
            if (!selecting || !node.data.item.artifact || (event.target instanceof Element &&
              event.target.closest("button, a, input, textarea, select, summary"))) return;
            setSelectedIds([node.id]);
            window.requestAnimationFrame(focusArtifactEditor);
          }}
          onEdgesChange={handleEdgesChange}
          onInit={(instance) => { flow.current = instance; }}
          onMoveStart={(event) => { if (event) clearSelection(); }}
          selectionOnDrag={selecting}
          zoomOnDoubleClick={false}
        >
          {draggingIds.length === 0 && selectedItems.length === 1 && selectedItems[0]?.artifact ?
            <NodeToolbar nodeId={selectedItems[0].id} isVisible position={Position.Bottom} offset={EDITOR_NODE_GAP}
              className="workspace-media-toolbar nodrag nowheel nopan">
              <div className="workspace-media-editor" aria-label="所选卡片编辑区">
                <button aria-label="关闭编辑区" className="workspace-bottom-close"
                  onClick={() => setSelectedIds([])} type="button"><X size={15} /></button>
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
              </div>
            </NodeToolbar> : null}
          <Background color="var(--ui-border-strong)" gap={20} size={1.1} />
          <MiniMap pannable zoomable />
          <Controls position="bottom-right" />
        </ReactFlow>
        <CanvasToolMenu tool={tool} spaceHeld={spaceHeld} onToolChange={setTool} onAdd={() => {
          const rect = canvasElement.current?.getBoundingClientRect();
          if (rect) openCreationMenu(rect.left + rect.width / 2, rect.top + rect.height / 2);
        }} />
        {creationMenu ? <DropdownMenu className="workspace-create-menu" role="menu" aria-label="添加卡片"
          ref={creationMenuElement}
          style={{ left: creationMenu.x, top: creationMenu.y, width: CREATION_MENU_WIDTH }}>
          <p className="workspace-create-title">添加卡片</p>
          {CREATION_KINDS.map(({ kind, label, icon: CreationIcon }) => <button key={kind} role="menuitem"
            disabled={kind === "TEXT" && addTextCard.isPending}
            onClick={() => chooseCreationKind(kind)} type="button">
            <CreationIcon size={20} aria-hidden="true" /><span>{label}</span>
          </button>)}
        </DropdownMenu> : null}
        {addTextCard.isPending ? <div className="canvas-message" role="status">正在创建文字节点…</div> : null}
        {addTextCard.error ? <div className="canvas-message">
          <WorkspaceError error={addTextCard.error} />
          <button className="node-action" type="button" onClick={() => {
            if (addTextCard.variables) addTextCard.mutate(addTextCard.variables);
          }}>重试创建文字节点</button>
        </div> : null}
        {openAudioConversation.error ? <div className="canvas-message" role="alert"><WorkspaceError error={openAudioConversation.error} /></div> : null}
        {addBlankMedia.error ? <div className="canvas-message" role="alert">
          <WorkspaceError error={addBlankMedia.error} /></div> : null}
        {connectInput.error && !toolsKind ? <div className="canvas-message">
          <WorkspaceError error={connectInput.error} /></div> : null}
        {!toolsKind && !resourcesOpen && !inspectingId && (removeItem.error || toggleLocked.error) ?
          <div className="canvas-message"><WorkspaceError error={(removeItem.error ?? toggleLocked.error)!} /></div> : null}
        {draggingIds.length === 0 && selectedItems.length > 1 ? <div className="workspace-bottom-editor" aria-label="批量操作">
          <button aria-label="关闭编辑区" className="workspace-bottom-close"
            onClick={() => setSelectedIds([])} type="button"><X size={15} /></button>
          <span>{selectedItems.length} 张卡片已选中</span>
          <button className="node-action" disabled={alignSelected.isPending}
            onClick={() => alignSelected.mutate()} type="button">左对齐</button>
          <button className="node-action" disabled={!canBindSelection || bindSelection.isPending}
            onClick={() => bindSelection.mutate()} type="button">绑定到 Agent</button>
          <button className="node-action" disabled={!canClearBindings || clearBindings.isPending}
            onClick={() => clearBindings.mutate()} type="button">清空 Agent 输入</button>
        </div> : null}
      </section>
      {inspectingId && selectedItems.some((item) => item.id === inspectingId) ? (() => {
        const item = selectedItems.find((candidate) => candidate.id === inspectingId);
        if (!item?.artifact) return null;
        const inspectedVersion = canvasItemVersion(item);
        return <aside className="workspace-drawer media-inspector" aria-label="卡片详情">
          <div className="workspace-drawer-heading"><h2>{item.artifact.title}</h2>
            <button className="node-action" aria-label="关闭卡片详情" type="button" onClick={() => setInspectingId(null)}><X size={16} /></button></div>
          <p className="mt-3 text-xs text-[var(--muted)]">{ARTIFACT_LABELS[item.artifact.kind]} · {inspectedVersion ? "已有结果" : "暂无结果"}</p>
          {item.artifact.kind === "TEXT" ? <ArtifactVersionHistory artifact={item.artifact} /> : null}
          {inspectedVersion?.inputReferences.length ? <div className="mt-4 text-xs">
            <h3>输入引用（{inspectedVersion.inputReferences.length} 个精确版本）</h3><ul className="mt-2 space-y-2">
              {inspectedVersion.inputReferences.map((reference) =>
                <li className="break-all text-[var(--muted)]" key={`${reference.role}:${reference.order}:${reference.versionId}`}>
                  {reference.role} · {ARTIFACT_LABELS[reference.kind]} · {reference.versionId}
                </li>)}
            </ul>
          </div> : null}
          <div className="mt-5 flex gap-2">
            <button className="node-action" type="button" disabled={toggleLocked.isPending} onClick={() => handleToggleLocked(item)}>{toggleLocked.isPending ? "保存中…" : item.locked ? "解锁" : "锁定"}</button>
            <button className="node-action" type="button" disabled={removeItem.isPending} onClick={() => handleRemove(item)}>{removeItem.isPending ? "移除中…" : "移除卡片"}</button>
          </div>
          {removeItem.error ? <WorkspaceError error={removeItem.error} /> : null}
          {toggleLocked.error ? <WorkspaceError error={toggleLocked.error} /> : null}
          <p className="mt-3 text-xs text-[var(--muted)]">移除节点不会删除已归档内容。</p>
        </aside>;
      })() : null}
      <div className="workspace-narrow-warning">画布编辑需要至少 1280px 宽度；当前仅提供只读预览。</div>
    </main>
  );
}

const CanvasCardNode = memo(function CanvasCardNode({ data, selected }: NodeProps<CanvasNode>) {
  const halo = data.connectionTarget
    ? <span className={`canvas-connection-halo canvas-connection-halo--${data.connectionTarget}`} />
    : null;
  if (data.item.agent) return <>{halo}<AgentChatCard data={data} selected={selected || data.dragging} /></>;
  const artifact = data.item.artifact;
  if (!artifact) return null;
  const cardProps = {
    artifact, item: data.item, selected: selected || data.dragging, locked: data.item.locked,
    toolbarVisible: data.toolbarVisible,
    onInspect: () => data.onInspect(data.item),
    children: <NodeResizer isVisible={selected && data.toolbarVisible && !data.item.locked}
      {...(data.imageAspectRatio === undefined
        ? { minHeight: MIN_ARTIFACT_CARD_SIZE, minWidth: MIN_ARTIFACT_CARD_SIZE,
          maxWidth: CANVAS_MAX_SIZE, maxHeight: CANVAS_MAX_SIZE }
        : imageNodeResizeBounds(data.imageAspectRatio))}
      keepAspectRatio={data.imageAspectRatio !== undefined}
      onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />,
  };
  return (
    <>
      <CanvasHandle id="artifact-input" />
      <CanvasHandle id="artifact-output" />
      {halo}
      {artifact.kind === "IMAGE" || artifact.kind === "VIDEO" || artifact.kind === "AUDIO"
        ? <MediaCanvasCard {...cardProps} onEdit={focusArtifactEditor}
          onDuplicate={() => data.onDuplicate(data.item)} onMakeMV={() => data.onMakeMV(data.item)} />
        : <ContentCanvasCard {...cardProps} />}
    </>
  );
});


const nodeTypes = { canvasCard: CanvasCardNode };

function selectedArtifactBindings(items: CanvasItem[], selectedIds: string[]) {
  return items.flatMap((item) => {
    if (!selectedIds.includes(item.id) || !item.artifact || item.artifact.kind === "IMAGE") return [];
    const selectedVersionId = item.artifact.kind !== "TEXT"
      ? item.selectedVersionId : item.artifact.resourceDefaultVersionId;
    if (!selectedVersionId) return [];
    return [{ artifactId: item.artifact.id, selectedVersionId }];
  });
}

function SaveBadge({ state }: { state: "saved" | "saving" | "failed" | "conflict" }) {
  const labels = { saved: "已保存", saving: "保存中…", failed: "保存失败，草稿已保留",
    conflict: "内容有冲突，当前修改未保存" };
  return <span className={`save-badge save-badge-${state}`}>{labels[state]}</span>;
}

function WorkspaceError({ error }: { error: Error }) {
  const message = error instanceof ApiError || error instanceof CanvasConnectionError
    ? error.message : "操作失败，画布草稿仍保留在本页。";
  return <p className="mt-3 rounded-xl bg-red-50 p-3 text-xs text-red-800" role="alert">{message}</p>;
}
