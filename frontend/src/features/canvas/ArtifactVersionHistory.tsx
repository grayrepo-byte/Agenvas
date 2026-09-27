import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, getMediaDraft, listArtifactVersions, restoreMediaDraftVersionInputs,
  selectCanvasItemVersion,
  setArtifactResourceDefaultVersion, type Artifact, type CanvasItem } from "../../shared/api/client";

/** On-demand immutable history; selecting a past version never rewrites its bytes. */
export function ArtifactVersionHistory({ artifact, item }: {
  artifact: Artifact; item?: CanvasItem;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const history = useQuery({
    queryKey: ["artifact-versions", artifact.projectId, artifact.id],
    queryFn: () => listArtifactVersions(artifact.projectId, artifact.id),
    enabled: open,
  });
  const cardLocal = item != null && (artifact.kind === "IMAGE" || artifact.kind === "VIDEO");
  const selectedVersionId = cardLocal ? item.selectedVersionId : artifact.resourceDefaultVersionId;
  const select = useMutation({
    mutationFn: async (versionId: string) => {
      if (cardLocal && item) {
        await selectCanvasItemVersion(artifact.projectId, item.id, versionId, item.version);
      } else {
        await setArtifactResourceDefaultVersion(
          artifact.projectId, artifact.id, versionId, artifact.version);
      }
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId,
        artifact.id] });
      if (cardLocal && item) {
        await queryClient.invalidateQueries({ queryKey: ["media-draft", artifact.projectId,
          item.id] });
      }
    },
  });
  const restoreInputs = useMutation({
    mutationFn: async (versionId: string) => {
      if (!item) return;
      const draft = await getMediaDraft(artifact.projectId, item.id);
      const confirmed = window.confirm(
        "这会用该版本冻结的提示词、参数、模式、图片顺序和标签完整替换当前草稿。历史连线不会恢复，图片将成为手工来源。继续吗？",
      );
      if (!confirmed) return;
      await restoreMediaDraftVersionInputs(artifact.projectId, item.id, {
        versionId, expectedVersion: draft.version,
      });
    },
    onSuccess: async () => {
      if (!item) return;
      await queryClient.invalidateQueries({ queryKey: ["media-draft", artifact.projectId,
        item.id] });
      await queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] });
    },
  });

  return <details className="nodrag nowheel mt-3 border-t border-[var(--line)] pt-2 text-xs"
    onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">版本历史与选用</summary>
    <p className="mt-2 text-[var(--muted)]">{cardLocal
      ? "选用旧版本只改变这张卡片的展示结果，不修改资源库默认版本。"
      : "选用旧版本会改变资源库默认内容，不修改历史版本。"}</p>
    {history.isPending ? <p className="mt-2">正在读取版本…</p> : null}
    {history.error ? <p className="mt-2 text-red-700" role="alert">
      {history.error instanceof ApiError ? history.error.message : "版本历史读取失败，请重试。"}
    </p> : null}
    {history.data ? <ol className="mt-2 space-y-2">
      {history.data.items.map((version) => <li className="rounded-lg border border-[var(--line)] p-2"
        key={version.id}>
        <span>v{version.versionNo}{version.baseVersionId
          ? ` · 基于 v${history.data.items.find((candidate) => candidate.id === version.baseVersionId)?.versionNo ?? "?"}`
          : ""} · {version.createdByKind} · {version.createdAt}</span>
        {version.id === artifact.resourceDefaultVersionId
          ? <span className="ml-2">资源默认</span> : null}
        {version.id === selectedVersionId ? cardLocal
          ? <span className="ml-2">当前卡片</span> : null
          : <button className="node-action ml-2" disabled={select.isPending}
            onClick={() => select.mutate(version.id)} type="button">
            {select.isPending && select.variables === version.id ? "选用中…" : "选用此版本"}
          </button>}
        {cardLocal && version.frozenInput ? <button className="node-action ml-2"
          disabled={restoreInputs.isPending}
          onClick={() => restoreInputs.mutate(version.id)} type="button">
          {restoreInputs.isPending && restoreInputs.variables === version.id
            ? "恢复中…" : "使用此版本的输入"}
        </button> : null}
      </li>)}
    </ol> : null}
    {select.error ? <p className="mt-2 text-red-700" role="alert">
      {select.error instanceof ApiError && select.error.status === 409
        ? "内容有冲突，未切换版本；请核对当前版本后重试。"
        : select.error instanceof ApiError ? select.error.message : "版本选用失败，请重试。"}
    </p> : null}
    {restoreInputs.error ? <p className="mt-2 text-red-700" role="alert">
      {restoreInputs.error instanceof ApiError && restoreInputs.error.status === 409
        ? "草稿有冲突，未恢复历史输入；请保留当前编辑并重新核对。"
        : restoreInputs.error instanceof ApiError ? restoreInputs.error.message
          : "历史输入恢复失败，请重试。"}
    </p> : null}
  </details>;
}
