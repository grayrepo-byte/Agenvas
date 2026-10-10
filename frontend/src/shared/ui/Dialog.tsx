import { X } from "@/shared/ui/icons";
import { cn } from "cn";
import { useId,useRef,type FormEventHandler,type ReactNode } from "react";
import { t,useLocale } from "../i18n";
import "./design-tokens.css";
import "./Dialog.css";
import "./PageTheme.css";
import { Button } from "./primitives/button";
import { DialogContent,DialogDescription,DialogFooter,DialogHeader,Dialog as DialogRoot,DialogTitle } from "./primitives/dialog";

/** Business form composition; shadcn/Radix handles modal isolation and nested focus. */
export function Dialog({ title, description, children, footer, onClose, onSubmit, busy = false, className, compact = false }: {
  title: string; description?: string; children: ReactNode; footer?: ReactNode;
  onClose: () => void; onSubmit: FormEventHandler<HTMLFormElement>; busy?: boolean; className?: string; compact?: boolean;
}) {
  useLocale();
  const descriptionId = useId();
  const previousFocus = useRef(document.activeElement);
  return <DialogRoot open onOpenChange={(open) => { if (!open && !busy) onClose(); }}>
    <DialogContent className={cn("ui-dialog app-page", compact && "ui-dialog--compact", className)} showCloseButton={false}
      aria-describedby={description ? descriptionId : undefined}
      onEscapeKeyDown={(event) => { event.stopPropagation(); if (busy) event.preventDefault(); }}
      onPointerDownOutside={(event) => event.preventDefault()}
      onCloseAutoFocus={(event) => {
        event.preventDefault();
        if (previousFocus.current instanceof HTMLElement && previousFocus.current.isConnected) previousFocus.current.focus();
      }}>
      <DialogHeader className="ui-dialog-header">
        <div><DialogTitle>{title}</DialogTitle>{description ? <DialogDescription id={descriptionId}>{description}</DialogDescription> : null}</div>
        <Button type="button" variant="ghost" size="icon-sm" aria-label={t("ui.dialog.close")} disabled={busy} onClick={onClose}><X /></Button>
      </DialogHeader>
      <form className="ui-dialog-form" onSubmit={onSubmit}>
        <div className="ui-dialog-body">{children}</div>
        {footer ? <DialogFooter className="ui-dialog-footer">{footer}</DialogFooter> : null}
      </form>
    </DialogContent>
  </DialogRoot>;
}
