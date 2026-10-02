import { ArrowUp,LockSimple,X } from "@phosphor-icons/react";
import { useEffect, useState, type FormEvent, type RefObject } from "react";
import {
ApiError,reviseArtifact,
type ReviseArtifactRequest
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog } from "../../shared/ui/Dialog";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Select } from "../../shared/ui/Select";
import { SaveToLibraryButton } from "../library/SaveToLibraryButton";
import { readContentText } from "./artifactContent";
import { useArtifactRevision } from "./useArtifactRevision";
import type { VersionedArtifact } from "./versionedArtifact";

const MAX_TEXT_LENGTH = 20_000;

type TextFields = {
  text: string;
  format: "PLAIN_TEXT" | "MARKDOWN";
};

export type TextEditingState = { artifact: VersionedArtifact; dirty: boolean; busy: boolean };

/** Keeps text editing and immutable-version selection inside the text node itself. */
export function TextCanvasEditor({ artifact, canvasItemId, locked, onDone, editorRef, onEditingStateChange }: {
  artifact: VersionedArtifact;
  canvasItemId?: string;
  locked: boolean;
  onDone: () => void;
  editorRef?: RefObject<HTMLTextAreaElement | null>;
  onEditingStateChange?: (state: TextEditingState) => void;
}) {
  useLocale();
  const { base, fields, edit, save, saveAsync, reload, status } = useArtifactRevision({
    artifact,
    readFields,
    saveRevision: (current, revision: ReviseArtifactRequest) =>
      reviseArtifact(current.projectId, current.id, revision),
  });
  const valid = Boolean(fields.text.trim());
  const [confirmExit, setConfirmExit] = useState(false);
  // Keep the toolbar's version selector pinned to the same base as this draft.
  useEffect(() => {
    onEditingStateChange?.({ artifact: base, dirty: status.dirty, busy: status.busy });
  }, [base, status.dirty, status.busy, onEditingStateChange]);
  useEffect(() => {
    if (!status.dirty) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [status.dirty]);
  async function saveAndExit() {
    try {
      await saveAsync({ expectedVersion: base.version, title: base.title,
        content: { format: fields.format, text: fields.text.trim() } });
      onDone();
    } catch { setConfirmExit(false); } // Mutation renders the failure while retaining the editor.
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status.busy || !status.dirty || !valid) return;
    save({ expectedVersion: base.version, title: base.title,
      content: { format: fields.format, text: fields.text.trim() } });
  }

  return <><form className="text-card-editor nodrag nowheel nopan" onSubmit={submit}>
    <div className="content-card-body text-card-editor-body">
      {status.newerAvailable || status.conflict ? <div className="text-card-notice">
        <span>{t("common.versionConflict")}</span>
        <Button variant="ghost" type="button" onClick={reload}>{t("common.refreshVersion")}</Button>
      </div> : null}
      <Textarea ref={editorRef} aria-label={t("text.editor.content")} data-content-editor-focus="true" maxLength={MAX_TEXT_LENGTH}
        disabled={status.busy} placeholder={t("text.editor.contentPlaceholder")} required value={fields.text}
        onChange={(event) => edit({ text: event.target.value })} />
      {status.dirty ? <span className="text-card-count" role="status">{t("common.unsavedChanges")}</span> : null}
      {status.error ? <p className="text-card-error" role="alert">{status.conflict
        ? t("text.editor.conflict")
        : t("common.inputPreserved", { "0": status.error instanceof ApiError ? status.error.message : t("text.editor.saveFailed") })}</p> : null}
      {status.reloadError ? <p className="text-card-error" role="alert">{t("text.editor.loadFailed")}</p> : null}
      {status.saved && !status.dirty ? <span className="text-card-saved" role="status">{t("text.editor.saved")}</span> : null}
    </div>
    <footer className="content-card-sources text-card-editor-footer">
      {canvasItemId ? <SaveToLibraryButton projectId={artifact.projectId} itemId={canvasItemId} disabled={status.busy || !valid}
        beforeOpen={async () => { if (status.dirty) await saveAsync({ expectedVersion: base.version, title: base.title,
          content: { format: fields.format, text: fields.text.trim() } }); }} /> : null}
      <Select variant="ghost" density="compact" aria-label={t("text.editor.format")} disabled={status.busy} value={fields.format}
        onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
        <option value="PLAIN_TEXT">{t("common.plainText")}</option><option value="MARKDOWN">Markdown</option>
      </Select>
      <span className="text-card-count">{fields.text.length}/{MAX_TEXT_LENGTH}</span>
      {locked ? <LockSimple className="content-card-locked" size={13} aria-label={t("canvas.card.locked")} /> : null}
      <Button variant="ghost" aria-label={t("text.editor.exit")} className="text-card-done" disabled={status.busy}
        onClick={() => status.dirty ? setConfirmExit(true) : onDone()} title={t("text.editor.exit")} type="button"><X size={14} /></Button>
      <Button variant="ghost" aria-label={t("text.editor.saveVersion")} className="text-card-save" disabled={status.busy || !status.dirty || !valid}
        title={status.saving ? t("common.saving") : t("text.editor.saveVersion")} type="submit"><ArrowUp size={15} weight="bold" /></Button>
    </footer>
  </form>
    {confirmExit ? <Dialog title={t("common.unsavedChanges")} onClose={() => setConfirmExit(false)}
      busy={status.busy} onSubmit={(event) => { event.preventDefault(); if (valid) void saveAndExit(); }}
      footer={<><Button variant="outline" type="button" disabled={status.busy} onClick={() => setConfirmExit(false)}>{t("common.continueEditing")}</Button>
        <Button variant="destructive" type="button" disabled={status.busy} onClick={onDone}>{t("common.discardChanges")}</Button>
        <Button type="submit" disabled={status.busy || !valid}>{t("text.editor.saveAndExit")}</Button></>}>
      <p>{t("text.editor.exitHint")}</p>
    </Dialog> : null}
  </>;
}

function readFields(artifact: VersionedArtifact): TextFields {
  return {
    text: readContentText(artifact.resourceDefaultVersion.content, "text"),
    format: readContentText(artifact.resourceDefaultVersion.content, "format") === "MARKDOWN"
      ? "MARKDOWN" : "PLAIN_TEXT",
  };
}
