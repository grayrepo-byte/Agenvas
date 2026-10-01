import { t, useLocale } from "../i18n";
import { useEffect, useId, useRef, type FormEventHandler, type ReactNode } from "react";
import { createPortal } from "react-dom";
import { X } from "@phosphor-icons/react";
import "./design-tokens.css";
import "./PageTheme.css";
import "./Dialog.css";

// Native dialogs can stack (call details -> Prompt). Restore body scrolling only
// after the last dialog closes, including when a route unmounts the whole stack.
let openDialogCount = 0;
let beforeDialogsOverflow = "";

/** Native modal isolation and focus restoration; the form body alone can scroll. */
export function Dialog({ title, description, children, footer, onClose, onSubmit, busy = false, className = "" }: {
  title: string; description?: string; children: ReactNode; footer: ReactNode;
  onClose: () => void; onSubmit: FormEventHandler<HTMLFormElement>; busy?: boolean; className?: string;
}) {
  useLocale();
  const titleId = useId();
  const descriptionId = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const previousFocus = document.activeElement;
    const element = dialog.current;
    if (openDialogCount === 0) beforeDialogsOverflow = document.body.style.overflow;
    openDialogCount += 1;
    document.body.style.overflow = "hidden";
    // The attribute fallback supports DOM test environments without the dialog API.
    if (element?.showModal) element.showModal();
    else element?.setAttribute("open", "");
    return () => {
      element?.close?.();
      openDialogCount -= 1;
      if (openDialogCount === 0) document.body.style.overflow = beforeDialogsOverflow;
      if (previousFocus instanceof HTMLElement && previousFocus.isConnected) previousFocus.focus();
    };
  }, []);
  return createPortal(<dialog ref={dialog} className={`ui-dialog app-page ${className}`}
    aria-modal="true" aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}
    onCancel={(event) => { event.preventDefault(); event.stopPropagation(); if (!busy) onClose(); }}
    onKeyDown={(event) => {
      if (event.key === "Escape") { event.preventDefault(); event.stopPropagation(); if (!busy) onClose(); }
      if (event.key === "Tab") {
        event.stopPropagation();
        const controls = [...event.currentTarget.querySelectorAll<HTMLElement>("button, input, select, textarea, a[href], [tabindex]")]
          .filter((control) => control.tabIndex >= 0 && !control.matches(":disabled") && !control.closest("[hidden]"));
        const first = controls[0]; const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        else if (!controls.length) event.preventDefault();
      }
    }}>
    <header className="ui-dialog-header">
      <div><h2 id={titleId}>{title}</h2>{description ? <p id={descriptionId}>{description}</p> : null}</div>
      <button type="button" className="ghost-button" aria-label={t("关闭窗口")} disabled={busy} onClick={onClose}><X size={18} /></button>
    </header>
    <form className="ui-dialog-form" onSubmit={onSubmit}>
      <div className="ui-dialog-body">{children}</div>
      <footer className="ui-dialog-footer">{footer}</footer>
    </form>
  </dialog>, document.body);
}
