import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import {
  assetContentUrl,
  approveExportProposal,
  cancelMediaExport,
  createMediaExport,
  getAssetMetadata,
  listExportProposals,
  listMediaExports,
  listProjectUsage,
  projectExportManifestUrl,
  rejectExportProposal,
  ApiError,
  type CanvasItem,
  type ExportProposal,
  type Task,
} from "../../shared/api/client";
import { hasCurrentVersion } from "./versionedArtifact";

type VideoChoice = { artifactId: string; versionId: string; assetId: string; title: string };
type SegmentDraft = VideoChoice & { startSeconds: number; endSeconds: number; sourceDurationMs: number };

/** Explicit sequence editor for a project-level, immutable silent export request. */
export function MediaExportPanel({ projectId, items }: { projectId: string; items: CanvasItem[] }) {
  const queryClient = useQueryClient();
  const [segments, setSegments] = useState<SegmentDraft[]>([]);
  const [selected, setSelected] = useState("");
  const pendingKey = useRef<string | null>(null);
  const choices: VideoChoice[] = [...new Map(items.flatMap((item) => {
    const artifact = item.artifact;
    const content = artifact?.currentVersion?.content;
    if (!hasCurrentVersion(artifact) || artifact.kind !== "VIDEO" || !content ||
      typeof content !== "object" || !("assetId" in content) ||
      typeof content.assetId !== "string") return [];
    return [[artifact.id, {
      artifactId: artifact.id,
      versionId: artifact.currentVersionId,
      assetId: content.assetId,
      title: artifact.title,
    }] as const];
  })).values()];
  const selectedChoice = choices.find((choice) => choice.artifactId === selected);
  const selectedAsset = useQuery({
    queryKey: ["asset-metadata", projectId, selectedChoice?.assetId],
    queryFn: () => getAssetMetadata(projectId, selectedChoice!.assetId),
    enabled: selectedChoice !== undefined,
  });
  const selectedDurationMs = selectedAsset.data?.mediaKind === "VIDEO"
    ? selectedAsset.data.durationMs : null;
  const artifactTitles = new Map(items.flatMap((item) => item.artifact ?
    [[item.artifact.id, item.artifact.title] as const] : []));
  const exportsQuery = useQuery({
    queryKey: ["media-exports", projectId],
    queryFn: () => listMediaExports(projectId),
    refetchInterval: (query) => query.state.data?.some((task) =>
      ["PENDING", "READY", "RUNNING"].includes(task.status)) ? 3000 : false,
  });
  const proposalsQuery = useQuery({
    queryKey: ["export-proposals", projectId],
    queryFn: () => listExportProposals(projectId),
  });
  const usageQuery = useQuery({
    queryKey: ["project-usage", projectId],
    queryFn: () => listProjectUsage(projectId),
    refetchInterval: 15_000,
  });
  const create = useMutation({
    mutationFn: () => {
      if (!pendingKey.current) pendingKey.current = crypto.randomUUID();
      return createMediaExport(projectId, pendingKey.current, {
        segments: segments.map(({ artifactId, versionId, startSeconds, endSeconds }) => ({
          videoArtifactId: artifactId, videoVersionId: versionId, startSeconds, endSeconds,
        })),
      });
    },
    onSuccess: async () => {
      pendingKey.current = null;
      await queryClient.invalidateQueries({ queryKey: ["media-exports", projectId] });
    },
  });
  const cancel = useMutation({
    mutationFn: (taskId: string) => cancelMediaExport(projectId, taskId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["media-exports", projectId] });
    },
  });
  const approve = useMutation({
    mutationFn: (proposal: ExportProposal) => approveExportProposal(projectId,
      proposal.id, proposal.proposalHash),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["export-proposals", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-exports", projectId] }),
      ]);
    },
  });
  const reject = useMutation({
    mutationFn: (proposalId: string) => rejectExportProposal(projectId, proposalId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["export-proposals", projectId] });
    },
  });
  const totalSeconds = segments.reduce((total, item) => total + item.endSeconds - item.startSeconds, 0);
  const valid = segments.length > 0 && segments.length <= 6 && totalSeconds <= 60 &&
    segments.every((item) => Number.isInteger(item.startSeconds) && Number.isInteger(item.endSeconds) &&
      item.startSeconds >= 0 && item.endSeconds > item.startSeconds
      && item.endSeconds * 1000 <= item.sourceDurationMs && item.endSeconds <= 60);

  function edit(update: (previous: SegmentDraft[]) => SegmentDraft[]) {
    pendingKey.current = null;
    setSegments(update);
  }

  return <section className="mt-6 border-t border-[var(--line)] pt-5" aria-label="顺序导出">
    <h2 className="text-base font-semibold">无声顺序导出</h2>
    <a className="mt-2 inline-block text-xs text-[var(--accent)] underline"
      href={projectExportManifestUrl(projectId)} download={`agenvas-project-${projectId}.json`}>
      下载项目 JSON 与素材清单
    </a>
    <p className="mt-1 text-xs leading-5 text-[var(--muted)]">
      选择当前视频的精确版本，按列表顺序拼接。调整前后区间，最多 6 段、合计 60 秒；输出 720p/24fps MP4，不含声音。
    </p>
    <div className="mt-3 flex gap-2">
      <select aria-label="选择视频" className="min-w-0 flex-1 rounded-lg border border-[var(--line)] bg-white p-2 text-sm"
        onChange={(event) => setSelected(event.target.value)} value={selected}>
        <option value="">选择视频卡片</option>
        {choices.map((choice) => <option key={choice.artifactId} value={choice.artifactId}>
          {choice.title}
        </option>)}
      </select>
      <button className="node-action" disabled={!selectedChoice || segments.length >= 6
        || !selectedDurationMs || selectedDurationMs < 1000} onClick={() => {
        if (selectedChoice && selectedDurationMs) edit((previous) => [...previous, {
          ...selectedChoice, startSeconds: 0,
          endSeconds: Math.min(5, Math.floor(selectedDurationMs / 1000)),
          sourceDurationMs: selectedDurationMs,
        }]);
      }} type="button">添加</button>
    </div>
    {selectedChoice && selectedAsset.isPending ? <p className="mt-2 text-xs">正在读取视频时长…</p> : null}
    {selectedAsset.error ? <p className="mt-2 text-xs text-red-700" role="alert">{selectedAsset.error.message}</p> : null}
    {selectedChoice && selectedAsset.data && !selectedDurationMs ?
      <p className="mt-2 text-xs text-red-700" role="alert">此视频缺少可验证时长，无法选择导出区间。</p> : null}
    {choices.length === 0 ? <p className="mt-2 text-xs text-[var(--muted)]">暂无视频卡片；完成视频生成后可在此导出。</p> : null}
    <ol className="mt-3 space-y-3">
      {segments.map((segment, index) => <li className="rounded-lg border border-[var(--line)] bg-white p-3 text-xs"
        key={`${segment.artifactId}-${index}`}>
        <p className="font-medium">{index + 1}. {segment.title}</p>
        <p className="mt-1 break-all text-[var(--muted)]">版本 {segment.versionId}</p>
        <p className="mt-1 text-[var(--muted)]">片源时长 {(segment.sourceDurationMs / 1000).toFixed(3)} 秒</p>
        <div className="mt-2 grid grid-cols-2 gap-2">
          <label>起点（秒）<input min="0" max="59" step="1" type="number"
            value={segment.startSeconds} onChange={(event) => edit((previous) => previous.map((item, at) =>
              at === index ? { ...item, startSeconds: Number(event.target.value) } : item))} /></label>
          <label>终点（秒）<input min="1" max={Math.floor(segment.sourceDurationMs / 1000)} step="1" type="number"
            value={segment.endSeconds} onChange={(event) => edit((previous) => previous.map((item, at) =>
              at === index ? { ...item, endSeconds: Number(event.target.value) } : item))} /></label>
        </div>
        <div className="mt-2 flex gap-2">
          <button className="node-action" disabled={index === 0} onClick={() => edit((previous) => swap(previous, index, index - 1))} type="button">上移</button>
          <button className="node-action" disabled={index === segments.length - 1} onClick={() => edit((previous) => swap(previous, index, index + 1))} type="button">下移</button>
          <button className="node-action node-action-danger" onClick={() => edit((previous) => previous.filter((_, at) => at !== index))} type="button">移除</button>
        </div>
      </li>)}
    </ol>
    {segments.length > 0 ? <p className={`mt-2 text-xs ${valid ? "text-[var(--muted)]" : "text-red-700"}`}>
      合计 {totalSeconds} 秒。裁剪终点不得超过各自片源时长，且必须是整数秒。
    </p> : null}
    <button className="primary-button mt-3 w-full" disabled={!valid || create.isPending}
      onClick={() => create.mutate()} type="button">{create.isPending ? "正在提交…" : "开始导出"}</button>
    {create.error ? <p className="mt-2 text-xs text-red-700" role="alert">{create.error.message}</p> : null}
    <h3 className="mt-5 text-sm font-semibold">Agent 导出提案</h3>
    <p className="mt-1 text-xs text-[var(--muted)]">Agent 只能提出顺序和精确视频版本；批准前不会启动导出。</p>
    {proposalsQuery.isPending ? <p className="mt-2 text-xs">正在读取导出提案…</p> : null}
    {proposalsQuery.error ? <p className="mt-2 text-xs text-red-700" role="alert">{proposalsQuery.error.message}</p> : null}
    {proposalsQuery.data?.length === 0 ? <p className="mt-2 text-xs text-[var(--muted)]">暂无 Agent 导出提案。</p> : null}
    <ul className="mt-2 space-y-2">
      {proposalsQuery.data?.map((proposal) => <li className="rounded-lg border border-[var(--line)] bg-white p-3 text-xs"
        key={proposal.id}>
        <p className="font-medium">提案 {proposal.id.slice(0, 8)} · {proposal.status === "PENDING" ? "待确认" :
          proposal.status === "APPROVED" ? "已批准" : proposal.status === "STALE" ? "已失效" : "已拒绝"}</p>
        <p className="mt-1 text-[var(--muted)]">{proposal.input.aspectRatio} · 共 {
          proposal.input.schemaVersion === 2 ? proposal.input.durationSeconds
            : (proposal.input.durationMs / 1000).toFixed(3)} 秒 · 无声 720p/24fps</p>
        <p className="mt-1 break-all text-[var(--muted)]">提案校验值 {proposal.proposalHash}</p>
        <ol className="mt-2 list-inside list-decimal space-y-1">
          {proposal.input.segments.map((segment) => <li key={`${segment.shotArtifactId}-${segment.videoVersionId}`}>
            <span>镜头 {artifactTitles.get(segment.shotArtifactId) ?? segment.shotArtifactId.slice(0, 8)} ·
              视频 {artifactTitles.get(segment.videoArtifactId) ?? segment.videoArtifactId.slice(0, 8)} ·
              {" "}{"startSeconds" in segment ? `${segment.startSeconds}–${segment.endSeconds}`
                : `${(segment.startMs / 1000).toFixed(3)}–${(segment.endMs / 1000).toFixed(3)}`} 秒</span>
            <p className="ml-4 break-all text-[var(--muted)]">镜头版本 {segment.shotVersionId} · 视频版本 {segment.videoVersionId}</p>
          </li>)}
        </ol>
        {proposal.status === "PENDING" ? <div className="mt-3 flex gap-2">
          <button className="node-action" disabled={approve.isPending || reject.isPending}
            onClick={() => approve.mutate(proposal)} type="button">
            {approve.isPending && approve.variables?.id === proposal.id ? "正在批准…" : "批准并开始导出"}
          </button>
          <button className="node-action node-action-danger" disabled={approve.isPending || reject.isPending}
            onClick={() => reject.mutate(proposal.id)} type="button">拒绝</button>
        </div> : null}
      </li>)}
    </ul>
    {approve.error ? <p className="mt-2 text-xs text-red-700" role="alert">批准失败：{
      approve.error instanceof ApiError && approve.error.code === "EXPORT_PROPOSAL_CONFLICT" ?
        "提案或所引用的版本已变化；请拒绝旧提案并重新创建。" : approve.error.message
    }</p> : null}
    {reject.error ? <p className="mt-2 text-xs text-red-700" role="alert">拒绝失败：{reject.error.message}</p> : null}
    <h3 className="mt-5 text-sm font-semibold">导出记录</h3>
    {exportsQuery.isPending ? <p className="mt-2 text-xs">正在读取导出记录…</p> : null}
    {exportsQuery.error ? <p className="mt-2 text-xs text-red-700" role="alert">{exportsQuery.error.message}</p> : null}
    {exportsQuery.data?.length === 0 ? <p className="mt-2 text-xs text-[var(--muted)]">尚无导出。</p> : null}
    <ul className="mt-2 space-y-2">
      {exportsQuery.data?.map((task) => <li className="rounded-lg border border-[var(--line)] bg-white p-3 text-xs" key={task.id}>
        <p>导出 {task.id.slice(0, 8)} · {exportStatus(task)}</p>
        {task.errorCode ? <p className="mt-1 text-red-700">错误：{task.errorCode}</p> : null}
        {exportAssetId(task) ? <ExportPlayback projectId={projectId} taskId={task.id}
          assetId={exportAssetId(task) ?? ""} /> : null}
        {task.status === "SUCCEEDED" && exportSourceAssets(task).length > 0 ?
          <div className="mt-2">
            <p className="text-[var(--muted)]">本次导出固定的原视频片段：</p>
            <ol className="mt-1 space-y-1">
              {exportSourceAssets(task).map((assetId, index) => <li key={`${assetId}-${index}`}>
                <a className="text-[var(--accent)] underline"
                  download={`agenvas-source-${index + 1}.mp4`}
                  href={assetContentUrl(projectId, assetId)}>下载片段 {index + 1} 原视频</a>
              </li>)}
            </ol>
          </div> : null}
        {["PENDING", "READY", "RUNNING"].includes(task.status) ?
          <button className="node-action mt-2 block" disabled={cancel.isPending}
            onClick={() => cancel.mutate(task.id)} type="button">取消导出</button> : null}
      </li>)}
    </ul>
    {cancel.error ? <p className="mt-2 text-xs text-red-700" role="alert">{cancel.error.message}</p> : null}
    <h3 className="mt-5 text-sm font-semibold">用量记录</h3>
    <p className="mt-1 text-xs text-[var(--muted)]">预留与结算是同一任务的不同阶段，不相加；未定价费用不显示为零。</p>
    {usageQuery.isPending ? <p className="mt-2 text-xs">正在读取用量…</p> : null}
    {usageQuery.error ? <p className="mt-2 text-xs text-red-700" role="alert">{usageQuery.error.message}</p> : null}
    {usageQuery.data?.length === 0 ? <p className="mt-2 text-xs text-[var(--muted)]">暂无用量记录。</p> : null}
    <ul className="mt-2 space-y-2">
      {usageQuery.data?.slice(-6).reverse().map((entry) => <li className="rounded-lg border border-[var(--line)] bg-white p-3 text-xs" key={entry.id}>
        <p>{entry.entryType === "RESERVATION" ? "已预留" : entry.entryType === "SETTLEMENT" ? "已结算" : "已释放"} ·
          图片 {entry.quantity.imageCount} · 视频 {entry.quantity.videoCount} / {entry.quantity.videoSeconds} 秒 · 导出 {entry.quantity.exportCount} · 模型请求 {entry.quantity.llmRequestCount}</p>
        {entry.quantity.llmRequestCount > 0 ? <p className="mt-1 text-[var(--muted)]">
          输入 Token {entry.quantity.inputTokens ?? "未返回"} · 输出 Token {entry.quantity.outputTokens ?? "未返回"}
        </p> : null}
        <p className="mt-1 text-[var(--muted)]">{entry.costStatus === "UNKNOWN" ? "费用未知" :
          `${entry.actualCost ?? entry.estimatedCost ?? "费用待核实"}${entry.currency ? ` ${entry.currency}` : ""}`}
          {entry.costSource === "MOCK_UNPRICED" ? " · Mock 演示，不代表真实计费" : null}</p>
      </li>)}
    </ul>
  </section>;
}

