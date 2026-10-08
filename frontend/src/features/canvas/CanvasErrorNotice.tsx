import { X } from "@/shared/ui/icons";
import { useState,type ReactNode } from "react";
import { t,useLocale } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";

/** Dismiss only this occurrence; retain request state for safe retries and future failures. */
export function CanvasErrorNotice({ error,title,message,onLocate,children }: {
  error: Error | string; title: string; message: string; onLocate?: () => void; children?: ReactNode;
}) {
  useLocale();
  const [dismissed,setDismissed] = useState<Error | string | null>(null);
  if (dismissed === error) return null;
  return <div className="canvas-error-notice nodrag nopan">
    <Notice tone="danger" title={title}>
      <p>{message}</p>
      <div className="canvas-error-actions">
        {onLocate ? <Button variant="ghost" size="xs" type="button" onClick={onLocate}>{t("canvas.feedback.locate")}</Button> : null}
        {children}
        <Button variant="ghost" size="icon-xs" type="button" aria-label={t("canvas.feedback.dismiss")}
          onClick={() => setDismissed(error)}><X /></Button>
      </div>
    </Notice>
  </div>;
}
