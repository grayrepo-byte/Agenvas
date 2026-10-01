import { t, useLocale } from "../../shared/i18n";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, listCanvasItems, uploadCanvasItemVersion, uploadImageAsset, uploadAudioAsset,
  type Artifact, type CanvasItem } from "../../shared/api/client";

/** Upload fills an empty media node, and only derives when the source already has a result. */
export function MediaCardUpload({ artifact, item, initialFile, onDone, compact = false }: {
  artifact: Artifact; item: CanvasItem; initialFile: File; onDone: () => void; compact?: boolean;
}) {
  useLocale();
  const isAudio = artifact.kind === "AUDIO";
  const label = isAudio ? t("音频") : t("图片");
  const queryClient = useQueryClient();
  const [file, setFile] = useState<File | null>(initialFile);
  const [expectedVersion, setExpectedVersion] = useState(item.version);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const initialUploadStarted = useRef(false);
  const progress = useRef<{ file: File; targetItemId: string; assetId?: string } | null>({
    file: initialFile,
    targetItemId: item.selectedVersionId ? crypto.randomUUID() : item.id,
  });
  const upload = useMutation({
    mutationFn: async (image: File) => {
      if (progress.current?.file !== image) progress.current = {
        file: image,
        targetItemId: item.selectedVersionId ? crypto.randomUUID() : item.id,
      };
      const pending = progress.current;
      if (!pending.assetId) pending.assetId = (await (isAudio ? uploadAudioAsset : uploadImageAsset)(artifact.projectId, image)).id;
      const request = { targetItemId: pending.targetItemId, expectedVersion,
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
        queryClient.invalidateQueries({ queryKey: ["canvas-connections", artifact.projectId] }),
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
      if (!current) throw new Error(t("卡片已被删除"));
      setExpectedVersion(current.version);
      upload.reset();
    } catch (failure) {
      setRefreshError(failure instanceof Error ? failure.message : t("无法读取当前版本"));
    } finally { setRefreshing(false); }
  }
  const errorMessage = upload.error instanceof ApiError && upload.error.status === 409
    ? t("卡片已有更新，文件已保留；读取最新版本后可重新上传。")
    : upload.error?.message;
  if (compact) return <div className="media-card-upload-status nodrag nowheel nopan">
    {upload.isPending ? <p role="status">{t("正在上传{0}…", { "0": label })}</p> : null}
    {upload.error ? <div className="media-card-error" role="alert">
      <span>{errorMessage}</span>
      <button type="button" disabled={!file || refreshing} onClick={() => file && upload.mutate(file)}>
        {refreshing ? t("读取中…") : t("重试上传")}
      </button>
      {upload.error instanceof ApiError && upload.error.status === 409 ? <button type="button"
        disabled={refreshing} onClick={() => void refreshVersion()}>{t("读取最新版本")}</button> : null}
    </div> : null}
    {refreshError ? <p className="media-card-error" role="alert">{refreshError}</p> : null}
  </div>;
  return <form className="media-card-upload-form" onSubmit={submit}>
    <p>{item.selectedVersionId ? t("基于「{0}」上传{1}，并创建一个新节点。", { "0": artifact.title, "1": label })
      : t("上传{0}到「{1}」。", { "0": label, "1": artifact.title })}</p>
    <p className="text-xs text-[var(--muted)]">{t("已选择：{0}", { "0": file?.name })}</p>
    <label>{t("更换{0}", { "0": label })}<input type="file" accept={isAudio ? "audio/mpeg,audio/wav,audio/ogg" : "image/png,image/jpeg,image/webp"}
      disabled={upload.isPending} onChange={(event) => {
        const selected = event.target.files?.[0] ?? null;
        setFile(selected);
        upload.reset();
        if (selected) upload.mutate(selected);
      }} /></label>
    <p className="text-xs text-[var(--muted)]">{isAudio ? t("MP3、WAV、OGG · 最大 50 MiB / 10 分钟") : t("PNG、JPEG、WebP · 最大 20 MiB / 40 MP")}</p>
    <button className="primary-button" type="submit" disabled={!file || upload.isPending || refreshing}>
      {upload.isPending ? t("正在上传…") : upload.error ? t("重试上传") : t("上传并创建节点")}</button>
    {upload.error ? <p role="alert">{errorMessage}</p> : null}
    {upload.error instanceof ApiError && upload.error.status === 409 ? <button className="secondary-button"
      type="button" disabled={refreshing} onClick={() => void refreshVersion()}>
      {refreshing ? t("读取中…") : t("读取最新版本")}</button> : null}
    {expectedVersion !== item.version ? <p role="status">{t("已读取当前节点状态，可再次上传并创建新节点。")}</p> : null}
    {refreshError ? <p role="alert">{refreshError}</p> : null}
  </form>;
}
