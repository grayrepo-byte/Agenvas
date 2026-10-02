import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { BookmarkSimple } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { assetThumbnailUrl,getLibrarySource,saveLibraryEntry,type LibraryCategory,type SaveLibraryRequest } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import "./Library.css";
import { CATEGORY_LABELS,MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";

export function SaveToLibraryButton({ projectId, itemId, disabled = false, beforeOpen }: {
  projectId: string; itemId: string; disabled?: boolean; beforeOpen?: () => Promise<unknown>;
}) {
  useLocale();
  const [open, setOpen] = useState(false);
  const [preparing, setPreparing] = useState(false);
  return <><Button variant="ghost" type="button" disabled={disabled || preparing} onClick={() => {
    setPreparing(true);
    void Promise.resolve().then(beforeOpen).then(() => setOpen(true)).catch(() => { /* Editor owns its save error and keeps its input. */ }).finally(() => setPreparing(false));
  }}><BookmarkSimple size={17} />{preparing ? t("library.save.savingText") : t("library.save.saveAction")}</Button>
    {open ? <SaveWindow projectId={projectId} itemId={itemId} onClose={() => setOpen(false)} /> : null}</>;
}
function SaveWindow({ projectId, itemId, onClose }: { projectId: string; itemId: string; onClose: () => void }) {
  useLocale();
  const source = useQuery({ queryKey: ["library-source", projectId, itemId], queryFn: () => getLibrarySource(projectId, itemId), retry: false, staleTime: 0, refetchOnWindowFocus: false });
  const [name, setName] = useState<string | null>(null);
  const [category, setCategory] = useState<LibraryCategory>("OTHER");
  const transfer = useLibraryTransfer<Omit<SaveLibraryRequest, "commandKey">>((input) => saveLibraryEntry(projectId, itemId, input));
  const value = name ?? source.data?.title ?? "";
  const done = transfer.data?.status === "SUCCEEDED";
  const locked = transfer.working || transfer.frozen;
  const success = transfer.data?.result?.trashed ? t("library.save.inTrash")
    : transfer.data?.result?.alreadySaved ? t("library.save.alreadySaved") : t("library.page.savedCategory", { "0": CATEGORY_LABELS[category] });
  return <Dialog title={t("library.save.saveAction")} onClose={onClose} busy={transfer.working} onSubmit={(event) => {
    event.preventDefault(); if (!source.data || done) return;
    transfer.start({ versionId: source.data.versionId, expectedSelectionEpoch: source.data.expectedSelectionEpoch,
      ...(source.data.kind === "TEXT" ? { expectedArtifactVersion: source.data.expectedArtifactVersion } : {}), name: value.trim(), category });
  }} footer={<><Button variant="outline"  type="button" disabled={transfer.working} onClick={onClose}>{done ? t("common.done") : t("common.cancel")}</Button>
    {done ? <Link className="primary-button" to="/library">{t("library.save.view")}</Link> : <Button variant="default"  type="submit" disabled={!source.data || !value.trim() || transfer.working || transfer.data?.status === "FAILED"}>{transfer.working ? t("common.saving") : t("library.save.title")}</Button>}</>}>
    {source.isPending ? <p>{t("library.save.resultLoading")}</p> : null}
    {source.error ? <p role="alert">{source.error.message}<Button variant="ghost" type="button" onClick={() => void source.refetch()}>{t("library.save.refreshPreview")}</Button></p> : null}
    {source.data ? <><p>{t("library.save.nodeVersionTitle", { "0": source.data.title, "1": source.data.versionNo })}</p>
      {source.data.kind === "TEXT" ? <p className="library-text-preview">{String(source.data.textContent?.text ?? "")}</p>
        : source.data.kind === "IMAGE" || source.data.kind === "VIDEO" ? <img className="library-save-preview" src={assetThumbnailUrl(projectId, source.data.assetId!)} alt={t("library.save.preview")} /> : <p>{t("library.save.audioResult")}</p>}
      <Field><FieldLabel className="field block">{t("library.page.assetName")}<Input maxLength={MAX_LIBRARY_NAME_LENGTH} value={value} disabled={locked} required onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
      <Field><FieldLabel className="field block">{t("library.page.category")}<Select value={category} disabled={locked} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></FieldLabel></Field></> : null}
    <TransferState transfer={transfer} success={success} />
    {transfer.error && !transfer.frozen ? <Button variant="ghost" type="button" onClick={() => void source.refetch()}>{t("library.save.refreshSelection")}</Button> : null}
  </Dialog>;
}
