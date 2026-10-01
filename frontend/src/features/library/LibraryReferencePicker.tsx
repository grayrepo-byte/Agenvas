import { useEffect,useRef,useState } from "react";
import { getMediaDraft,referenceLibraryEntry,type Artifact,type LibraryEntry,type MediaDraft,type ReferenceLibraryRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { LibraryBrowser } from "./LibraryBrowser";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";

type ReferencePlan = Pick<ReferenceLibraryRequest, "role" | "color"> & { videoInputMode: ReferenceLibraryRequest["draft"]["videoInputMode"] };
export function LibraryReferencePicker({ projectId, itemId, draft, kinds, plan, onApplied, onBusy }: {
  projectId: string; itemId: string; draft: ReferenceLibraryRequest["draft"]; kinds: Artifact["kind"][];
  plan: (entry: LibraryEntry) => ReferencePlan | null;
  onApplied: (saved: MediaDraft, submitted: ReferenceLibraryRequest["draft"]) => void; onBusy?: (busy: boolean) => void;
}) {
  useLocale();
  const [error, setError] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<LibraryEntry | null>(null);
  const [readFinished, setReadFinished] = useState(false);
  const submitted = useRef(draft);
  const applied = useRef<string | null>(null);
  const transfer = useLibraryTransfer<Omit<ReferenceLibraryRequest, "commandKey">>((request) => referenceLibraryEntry(projectId, itemId, request));
  const busy = transfer.working || transfer.data?.status === "SUCCEEDED" && !readFinished;
  useEffect(() => { onBusy?.(busy); return () => onBusy?.(false); }, [busy, onBusy]);
  useEffect(() => {
    if (transfer.data?.status !== "SUCCEEDED" || applied.current === transfer.data.id) return;
    applied.current = transfer.data.id;
    const resultVersion = transfer.data.result?.draftVersion;
    void getMediaDraft(projectId, itemId).then((saved) => {
      if (saved.version !== resultVersion) {
        setError(t("参考已添加，但草稿已被其他操作修改。本地输入已保留，请关闭选择器并刷新核对。"));
        return;
      }
      onApplied(saved, submitted.current);
    }).catch((failure: unknown) => {
      setError(failure instanceof Error ? failure.message : t("引用已提交，暂时无法读取新草稿，请关闭选择器并核对草稿。"));
    }).finally(() => setReadFinished(true));
  }, [transfer.data, projectId, itemId, onApplied]);
  function add(entry: LibraryEntry, confirmed = false) {
    const selected = plan(entry);
    if (!selected) { setError(t("所选能力或当前模式不能再添加这个类型的参考，请先调整模式或移除已有输入。")); return; }
    if (!confirmed && selected.videoInputMode && selected.videoInputMode !== draft.videoInputMode) { setConfirmation(entry); return; }
    setError(null); setConfirmation(null);
    submitted.current = { ...draft, ...(selected.videoInputMode ? { videoInputMode: selected.videoInputMode } : {}) };
    transfer.start({ entryId: entry.id, expectedVersion: entry.version, draft: submitted.current, role: selected.role, color: selected.color });
  }
  return <div className="library-picker"><p>{t("选择资产加入本次参考，不额外创建画布节点。")}</p>
    {error ? <p role="alert">{error}</p> : null}
    {confirmation ? <div role="alert">{t("添加此参考会切换视频输入模式，保留当前提示词与输入。")}<Button variant="ghost" type="button" onClick={() => add(confirmation, true)}>{t("确认切换并添加")}</Button><Button variant="ghost" type="button" onClick={() => setConfirmation(null)}>{t("取消切换")}</Button></div> : null}
    <TransferState transfer={transfer} success={t("已添加为参考")} />
    <LibraryBrowser kinds={kinds} disabled={transfer.working || transfer.frozen} onPick={add} />
  </div>;
}
