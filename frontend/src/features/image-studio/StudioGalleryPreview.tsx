import { useMemo, useState } from "react";
import { assetContentUrl } from "../../shared/api/client";
import type { ImageStudioBatch } from "../../shared/api/imageStudio";
import { translate, useLocale } from "../../shared/i18n";
import { MediaPreviewDialog } from "../canvas/MediaPreviewDialog";
import { studioGallery } from "./studioGallery";

export function StudioGalleryPreview({
  batch,
  initialVersionId,
  onClose,
}: {
  batch: ImageStudioBatch;
  initialVersionId: string;
  onClose: (versionId: string) => void;
}) {
  const locale = useLocale();
  const [selectedVersion, setSelectedVersion] = useState(initialVersionId);
  const { variants, slides, total } = useMemo(() => {
    const { slots } = studioGallery(batch);
    const variants = slots.flatMap(({ result }, index) => (result ? [{ result, slot: index + 1 }] : []));
    const slides = variants.map(({ result, slot }) => ({
      src: assetContentUrl(batch.projectId, result.assetId),
      alt: translate(locale, "imageStudio.resultImage", { "0": slot }),
      width: result.width,
      height: result.height,
    }));
    return { variants, slides, total: slots.length };
  }, [batch, locale]);
  // Selection uses the immutable version, so a newly available earlier slot cannot swap the photo in view.
  const index = Math.max(
    0,
    variants.findIndex(({ result }) => result.artifactVersionId === selectedVersion),
  );
  const current = variants[index];
  const slide = slides[index];
  if (!current || !slide) return null;
  const positionLabel = `${String(current.slot).padStart(2, "0")} / ${String(total).padStart(2, "0")}`;
  return (
    <MediaPreviewDialog
      kind="image"
      title={slide.alt}
      sourceUrl={slide.src}
      width={slide.width}
      height={slide.height}
      contentType={current.result.contentType}
      imageGallery={{
        slides,
        index,
        positionLabel,
        onIndexChange: next => {
          const versionId = variants[next]?.result.artifactVersionId;
          if (versionId) setSelectedVersion(versionId);
        },
      }}
      onClose={() => onClose(current.result.artifactVersionId)}
    />
  );
}
