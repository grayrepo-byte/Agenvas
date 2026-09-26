import {
  Background,
  ConnectionLineType,
  Controls,
  Handle,
  MiniMap,
  NodeResizer,
  Position,
  ReactFlow,
  type Connection,
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
  createAgent,
  createArtifact,
  getCurrentUser,
  getProject,
  getProjectSnapshot,
  listCanvasItems,
  listArtifacts,
  reviseArtifact,
  uploadImageAsset,
  updateAgent,
  type Agent,
  type AgentRun,
  type Artifact,
  type CanvasCommand,
  type CanvasItem,
  type ProjectSnapshot,
} from "../../shared/api/client";
import { useCanvasStore } from "./canvasStore";
import { subscribeProjectEvents, type EventSyncStatus } from "./projectEvents";
import { MediaExportPanel } from "./MediaExportPanel";
import { ShotRedoEditor } from "./ShotRedoEditor";
import { AgentChatCard, AGENT_CHAT_WIDTH, AGENT_CHAT_HEIGHT, AGENT_CHAT_MIN_WIDTH, AGENT_CHAT_MIN_HEIGHT } from "./AgentChatCard";
import { ManualStoryboardPanel } from "./ManualStoryboardPanel";
import { ArtifactVersionHistory } from "./ArtifactVersionHistory";
import { StructuredArtifactEditor } from "./StructuredArtifactEditor";
import { hasCurrentVersion } from "./versionedArtifact";
import { MediaDraftEditor } from "./MediaDraftEditor";
import { MediaCanvasCard } from "./MediaCanvasCard";
import { ContentCanvasCard } from "./ContentCanvasCard";
import { MediaCardUpload } from "./MediaCardUpload";
import { Plus, X } from "@phosphor-icons/react";
import { inputConnectionUpdate, projectCanvasRelations,
  semanticConnectionRevision, semanticReferenceRemoval } from "./canvasRelations";
import { CANVAS_MAX_SIZE, imageNodeResizeBounds, persistableNodeSize, projectImageNodeSize } from "./imageNodeLayout";
import { useImageNodeRatios } from "./useImageNodeRatios";

type LayoutPatch = Pick<ResizeParams, "x" | "y" | "width" | "height">;
type ArtifactInputReference = NonNullable<
  NonNullable<CanvasItem["artifact"]>["currentVersion"]
>["inputReferences"][number];
type CreationKind = "TEXT" | "IMAGE" | "VIDEO" | "CHARACTER" | "SCENE" | "SHOT" | "AGENT";
type DrawerKind = CreationKind | "UPLOAD" | "EXPORT" | "ALIGN";
type CreationPoint = { x: number; y: number };
type CreationMenu = { x: number; y: number; point: CreationPoint };
type RestorableResource = { subjectType: "ARTIFACT" | "AGENT"; subjectId: string };
const CREATION_MENU_WIDTH = 184;
const CREATION_MENU_HEIGHT = 330;
const CREATION_MENU_MARGIN = 12;
const DEFAULT_CARD_WIDTH = 280;
const DEFAULT_MEDIA_CARD_HEIGHT = 300;
const DEFAULT_IMAGE_CARD_WIDTH = 225;
const DEFAULT_VIDEO_CARD_WIDTH = 534;
const MIN_ARTIFACT_CARD_SIZE = 120;
const ARTIFACT_LABELS: Record<Artifact["kind"], string> = {
  TEXT: "文字", IMAGE: "图片", VIDEO: "视频", CHARACTER: "角色", SCENE: "场景", SHOT: "镜头",
};

function focusArtifactEditor() {
  document.querySelector<HTMLElement>(".workspace-bottom-editor [data-content-editor-focus], .workspace-bottom-editor .media-draft-prompt")?.focus();
}
const CREATION_KINDS: ReadonlyArray<{ kind: CreationKind; label: string }> = [
  { kind: "TEXT", label: "文字" }, { kind: "IMAGE", label: "图片" },
  { kind: "VIDEO", label: "视频" }, { kind: "CHARACTER", label: "角色" },
  { kind: "SCENE", label: "场景" }, { kind: "SHOT", label: "镜头" },
  { kind: "AGENT", label: "Agent" },
];

