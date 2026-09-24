import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  assetThumbnailUrl,
  getArtifact,
  getShotKeyframeSelection,
  listRunTasks,
  selectShotKeyframe,
  type Task,
} from "../../shared/api/client";

/** Lists completed image outputs so the user can explicitly choose each shot's stage-B input. */
export function KeyframeSelectionPanel({ projectId, runId }: { projectId: string; runId: string }) {
  const tasks = useQuery({
    queryKey: ["run-tasks", projectId, runId],
    queryFn: () => listRunTasks(projectId, runId),
  });
  const images = tasks.data?.filter((task) => task.kind === "IMAGE_GENERATION" &&
    task.status === "SUCCEEDED" && typeof task.output?.artifactId === "string" &&
    typeof task.output?.artifactVersionId === "string") ?? [];
  if (tasks.isPending) return <p className="border-b px-6 py-3 text-sm" role="status">正在读取关键帧结果…</p>;
  if (tasks.error) return <p className="border-b px-6 py-3 text-sm text-red-800" role="alert">无法读取关键帧结果：{tasks.error.message}</p>;
  // Once stage B has tasks, its pinned keyframes are immutable for this Run.
  if (tasks.data?.some((task) => task.kind === "VIDEO_GENERATION")) return null;
  if (images.length === 0) return null;

  return <section aria-label="选择镜头关键帧" className="col-span-full border-b border-sky-200 bg-sky-50 px-6 py-4 text-sm">
    <h2 className="font-semibold">选择镜头关键帧</h2>
    <p className="mt-1 text-[var(--muted)]">视频计划只会使用你明确选定的图片版本。当前是已归档的演示图片，非 AI 生成。</p>
    <ul className="mt-3 space-y-2">
      {images.map((task) => <KeyframeChoice key={task.id} projectId={projectId} runId={runId} task={task} />)}
    </ul>
  </section>;
}

function KeyframeChoice({ projectId, runId, task }: { projectId: string; runId: string; task: Task }) {
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

  return <li className="rounded-lg border border-sky-200 bg-white p-3">
    {assetId ?
      <img alt={`镜头 ${shotId} 的演示关键帧`} className="mb-2 h-36 w-auto rounded object-contain"
        decoding="async" loading="lazy"
        src={assetThumbnailUrl(projectId, assetId)} /> : null}
    <p className="font-medium">镜头 {shotId} · 图片版本 {imageVersionId}</p>
    <p className="mt-1 text-xs text-[var(--muted)]">来源任务 {task.id} · 提示词 {String(task.input.prompt ?? "")}</p>
    <button className="secondary-button mt-2" disabled={!canChoose || selected || choose.isPending}
      onClick={() => choose.mutate()} type="button">
      {selected ? "已选为关键帧" : choose.isPending ? "正在保存选择…" : "选为此镜头关键帧"}
    </button>
    {selection.error && !selectionMissing ? <p role="alert">选择状态读取失败：{selection.error.message}</p> : null}
    {choose.error ? <p role="alert">选择失败：{choose.error.message}。请刷新后重试。</p> : null}
  </li>;
}
