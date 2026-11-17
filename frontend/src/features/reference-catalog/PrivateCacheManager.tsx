import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  clearPrivateReferenceImage,
  listPrivateReferenceImages,
  type ReferencePrivateImage,
  type ReferenceSource,
} from "../../shared/api/referenceCatalog";
import { formatNumber, t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";

/** Owner-wide cleanup includes retained images from snapshots no longer in the current catalog. */
export function PrivateCacheManager({
  ownerId,
  sources = [],
  onClose,
}: {
  ownerId: string;
  sources?: ReferenceSource[];
  onClose: () => void;
}) {
  useLocale();
  const client = useQueryClient();
  const [selected, setSelected] = useState<ReferencePrivateImage | null>(null);
  const index = useQuery({
    queryKey: ["reference-catalog-private-images", ownerId, "all"],
    queryFn: () => listPrivateReferenceImages(),
    retry: false,
  });
  const clear = useMutation({
    mutationFn: (image: ReferencePrivateImage) => clearPrivateReferenceImage(image.id),
    onSuccess: saved => {
      client.setQueriesData<{ items: ReferencePrivateImage[] }>(
        { queryKey: ["reference-catalog-private-images", ownerId] },
        current => (current ? { items: current.items.map(image => (image.id === saved.id ? saved : image)) } : current),
      );
      void client.invalidateQueries({ queryKey: ["reference-catalog-cache-stats"] });
      setSelected(null);
    },
  });
  const images = index.data?.items.filter(image => image.cached) ?? [];
  return (
    <Dialog
      className="reference-private-manager-dialog"
      title={t("reference.private.manage")}
      description={t("reference.private.managerHint")}
      busy={clear.isPending}
      onClose={onClose}
      onSubmit={event => event.preventDefault()}
      footer={
        <Button type="button" variant="outline" disabled={clear.isPending} onClick={onClose}>
          {t("common.close")}
        </Button>
      }
    >
      {index.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {index.error ? (
        <Notice tone="danger">
          <p>{index.error.message}</p>
          <Button type="button" variant="outline" onClick={() => void index.refetch()}>
            {t("reference.private.retryStatus")}
          </Button>
        </Notice>
      ) : null}
      {index.data ? (
        <p>
          {t("reference.private.totalUsage", {
            "0": formatNumber(images.length),
            "1": formatNumber(images.reduce((sum, image) => sum + image.byteSize, 0)),
          })}
        </p>
      ) : null}
      {index.isSuccess && !images.length ? <p role="status">{t("reference.private.empty")}</p> : null}
      <div className="reference-private-records">
        {images.map(image => (
          <section className="reference-private-record" key={image.id}>
            <dl>
              <div>
                <dt>{t("reference.source")}</dt>
                <dd>{sources.find(source => source.id === image.sourceId)?.name ?? image.sourceId}</dd>
              </div>
              <div>
                <dt>{t("reference.private.entry")}</dt>
                <dd>{image.entryId}</dd>
              </div>
              <div>
                <dt>{t("reference.view")}</dt>
                <dd>{image.variantId}</dd>
              </div>
              <div>
                <dt>{t("reference.private.snapshot")}</dt>
                <dd>{image.snapshotId}</dd>
              </div>
              <div>
                <dt>{t("reference.private.imageSize")}</dt>
                <dd>{formatNumber(image.byteSize)}</dd>
              </div>
            </dl>
            <Button
              size="content"
              type="button"
              variant="outline"
              disabled={clear.isPending}
              aria-label={t("reference.private.clearNamed", { "0": image.entryId, "1": image.variantId })}
              onClick={() => {
                clear.reset();
                setSelected(image);
              }}
            >
              {t("reference.private.clear")}
            </Button>
          </section>
        ))}
      </div>
      {selected ? (
        <Notice tone="warning">
          <p>{t("reference.private.clearConfirm")}</p>
          <p>
            {selected.entryId} · {selected.variantId} · {selected.snapshotId}
          </p>
          <div className="ui-form-actions">
            <Button
              size="content"
              type="button"
              variant="destructive"
              disabled={clear.isPending}
              onClick={() => clear.mutate(selected)}
            >
              {t("reference.private.confirmClear")}
            </Button>
            <Button type="button" variant="outline" disabled={clear.isPending} onClick={() => setSelected(null)}>
              {t("common.cancel")}
            </Button>
          </div>
        </Notice>
      ) : null}
      {clear.error ? (
        <Notice tone="danger">
          <p>{clear.error.message}</p>
        </Notice>
      ) : null}
    </Dialog>
  );
}
