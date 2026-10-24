import { Image as ImageIcon, ShieldCheck } from "@/shared/ui/icons";
import { useState, type FormEventHandler, type ReactNode } from "react";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import "./MediaToolWorkspace.css";

/** Viewport-bound editing surface: preview and controls scroll independently of the canvas. */
export function MediaToolWorkspace({ title, description, preview, children, footer, busy, onClose, onSubmit }: {
  title: string; description: string; preview: ReactNode; children: ReactNode; footer: ReactNode;
  busy: boolean; onClose: () => void; onSubmit: FormEventHandler<HTMLFormElement>;
}) {
  useLocale();
  return <Dialog title={title} description={description} className="media-tool-workspace nodrag nowheel nopan"
    busy={busy} onClose={onClose} onSubmit={onSubmit} footer={<>
      <span className="media-tool-result-note"><ShieldCheck />{t("media.tools.newResult")}</span>
      <div className="media-tool-footer-actions">{footer}</div>
    </>}>
    <div className="media-tool-layout">
      <section className="media-tool-stage" aria-label={t("media.tools.sourcePreview")}>{preview}</section>
      <div className="media-tool-controls">{children}</div>
    </div>
  </Dialog>;
}

/** Always show the real source; guides describe intent and never impersonate an AI result. */
export function MediaToolImage({ src, title, aspectRatio, caption, children }: {
  src?: string; title: string; aspectRatio?: string; caption?: string; children?: ReactNode;
}) {
  useLocale();
  const [failed, setFailed] = useState(false);
  const [width = 1, height = 1] = aspectRatio?.split(":").map(Number) ?? [];
  return <>
    <div className="media-tool-stage-heading"><span>{t("media.tools.original")}</span><span title={title}>{title}</span></div>
    <div className="media-tool-image-area">
      {failed || !src ? <div className="media-tool-preview-error" role="status"><ImageIcon />
        <p>{t("media.card.previewUnavailable")}</p>
        {src ? <Button type="button" variant="outline" size="sm" onClick={() => setFailed(false)}>{t("media.card.retryPreview")}</Button> : null}
      </div> : <div className="media-tool-image-frame" data-guide={aspectRatio ? "true" : undefined}
        style={aspectRatio ? { aspectRatio: aspectRatio.replace(":", " / "),
          width: `min(100%, 440px, calc(var(--media-tool-guide-height) * ${width / height}))` } : undefined}>
        <img src={src} alt={title} draggable={false} onError={() => setFailed(true)} />
      </div>}
      {children}
    </div>
    <p className="media-tool-preview-caption">{caption ?? t("media.tools.aiPreviewHint")}</p>
  </>;
}

export function MediaToolVideo({ src, poster, title }: { src: string; poster?: string; title: string }) {
  useLocale();
  const [failed, setFailed] = useState(false);
  return failed ? <div className="media-tool-image-area"><div className="media-tool-preview-error" role="status">
    <ImageIcon /><p>{t("media.card.previewUnavailable")}</p>
    <Button variant="outline" type="button" size="sm" onClick={() => setFailed(false)}>{t("media.card.retryPreview")}</Button>
  </div></div> : <video className="media-tool-video" src={src} poster={poster} controls preload="metadata" aria-label={title} onError={() => setFailed(true)} />;
}
