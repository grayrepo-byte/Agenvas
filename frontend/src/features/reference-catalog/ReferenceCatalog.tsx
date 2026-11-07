import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { getCurrentUser } from "../../shared/api/client";
import { importReference, listReferenceEntries, listReferenceSources, referenceExternalUrl, referencePreviewUrl,
  importPrivateReferenceImage, privateReferencePreviewUrl,
  type ReferencePrivateImportRequest, type ReferenceEntry, type ReferenceImport, type ReferenceImportRequest, type ReferenceKind, type ReferenceVariant } from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowClockwise, Database, GearSix, MagnifyingGlass } from "../../shared/ui/icons";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { ReferenceSourcesDialog } from "./ReferenceSourcesDialog";
import { referenceLabel, referenceFacetLabel } from "./referenceLabels";
import { PrivateCacheManager } from "./PrivateCacheManager";
import { PrivateReferenceCacheControls, usePrivateReferenceCache } from "./PrivateReferenceCache";
import "./ReferenceCatalog.css";

export type ReferenceCatalogProps = { projectId?: string; onApply?: (image: ReferenceImport) => Promise<void>; onBusy?: (busy: boolean) => void; disabledReason?: string; actionLabel?: string; children?: ReactNode; placement?: { x: number; y: number } };
const PAGE_SIZE = 50;
type CatalogImportIntent = { kind: "SHARED"; request: ReferenceImportRequest } | { kind: "PRIVATE"; id: string; request: ReferencePrivateImportRequest };
export function ReferenceCatalog({ projectId, onApply, onBusy, disabledReason, actionLabel, children, placement }: ReferenceCatalogProps) {
  const locale = useLocale(); const id = useId();
  const [kind, setKind] = useState<ReferenceKind>("POSE"); const [query, setQuery] = useState(""); const [category, setCategory] = useState(""); const [sourceId, setSourceId] = useState<string | null>(null);
  const [privateManager, setPrivateManager] = useState(false);
  const [viewSelections, setViewSelections] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<ReferenceEntry | null>(null); const [manage, setManage] = useState(false);
  const [tag, setTag] = useState(""); const [style, setStyle] = useState(""); const [collection, setCollection] = useState(""); const [extended, setExtended] = useState(""); const [angle, setAngle] = useState("");
  const sources = useQuery({ queryKey: ["reference-catalog-sources"], queryFn: listReferenceSources, retry: false });
  // Resolve the saved website before the first catalog read; a transient All
  // query would start legacy GitHub image acquisitions before the default loads.
  const activeSourceId = sourceId ?? sources.data?.items.find((source) => source.sourceType === "OMILIC" && source.enabled && source.currentSnapshotId)?.id ?? "";
  const ready = sourceId !== null || Boolean(sources.data);
  const filters = { kind, query, category, sourceId: activeSourceId, tag, style, collection, angle, ...(extended ? { extended: extended === "true" } : {}), limit: PAGE_SIZE };
  const list = useInfiniteQuery({ queryKey: ["reference-catalog", filters], queryFn: ({ pageParam }) => listReferenceEntries({ ...filters, offset: pageParam }), initialPageParam: 0,
    getNextPageParam: (page) => page.offset + page.items.length < page.total ? page.offset + page.items.length : undefined, retry: false, enabled: ready });
  const user = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const entries = list.data?.pages.flatMap((page) => page.items) ?? [];
  const available = list.data?.pages[0]?.filters;
  const categories = [...new Set([category, ...(available?.categories ?? entries.map((entry) => entry.category ?? ""))])].filter(Boolean);
  return <section className="reference-catalog" aria-label={t("reference.title")}>
    <div className="reference-toolbar"><ToggleGroup type="single" variant="outline" spacing={2} value={kind} onValueChange={(value) => { if (value) { setKind(value as ReferenceKind); setCategory(""); setTag(""); setStyle(""); setCollection(""); setExtended(""); setAngle(""); } }} aria-label={t("reference.title")}>
      <ToggleGroupItem value="POSE">{t("reference.poses")}</ToggleGroupItem><ToggleGroupItem value="COSTUME">{t("reference.costumes")}</ToggleGroupItem></ToggleGroup>
      <div className="reference-toolbar-actions">{user.data?.id ? <Button className="reference-private-cache-trigger" size="sm" type="button" variant="outline" onClick={() => setPrivateManager(true)}><Database data-icon="inline-start" aria-hidden />{t("reference.private.manage")}</Button> : null}
        <Button size="sm" type="button" variant="outline" disabled={!ready || list.isFetching} onClick={() => void list.refetch()}><ArrowClockwise data-icon="inline-start" aria-hidden />{t("common.refresh")}</Button></div></div>
    <FieldGroup className="reference-filters" role="search" aria-label={t("reference.search")}>
      <Field className="reference-search-field"><FieldLabel htmlFor={`${id}-search`}><MagnifyingGlass size={16} aria-hidden />{t("reference.search")}</FieldLabel>
        <Input id={`${id}-search`} type="search" maxLength={200} aria-label={t("reference.search")} placeholder={t("reference.searchPlaceholder")} value={query} onChange={(event) => setQuery(event.target.value)} /></Field>
      <Field className="reference-source-field"><FieldLabel htmlFor={`${id}-source`}>{t("reference.source")}</FieldLabel><div className="reference-source-choice">
        <Select id={`${id}-source`} aria-label={t("reference.source")} value={activeSourceId} onChange={(event) => setSourceId(event.target.value)}><option value="">{t("reference.all")}</option>{sources.data?.items.map((source) => <option key={source.id} value={source.id}>{source.name}</option>)}</Select>
        {user.data?.role === "ADMIN" ? <Button className="reference-source-settings-trigger" type="button" variant="secondary" onClick={() => setManage(true)}><GearSix data-icon="inline-start" aria-hidden />{t("reference.manageSources")}</Button> : null}</div></Field>
      <Field><FieldLabel htmlFor={`${id}-category`}>{t("reference.category")}</FieldLabel><Select id={`${id}-category`} aria-label={t("reference.category")} value={category} onChange={(event) => setCategory(event.target.value)}><option value="">{t("reference.all")}</option>{categories.map((item) => <option key={item} value={item}>{referenceLabel(item, entries.find((entry) => entry.category === item)?.localizedCategory, locale)}</option>)}</Select></Field>
      {([ ["reference.tag", tag, setTag, available?.tags ?? []], ["reference.style", style, setStyle, available?.styles ?? []], ["reference.collection", collection, setCollection, available?.collections ?? []], ["reference.angle", angle, setAngle, available?.angles ?? []] ] as const).map(([label, value, setter, choices]) => <Field key={label}><FieldLabel htmlFor={`${id}-${label}`}>{t(label)}</FieldLabel><Select id={`${id}-${label}`} aria-label={t(label)} value={value} onChange={(event) => setter(event.target.value)}><option value="">{t("reference.all")}</option>{[...new Set([value, ...choices])].filter(Boolean).map((choice) => <option key={choice} value={choice}>{referenceFacetLabel(choice)}</option>)}</Select></Field>)}
      <Field><FieldLabel htmlFor={`${id}-extended`}>{t("reference.classification")}</FieldLabel><Select id={`${id}-extended`} aria-label={t("reference.classification")} value={extended} onChange={(event) => setExtended(event.target.value)}><option value="">{t("reference.all")}</option><option value="true">{t("reference.extended")}</option><option value="false">{t("reference.standard")}</option></Select></Field>
    </FieldGroup>
    {children}
    {disabledReason ? <p className="reference-hint">{disabledReason}</p> : null}
    {list.error || sources.error ? <Notice tone="danger"><p>{(list.error ?? sources.error)?.message}</p><Button type="button" variant="outline" onClick={() => { if (sources.error) void sources.refetch(); else void list.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {list.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {list.isSuccess && !entries.length ? <p className="reference-empty">{t("reference.empty")}</p> : null}
    <div className="reference-grid">{entries.map((entry) => { const title = referenceLabel(entry.title, entry.localizedTitle, locale); return <Button key={`${entry.sourceId}:${entry.snapshotId}:${entry.id}`} className="reference-card" size="content" type="button" variant="outline" aria-label={t("reference.preview", { "0": title })} onClick={() => setSelected(entry)}>
      <ReferenceCardImage entry={entry} /><strong>{title}</strong><span>{[referenceLabel(entry.category ?? "", entry.localizedCategory, locale), `${entry.variants.length} ${t("reference.view")}`].filter(Boolean).join(" · ")}</span><span className="reference-tags">{entry.tags.map((tag) => referenceLabel(referenceFacetLabel(tag), entry.localizedTags?.[tag], locale)).join(" · ")}</span>
    </Button>; })}</div>
    {list.hasNextPage ? <Button type="button" variant="ghost" disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {selected ? <ReferencePreview key={`${selected.snapshotId}:${selected.id}`} entry={selected} projectId={projectId} onApply={onApply} onBusy={onBusy} placement={placement} initialAngle={angle} initialVariantId={viewSelections[`${selected.snapshotId}:${selected.id}`]} onVariantChange={(variantId) => setViewSelections((current) => ({ ...current, [`${selected.snapshotId}:${selected.id}`]: variantId }))} disabledReason={disabledReason} actionLabel={actionLabel} onClose={() => setSelected(null)} /> : null}
    {privateManager && user.data?.id ? <PrivateCacheManager key={user.data.id} ownerId={user.data.id} sources={sources.data?.items} onClose={() => setPrivateManager(false)} /> : null}
    {manage ? <ReferenceSourcesDialog onLoaded={(source) => setSourceId(source.id)} onClose={() => setManage(false)} /> : null}
  </section>;
}
function ReferenceCardImage({ entry }: { entry: ReferenceEntry }) {
  const element = useRef<HTMLSpanElement>(null);
  const [visible, setVisible] = useState(() => typeof IntersectionObserver === "undefined");
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    if (visible || !element.current) return;
    const observer = new IntersectionObserver((entries) => { if (entries.some((entry) => entry.isIntersecting)) { setVisible(true); observer.disconnect(); } }, { rootMargin: "200px" });
    observer.observe(element.current); return () => observer.disconnect();
  }, [visible]);
  const variant = entry.variants[0];
  const sharedPreview = entry.importAllowed ? entry.variants.map(referencePreviewUrl).find(Boolean) : undefined;
  const cache = usePrivateReferenceCache(entry, variant, Boolean(sharedPreview), undefined, visible);
  const preview = sharedPreview ?? (cache.allowed && cache.image ? privateReferencePreviewUrl(cache.image) : undefined);
  return <span ref={element} className="reference-card-image">{preview && !failed ? <img src={preview} alt="" loading="lazy" onError={() => setFailed(true)} />
    : <span>{failed ? t("reference.previewFailed") : cache.error || cache.index.error || cache.ownerError ? t("reference.private.failed") : cache.busy || (visible && cache.visible && cache.index.isPending) ? t("common.loading") : t("reference.metadataOnly")}</span>}</span>;
}
function ReferencePreview({ entry, projectId, onApply, onBusy, disabledReason, actionLabel, placement, initialVariantId, initialAngle, onVariantChange, onClose }: ReferenceCatalogProps & { entry: ReferenceEntry; initialVariantId?: string; initialAngle?: string; onVariantChange: (id: string) => void; onClose: () => void }) {
  const locale = useLocale(); const [variantId, setVariantId] = useState(initialVariantId ?? entry.variants.find((variant) => variant.angle === initialAngle)?.id ?? entry.variants[0]?.id ?? ""); const [busy, setBusy] = useState(false); const [error, setError] = useState<Error | null>(null);
  const [previewFailed, setPreviewFailed] = useState(false); const [previewAttempt, setPreviewAttempt] = useState(0);
  const mounted = useRef(true); useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const intent = useRef<CatalogImportIntent | null>(null); const imported = useRef<ReferenceImport | null>(null);
  const variant: ReferenceVariant | undefined = entry.variants.find((candidate) => candidate.id === variantId);
  const title = referenceLabel(entry.title, entry.localizedTitle, locale);
  const sharedAllowed = Boolean(entry.importAllowed && variant?.importAllowed && variant.sha256);
  const privateCache = usePrivateReferenceCache(entry, variant, sharedAllowed, onBusy);
  const privateImage = privateCache.allowed ? privateCache.image : undefined;
  const allowed = sharedAllowed || Boolean(privateImage);
  const working = busy || privateCache.busy;
  const preview = variant && sharedAllowed ? referencePreviewUrl(variant) : privateImage ? privateReferencePreviewUrl(privateImage) : undefined;
  const external = referenceExternalUrl(variant?.externalUrl ?? entry.externalUrl);
  async function apply() {
    if (!projectId || !onApply || !variant || !allowed || disabledReason || working) return;
    if (!intent.current) {
      const command = { commandKey: crypto.randomUUID(), ...(placement ? { placement } : {}) };
      if (privateImage && !sharedAllowed) intent.current = { kind: "PRIVATE", id: privateImage.id, request: command };
      else if (variant.sha256) intent.current = { kind: "SHARED", request: { ...command, sourceId: entry.sourceId, snapshotId: entry.snapshotId, entryId: entry.id, variantId: variant.id, sha256: variant.sha256 } };
      else return;
    }
    setBusy(true); onBusy?.(true); setError(null);
    try { imported.current ??= await (intent.current.kind === "PRIVATE" ? importPrivateReferenceImage(projectId, intent.current.id, intent.current.request) : importReference(projectId, intent.current.request)); if (mounted.current) { await onApply(imported.current); onClose(); } }
    catch (failure) { setError(failure instanceof Error ? failure : new Error(t("reference.importFailed"))); }
    finally { setBusy(false); onBusy?.(false); }
  }
  return <Dialog className="reference-preview-dialog" title={title} description={[referenceLabel(entry.category ?? "", entry.localizedCategory, locale), entry.tags.map((tag) => referenceLabel(referenceFacetLabel(tag), entry.localizedTags?.[tag], locale)).join(" · ")].filter(Boolean).join(" · ")} busy={working} onClose={onClose} onSubmit={(event) => { event.preventDefault(); void apply(); }}
    footer={<><Button type="button" variant="outline" disabled={working} onClick={onClose}>{t("common.close")}</Button>{onApply ? <Button type="submit" disabled={!allowed || Boolean(disabledReason) || working || !projectId}>{busy ? t("reference.importing") : error ? t("common.retry") : actionLabel ?? t("reference.append")}</Button> : null}</>}>
    <div className="reference-preview-image">{previewFailed ? <Notice tone="danger"><p>{t("reference.previewFailed")}</p><Button type="button" variant="outline" onClick={() => { setPreviewFailed(false); setPreviewAttempt((value) => value + 1); }}>{t("reference.retryPreview")}</Button></Notice> : preview ? <img key={`${variantId}:${previewAttempt}`} src={preview} alt={`${title} · ${referenceLabel(variant?.label ?? "", variant?.localizedLabel, locale)}`} onError={() => setPreviewFailed(true)} /> : privateCache.allowed && (privateCache.busy || (privateCache.index.isPending && !privateCache.ownerError)) ? <p role="status">{t("reference.private.caching")}</p> : <p>{t(allowed ? "reference.noPreview" : (variant?.disabledReason ?? entry.disabledReason) === "REFERENCE_CATALOG_SOURCE_DISABLED" ? "reference.sourceUnavailable" : "reference.rightsRequired")}</p>}</div>
    <ToggleGroup className="reference-views" type="single" variant="outline" spacing={2} aria-label={t("reference.view")} value={variantId} disabled={working || privateCache.frozen || Boolean(intent.current)} onValueChange={(value) => { if (value) { setVariantId(value); onVariantChange(value); setPreviewFailed(false); } }}>{entry.variants.map((candidate) => <ToggleGroupItem key={candidate.id} value={candidate.id}>{referenceLabel(candidate.label, candidate.localizedLabel, locale)}</ToggleGroupItem>)}</ToggleGroup>
    <PrivateReferenceCacheControls cache={privateCache} disabled={working || Boolean(intent.current)} />
    {sharedAllowed && variant?.cached ? <p className="reference-hint">{t("reference.cache.cached")}</p> : null}
    {external ? <Button asChild variant="outline"><a href={external} target="_blank" rel="noopener noreferrer">{t("reference.external")}</a></Button> : null}
    {entry.code ? <p>{entry.code}</p> : null}
    {referenceLabel("", entry.series, locale) ? <p>{referenceLabel("", entry.series, locale)}</p> : null}
    {referenceLabel("", entry.localizedKeywords, locale) ? <p className="reference-hint">{referenceLabel("", entry.localizedKeywords, locale)}</p> : null}
    {Object.entries(entry.facets).map(([name, value]) => <p className="reference-facet" key={name}>{name === "style" ? t("reference.style") : name}: {referenceFacetLabel(value)}</p>)}
    {onApply ? <p>{disabledReason ?? t("reference.importHint")}</p> : null}
    {error ? <Notice tone="danger"><p>{t("reference.importFailed")}</p><p>{error.message}</p></Notice> : null}
  </Dialog>;
}
