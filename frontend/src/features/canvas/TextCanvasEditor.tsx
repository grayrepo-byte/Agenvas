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
export function TextCanvasEditor({ artifact, locked, onDone }: {
  artifact: VersionedArtifact;
  locked: boolean;
  onDone: () => void;
}) {
  const { base, fields, edit, save, reload, status } = useArtifactRevision({
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
        <span>当前版本已更新，本地修改仍保留。</span>
        <button type="button" onClick={reload}>载入最新版本</button>
      </div> : null}
      <textarea aria-label="内容" data-content-editor-focus="true" maxLength={MAX_TEXT_LENGTH}
        disabled={status.busy} placeholder="写下想法，让创作开始…" required value={fields.text}
        onChange={(event) => edit({ text: event.target.value })} />
      {status.error ? <p className="text-card-error" role="alert">{status.conflict
        ? "内容有冲突，修改未保存；请核对当前版本后重试。"
        : `${status.error instanceof ApiError ? status.error.message : "修改未完成，请重试。"} 输入已保留。`}</p> : null}
      {status.reloadError ? <p className="text-card-error" role="alert">载入失败，当前输入已保留；请重试。</p> : null}
      {status.saved && !status.dirty ? <span className="text-card-saved" role="status">新版本已保存</span> : null}
    </div>
    <footer className="content-card-sources text-card-editor-footer">
      <span className="content-card-chip"><TextT size={12} aria-hidden />文字</span>
      <TextVersionPicker artifact={base} disabled={status.dirty} />
      <Select density="compact" aria-label="文字格式" disabled={status.busy} value={fields.format}
        onChange={(event) => edit({ format: event.target.value === "MARKDOWN" ? "MARKDOWN" : "PLAIN_TEXT" })}>
        <option value="PLAIN_TEXT">纯文本</option><option value="MARKDOWN">Markdown</option>
      </Select>
      <span className="text-card-count">{fields.text.length}/{MAX_TEXT_LENGTH}</span>
      {locked ? <LockSimple className="content-card-locked" size={13} aria-label="已锁定" /> : null}
      <button aria-label="退出内容编辑" className="text-card-done" disabled={status.busy}
        onClick={onDone} title="退出内容编辑" type="button"><X size={14} /></button>
      <button aria-label="保存新版本" className="text-card-save" disabled={status.busy || !status.dirty || !valid}
        title={status.saving ? "保存中…" : "保存新版本"} type="submit"><ArrowUp size={15} weight="bold" /></button>
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