/** Keeps the private export stream unloaded until the user explicitly asks to watch it. */
function ExportPlayback({ projectId, taskId, assetId }: {
  projectId: string; taskId: string; assetId: string;
}) {
  const [playing, setPlaying] = useState(false);
  const source = assetContentUrl(projectId, assetId);
  return <div className="mt-2">
    <div className="flex gap-3">
      <button className="text-[var(--accent)] underline" onClick={() => setPlaying(!playing)}
        type="button">{playing ? "关闭预览" : "播放导出"}</button>
      <a className="text-[var(--accent)] underline" download="agenvas-export.mp4"
        href={source}>下载 MP4</a>
    </div>
    {playing ? <video aria-label={`导出 ${taskId.slice(0, 8)} 的视频`}
      className="mt-2 h-48 w-full max-w-lg rounded-lg bg-black" controls
      preload="metadata" src={source} /> : null}
  </div>;
}

function swap<T>(items: T[], left: number, right: number): T[] {
  const copy = [...items];
  const original = copy[left];
  const replacement = copy[right];
  if (original !== undefined && replacement !== undefined) {
    copy[left] = replacement;
    copy[right] = original;
  }
  return copy;
}

function exportAssetId(task: Task): string | null {
  const id = task.status === "SUCCEEDED" ? task.output?.assetId : null;
  return typeof id === "string" && /^[0-9a-f-]{36}$/i.test(id) ? id : null;
}

/** Only server-pinned Asset IDs in the persisted export snapshot become download links. */
function exportSourceAssets(task: Task): string[] {
  const segments = task.input.segments;
  if (!Array.isArray(segments)) return [];
  return segments.flatMap((segment) => {
    if (!segment || typeof segment !== "object" || !("assetId" in segment)) return [];
    const id = segment.assetId;
    return typeof id === "string" &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)
      ? [id] : [];
  });
}

function exportStatus(task: Task): string {
  if (task.cancelRequested && task.status !== "SUCCEEDED") return "取消中";
  return ({ PENDING: "排队中", READY: "待处理", RUNNING: "导出中", SUCCEEDED: "已完成",
    FAILED: "失败", CANCELED: "已取消" } as Record<string, string>)[task.status] ?? task.status;
}
