import { useQuery } from "@tanstack/react-query";
import { useEffect,useRef,useState } from "react";
import { getMediaDraft,referenceLibraryEntry,type Artifact,type LibraryEntry,type MediaDraft,type ReferenceLibraryRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { LibraryBrowser } from "./LibraryBrowser";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";

type ReferencePlan = Pick<ReferenceLibraryRequest, "role" | "color" | "slotKey"> & { videoInputMode: ReferenceLibraryRequest["draft"]["videoInputMode"] };
export function LibraryReferencePicker({ projectId, itemId, draft, kinds, plan, onApplied, onBusy, slotKey }: {
  projectId: string; itemId: string; slotKey?: string; draft: ReferenceLibraryRequest["draft"]; kinds: Artifact["kind"][];
  plan: (entry: LibraryEntry) => ReferencePlan | null;
  onApplied: (saved: MediaDraft, submitted: ReferenceLibraryRequest["draft"], selectedSlotKey?: ReferenceLibraryRequest["slotKey"]) => void; onBusy?: (busy: boolean) => void;
}) {
  useLocale();
  const [error, setError] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<LibraryEntry | null>(null);
  const submitted = useRef(draft);
  const selectedSlot = useRef<ReferenceLibraryRequest["slotKey"]>(undefined);
  const applied = useRef<string | null>(null);
  const transfer = useLibraryTransfer<Omit<ReferenceLibraryRequest, "commandKey">>((request) => referenceLibraryEntry(projectId, itemId, request));
  const completed = transfer.data?.status === "SUCCEEDED" ? transfer.data : undefined;
  const resultDraft = useQuery({
    queryKey: ["library-reference-draft", projectId, itemId, completed?.id],
    queryFn: () => getMediaDraft(projectId, itemId),
    enabled: Boolean(completed), retry: false, staleTime: Infinity, refetchOnWindowFocus: false,
  });
  const busy = transfer.working || Boolean(completed && resultDraft.isFetching);
  useEffect(() => { onBusy?.(busy); return () => onBusy?.(false); }, [busy, onBusy]);
  useEffect(() => {
    if (!completed || !resultDraft.data || applied.current === completed.id) return;
    applied.current = completed.id;
    if (resultDraft.data.version !== completed.result?.draftVersion) {
      setError(t("library.referencePicker.conflict"));
      return;
    }
    onApplied(resultDraft.data, submitted.current, selectedSlot.current);
  }, [completed, resultDraft.data, onApplied]);
  function add(entry: LibraryEntry, confirmed = false) {
    const selected = plan(entry);
    if (!selected) { setError(t("library.referencePicker.referenceUnsupported")); return; }
    if (!confirmed && selected.videoInputMode && selected.videoInputMode !== draft.videoInputMode) { setConfirmation(entry); return; }
    setError(null); setConfirmation(null);
    submitted.current = { ...draft, ...(selected.videoInputMode ? { videoInputMode: selected.videoInputMode } : {}) };
    selectedSlot.current = selected.slotKey ?? slotKey;
    transfer.start({ entryId: entry.id, expectedVersion: entry.version, draft: submitted.current, role: selected.role, color: selected.color, ...(selectedSlot.current ? { slotKey: selectedSlot.current } : {}) });
  }
  return <div className="library-picker"><p>{t("library.referencePicker.referenceHint")}</p>
    {error ? <p role="alert">{error}</p> : null}
    {confirmation ? <div role="alert">{t("library.referencePicker.modeChangeHint")}<Button variant="ghost" type="button" onClick={() => add(confirmation, true)}>{t("library.referencePicker.confirmModeChange")}</Button><Button variant="ghost" type="button" onClick={() => setConfirmation(null)}>{t("library.referencePicker.cancelModeChange")}</Button></div> : null}
    <TransferState transfer={{ ...transfer, error: transfer.error ?? resultDraft.error,
      // A completed transfer is immutable. Recover only its follow-up read, never submit again.
      retry: resultDraft.isError ? () => { void resultDraft.refetch(); } : transfer.retry }}
      success={t("library.referencePicker.added")} />
    <LibraryBrowser pickAction="REFERENCE" kinds={kinds} disabled={transfer.working || transfer.frozen} onPick={add} />
  </div>;
}
