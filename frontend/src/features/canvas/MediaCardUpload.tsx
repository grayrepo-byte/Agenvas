import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, listCanvasItems, uploadCanvasItemVersion, uploadImageAsset,
  type Artifact, type CanvasItem } from "../../shared/api/client";

/** Upload derives a new media node and preserves completed stages if the next request fails. */
export function MediaCardUpload({ artifact, item, initialFile, onDone }: {
  artifact: Artifact; item: CanvasItem; initialFile: File; onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(initialFile);
  const [expectedVersion, setExpectedVersion] = useState(item.version);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const initialUploadStarted = useRef(false);
  const progress = useRef<{ file: File; assetId?: string } | null>({ file: initialFile });
  const upload = useMutation({
    mutationFn: async (image: File) => {
      if (progress.current?.file !== image) progress.current = { file: image };
      const pending = progress.current;
      if (!pending.assetId) pending.assetId = (await uploadImageAsset(artifact.projectId, image)).id;
      const request = { targetItemId: crypto.randomUUID(), expectedVersion,
        content: { sourceType: "UPLOAD" as const, assetId: pending.assetId } };
      try {
        await uploadCanvasItemVersion(artifact.projectId, item.id, request);
      } catch (failure) {
        if (!(failure instanceof ApiError) || failure.status === 409 || failure.status < 500) throw failure;
        // Reuse the same target ID so a response lost after commit cannot create another node.
        await uploadCanvasItemVersion(artifact.projectId, item.id, request);
      }
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["media-draft", artifact.projectId, item.id] }),
        queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId, artifact.id] }),
      ]);
      onDone();
    },
  });
  useEffect(() => {
    if (initialUploadStarted.current) return;
    initialUploadStarted.current = true;
    upload.mutate(initialFile);
  }, [initialFile, upload]);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (file) upload.mutate(file);
  }
  async function refreshVersion() {
    setRefreshing(true); setRefreshError(null);
    try {
      const current = (await listCanvasItems(artifact.projectId)).items
        .find((candidate) => candidate.id === item.id);
      if (!current) throw new Error("卡片已被删除");
      setExpectedVersion(current.version);
      upload.reset();
    } catch (failure) {
      setRefreshError(failure instanceof Error ? failure.message : "无法读取当前版本");
    } finally { setRefreshing(false); }
  }
  return <form className="media-card-upload-form" onSubmit={submit}>
    <p>基于「{artifact.title}」上传图片，并创建一个新节点。</p>
    <p className="text-xs text-[var(--muted)]">已选择：{file?.name}</p>
    <label>更换图片<input type="file" accept="image/png,image/jpeg,image/webp"
      disabled={upload.isPending} onChange={(event) => {
        const selected = event.target.files?.[0] ?? null;
        setFile(selected);
        upload.reset();
        if (selected) upload.mutate(selected);
      }} /></label>
    <p className="text-xs text-[var(--muted)]">PNG、JPEG、WebP · 最大 20 MiB / 40 MP</p>
    <button className="primary-button" type="submit" disabled={!file || upload.isPending || refreshing}>
      {upload.isPending ? "正在上传…" : upload.error ? "重试上传" : "上传并创建节点"}</button>
    {upload.error ? <p role="alert">{upload.error instanceof ApiError && upload.error.status === 409
      ? "卡片已有更新，文件已保留；读取最新版本后可重新上传。"
      : upload.error.message}</p> : null}
    {upload.error instanceof ApiError && upload.error.status === 409 ? <button className="secondary-button"
      type="button" disabled={refreshing} onClick={() => void refreshVersion()}>
      {refreshing ? "读取中…" : "读取最新版本"}</button> : null}
    {expectedVersion !== item.version ? <p role="status">已读取当前节点状态，可再次上传并创建新节点。</p> : null}
    {refreshError ? <p role="alert">{refreshError}</p> : null}
  </form>;
}
