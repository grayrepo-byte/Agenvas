import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useEffectEvent, useRef, useState } from "react";
import { ApiError, getCurrentUser } from "../../shared/api/client";
import { cachePrivateReferenceImage, clearPrivateReferenceImage, listPrivateReferenceImages,
  type ReferenceEntry, type ReferencePrivateImage, type ReferenceVariant } from "../../shared/api/referenceCatalog";
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
const MAX_DOWNLOADS = 4;
let activeDownloads = 0;
const waitingDownloads: (() => void)[] = [];
/** Bound visible-card downloads across catalog instances, including an open preview. */
async function download<T>(operation: () => Promise<T>): Promise<T> {
  await new Promise<void>((resolve) => {
    const start = () => { activeDownloads++; resolve(); };
    if (activeDownloads < MAX_DOWNLOADS) start(); else waitingDownloads.push(start);
  });
  try { return await operation(); }
  finally { activeDownloads--; waitingDownloads.shift()?.(); }
}
/** Visible cards and the selected view share one owner/snapshot-scoped acquisition. */
export function usePrivateReferenceCache(entry: ReferenceEntry, variant: ReferenceVariant | undefined, sharedAllowed: boolean, onBusy?: (busy: boolean) => void, autoLoad = true) {
  const client = useQueryClient(); const [clearing, setClearing] = useState(false); const [clearError, setClearError] = useState<Error | null>(null);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const visible = !sharedAllowed && Boolean(variant?.privateCacheAllowed || variant?.privateCacheDisabledReason);
  const owner = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const key = ["reference-catalog-private-images", owner.data?.id, entry.sourceId, entry.snapshotId];
  const index = useQuery({ queryKey: key, queryFn: () => listPrivateReferenceImages(entry.sourceId, entry.snapshotId), enabled: autoLoad && visible && Boolean(owner.data?.id), retry: false });
  const scoped = index.data?.items.filter((item) => item.sourceId === entry.sourceId && item.snapshotId === entry.snapshotId) ?? [];
  const record = scoped.find((item) => item.entryId === entry.id && item.variantId === variant?.id);
  const image = record?.cached ? record : undefined;
  function accept(saved: ReferencePrivateImage) {
    client.setQueryData<{ items: ReferencePrivateImage[] }>(key, (current) => ({ items: [saved, ...(current?.items.filter((item) => item.id !== saved.id) ?? [])] }));
    void client.invalidateQueries({ queryKey: ["reference-catalog-cache-stats"] });
    void client.invalidateQueries({ queryKey: ["reference-catalog-private-images", owner.data?.id, "all"] });
  }
  const acquisitionKey = ["reference-catalog-private-acquisition", owner.data?.id, entry.sourceId, entry.snapshotId, entry.id, variant?.id];
  // Keep the accepted command key with the shared query so a lost response can be
  // retried from either the card or preview without creating another command.
  const existingCommandKey = client.getQueryCache().find({ queryKey: acquisitionKey })?.meta?.commandKey;
  const commandKey = typeof existingCommandKey === "string" ? existingCommandKey : crypto.randomUUID();
  const acquisition = useQuery({ queryKey: acquisitionKey, meta: { commandKey },
    queryFn: async ({ signal }) => {
      if (!variant?.privateCacheAllowed || !owner.data?.id) throw new Error(t("reference.private.failed"));
      const saved = await download(() => {
        signal.throwIfAborted();
        return cachePrivateReferenceImage({ sourceId: entry.sourceId, snapshotId: entry.snapshotId, entryId: entry.id, variantId: variant.id, commandKey });
      });
      if (!signal.aborted) accept(saved); return saved;
    },
    // Retained uncached records include explicitly cleared copies. Do not undo a
    // user's cleanup on the next render; those copies can be recached explicitly.
    enabled: autoLoad && visible && Boolean(variant?.privateCacheAllowed && owner.data?.id) && index.isSuccess && !record,
    retry: false, retryOnMount: false, refetchOnReconnect: false, refetchOnWindowFocus: false, staleTime: Infinity });
  const busy = clearing || acquisition.isFetching;
  const error = clearError ?? acquisition.error;
  const frozen = acquisition.isError && !(acquisition.error instanceof ApiError && acquisition.error.status < 500 && acquisition.error.status !== 408);
  const notifyBusy = useEffectEvent((value: boolean) => onBusy?.(value));
  useEffect(() => { if (!busy) return; notifyBusy(true); return () => notifyBusy(false); }, [busy]);
  async function cache() { if (variant?.privateCacheAllowed && owner.data?.id && index.isSuccess && !busy) { setClearError(null); await acquisition.refetch(); } }
  async function clear() {
    if (!image || busy) return;
    setClearing(true); setClearError(null);
    try { accept(await clearPrivateReferenceImage(image.id)); }
    catch (failure) { if (mounted.current) setClearError(failure instanceof Error ? failure : new Error(t("reference.private.failed"))); }
    finally { if (mounted.current) setClearing(false); }
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
