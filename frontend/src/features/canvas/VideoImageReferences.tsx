import { useQueries } from "@tanstack/react-query";
import { Image as ImageIcon } from "@phosphor-icons/react";
import { useState } from "react";
import { assetThumbnailUrl, listArtifactVersions, type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { readContentText } from "./artifactContent";
import { ImagePreviewDialog } from "./ImagePreviewDialog";
import { assetContentUrl } from "../../shared/api/client";
import "./VideoImageReferences.css";

type ImageReference = { artifactId: string; versionId: string; role: string; order: number };
const IMAGE_ROLES = new Set(["REFERENCE", "START_FRAME", "END_FRAME"]);
function object(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

/** A displayed result uses its frozen inputs, even when the working draft now references different images. */
export function videoImageReferences(item: CanvasItem, draft: MediaDraft | undefined, showDraft: boolean): ImageReference[] {
  if (showDraft) return (draft?.mediaInputs ?? []).filter((input) => IMAGE_ROLES.has(input.role)).sort((a, b) => a.order - b.order);
  const images: unknown = object(item.selectedVersion?.frozenInput).images;
  if (!Array.isArray(images)) return [];
  return images.flatMap((image: unknown) => {
    const ref = object(image);
    return typeof ref.artifactId === "string" && typeof ref.versionId === "string" && typeof ref.role === "string" && IMAGE_ROLES.has(ref.role)
      ? [{ artifactId: ref.artifactId, versionId: ref.versionId, role: ref.role, order: typeof ref.order === "number" ? ref.order : 0 }] : [];
  }).sort((a, b) => a.order - b.order);
}

export function VideoImageReferences({ projectId, references }: { projectId: string; references: ImageReference[] }) {
  useLocale();
  const histories = useQueries({ queries: references.map((ref) => ({
    queryKey: ["artifact-versions", projectId, ref.artifactId], queryFn: () => listArtifactVersions(projectId, ref.artifactId), retry: false,
  })) });
  const [preview, setPreview] = useState<{ title: string; assetId: string } | null>(null);
  if (!references.length) return null;
  return <section className="video-image-references nodrag nopan nowheel" aria-label={t("video.references.title")}>
    <span>{t("video.references.title")}</span>
    <div className="video-image-reference-list">
      {references.map((ref, index) => {
        const history = histories[index];
        const version = history?.data?.items.find((value) => value.id === ref.versionId);
        const assetId = readContentText(version?.content, "assetId");
        const role = ref.role === "START_FRAME" ? t("video.references.startFrame") : ref.role === "END_FRAME" ? t("video.references.endFrame") : t("video.references.reference", { "0": index + 1 });
        const title = `${role}${version ? ` · v${version.versionNo}` : ""}`;
        const status = history?.isPending ? t("video.references.loading") : history?.isError ? t("video.references.failed") : !assetId ? t("video.references.unavailable") : title;
        return <div className="video-image-reference" key={`${ref.role}:${ref.versionId}`}>
          <Button type="button" variant="ghost" disabled={!assetId} title={status} aria-label={title} onClick={() => setPreview({ title, assetId })}>
            {assetId ? <img src={assetThumbnailUrl(projectId, assetId)} alt={title} loading="lazy" draggable={false} /> : <ImageIcon aria-hidden />}
            <span>{title}</span>
          </Button>
          {history?.isError ? <Button type="button" variant="ghost" size="xs" disabled={history.isFetching} title={t("video.references.failed")}
            onClick={() => void history.refetch()}>{t("common.retry")}</Button> : null}
        </div>;
      })}
    </div>
    {preview ? <ImagePreviewDialog title={preview.title} sourceUrl={assetContentUrl(projectId, preview.assetId)} onClose={() => setPreview(null)} /> : null}
  </section>;
}