type CanvasNodeData = {
  item: CanvasItem;
  projectId: string;
  activeRun: AgentRun | null;
  redoCandidates: { artifactId: string; versionId: string; title: string }[];
  outputCount: number;
  onShowOutputs: (agent: Agent) => void;
  onResizeEnd: (itemId: string, layout: LayoutPatch) => void;
  onRemove: (item: CanvasItem) => void;
  onToggleLocked: (item: CanvasItem) => void;
  onInspect: (item: CanvasItem) => void;
  onUpload: (item: CanvasItem) => void;
  onUpdateAgent: (agent: Agent, name: string, instruction: string) => void;
  updatingAgent: boolean;
  updateAgentError: Error | null;
  imageAspectRatio: number | undefined;
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
  const [title, setTitle] = useState("");
  const [text, setText] = useState("");
  const textProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; artifactId?: string } | null>(null);
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
  const creationMenuElement = useRef<HTMLDivElement>(null);
  const creationMenuReturnFocus = useRef<HTMLElement | null>(null);
  const [creationMenu, setCreationMenu] = useState<CreationMenu | null>(null);
  const [creationPoint, setCreationPoint] = useState<CreationPoint | null>(null);
  const [toolsKind, setToolsKind] = useState<DrawerKind | null>(null);
  const [resourcesOpen, setResourcesOpen] = useState(false);
  const [inspectingId, setInspectingId] = useState<string | null>(null);
  const [uploadingItem, setUploadingItem] = useState<CanvasItem | null>(null);
  const [resourceSearch, setResourceSearch] = useState("");
  const mediaProgress = useRef<{ fingerprint: string; createKey: string;
    itemId: string; artifactId?: string } | null>(null);
  const drafts = useCanvasStore((state) => state.drafts);
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
  const imageRatios = useImageNodeRatios(canvas.data?.items);
  const effectiveNodeSize = useCallback((item: CanvasItem, patch?: Partial<LayoutPatch>) => {
    const draft = useCanvasStore.getState().drafts[item.id];
    return projectImageNodeSize({
      width: Math.max(patch?.width ?? draft?.width ?? item.width, item.agent ? AGENT_CHAT_MIN_WIDTH : 0),
      height: Math.max(patch?.height ?? draft?.height ?? item.height, item.agent ? AGENT_CHAT_MIN_HEIGHT : 0),
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
        if (event.type.startsWith("artifact.") || event.type === "canvas.items.changed" ||
            event.type === "agent.instance.changed") {
          void queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
          if (event.type === "agent.instance.changed") {
            void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
          }
          if (event.type.startsWith("artifact.")) {
            void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
          }
        }
        if (event.type === "project.changed") {
          void queryClient.invalidateQueries({ queryKey: ["projects", projectId] });
        }
        if (event.type === "task.status.changed" || event.type === "agent.run.changed" || event.type === "agent.conversation.changed" ||
            event.type.startsWith("execution.plan.")) {
          void queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-history", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["agent-conversations", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["conversation-runs", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-history-plans", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["run-actions", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["provider-attempts", projectId] });
        }
        const activeRunId = queryClient.getQueryData<ProjectSnapshot>(["snapshot", projectId])?.activeRun?.id;
        if (event.type.startsWith("execution.plan.") && activeRunId) {
          void queryClient.invalidateQueries({ queryKey: ["plans", projectId, activeRunId] });
        }
        if (event.type === "task.status.changed" && event.payload.artifactId) {
          void queryClient.invalidateQueries({ queryKey: ["canvas", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
        }
        if (event.type.startsWith("task.") && activeRunId) {
          void queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId, activeRunId] });
        }
        if (event.type === "task.status.changed") {
          void queryClient.invalidateQueries({ queryKey: ["media-exports", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] });
          void queryClient.invalidateQueries({ queryKey: ["direct-media-queue", projectId] });
        }
        if (event.type === "media.draft.changed") {
          void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
        }
        if (event.type === "export.proposal.changed") {
          void queryClient.invalidateQueries({ queryKey: ["export-proposals", projectId] });
        }
        if (event.type === "usage.changed") {
          void queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] });
        }
        if (event.type === "shot.keyframe.selected" && activeRunId) {
          void queryClient.invalidateQueries({ queryKey: ["keyframe-selection", projectId,
            activeRunId] });
        }
      },
      onSnapshot: (fresh) => {
        queryClient.setQueryData(["snapshot", projectId], fresh);
        queryClient.setQueryData(["projects", projectId], fresh.project);
        queryClient.setQueryData(["canvas", projectId], fresh.canvas);
        // The snapshot contains the current workspace, but not historical panels or lists.
        // A missed event may have changed any of them while the stream was unavailable.
        void queryClient.invalidateQueries({ queryKey: ["run-history", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["agent-conversations", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["conversation-runs", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-history-plans", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-actions", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["provider-attempts", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["plans", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["keyframe-selection", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["export-proposals", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["media-exports", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["media-draft", projectId] });
        void queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] });
      },
      onStatus: setEventStatus,
    });
  }, [projectId, queryClient, snapshot.isSuccess]);

  const saveLayout = useMutation({
    mutationFn: ({ item, patch }: { item: CanvasItem; patch: Partial<LayoutPatch> }) => {
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
      return applyCanvasCommands(projectId, [command]);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved, variables) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      clearDraft(variables.item.id);
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
    mutationFn: async ({ cardTitle, cardText }: { cardTitle: string; cardText: string }) => {
      const fingerprint = JSON.stringify({ projectId, cardTitle, cardText });
      if (textProgress.current?.fingerprint !== fingerprint) {
        textProgress.current = { fingerprint, createKey: crypto.randomUUID(),
          itemId: crypto.randomUUID() };
      }
      const pending = textProgress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, {
          kind: "TEXT", title: cardTitle,
          content: { format: "PLAIN_TEXT", text: cardText },
        }, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const index = canvas.data?.items.length ?? 0;
      return applyCanvasCommands(projectId, [
        {
          type: "PLACE_ARTIFACT",
          itemId: pending.itemId,
          artifactId: pending.artifactId,
          x: creationPoint?.x ?? 80 + (index % 3) * 320,
          y: creationPoint?.y ?? 80 + Math.floor(index / 3) * 220,
          width: 280,
          height: 180,
          zIndex: index,
          locked: false,
        },
      ]);
    },
    onMutate: () => setSaveState("saving"),
    onSuccess: (saved) => {
      queryClient.setQueryData(["canvas", projectId], saved);
      textProgress.current = null;
      setTitle("");
      setText("");
      setToolsKind(null);
      setSaveState("saved");
    },
    onError: setSaveError,
  });
  const addImageCard = useMutation({
    mutationFn: async ({ cardTitle, file }: { cardTitle: string; file: File }) => {
      const progress = imageProgress.current?.projectId === projectId &&
        imageProgress.current.file === file && imageProgress.current.title === cardTitle
        ? imageProgress.current : { projectId, file, title: cardTitle };
      imageProgress.current = progress;
      if (!progress.assetId) {
        const asset = await uploadImageAsset(projectId, file);
        progress.assetId = asset.id;
      }
      if (!progress.artifactId) {
        progress.createKey ??= crypto.randomUUID();
        const artifact = await createArtifact(projectId, {
          kind: "IMAGE",
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
        width: 280,
        height: 240,
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
    mutationFn: async ({ kind, point }: { kind: "IMAGE" | "VIDEO"; point: CreationPoint }) => {
      const fingerprint = JSON.stringify({ projectId, kind, point });
      if (mediaProgress.current?.fingerprint !== fingerprint) {
        mediaProgress.current = { fingerprint, createKey: crypto.randomUUID(),
          itemId: crypto.randomUUID() };
      }
      const pending = mediaProgress.current;
      if (!pending.artifactId) {
        const artifact = await createArtifact(projectId, { kind,
          title: kind === "IMAGE" ? "新图片" : "新视频", content: null }, pending.createKey);
        pending.artifactId = artifact.id;
      }
      const index = canvas.data?.items.length ?? 0;
      const saved = await applyCanvasCommands(projectId, [{
        type: "PLACE_ARTIFACT", itemId: pending.itemId, artifactId: pending.artifactId,
        x: point.x, y: point.y, width: kind === "IMAGE" ? DEFAULT_IMAGE_CARD_WIDTH : DEFAULT_VIDEO_CARD_WIDTH,
        height: DEFAULT_MEDIA_CARD_HEIGHT, zIndex: index, locked: false,
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
      if (connection.targetHandle === "artifact-input") {
        const update = semanticConnectionRevision(canvas.data?.items ?? [], connection);
        if (!update) {
          throw new CanvasConnectionError("仅支持图片→角色/场景、角色/场景→镜头的精确版本语义关系。");
        }
        if (update.revision) {
          await reviseArtifact(projectId, update.artifactId, update.revision);
        }
        return listCanvasItems(projectId);
      }
      const update = inputConnectionUpdate(canvas.data?.items ?? [], connection);
      if (!update) {
        throw new CanvasConnectionError("仅支持把 Artifact 连到 Agent 输入或支持的 Artifact 语义关系；连线不会触发生成。");
      }
      await updateAgent(projectId, update.agent.id, {
        expectedVersion: update.agent.version,
        name: update.agent.name,
        instruction: update.agent.instruction,
        bindings: update.bindings,
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
  const removeReference = useMutation({
    mutationFn: async ({ item, reference }: { item: CanvasItem;
      reference: ArtifactInputReference }) => {
      const revision = semanticReferenceRemoval(item, reference);
      if (!item.artifact || !revision) {
        throw new CanvasConnectionError("此引用是必填项或版本已变化，不能直接移除。请刷新后检查。");
      }
      await reviseArtifact(projectId, item.artifact.id, revision);
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
  const removeReferenceMutate = removeReference.mutate;

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
  const handleToggleLocked = useCallback(
    (item: CanvasItem) => toggleLockedMutate(item),
    [toggleLockedMutate],
  );
  const handleUpdateAgent = useCallback(
    (agent: Agent, name: string, instruction: string) =>
      editAgentMutate({ agent, name, instruction }),
    [editAgentMutate],
  );
  const handleRemoveReference = useCallback(
    (item: CanvasItem,
      reference: ArtifactInputReference) =>
      removeReferenceMutate({ item, reference }),
    [removeReferenceMutate],
  );

  const handleInspect = useCallback((item: CanvasItem) => {
    setToolsKind(null); setResourcesOpen(false); setUploadingItem(null); setInspectingId(item.id);
  }, []);
  const handleUpload = useCallback((item: CanvasItem) => {
    // Each opening has a distinct identity, so an older upload cannot close a newer session.
    setToolsKind(null); setResourcesOpen(false); setInspectingId(null); setUploadingItem({ ...item });
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
            draggable: !item.locked,
            selected: selectedIds.includes(item.id),
            data: {
              item,
              projectId,
              activeRun: snapshot.data?.activeRun ?? null,
              redoCandidates: item.agent ? (canvas.data?.items ?? []).flatMap((candidate) => {
                const shot = candidate.artifact;
                if (!shot?.currentVersionId || shot.kind !== "SHOT" ||
                  !item.agent?.bindings.some((binding) =>
                  binding.artifactId === shot.id &&
                  binding.selectedVersionId === shot.currentVersionId)) return [];
                return [{ artifactId: shot.id, versionId: shot.currentVersionId,
                  title: shot.title }];
              }) : [],
              outputCount: item.agent ? (canvas.data?.items ?? []).filter((candidate) => candidate.artifact && candidate.groupId === item.agent?.outputGroupId).length : 0,
              onShowOutputs: handleShowOutputs,
              onResizeEnd: handleResizeEnd,
              onRemove: handleRemove,
              onToggleLocked: handleToggleLocked,
              onInspect: handleInspect,
              onUpload: handleUpload,
              onUpdateAgent: handleUpdateAgent,
              updatingAgent: editAgent.isPending,
              updateAgentError: editAgent.error,
              imageAspectRatio: imageRatios[item.id],
            },
          };
        }),
    [
      canvas.data?.items,
      drafts,
      editAgent.isPending,
      editAgent.error,
      effectiveNodeSize,
      handleRemove,
      handleResizeEnd,
      handleShowOutputs,
      handleToggleLocked,
      handleInspect,
      handleUpload,
      handleUpdateAgent,
      imageRatios,
      projectId,
      selectedIds,
      snapshot.data?.activeRun,
    ],
  );
  const relationEdges = useMemo(() => projectCanvasRelations(canvas.data?.items ?? []),
    [canvas.data?.items]);

  const handleNodesChange = useCallback(
    (changes: NodeChange<CanvasNode>[]) => {
      for (const change of changes) {
        if (change.type === "position" && change.position) {
          updateDraft(change.id, change.position);
        } else if (change.type === "dimensions" && change.dimensions && change.resizing) {
          // DOM measurements reflect the current projection; only a resize gesture is a draft.
          updateDraft(change.id, change.dimensions);
        }
      }
    },
    [updateDraft],
  );
  const handleSelectionChange = useCallback(({ nodes: selectedNodes }:
    { nodes: CanvasNode[] }) => {
    const next = selectedNodes.map((node) => node.id);
    const current = useCanvasStore.getState().selectedIds;
    if (next.length === current.length && next.every((id, index) => id === current[index])) return;
    setSelectedIds(next);
  }, [setSelectedIds]);

  function submitText(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    addTextCard.mutate({ cardTitle: title, cardText: text });
  }

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
    setInspectingId(null); setUploadingItem(null);
    setCreationMenu(null);
    setResourcesOpen(false);
    setCreationPoint(point);
    if (kind === "IMAGE" || kind === "VIDEO") {
      addBlankMedia.mutate({ kind, point });
    } else {
      setToolsKind(kind);
    }
  }

  const selectedItems = (canvas.data?.items ?? []).filter((item) => selectedIds.includes(item.id));
  const canBindSelection =
    selectedItems.filter((item) => item.agent !== null).length === 1 &&
    selectedItems.some((item) => item.artifact !== null);
  const canClearBindings = selectedItems.filter((item) => item.agent !== null).length === 1;
  const projectResources = [
    ...(resources.data?.items ?? []).map((artifact) => ({
      subjectType: "ARTIFACT" as const, subjectId: artifact.id,
      label: `${artifact.title} · ${artifact.kind} · ${artifact.currentVersionId ? "有结果" : "草稿"}`,
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
            setToolsKind(null); setInspectingId(null); setUploadingItem(null); setResourcesOpen(true);
          }} type="button">资源</button>
          <button className="secondary-button" onClick={() => {
            setResourcesOpen(false); setInspectingId(null); setUploadingItem(null); setToolsKind("UPLOAD");
          }} type="button">导入素材</button>
          <button className="secondary-button" onClick={() => {
            setResourcesOpen(false); setInspectingId(null); setUploadingItem(null); setToolsKind("EXPORT");
          }} type="button">导出</button>
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
              return <li className="resource-entry" key={`${resource.subjectType}:${resource.subjectId}`}>
                <span>{resource.label}</span>
                <button className="node-action" disabled={placed || restoreResource.isPending}
                  onClick={() => restoreResource.mutate(resource)} type="button">
                  {placed ? "已在画布" : "放回画布"}</button>
              </li>;
            })}
          </ul>
          {restoreResource.error ? <WorkspaceError error={restoreResource.error} /> : null}
        </> : null}
        {toolsKind === "TEXT" ? <>
        <h2 className="text-base font-semibold">添加文字卡片</h2>
        <p className="mt-1 text-xs leading-5 text-[var(--muted)]">创建 Artifact 后再放到画布；删除卡片不会删除内容。</p>
        <form className="mt-4" onSubmit={submitText}>
          <label className="text-sm font-medium">标题<input maxLength={160} required value={title} onChange={(event) => setTitle(event.target.value)} /></label>
          <label className="mt-3 block text-sm font-medium">内容<textarea className="mt-2 min-h-32 w-full rounded-xl border border-[var(--line)] bg-white p-3" maxLength={20000} required value={text} onChange={(event) => setText(event.target.value)} /></label>
          <button className="primary-button mt-4 w-full" disabled={addTextCard.isPending} type="submit">{addTextCard.isPending ? "正在添加…" : "添加到画布"}</button>
        </form>
        {addTextCard.error ? <WorkspaceError error={addTextCard.error} /> : null}
        </> : null}
        {toolsKind === "CHARACTER" || toolsKind === "SCENE" || toolsKind === "SHOT" ?
          <ManualStoryboardPanel key={toolsKind} initialKind={toolsKind}
          placement={creationPoint ?? undefined} projectId={projectId} items={canvas.data?.items ?? []}
          onSaveStart={() => setSaveState("saving")}
          onSaved={(saved) => {
            queryClient.setQueryData(["canvas", projectId], saved);
            setSaveState("saved");
            setToolsKind(null);
          }}
          onSaveError={setSaveError} /> : null}
        {toolsKind === "UPLOAD" ? <div className="mt-6 border-t border-[var(--line)] pt-5">
          <h2 className="text-base font-semibold">上传参考图</h2>
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">支持 PNG、JPEG、WebP；不超过 20 MiB/40 MP。上传后选中图片卡片与 Agent 卡片，再绑定为精确版本输入。模型规划默认不读取图片字节。</p>
          <form className="mt-4" onSubmit={submitImage}>
            <label className="text-sm font-medium">图片标题<input maxLength={160} required value={imageTitle} onChange={(event) => { setImageTitle(event.target.value); setImagePartialStage(null); }} /></label>
            <label className="mt-3 block text-sm font-medium">参考图片<input accept="image/png,image/jpeg,image/webp" className="mt-2 block w-full" ref={imageInput} required type="file" onChange={(event) => { setImageFile(event.target.files?.[0] ?? null); setImagePartialStage(null); }} /></label>
            <button className="secondary-button mt-4 w-full" disabled={!imageFile || addImageCard.isPending} type="submit">{addImageCard.isPending ? "正在上传并放置…" : "上传并放到画布"}</button>
          </form>
          {addImageCard.error ? <WorkspaceError error={addImageCard.error} /> : null}
          {imagePartialStage ? <p className="mt-2 text-xs text-amber-900" role="status">{imagePartialStage === "artifact"
            ? "图片和产物已创建，但画布放置未完成；保留当前标题与文件重试会继续放置。"
            : "图片已归档，但产物创建未完成；保留当前标题与文件重试会复用已确认的上传。"}若请求结果不明，请先刷新并核对，避免重复创建。</p> : null}
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
          <p className="mt-1 text-xs leading-5 text-[var(--muted)]">按住 Shift 或拖出选框选择多张卡片。</p>
          <p className="mt-2 text-xs leading-5 text-[var(--muted)]">从 Artifact 右侧连接点拖到 Agent 左侧可保存输入；拖到另一张 Artifact 左侧可建立图片→角色/场景、角色/场景→镜头的精确版本引用，并为目标产物创建新版本（场景→镜头会替换原场景引用）。蓝线是输入、绿线是输出组、灰虚线是素材引用；连线不会触发生成。</p>
          <button className="secondary-button mt-3 w-full" disabled={selectedIds.length < 2 || alignSelected.isPending} onClick={() => alignSelected.mutate()} type="button">左对齐已选卡片</button>
          <button className="secondary-button mt-3 w-full" disabled={!canBindSelection || bindSelection.isPending} onClick={() => bindSelection.mutate()} type="button">把已选 Artifact 绑定到 Agent</button>
          <button className="secondary-button mt-3 w-full" disabled={!canClearBindings || clearBindings.isPending} onClick={() => clearBindings.mutate()} type="button">清空已选 Agent 输入</button>
          {alignSelected.error ? <WorkspaceError error={alignSelected.error} /> : null}
          {bindSelection.error ? <WorkspaceError error={bindSelection.error} /> : null}
          {clearBindings.error ? <WorkspaceError error={clearBindings.error} /> : null}
          {connectInput.error ? <WorkspaceError error={connectInput.error} /> : null}
          {removeReference.error ? <WorkspaceError error={removeReference.error} /> : null}
        </div> : null}
        {toolsKind === "EXPORT" ? <MediaExportPanel projectId={projectId}
          items={canvas.data?.items ?? []} /> : null}
      </aside> : null}

      <section className="workspace-canvas" aria-label="项目画布" ref={canvasElement}
        onDoubleClickCapture={(event) => {
          if ((event.target as HTMLElement).classList.contains("react-flow__pane")) {
            openCreationMenu(event.clientX, event.clientY);
          }
        }}>
        {snapshot.isPending || canvas.isPending ? <div className="canvas-message">正在恢复画布…</div> : null}
        {snapshot.error ? <div className="canvas-message"><WorkspaceError error={snapshot.error} /></div> : null}
        {canvas.error ? <div className="canvas-message"><WorkspaceError error={canvas.error} /></div> : null}
        {canvas.data && canvas.data.items.length === 0 ? <div className="canvas-message">双击空白画布或点击“+”添加第一张卡片。</div> : null}
        <ReactFlow<CanvasNode>
          colorMode="dark"
          connectionLineType={ConnectionLineType.Bezier}
          deleteKeyCode={null}
          edges={relationEdges}
          fitView
          minZoom={0.25}
          nodes={nodes}
          nodeTypes={nodeTypes}
          nodesConnectable
          onConnect={(connection) => connectInput.mutate(connection)}
          onNodeDragStop={(_, node) => {
            const item = canvas.data?.items.find((candidate) => candidate.id === node.id);
            if (item) {
              const patch = { x: node.position.x, y: node.position.y, ...effectiveNodeSize(item) };
              updateDraft(item.id, patch);
              saveLayout.mutate({ item, patch });
            }
          }}
          onNodeClick={(_, node) => setSelectedIds([node.id])}
          onNodesChange={handleNodesChange}
          onNodeDoubleClick={(event, node) => {
            if (!node.data.item.artifact || (event.target instanceof Element &&
              event.target.closest("button, a, input, textarea, select, summary"))) return;
            setSelectedIds([node.id]);
            window.requestAnimationFrame(focusArtifactEditor);
          }}
          onInit={(instance) => { flow.current = instance; }}
          onSelectionChange={handleSelectionChange}
          selectionOnDrag
        >
          <Background color="#454545" gap={20} size={1.1} />
          <MiniMap pannable zoomable />
          <Controls position="bottom-right" />
        </ReactFlow>
        <button aria-label="添加卡片" className="workspace-add-button" onClick={() => {
          const rect = canvasElement.current?.getBoundingClientRect();
          if (rect) openCreationMenu(rect.left + rect.width / 2, rect.top + rect.height / 2);
        }} type="button"><Plus size={22} /></button>
        {creationMenu ? <div className="workspace-create-menu" role="menu"
          ref={creationMenuElement}
          style={{ left: creationMenu.x, top: creationMenu.y }}>
          <p className="workspace-create-title">添加卡片</p>
          {CREATION_KINDS.map(({ kind, label }) => <button key={kind} role="menuitem"
            onClick={() => chooseCreationKind(kind)} type="button">{label}</button>)}
        </div> : null}
        {addBlankMedia.error ? <div className="canvas-message" role="alert">
          <WorkspaceError error={addBlankMedia.error} /></div> : null}
        {removeReference.error && !toolsKind && !inspectingId ? <div className="canvas-message">
          <WorkspaceError error={removeReference.error} /></div> : null}
        {connectInput.error && !toolsKind ? <div className="canvas-message">
          <WorkspaceError error={connectInput.error} /></div> : null}
        {!toolsKind && !resourcesOpen && !inspectingId && (removeItem.error || toggleLocked.error) ?
          <div className="canvas-message"><WorkspaceError error={(removeItem.error ?? toggleLocked.error)!} /></div> : null}
        {selectedItems.length === 1 && selectedItems[0]?.artifact ?
          <div className="workspace-bottom-editor workspace-media-editor" aria-label="所选卡片编辑区">
            <button aria-label="关闭编辑区" className="workspace-bottom-close"
              onClick={() => setSelectedIds([])} type="button"><X size={15} /></button>
            {selectedItems[0].artifact.kind === "IMAGE" ||
              selectedItems[0].artifact.kind === "VIDEO" ?
              <MediaDraftEditor key={selectedItems[0].artifact.id}
                artifact={selectedItems[0].artifact} /> : null}
            {hasCurrentVersion(selectedItems[0].artifact) &&
              (["TEXT", "CHARACTER", "SCENE"] as const).some((kind) =>
                kind === selectedItems[0]?.artifact?.kind) ?
              <StructuredArtifactEditor key={selectedItems[0].artifact.id}
                artifact={selectedItems[0].artifact} /> : null}
            {hasCurrentVersion(selectedItems[0].artifact) &&
              selectedItems[0].artifact.kind === "SHOT" ?
              <ShotRedoEditor key={selectedItems[0].artifact.id}
                artifact={selectedItems[0].artifact} /> : null}
          </div> : null}
        {selectedItems.length > 1 ? <div className="workspace-bottom-editor" aria-label="批量操作">
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
        return <aside className="workspace-drawer media-inspector" aria-label="卡片详情">
          <div className="workspace-drawer-heading"><h2>{item.artifact.title}</h2>
            <button className="node-action" aria-label="关闭卡片详情" type="button" onClick={() => setInspectingId(null)}><X size={16} /></button></div>
          <p className="mt-3 text-xs text-[var(--muted)]">{ARTIFACT_LABELS[item.artifact.kind]} · {item.artifact.currentVersion ? `v${item.artifact.currentVersion.versionNo}` : "暂无结果"}</p>
          <ArtifactVersionHistory artifact={item.artifact} />
          {item.artifact.currentVersion?.inputReferences.length ? <div className="mt-4 text-xs">
            <h3>素材引用（{item.artifact.currentVersion.inputReferences.length} 个精确版本）</h3><ul className="mt-2 space-y-2">
              {item.artifact.currentVersion.inputReferences.map((reference) =>
                <li className="break-all text-[var(--muted)]" key={`${reference.role}:${reference.order}:${reference.versionId}`}>
                  {reference.role} · {ARTIFACT_LABELS[reference.kind]} · {reference.versionId}
                  {semanticReferenceRemoval(item, reference) ? <button className="node-action mt-2"
                    disabled={removeReference.isPending} onClick={() => handleRemoveReference(item, reference)} type="button">
                    {removeReference.isPending ? "保存中…" : "移除引用"}
                  </button> : null}
                </li>)}
            </ul>
          </div> : null}
          <div className="mt-5 flex gap-2">
            <button className="node-action" type="button" disabled={toggleLocked.isPending} onClick={() => handleToggleLocked(item)}>{toggleLocked.isPending ? "保存中…" : item.locked ? "解锁" : "锁定"}</button>
            <button className="node-action" type="button" disabled={removeItem.isPending} onClick={() => handleRemove(item)}>{removeItem.isPending ? "移除中…" : "移除卡片"}</button>
          </div>
          {removeItem.error ? <WorkspaceError error={removeItem.error} /> : null}
          {toggleLocked.error ? <WorkspaceError error={toggleLocked.error} /> : null}
          {removeReference.error ? <WorkspaceError error={removeReference.error} /> : null}
          <p className="mt-3 text-xs text-[var(--muted)]">移除卡片后，内容和历史版本仍保留在项目资源中。</p>
        </aside>;
      })() : null}
      {uploadingItem?.artifact ? <aside className="workspace-drawer" aria-label="上传到图片卡片">
        <div className="workspace-drawer-heading"><h2>上传图片</h2>
          <button className="node-action" type="button" aria-label="关闭图片上传" onClick={() => setUploadingItem(null)}><X size={16} /></button></div>
        <MediaCardUpload key={`${uploadingItem.id}:${uploadingItem.artifact.version}`} artifact={uploadingItem.artifact}
          onDone={() => setUploadingItem((current) => current === uploadingItem ? null : current)} />
      </aside> : null}
      <div className="workspace-narrow-warning">画布编辑需要至少 1280px 宽度；当前仅提供只读预览。</div>
    </main>
  );
}

const CanvasCardNode = memo(function CanvasCardNode({ data, selected }: NodeProps<CanvasNode>) {
  if (data.item.agent) return <AgentChatCard data={data} selected={selected} />;
  const artifact = data.item.artifact;
  if (!artifact) return null;
  const cardProps = {
    artifact, selected, locked: data.item.locked,
    onInspect: () => data.onInspect(data.item), onEdit: focusArtifactEditor,
    children: <NodeResizer isVisible={selected && !data.item.locked}
      {...(data.imageAspectRatio === undefined
        ? { minHeight: MIN_ARTIFACT_CARD_SIZE, minWidth: MIN_ARTIFACT_CARD_SIZE,
          maxWidth: CANVAS_MAX_SIZE, maxHeight: CANVAS_MAX_SIZE }
        : imageNodeResizeBounds(data.imageAspectRatio))}
      keepAspectRatio={data.imageAspectRatio !== undefined}
      onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />,
  };
  return (
    <>
      <Handle id="artifact-input" position={Position.Left} type="target" />
      <Handle id="artifact-output" position={Position.Right} type="source" />
      {artifact.kind === "IMAGE" || artifact.kind === "VIDEO"
        ? <MediaCanvasCard {...cardProps} onUpload={() => data.onUpload(data.item)} />
        : <ContentCanvasCard {...cardProps} />}
    </>
  );
});


const nodeTypes = { canvasCard: CanvasCardNode };

function selectedArtifactBindings(items: CanvasItem[], selectedIds: string[]) {
  return items.flatMap((item) => {
    if (!selectedIds.includes(item.id) || !hasCurrentVersion(item.artifact)) return [];
    return [{ artifactId: item.artifact.id, selectedVersionId: item.artifact.currentVersionId }];
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
