import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AgentChatApproval } from "./AgentChatPrimitives";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import "./AgentChatPanels.css";
import {
  ApiError,
  assetContentUrl,
  getArtifact,
  getShotKeyframeSelection,
  listRunTasks,
  selectShotKeyframe,
  type Task,
} from "../../shared/api/client";

/** Lists completed image outputs so the user can explicitly choose each shot's stage-B input. */
export function KeyframeSelectionPanel({ projectId, runId }: {
  projectId: string; runId: string;
}) {
  const tasks = useQuery({
    queryKey: ["run-tasks", projectId, runId],
    queryFn: () => listRunTasks(projectId, runId),
  });
  const images = tasks.data?.filter((task) => task.kind === "IMAGE_GENERATION" &&
    task.status === "SUCCEEDED" && typeof task.output?.artifactId === "string" &&
    typeof task.output?.artifactVersionId === "string") ?? [];
  if (tasks.isPending) return <div className="agent-chat-panel"><CanvasLoadingState compact label="正在读取关键帧结果…" /></div>;
  if (tasks.error) return <div className="agent-chat-panel"><p role="alert">无法读取关键帧结果：{tasks.error.message}</p>
    <button className="agent-chat-panel-text-button" disabled={tasks.isFetching} onClick={() => void tasks.refetch()} type="button">重新读取关键帧</button></div>;
  // Once stage B has tasks, its pinned keyframes are immutable for this Run.
  if (tasks.data?.some((task) => task.kind === "VIDEO_GENERATION")) return null;
  if (images.length === 0) return null;

  return <AgentChatApproval title="选择镜头关键帧"
    description="视频计划只会使用你明确选定的图片版本。Mock 只产生演示素材。"
    className="agent-chat-panel agent-chat-keyframes">
    <ul className="agent-chat-keyframe-list">
      {images.map((task) => <KeyframeChoice key={task.id} projectId={projectId} runId={runId} task={task} />)}
    </ul>
  </AgentChatApproval>;
}

function KeyframeChoice({ projectId, runId, task }: {
  projectId: string; runId: string; task: Task;
}) {
  const queryClient = useQueryClient();
  const shotId = String(task.input.shotArtifactId ?? "");
  const shotVersionId = String(task.input.shotVersionId ?? "");
  const imageId = String(task.output?.artifactId ?? "");
  const imageVersionId = String(task.output?.artifactVersionId ?? "");
  const image = useQuery({
    queryKey: ["artifact", projectId, imageId],
    queryFn: () => getArtifact(projectId, imageId),
    enabled: imageId.length > 0,
  });
  const selection = useQuery({
    queryKey: ["keyframe-selection", projectId, runId, shotId],
    queryFn: () => getShotKeyframeSelection(projectId, runId, shotId),
    enabled: shotId.length > 0,
    retry: false,
  });
  const choose = useMutation({
    mutationFn: () => selectShotKeyframe(projectId, runId, shotId, {
      shotVersionId,
      imageArtifactId: imageId,
      imageVersionId,
      expectedVersion: selection.data?.version ?? null,
    }),
    onSuccess: (saved) => {
      queryClient.setQueryData(["keyframe-selection", projectId, runId, shotId], saved);
    },
  });
  const selected = selection.data?.imageVersionId === imageVersionId;
  const selectionMissing = selection.error instanceof ApiError && selection.error.status === 404;
  const canChoose = !selection.isPending && (!selection.error || selectionMissing) &&
    shotId.length > 0 && shotVersionId.length > 0 && imageId.length > 0 && imageVersionId.length > 0;
  const content: unknown = image.data?.currentVersion?.content;
  const assetId = typeof content === "object" && content !== null && "assetId" in content &&
    typeof content.assetId === "string" ? content.assetId : null;

  return <li className="agent-chat-keyframe-choice"
    data-selected={selected || undefined}>
    {assetId ?
      <img alt={`镜头 ${shotId} 的关键帧`} className="agent-chat-keyframe-preview"
        decoding="async" loading="lazy"
        src={assetContentUrl(projectId, assetId)} /> : null}
    <div className="agent-chat-keyframe-details">
      <p className="font-medium">镜头 {shotId} · 图片版本 {imageVersionId}</p>
      <p className="mt-1 text-xs text-[var(--muted)]">来源任务 {task.id} · 提示词 {String(task.input.prompt ?? "")}</p>
      <button className="agent-chat-panel-secondary" disabled={!canChoose || selected || choose.isPending}
        onClick={() => choose.mutate()} type="button">
        {selected ? "已选为关键帧" : choose.isPending ? "正在保存选择…" : "选为此镜头关键帧"}
      </button>
      {selection.error && !selectionMissing ? <p role="alert">选择状态读取失败：{selection.error.message}</p> : null}
      {choose.error ? <p role="alert">选择失败：{choose.error.message}。请刷新后重试。</p> : null}
    </div>
  </li>;
}
