import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { getCurrentUser } from "../../shared/api/client";
import { importReference, listReferenceEntries, listReferenceSources, referenceExternalUrl, referencePreviewUrl,
  type ReferenceEntry, type ReferenceImport, type ReferenceImportRequest, type ReferenceKind, type ReferenceVariant } from "../../shared/api/referenceCatalog";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { ToggleGroup, ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import { ReferenceSourcesDialog } from "./ReferenceSourcesDialog";
import { referenceLabel, referenceFacetLabel } from "./referenceLabels";
import "./ReferenceCatalog.css";

export type ReferenceCatalogProps = { projectId?: string; onApply?: (image: ReferenceImport) => Promise<void>; onBusy?: (busy: boolean) => void; disabledReason?: string; actionLabel?: string; children?: ReactNode; placement?: { x: number; y: number } };
const PAGE_SIZE = 50;
export function ReferenceCatalog({ projectId, onApply, onBusy, disabledReason, actionLabel, children, placement }: ReferenceCatalogProps) {
  const locale = useLocale(); const id = useId();
  const [kind, setKind] = useState<ReferenceKind>("POSE"); const [query, setQuery] = useState(""); const [category, setCategory] = useState(""); const [sourceId, setSourceId] = useState("");
  const [viewSelections, setViewSelections] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<ReferenceEntry | null>(null); const [manage, setManage] = useState(false);
  const [tag, setTag] = useState(""); const [style, setStyle] = useState(""); const [collection, setCollection] = useState(""); const [extended, setExtended] = useState(""); const [angle, setAngle] = useState("");
  const filters = { kind, query, category, sourceId, tag, style, collection, angle, ...(extended ? { extended: extended === "true" } : {}), limit: PAGE_SIZE };
  const list = useInfiniteQuery({ queryKey: ["reference-catalog", filters], queryFn: ({ pageParam }) => listReferenceEntries({ ...filters, offset: pageParam }), initialPageParam: 0,
    getNextPageParam: (page) => page.offset + page.items.length < page.total ? page.offset + page.items.length : undefined, retry: false });
  const sources = useQuery({ queryKey: ["reference-catalog-sources"], queryFn: listReferenceSources, retry: false });
  const user = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const entries = list.data?.pages.flatMap((page) => page.items) ?? [];
  const available = list.data?.pages[0]?.filters;
  const categories = [...new Set([category, ...(available?.categories ?? entries.map((entry) => entry.category ?? ""))])].filter(Boolean);
  return <section className="reference-catalog" aria-label={t("reference.title")}>
    <div className="reference-toolbar"><ToggleGroup type="single" variant="outline" spacing={2} value={kind} onValueChange={(value) => { if (value) { setKind(value as ReferenceKind); setCategory(""); setTag(""); setStyle(""); setCollection(""); setExtended(""); setAngle(""); } }} aria-label={t("reference.title")}>
      <ToggleGroupItem value="POSE">{t("reference.poses")}</ToggleGroupItem><ToggleGroupItem value="COSTUME">{t("reference.costumes")}</ToggleGroupItem></ToggleGroup>
      {user.data?.role === "ADMIN" ? <Button type="button" variant="outline" onClick={() => setManage(true)}>{t("reference.manageSources")}</Button> : null}</div>
    <div className="reference-filters"><Input id={`${id}-search`} type="search" maxLength={200} aria-label={t("reference.search")} placeholder={t("reference.search")} value={query} onChange={(event) => setQuery(event.target.value)} />
      <Select aria-label={t("reference.source")} value={sourceId} onChange={(event) => setSourceId(event.target.value)}><option value="">{t("reference.all")}</option>{sources.data?.items.map((source) => <option key={source.id} value={source.id}>{source.name}</option>)}</Select>
      <Select aria-label={t("reference.category")} value={category} onChange={(event) => setCategory(event.target.value)}><option value="">{t("reference.all")}</option>{categories.map((item) => <option key={item} value={item}>{referenceLabel(item, entries.find((entry) => entry.category === item)?.localizedCategory, locale)}</option>)}</Select>
      {([ ["reference.tag", tag, setTag, available?.tags ?? []], ["reference.style", style, setStyle, available?.styles ?? []], ["reference.collection", collection, setCollection, available?.collections ?? []], ["reference.angle", angle, setAngle, available?.angles ?? []] ] as const).map(([label, value, setter, choices]) => <Select key={label} aria-label={t(label)} value={value} onChange={(event) => setter(event.target.value)}><option value="">{t("reference.all")}</option>{[...new Set([value, ...choices])].filter(Boolean).map((choice) => <option key={choice} value={choice}>{referenceFacetLabel(choice)}</option>)}</Select>)}
      <Select aria-label={t("reference.extended")} value={extended} onChange={(event) => setExtended(event.target.value)}><option value="">{t("reference.all")}</option><option value="true">{t("reference.extended")}</option><option value="false">{t("reference.standard")}</option></Select>
      <Button type="button" variant="ghost" disabled={list.isFetching} onClick={() => void list.refetch()}>{t("common.refresh")}</Button></div>
    {children}
    {disabledReason ? <p className="reference-hint">{disabledReason}</p> : null}
    {list.error || sources.error ? <Notice tone="danger"><p>{(list.error ?? sources.error)?.message}</p><Button type="button" variant="outline" onClick={() => { void list.refetch(); if (sources.error) void sources.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {list.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {list.isSuccess && !entries.length ? <p className="reference-empty">{t("reference.empty")}</p> : null}
    <div className="reference-grid">{entries.map((entry) => { const preview = entry.variants.map(referencePreviewUrl).find(Boolean); const title = referenceLabel(entry.title, entry.localizedTitle, locale); return <Button key={`${entry.sourceId}:${entry.snapshotId}:${entry.id}`} className="reference-card" size="content" type="button" variant="outline" aria-label={t("reference.preview", { "0": title })} onClick={() => setSelected(entry)}>
      <span className="reference-card-image">{preview && entry.importAllowed ? <img src={preview} alt="" loading="lazy" /> : <span>{t("reference.metadataOnly")}</span>}</span><strong>{title}</strong><span>{[referenceLabel(entry.category ?? "", entry.localizedCategory, locale), `${entry.variants.length} ${t("reference.view")}`].filter(Boolean).join(" · ")}</span><span className="reference-tags">{entry.tags.map((tag) => referenceLabel(referenceFacetLabel(tag), entry.localizedTags?.[tag], locale)).join(" · ")}</span>
    </Button>; })}</div>
    {list.hasNextPage ? <Button type="button" variant="outline" disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {selected ? <ReferencePreview key={`${selected.snapshotId}:${selected.id}`} entry={selected} projectId={projectId} onApply={onApply} onBusy={onBusy} placement={placement} initialAngle={angle} initialVariantId={viewSelections[`${selected.snapshotId}:${selected.id}`]} onVariantChange={(variantId) => setViewSelections((current) => ({ ...current, [`${selected.snapshotId}:${selected.id}`]: variantId }))} disabledReason={disabledReason} actionLabel={actionLabel} onClose={() => setSelected(null)} /> : null}
    {manage ? <ReferenceSourcesDialog onClose={() => setManage(false)} /> : null}
  </section>;
}
function ReferencePreview({ entry, projectId, onApply, onBusy, disabledReason, actionLabel, placement, initialVariantId, initialAngle, onVariantChange, onClose }: ReferenceCatalogProps & { entry: ReferenceEntry; initialVariantId?: string; initialAngle?: string; onVariantChange: (id: string) => void; onClose: () => void }) {
  const locale = useLocale(); const [variantId, setVariantId] = useState(initialVariantId ?? entry.variants.find((variant) => variant.angle === initialAngle)?.id ?? entry.variants[0]?.id ?? ""); const [busy, setBusy] = useState(false); const [error, setError] = useState<Error | null>(null);
  const [previewFailed, setPreviewFailed] = useState(false); const [previewAttempt, setPreviewAttempt] = useState(0);
  const mounted = useRef(true); useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const intent = useRef<ReferenceImportRequest | null>(null); const imported = useRef<ReferenceImport | null>(null);
  const variant: ReferenceVariant | undefined = entry.variants.find((candidate) => candidate.id === variantId);
  const title = referenceLabel(entry.title, entry.localizedTitle, locale);
  const allowed = entry.importAllowed && variant?.importAllowed && Boolean(variant.sha256);
  const preview = variant && allowed ? referencePreviewUrl(variant) : undefined;
  const external = referenceExternalUrl(variant?.externalUrl ?? entry.externalUrl);
  async function apply() {
    if (!projectId || !onApply || !variant?.sha256 || !allowed || disabledReason || busy) return;
    intent.current ??= { sourceId: entry.sourceId, snapshotId: entry.snapshotId, entryId: entry.id, variantId: variant.id, sha256: variant.sha256, commandKey: crypto.randomUUID(), ...(placement ? { placement } : {}) };
    setBusy(true); onBusy?.(true); setError(null);
    try { imported.current ??= await importReference(projectId, intent.current); if (mounted.current) { await onApply(imported.current); onClose(); } }
    catch (failure) { setError(failure instanceof Error ? failure : new Error(t("reference.importFailed"))); }
    finally { setBusy(false); onBusy?.(false); }
  }
  return <Dialog className="reference-preview-dialog" title={title} description={[referenceLabel(entry.category ?? "", entry.localizedCategory, locale), entry.tags.map((tag) => referenceLabel(referenceFacetLabel(tag), entry.localizedTags?.[tag], locale)).join(" · ")].filter(Boolean).join(" · ")} busy={busy} onClose={onClose} onSubmit={(event) => { event.preventDefault(); void apply(); }}
    footer={<><Button type="button" variant="outline" disabled={busy} onClick={onClose}>{t("common.close")}</Button>{onApply ? <Button type="submit" disabled={!allowed || Boolean(disabledReason) || busy || !projectId}>{busy ? t("reference.importing") : error ? t("common.retry") : actionLabel ?? t("reference.append")}</Button> : null}</>}>
    <div className="reference-preview-image">{previewFailed ? <Notice tone="danger"><p>{t("reference.previewFailed")}</p><Button type="button" variant="outline" onClick={() => { setPreviewFailed(false); setPreviewAttempt((value) => value + 1); }}>{t("reference.retryPreview")}</Button></Notice> : preview ? <img key={`${variantId}:${previewAttempt}`} src={preview} alt={`${title} · ${referenceLabel(variant?.label ?? "", variant?.localizedLabel, locale)}`} onError={() => setPreviewFailed(true)} /> : <p>{t(allowed ? "reference.noPreview" : (variant?.disabledReason ?? entry.disabledReason) === "REFERENCE_CATALOG_SOURCE_DISABLED" ? "reference.sourceUnavailable" : "reference.rightsRequired")}</p>}</div>
    <ToggleGroup className="reference-views" type="single" variant="outline" spacing={2} aria-label={t("reference.view")} value={variantId} disabled={busy || Boolean(intent.current)} onValueChange={(value) => { if (value) { setVariantId(value); onVariantChange(value); setPreviewFailed(false); } }}>{entry.variants.map((candidate) => <ToggleGroupItem key={candidate.id} value={candidate.id}>{referenceLabel(candidate.label, candidate.localizedLabel, locale)}</ToggleGroupItem>)}</ToggleGroup>
    {external ? <Button asChild variant="outline"><a href={external} target="_blank" rel="noopener noreferrer">{t("reference.external")}</a></Button> : null}
    {entry.code ? <p>{entry.code}</p> : null}
    {referenceLabel("", entry.series, locale) ? <p>{referenceLabel("", entry.series, locale)}</p> : null}
    {referenceLabel("", entry.localizedKeywords, locale) ? <p className="reference-hint">{referenceLabel("", entry.localizedKeywords, locale)}</p> : null}
    {Object.entries(entry.facets).map(([name, value]) => <p className="reference-facet" key={name}>{name === "style" ? t("reference.style") : name}: {referenceFacetLabel(value)}</p>)}
    {onApply ? <p>{disabledReason ?? t("reference.importHint")}</p> : null}
    {error ? <Notice tone="danger"><p>{t("reference.importFailed")}</p><p>{error.message}</p></Notice> : null}
  </Dialog>;
}
