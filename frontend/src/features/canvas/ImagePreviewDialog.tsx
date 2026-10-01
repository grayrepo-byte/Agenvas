import { t, useLocale } from "../../shared/i18n";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { X } from "@phosphor-icons/react";
import { LoadingState } from "../../shared/ui/LoadingState";
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
    <div ref={dialogRef} role="dialog" aria-modal="true" aria-label={t("{0} 的原图预览", { "0": title })}
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
        <button ref={closeRef} type="button" aria-label={t("关闭图片预览")} onClick={onClose}><X size={22} /></button>
      </header>
      <div className="image-preview-stage">
        {status === "FAILED" ? <div className="image-preview-error" role="alert">
          <p>{t("图片加载失败，请重试。")}</p>
          <button type="button" onClick={() => {
            closeRef.current?.focus();
            setStatus("LOADING");
          }}>{t("重试加载图片")}</button>
        </div> : <>
          <img src={sourceUrl} alt={t("{0} 的原图", { "0": title })} draggable={false}
            onLoad={() => setStatus("READY")} onError={() => setStatus("FAILED")} />
          {status === "LOADING" ? <div className="image-preview-loading">
            <LoadingState label={t("正在加载图片")} />
          </div> : null}
        </>}
      </div>
    </div>
  </div>, document.body);
}
