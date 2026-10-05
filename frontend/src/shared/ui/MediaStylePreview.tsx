import { PaintBrush } from "@phosphor-icons/react";
import { useState } from "react";
import type { MediaStyleSummary } from "../api/client";
import { t, useLocale } from "../i18n";
import "./MediaStyles.css";

/** Preset and uploaded previews use the same authenticated server-owned image URLs. */
export function MediaStylePreview({ style }: { style: Pick<MediaStyleSummary, "name" | "thumbnailUrl"> }) {
  useLocale();
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  return <div className="media-style-preview">
    {style.thumbnailUrl && failedUrl !== style.thumbnailUrl
      ? <img src={style.thumbnailUrl} alt={t("styles.previewAlt", { "0": style.name })} loading="lazy"
        onError={() => setFailedUrl(style.thumbnailUrl)} />
      : <span className="media-style-preview-empty"><PaintBrush aria-hidden />{t("styles.noPreview")}</span>}
  </div>;
}
