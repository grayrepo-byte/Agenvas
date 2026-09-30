import { useEffect, useRef, useState } from "react";
import { getMediaDraft, referenceLibraryEntry, type Artifact, type LibraryEntry, type MediaDraft, type ReferenceLibraryRequest } from "../../shared/api/client";
import { LibraryBrowser } from "./LibraryBrowser";
import { TransferState, useLibraryTransfer } from "./useLibraryTransfer";

type ReferencePlan = Pick<ReferenceLibraryRequest, "role" | "color"> & { videoInputMode: ReferenceLibraryRequest["draft"]["videoInputMode"] };
export function LibraryReferencePicker({ projectId, itemId, draft, kinds, plan, onApplied, onBusy }: {
  projectId: string; itemId: string; draft: ReferenceLibraryRequest["draft"]; kinds: Artifact["kind"][];
  plan: (entry: LibraryEntry) => ReferencePlan | null;
  onApplied: (saved: MediaDraft, submitted: ReferenceLibraryRequest["draft"]) => void; onBusy?: (busy: boolean) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<LibraryEntry | null>(null);
  const submitted = useRef(draft);
  const applied = useRef<string | null>(null);
  const transfer = useLibraryTransfer<Omit<ReferenceLibraryRequest, "commandKey">>((request) => referenceLibraryEntry(projectId, itemId, request));
  useEffect(() => { onBusy?.(transfer.working); return () => onBusy?.(false); }, [transfer.working, onBusy]);
  useEffect(() => {
    if (transfer.data?.status !== "SUCCEEDED" || applied.current === transfer.data.id) return;
    applied.current = transfer.data.id;
    void getMediaDraft(projectId, itemId).then((saved) => onApplied(saved, submitted.current)).catch((failure: unknown) => {
      applied.current = null; setError(failure instanceof Error ? failure.message : "引用已提交，暂时无法读取新草稿，请关闭选择器并核对草稿。");
    });
  }, [transfer.data, projectId, itemId, onApplied]);
  function add(entry: LibraryEntry, confirmed = false) {
    const selected = plan(entry);
    if (!selected) { setError("所选能力或当前模式不能再添加这个类型的参考，请先调整模式或移除已有输入。"); return; }
    if (!confirmed && selected.videoInputMode && selected.videoInputMode !== draft.videoInputMode) { setConfirmation(entry); return; }
    setError(null); setConfirmation(null);
    submitted.current = { ...draft, ...(selected.videoInputMode ? { videoInputMode: selected.videoInputMode } : {}) };
    transfer.start({ entryId: entry.id, expectedVersion: entry.version, draft: submitted.current, role: selected.role, color: selected.color });
  }
  return <div className="library-picker"><p>选择资产加入本次参考，不额外创建画布节点。</p>
    {error ? <p role="alert">{error}</p> : null}
    {confirmation ? <div role="alert">添加此参考会切换视频输入模式，保留当前提示词与输入。<button type="button" onClick={() => add(confirmation, true)}>确认切换并添加</button><button type="button" onClick={() => setConfirmation(null)}>取消切换</button></div> : null}
    <TransferState transfer={transfer} success="已添加为参考" />
    <LibraryBrowser kinds={kinds} disabled={transfer.working || transfer.frozen} onPick={add} />
  </div>;
}
