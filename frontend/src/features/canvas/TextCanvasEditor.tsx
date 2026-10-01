import { t, useLocale } from "../../shared/i18n";
import { SaveToLibraryButton } from "../library/SaveToLibraryButton";
import { Select } from "../../shared/ui/Select";
import { ArrowUp, LockSimple, TextT, X } from "@phosphor-icons/react";
import { type FormEvent } from "react";
import { ApiError, reviseArtifact,
  type ReviseArtifactRequest } from "../../shared/api/client";
import { readContentText } from "./artifactContent";
import { TextVersionPicker } from "./TextVersionPicker";
import { useArtifactRevision } from "./useArtifactRevision";
import type { VersionedArtifact } from "./versionedArtifact";

const MAX_TEXT_LENGTH = 20_000;

type TextFields = {
  text: string;
  format: "PLAIN_TEXT" | "MARKDOWN";
};

/** Keeps text editing and immutable-version selection inside the text node itself. */
export function TextCanvasEditor({ artifact, canvasItemId, locked, onDone }: {
  artifact: VersionedArtifact;
  canvasItemId?: string;
  locked: boolean;
  onDone: () => void;
}) {
  useLocale();
  const { base, fields, edit, save, saveAsync, reload, status } = useArtifactRevision({
    artifact,
    readFields,
    saveRevision: (current, revision: ReviseArtifactRequest) =>
      reviseArtifact(current.projectId, current.id, revision),
  });
  const valid = Boolean(fields.text.trim());

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (status.busy || !status.dirty || !valid) return;
    save({ expectedVersion: base.version, title: base.title,
      content: { format: fields.format, text: fields.text.trim() } });
  }

  return <form className="text-card-editor nodrag nowheel nopan" onSubmit={submit}>
    <div className="content-card-body text-card-editor-body">
      {status.newerAvailable || status.conflict ? <div className="text-card-notice">
        <span>{t("当前版本已更新，本地修改仍保留。")}</span>
        <button type="button" onClick={reload}>{t("载入最新版本")}</button>
      </div> : null}
      <textarea aria-label={t("内容")} data-content-editor-focus="true" maxLength={MAX_TEXT_LENGTH}
        disabled={status.busy} placeholder={t("写下想法，让创作开始…")} required value={fields.text}
        onChange={(event) => edit({ text: event.target.value })} />
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
      <span className="content-card-chip"><TextT size={12} aria-hidden />{t("文字")}</span>
      <TextVersionPicker artifact={base} disabled={status.dirty} />
      <Select density="compact" aria-label={t("文字格式")} disabled={status.busy} value={fields.format}
        onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
        <option value="PLAIN_TEXT">{t("纯文本")}</option><option value="MARKDOWN">Markdown</option>
      </Select>
      <span className="text-card-count">{fields.text.length}/{MAX_TEXT_LENGTH}</span>
      {locked ? <LockSimple className="content-card-locked" size={13} aria-label={t("已锁定")} /> : null}
      <button aria-label={t("退出内容编辑")} className="text-card-done" disabled={status.busy}
        onClick={onDone} title={t("退出内容编辑")} type="button"><X size={14} /></button>
      <button aria-label={t("保存新版本")} className="text-card-save" disabled={status.busy || !status.dirty || !valid}
        title={status.saving ? t("保存中…") : t("保存新版本")} type="submit"><ArrowUp size={15} weight="bold" /></button>
    </footer>
  </form>;
}

function readFields(artifact: VersionedArtifact): TextFields {
  return {
    text: readContentText(artifact.resourceDefaultVersion.content, "text"),
    format: readContentText(artifact.resourceDefaultVersion.content, "format") === "MARKDOWN"
      ? "MARKDOWN" : "PLAIN_TEXT",
  };
}
