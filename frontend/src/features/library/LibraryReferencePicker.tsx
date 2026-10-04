import { useEffect,useRef,useState } from "react";
import { getMediaDraft,referenceLibraryEntry,type Artifact,type LibraryEntry,type MediaDraft,type ReferenceLibraryRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { LibraryBrowser } from "./LibraryBrowser";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";

type ReferencePlan = Pick<ReferenceLibraryRequest, "role" | "color"> & { videoInputMode: ReferenceLibraryRequest["draft"]["videoInputMode"] };
export function LibraryReferencePicker({ projectId, itemId, draft, kinds, plan, onApplied, onBusy, slotKey }: {
  projectId: string; itemId: string; slotKey?: string; draft: ReferenceLibraryRequest["draft"]; kinds: Artifact["kind"][];
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
        setError(t("library.referencePicker.conflict"));
        return;
      }
      onApplied(saved, submitted.current);
    }).catch((failure: unknown) => {
      setError(failure instanceof Error ? failure.message : t("library.referencePicker.newDraftUnavailable"));
    }).finally(() => setReadFinished(true));
  }, [transfer.data, projectId, itemId, onApplied]);
  function add(entry: LibraryEntry, confirmed = false) {
    const selected = plan(entry);
    if (!selected) { setError(t("library.referencePicker.referenceUnsupported")); return; }
    if (!confirmed && selected.videoInputMode && selected.videoInputMode !== draft.videoInputMode) { setConfirmation(entry); return; }
    setError(null); setConfirmation(null);
    submitted.current = { ...draft, ...(selected.videoInputMode ? { videoInputMode: selected.videoInputMode } : {}) };
    transfer.start({ entryId: entry.id, expectedVersion: entry.version, draft: submitted.current, role: selected.role, color: selected.color, ...(slotKey ? { slotKey } : {}) });
  }
  return <div className="library-picker"><p>{t("library.referencePicker.referenceHint")}</p>
    {error ? <p role="alert">{error}</p> : null}
    {confirmation ? <div role="alert">{t("library.referencePicker.modeChangeHint")}<Button variant="ghost" type="button" onClick={() => add(confirmation, true)}>{t("library.referencePicker.confirmModeChange")}</Button><Button variant="ghost" type="button" onClick={() => setConfirmation(null)}>{t("library.referencePicker.cancelModeChange")}</Button></div> : null}
    <TransferState transfer={transfer} success={t("library.referencePicker.added")} />
    <LibraryBrowser pickAction="REFERENCE" kinds={kinds} disabled={transfer.working || transfer.frozen} onPick={add} />
  </div>;
}
