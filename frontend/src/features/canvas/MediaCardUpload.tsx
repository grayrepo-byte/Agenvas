import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRef, useState, type FormEvent } from "react";
import { ApiError, listCanvasItems, uploadCanvasItemVersion, uploadImageAsset,
  type Artifact, type CanvasItem } from "../../shared/api/client";

/** Upload fills this artifact and preserves completed stages if the next request fails. */
export function MediaCardUpload({ artifact, item, onDone }: {
  artifact: Artifact; item: CanvasItem; onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [expectedVersion, setExpectedVersion] = useState(item.version);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const progress = useRef<{ file: File; assetId?: string } | null>(null);
  const upload = useMutation({
    mutationFn: async (image: File) => {
      if (progress.current?.file !== image) progress.current = { file: image };
      const pending = progress.current;
      if (!pending.assetId) pending.assetId = (await uploadImageAsset(artifact.projectId, image)).id;
      const request = { expectedVersion,
        content: { sourceType: "UPLOAD" as const, assetId: pending.assetId } };
      try {
        await uploadCanvasItemVersion(artifact.projectId, item.id, request);
      } catch (failure) {
        if (!(failure instanceof ApiError) || failure.status === 409 || failure.status < 500) throw failure;
        // The server operation is idempotent for this card CAS and exact upload content, so one
        // immediate retry safely resolves a response lost after commit without changing defaults.
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
    <p>为「{artifact.title}」添加图片，已有版本会保留。</p>
    <label>选择图片<input type="file" accept="image/png,image/jpeg,image/webp" required
      disabled={upload.isPending} onChange={(event) => { setFile(event.target.files?.[0] ?? null); upload.reset(); }} /></label>
    <p className="text-xs text-[var(--muted)]">PNG、JPEG、WebP · 最大 20 MiB / 40 MP</p>
    <button className="primary-button" type="submit" disabled={!file || upload.isPending || refreshing}>
      {upload.isPending ? "正在上传…" : "上传到此卡片"}</button>
    {upload.error ? <p role="alert">{upload.error instanceof ApiError && upload.error.status === 409
      ? "卡片已有更新，文件已保留；读取最新版本后可重新上传。"
      : upload.error.message}</p> : null}
    {upload.error instanceof ApiError && upload.error.status === 409 ? <button className="secondary-button"
      type="button" disabled={refreshing} onClick={() => void refreshVersion()}>
      {refreshing ? "读取中…" : "读取最新版本"}</button> : null}
    {expectedVersion !== item.version ? <p role="status">已读取当前卡片版本，可再次上传以追加新内容版本。</p> : null}
    {refreshError ? <p role="alert">{refreshError}</p> : null}
  </form>;
}
