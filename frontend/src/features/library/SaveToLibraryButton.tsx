import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { BookmarkSimple } from "@phosphor-icons/react";
import { assetThumbnailUrl, getLibrarySource, saveLibraryEntry, type LibraryCategory, type SaveLibraryRequest } from "../../shared/api/client";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import "./Library.css";
import { CATEGORY_LABELS, MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import { TransferState, useLibraryTransfer } from "./useLibraryTransfer";

export function SaveToLibraryButton({ projectId, itemId, disabled = false, beforeOpen }: {
  projectId: string; itemId: string; disabled?: boolean; beforeOpen?: () => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [preparing, setPreparing] = useState(false);
  return <><button type="button" disabled={disabled || preparing} onClick={() => {
    setPreparing(true);
    void Promise.resolve().then(beforeOpen).then(() => setOpen(true)).catch(() => { /* Editor owns its save error and keeps its input. */ }).finally(() => setPreparing(false));
  }}><BookmarkSimple size={17} />{preparing ? "正在保存文字…" : "保存为资产"}</button>
    {open ? <SaveWindow projectId={projectId} itemId={itemId} onClose={() => setOpen(false)} /> : null}</>;
}
function SaveWindow({ projectId, itemId, onClose }: { projectId: string; itemId: string; onClose: () => void }) {
  const source = useQuery({ queryKey: ["library-source", projectId, itemId], queryFn: () => getLibrarySource(projectId, itemId), retry: false, staleTime: 0, refetchOnWindowFocus: false });
  const [name, setName] = useState<string | null>(null);
  const [category, setCategory] = useState<LibraryCategory>("OTHER");
  const transfer = useLibraryTransfer<Omit<SaveLibraryRequest, "commandKey">>((input) => saveLibraryEntry(projectId, itemId, input));
  const value = name ?? source.data?.title ?? "";
  const done = transfer.data?.status === "SUCCEEDED";
  const locked = transfer.working || transfer.frozen;
  const success = transfer.data?.result?.trashed ? "这个结果已在回收站，请到资产页恢复。"
    : transfer.data?.result?.alreadySaved ? "这个结果已保存为资产。" : `已保存到我的资产 · ${CATEGORY_LABELS[category]}`;
  return <Dialog title="保存为资产" onClose={onClose} busy={transfer.working} onSubmit={(event) => {
    event.preventDefault(); if (!source.data || done) return;
    transfer.start({ versionId: source.data.versionId, expectedSelectionEpoch: source.data.expectedSelectionEpoch,
      ...(source.data.kind === "TEXT" ? { expectedArtifactVersion: source.data.expectedArtifactVersion } : {}), name: value.trim(), category });
  }} footer={<><button className="secondary-button" type="button" disabled={transfer.working} onClick={onClose}>{done ? "完成" : "取消"}</button>
    {done ? <Link className="primary-button" to="/library">查看资产</Link> : <button className="primary-button" type="submit" disabled={!source.data || !value.trim() || transfer.working || transfer.data?.status === "FAILED"}>{transfer.working ? "保存中…" : "保存资产"}</button>}</>}>
    {source.isPending ? <p>正在读取当前结果…</p> : null}
    {source.error ? <p role="alert">{source.error.message}<button type="button" onClick={() => void source.refetch()}>刷新预览</button></p> : null}
    {source.data ? <><p>{source.data.title} · 节点 v{source.data.versionNo}</p>
      {source.data.kind === "TEXT" ? <p className="library-text-preview">{String(source.data.textContent?.text ?? "")}</p>
        : source.data.kind === "IMAGE" || source.data.kind === "VIDEO" ? <img className="library-save-preview" src={assetThumbnailUrl(projectId, source.data.assetId!)} alt="保存内容预览" /> : <p>音频结果</p>}
      <label className="field">资产名称<input maxLength={MAX_LIBRARY_NAME_LENGTH} value={value} disabled={locked} required onChange={(event) => setName(event.target.value)} /></label>
      <label className="field">资产分类<Select value={category} disabled={locked} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></label></> : null}
    <TransferState transfer={transfer} success={success} />
    {transfer.error && !transfer.frozen ? <button type="button" onClick={() => void source.refetch()}>刷新选中结果后重试</button> : null}
  </Dialog>;
}
