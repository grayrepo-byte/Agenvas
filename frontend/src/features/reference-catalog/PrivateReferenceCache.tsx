import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ApiError, getCurrentUser } from "../../shared/api/client";
import { cachePrivateReferenceImage, clearPrivateReferenceImage, listPrivateReferenceImages,
  type ReferenceEntry, type ReferencePrivateCacheRequest, type ReferencePrivateImage, type ReferenceVariant } from "../../shared/api/referenceCatalog";
import { formatNumber, t, useLocale, type MessageKey } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { referenceFailureMessage } from "./referenceFailure";

const REASONS: Record<string, MessageKey> = {
  REFERENCE_CATALOG_SOURCE_DISABLED: "reference.sourceUnavailable",
  PRIVATE_IMAGE_SOURCE_UNSUPPORTED: "reference.private.unsupportedSource",
  PRIVATE_IMAGE_CUSTOM_USE_SCOPE_UNVERIFIED: "reference.private.customUnverified",
  PRIVATE_IMAGE_ANIME_USE_SCOPE_UNVERIFIED: "reference.private.animeUnverified",
  PRIVATE_IMAGE_ARCHIVE_PATH_UNVERIFIED: "reference.private.pathUnverified",
};
/** Reading the owner index is metadata-only. Only an explicit cache action downloads one image. */
export function usePrivateReferenceCache(entry: ReferenceEntry, variant: ReferenceVariant | undefined, sharedAllowed: boolean, onBusy?: (busy: boolean) => void) {
  const client = useQueryClient(); const [busy, setBusy] = useState(false); const [error, setError] = useState<Error | null>(null); const [frozen, setFrozen] = useState(false);
  const intent = useRef<ReferencePrivateCacheRequest | null>(null); const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const visible = !sharedAllowed && Boolean(variant?.privateCacheAllowed || variant?.privateCacheDisabledReason);
  const owner = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const key = ["reference-catalog-private-images", owner.data?.id, entry.sourceId, entry.snapshotId];
  const index = useQuery({ queryKey: key, queryFn: () => listPrivateReferenceImages(entry.sourceId, entry.snapshotId), enabled: visible && Boolean(owner.data?.id), retry: false });
  const scoped = index.data?.items.filter((item) => item.sourceId === entry.sourceId && item.snapshotId === entry.snapshotId) ?? [];
  const image = scoped.find((item) => item.entryId === entry.id && item.variantId === variant?.id && item.cached);
  function accept(saved: ReferencePrivateImage) {
    client.setQueryData<{ items: ReferencePrivateImage[] }>(key, (current) => ({ items: [saved, ...(current?.items.filter((item) => item.id !== saved.id) ?? [])] }));
    void client.invalidateQueries({ queryKey: ["reference-catalog-cache-stats"] });
    void client.invalidateQueries({ queryKey: ["reference-catalog-private-images", owner.data?.id, "all"] });
  }
  async function cache() {
    if (!variant?.privateCacheAllowed || busy) return;
    intent.current ??= { sourceId: entry.sourceId, snapshotId: entry.snapshotId, entryId: entry.id, variantId: variant.id, commandKey: crypto.randomUUID() };
    setBusy(true); setFrozen(true); setError(null); onBusy?.(true);
    try { accept(await cachePrivateReferenceImage(intent.current)); intent.current = null; if (mounted.current) setFrozen(false); }
    catch (failure) {
      if (failure instanceof ApiError && failure.status < 500 && failure.status !== 408) { intent.current = null; if (mounted.current) setFrozen(false); }
      if (mounted.current) setError(failure instanceof Error ? failure : new Error(t("reference.private.failed")));
    } finally { if (mounted.current) setBusy(false); onBusy?.(false); }
  }
  async function clear() {
    if (!image || busy) return;
    setBusy(true); setError(null); onBusy?.(true);
    try { accept(await clearPrivateReferenceImage(image.id)); }
    catch (failure) { if (mounted.current) setError(failure instanceof Error ? failure : new Error(t("reference.private.failed"))); }
    finally { if (mounted.current) setBusy(false); onBusy?.(false); }
  }
  const cached = scoped.filter((item) => item.cached);
  return { visible, image, busy, frozen, error, index, ownerError: owner.error, retryStatus: () => { if (owner.error) void owner.refetch(); else void index.refetch(); }, cache, clear,
    count: cached.length, bytes: cached.reduce((sum, item) => sum + item.byteSize, 0),
    allowed: variant?.privateCacheAllowed === true,
    disabledReason: variant?.privateCacheDisabledReason ? t(REASONS[variant.privateCacheDisabledReason] ?? "reference.private.scopeUnverified") : undefined };
}
export function PrivateReferenceCacheControls({ cache, disabled }: { cache: ReturnType<typeof usePrivateReferenceCache>; disabled: boolean }) {
  useLocale(); const [confirm, setConfirm] = useState(false);
  if (!cache.visible) return null;
  return <section className="reference-private-cache">
    <h3>{t("reference.private.title")}</h3><p className="reference-hint">{t("reference.private.scopeHint")}</p>
    {cache.index.isPending && !cache.ownerError ? <p role="status">{t("common.loading")}</p> : null}
    {cache.index.data ? <p>{t("reference.private.usage", { "0": formatNumber(cache.count), "1": formatNumber(cache.bytes) })}</p> : null}
    {cache.disabledReason ? <p>{cache.disabledReason}</p> : null}
    {cache.index.error || cache.ownerError ? <Notice tone="danger"><p>{(cache.index.error ?? cache.ownerError)?.message}</p><Button size="content" type="button" variant="outline" disabled={disabled} onClick={cache.retryStatus}>{t("reference.private.retryStatus")}</Button></Notice> : null}
    {cache.error ? <Notice tone="danger"><p>{referenceFailureMessage(cache.error)}</p></Notice> : null}
    {cache.image ? <><p>{t("reference.private.cached")}</p><Button size="content" type="button" variant="outline" disabled={disabled} onClick={() => setConfirm(true)}>{t("reference.private.clear")}</Button></>
      : cache.allowed ? <Button size="content" type="button" variant="outline" disabled={disabled || !cache.index.isSuccess} onClick={() => void cache.cache()}>{cache.busy ? t("reference.private.caching") : cache.error ? t("reference.private.retry") : t("reference.private.cache")}</Button> : null}
    {confirm && cache.image ? <Notice tone="warning"><p>{t("reference.private.clearConfirm")}</p><div className="ui-form-actions"><Button size="content" type="button" variant="destructive" disabled={disabled} onClick={() => { void cache.clear(); setConfirm(false); }}>{t("reference.private.confirmClear")}</Button><Button size="content" type="button" variant="outline" disabled={disabled} onClick={() => setConfirm(false)}>{t("common.cancel")}</Button></div></Notice> : null}
  </section>;
}
