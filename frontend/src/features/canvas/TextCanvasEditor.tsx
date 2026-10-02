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
        <span>{t("当前版本已更新，本地修改仍保留。")}</span>
        <Button variant="ghost" type="button" onClick={reload}>{t("载入最新版本")}</Button>
      </div> : null}
      <Textarea ref={editorRef} aria-label={t("内容")} data-content-editor-focus="true" maxLength={MAX_TEXT_LENGTH}
        disabled={status.busy} placeholder={t("写下想法，让创作开始…")} required value={fields.text}
        onChange={(event) => edit({ text: event.target.value })} />
      {status.dirty ? <span className="text-card-count" role="status">{t("有未保存的修改")}</span> : null}
      {status.error ? <p className="text-card-error" role="alert">{status.conflict
        ? t("内容有冲突，修改未保存；请核对当前版本后重试。")
        : t("{0} 输入已保留。", { "0": status.error instanceof ApiError ? status.error.message : t("修改未完成，请重试。") })}</p> : null}
      {status.reloadError ? <p className="text-card-error" role="alert">{t("载入失败，当前输入已保留；请重试。")}</p> : null}
      {status.saved && !status.dirty ? <span className="text-card-saved" role="status">{t("新版本已保存")}</span> : null}
    </div>
    <footer className="content-card-sources text-card-editor-footer">
      {canvasItemId ? <SaveToLibraryButton projectId={artifact.projectId} itemId={canvasItemId} disabled={status.busy || !valid}
        beforeOpen={async () => { if (status.dirty) await saveAsync({ expectedVersion: base.version, title: base.title,
          content: { format: fields.format, text: fields.text.trim() } }); }} /> : null}
      <Select variant="ghost" density="compact" aria-label={t("文字格式")} disabled={status.busy} value={fields.format}
        onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
        <option value="PLAIN_TEXT">{t("纯文本")}</option><option value="MARKDOWN">Markdown</option>
      </Select>
      <span className="text-card-count">{fields.text.length}/{MAX_TEXT_LENGTH}</span>
      {locked ? <LockSimple className="content-card-locked" size={13} aria-label={t("已锁定")} /> : null}
      <Button variant="ghost" aria-label={t("退出内容编辑")} className="text-card-done" disabled={status.busy}
        onClick={() => status.dirty ? setConfirmExit(true) : onDone()} title={t("退出内容编辑")} type="button"><X size={14} /></Button>
      <Button variant="ghost" aria-label={t("保存新版本")} className="text-card-save" disabled={status.busy || !status.dirty || !valid}
        title={status.saving ? t("保存中…") : t("保存新版本")} type="submit"><ArrowUp size={15} weight="bold" /></Button>
    </footer>
  </form>
    {confirmExit ? <Dialog title={t("有未保存的修改")} onClose={() => setConfirmExit(false)}
      busy={status.busy} onSubmit={(event) => { event.preventDefault(); if (valid) void saveAndExit(); }}
      footer={<><Button variant="outline" type="button" disabled={status.busy} onClick={() => setConfirmExit(false)}>{t("继续编辑")}</Button>
        <Button variant="destructive" type="button" disabled={status.busy} onClick={onDone}>{t("放弃修改")}</Button>
        <Button type="submit" disabled={status.busy || !valid}>{t("保存并退出")}</Button></>}>
      <p>{t("保存后退出，或继续编辑以保留当前输入。")}</p>
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
