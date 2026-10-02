import { X } from "@phosphor-icons/react";
import { useEffect,useRef,useState } from "react";
import { createPortal } from "react-dom";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import "./ImagePreviewDialog.css";

export function ImagePreviewDialog({ title, sourceUrl, onClose }: {
  title: string; sourceUrl: string; onClose: () => void;
}) {
  useLocale();
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const [status, setStatus] = useState<"LOADING" | "READY" | "FAILED">("LOADING");

  useEffect(() => {
    const previousFocus = document.activeElement;
    closeRef.current?.focus();
    return () => { if (previousFocus instanceof HTMLElement) previousFocus.focus(); };
  }, []);

  return createPortal(<div className="image-preview-backdrop nodrag nowheel nopan"
    onPointerDown={(event) => event.stopPropagation()}
    onWheel={(event) => event.stopPropagation()}
    onClick={(event) => {
      event.stopPropagation();
      if (event.target === event.currentTarget) onClose();
    }}>
    <div ref={dialogRef} role="dialog" aria-modal="true" aria-label={t("image.preview.previewLabel", { "0": title })}
      className="image-preview-dialog" onKeyDown={(event) => {
        event.stopPropagation();
        if (event.key === "Escape") onClose();
        if (event.key === "Tab") {
          const controls = dialogRef.current?.querySelectorAll<HTMLButtonElement>("button");
          const first = controls?.[0];
          const last = controls?.[controls.length - 1];
          if (event.shiftKey && document.activeElement === first) {
            event.preventDefault(); last?.focus();
          } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault(); first?.focus();
          }
        }
      }}>
      <header><strong>{title}</strong>
        <Button variant="ghost" ref={closeRef} type="button" aria-label={t("image.preview.close")} onClick={onClose}><X size={22} /></Button>
      </header>
      <div className="image-preview-stage">
        {status === "FAILED" ? <div className="image-preview-error" role="alert">
          <p>{t("image.preview.loadFailed")}</p>
          <Button variant="ghost" type="button" onClick={() => {
            closeRef.current?.focus();
            setStatus("LOADING");
          }}>{t("image.preview.retry")}</Button>
        </div> : <>
          <img src={sourceUrl} alt={t("image.preview.originalImage", { "0": title })} draggable={false}
            onLoad={() => setStatus("READY")} onError={() => setStatus("FAILED")} />
          {status === "LOADING" ? <div className="image-preview-loading">
            <LoadingState label={t("image.preview.loading")} />
          </div> : null}
        </>}
      </div>
    </div>
  </div>, document.body);
}
