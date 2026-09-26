import { ArrowUp, CheckCircle } from "@phosphor-icons/react";
import type { FormEventHandler, ReactNode } from "react";
import { ApiError } from "../../shared/api/client";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import type { ArtifactRevisionStatus } from "./useArtifactRevision";
import "./ContentArtifactEditor.css";

type ContentEditorFrameProps = {
  kind: "TEXT" | "CHARACTER" | "SCENE" | "SHOT";
  kindLabel: string;
  icon: ReactNode;
  versionNo: number;
  status: ArtifactRevisionStatus;
  valid: boolean;
  onSubmit: FormEventHandler<HTMLFormElement>;
  onReload: () => void;
  conflictMessage: string;
  successMessage: string;
  saveAriaLabel?: string;
  notice?: ReactNode;
  footer: ReactNode;
  children: ReactNode;
};

/** Shared composer presentation; each editor owns its fields, validation and revision command. */
export function ContentEditorFrame({
  kind, kindLabel, icon, versionNo, status, valid, onSubmit, onReload,
  conflictMessage, successMessage, saveAriaLabel, notice, footer, children,
}: ContentEditorFrameProps) {
  return <section aria-label={`${kindLabel}编辑器`} className={`content-artifact-editor content-artifact-editor--${kind.toLowerCase()} nodrag nowheel nopan`}>
    <header className="content-editor-header">
      <span className="content-editor-heading">{icon}<strong>{kindLabel}</strong><span>v{versionNo}</span></span>
      <span className="content-editor-state" aria-live="polite">{status.busy
        ? <CanvasLoadingState compact label={status.saving ? "保存中…" : "正在载入…"} />
        : status.dirty ? "有未保存修改" : <><CheckCircle size={13} />已保存</>}</span>
    </header>
    <form onSubmit={onSubmit}>
      <fieldset disabled={status.busy}>
        <div className={`content-editor-scroll ${kind === "TEXT" ? "content-editor-text-area" : ""}`}>
          {status.newerAvailable || status.conflict ? <div className="content-editor-notice">
            <p>当前版本已更新。载入最新版本会替换这里未保存的修改。</p>
            <button className="content-editor-text-button" type="button" onClick={onReload}>载入最新版本</button>
          </div> : null}
          {notice}
          {status.error ? <p className="content-editor-error" role="alert">{status.conflict
            ? conflictMessage
            : `${status.error instanceof ApiError ? status.error.message : "修改未完成，请重试。"} 输入已保留。`}</p> : null}
          {status.reloadError ? <p className="content-editor-error" role="alert">载入失败，当前输入已保留；请重试。</p> : null}
          {children}
          {status.saved && !status.dirty ? <p className="content-editor-success" role="status">{successMessage}</p> : null}
        </div>
        <footer className="content-editor-footer">
          {footer}
          <button aria-label={saveAriaLabel} className="content-editor-save" type="submit" disabled={status.busy || !status.dirty || !valid}>
            <span>{status.saving ? "保存中…" : "保存新版本"}</span><ArrowUp size={17} weight="bold" />
          </button>
        </footer>
      </fieldset>
    </form>
  </section>;
}
