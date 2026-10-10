import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { createThirdPartyPromptSource, importThirdPartyPrompt, listThirdPartyPrompts, listThirdPartyPromptSources,
  setThirdPartyPromptSource, syncThirdPartyPromptSource, type MediaTemplateKind, type ThirdPartyPromptEntry,
  type ThirdPartyPromptSource } from "../../shared/api/client";
import { formatDate, t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { workflowFieldLabel } from "../canvas/workflowFieldPresentation";
import type { TemplatePickerContext } from "./MediaTemplatePicker";
import { templateImageSlots } from "./templateApplication";
import { thirdPartyApplicationError, thirdPartyReferences, thirdPartyVideoMode } from "./thirdPartyApplication";

export function ThirdPartyPromptCatalog({ kind, context, system = false }: {
  kind: MediaTemplateKind; context?: TemplatePickerContext; system?: boolean;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const [sourceId, setSourceId] = useState("");
  const [search, setSearch] = useState("");
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<ThirdPartyPromptEntry | null>(null);
  const [imageSlots, setImageSlots] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const keys = useRef(new Map<string, string>());
  const sources = useQuery({ queryKey: ["third-party-sources"], queryFn: listThirdPartyPromptSources });
  const query = useQuery({ queryKey: ["third-party-prompts", kind, sourceId, search.trim(), offset],
    queryFn: () => listThirdPartyPrompts(kind, sourceId, search.trim(), offset) });
  const data = selected?.image ?? selected?.video;
  const videoModeNames = { text_to_video: t("thirdParty.textToVideo"), image_to_video: t("thirdParty.imageToVideo"),
    video_reference: t("thirdParty.videoReference"), omni_reference: t("common.mixedReference"), text_to_image_to_video: t("thirdParty.textToImageToVideo") };
  const refs = selected ? thirdPartyReferences(selected) : [];
  const images = refs.filter((ref) => ref.kind === "IMAGE");
  const slots = context ? templateImageSlots(context.capability, { ...context.fields, prompt: data?.prompt ?? context.fields.prompt }) : [];
  const invalid = selected && context ? thirdPartyApplicationError(selected, context.fields, context.capability, imageSlots) : null;
  function reset() { setOffset(0); setSelected(null); setImageSlots([]); setError(null); }
  async function apply() {
    if (!selected || !context || busy || invalid) return;
    setBusy(true); context.onBusy(true); setError(null);
    try {
      const intent = `${selected.id}:${selected.version}`;
      const key = keys.current.get(intent) ?? crypto.randomUUID(); keys.current.set(intent, key);
      const imported = await importThirdPartyPrompt(context.projectId, selected, key);
      if (imported.targetKind !== context.targetKind) throw new Error(t("templates.kindMismatch"));
      await context.onApply(imported, { videoInputMode: thirdPartyVideoMode(selected), imageSlots }); context.onClose();
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.applyFailed"))); }
    finally { setBusy(false); context.onBusy(false); }
  }
  return <div className="media-template-catalog">
    {system ? <ThirdPartyPromptSources kind={kind} /> : null}
    <Notice tone="info">{t("thirdParty.cacheHint")}</Notice>
    <div className="media-template-toolbar">
      <Field><FieldLabel htmlFor={`${id}-source`}>{t("thirdParty.source")}</FieldLabel><Select id={`${id}-source`} value={sourceId} disabled={busy}
        onChange={(event) => { setSourceId(event.target.value); reset(); }}><option value="">{t("thirdParty.allSources")}</option>
        {sources.data?.items.filter((source) => source.targetKind === kind).map((source) => <option key={source.id} value={source.id}>{source.name}</option>)}</Select></Field>
      <Input type="search" value={search} disabled={busy} maxLength={160} aria-label={t("templates.search")} placeholder={t("templates.search")}
        onChange={(event) => { setSearch(event.target.value); reset(); }} />
      <Button type="button" variant="outline" disabled={busy || query.isFetching} onClick={() => { reset(); void query.refetch(); void sources.refetch(); }}>{t("templates.refresh")}</Button>
    </div>
    {query.isPending ? <LoadingState compact label={t("templates.loading")} /> : null}
    {query.error || sources.error ? <Notice tone="danger">{query.error?.message ?? sources.error?.message}</Notice> : null}
    {query.data && query.data.items.length === 0 ? <EmptyState title={t("templates.empty")} description={t("thirdParty.emptyHint")} /> : null}
    <div className="media-template-grid">{query.data?.items.map((entry) => {
      const value = entry.image ?? entry.video;
      return value ? <Button type="button" variant="outline" className="media-template-card" key={entry.id} disabled={busy}
        aria-label={value.title} aria-haspopup="dialog" onClick={() => { setSelected(entry); setImageSlots([]); setError(null); }}>
        {value.coverUrl ? <img src={value.coverUrl} alt="" loading="lazy" referrerPolicy="no-referrer" /> : <span className="media-template-card-prompt">{value.prompt}</span>}
        <span className="media-template-card-label"><strong>{value.title}</strong><small>{sources.data?.items.find((source) => source.id === entry.sourceId)?.name ?? entry.sourceId}</small></span>
      </Button> : null;
    })}</div>
    {query.data && query.data.total > query.data.limit ? <div className="ui-form-actions">
      <Button type="button" variant="outline" disabled={busy || offset === 0} onClick={() => { setOffset(Math.max(0, offset - 50)); setSelected(null); }}>{t("thirdParty.previous")}</Button>
      <span>{t("thirdParty.page", { "0": Math.floor(offset / 50) + 1, "1": Math.ceil(query.data.total / 50) })}</span>
      <Button type="button" variant="outline" disabled={busy || offset + 50 >= query.data.total} onClick={() => { setOffset(offset + 50); setSelected(null); }}>{t("thirdParty.next")}</Button>
    </div> : null}
    {selected && data ? <Dialog title={data.title} description={data.description || t("templates.previewHint")} className="media-template-detail"
      busy={busy} onClose={() => { setSelected(null); setError(null); }} onSubmit={(event) => event.preventDefault()}
      footer={context ? <div className="media-template-detail-actions">
        {invalid ? <Notice tone="warning">{invalid}</Notice> : null}
        {error ? <Notice tone="danger">{error.message}</Notice> : null}
        <div className="ui-form-actions"><Button type="button" disabled={busy || Boolean(invalid)} onClick={() => void apply()}>{busy ? t("templates.applying") : t("templates.use")}</Button></div>
      </div> : undefined}>
      <div className="media-template-preview">
      <p>{data.author}{data.tags.length ? ` · ${data.tags.join(" · ")}` : ""}</p>
      {data.sourceUrl ? <a href={data.sourceUrl} target="_blank" rel="noopener noreferrer">{t("thirdParty.original")}</a> : null}
      {selected.image ? <p>{t("thirdParty.imageMetadata", { "0": selected.image.imageModel, "1": selected.image.imageMode === "edit" ? t("thirdParty.editImage") : t("thirdParty.generateImage") })}</p> : null}
      {selected.video ? <p>{t("thirdParty.videoMetadata", { "0": selected.video.videoModel, "1": videoModeNames[selected.video.videoMode] })}</p> : null}
      {selected.video?.previewVideoUrl ? <video controls preload="none" playsInline poster={data.coverUrl || undefined}
        src={selected.video.previewVideoUrl} aria-label={t("thirdParty.videoPreview")} className="third-party-video-preview" />
        : data.coverUrl ? <img src={data.coverUrl} alt={t("thirdParty.imagePreview")} referrerPolicy="no-referrer" className="media-template-preview-cover" /> : null}
      <p className="media-template-preview-prompt">{data.prompt}</p>
      {selected.video?.missingReferences?.length ? <Notice tone="warning">{t("thirdParty.referencesRequired")}
        {` ${selected.video.missingReferences.map((ref) => ref.label).join(" · ")}`}</Notice> : null}
      {selected.video?.imageGeneration ? <><p>{t("thirdParty.imageStage")}</p><p className="media-template-preview-prompt">{selected.video.imageGeneration.prompt}</p></> : null}
      <div className="media-template-preview-images">{refs.map((ref, index) => ref.kind === "IMAGE"
        ? <img key={ref.url} src={ref.url} referrerPolicy="no-referrer" alt={t("templates.numberedImage", { "0": index + 1 })} />
        : <a key={ref.url} href={ref.url} target="_blank" rel="noopener noreferrer">{ref.kind} · {index + 1}</a>)}</div>
      {context && refs.length > 0 ? <Notice tone="warning">{t("thirdParty.replacementHint", { "0": refs.length })}</Notice> : null}
      {context && slots.length > 0 ? images.map((ref, index) => <Field key={ref.url}><FieldLabel htmlFor={`${id}-slot-${index}`}>{t("templates.numberedImage", { "0": index + 1 })}</FieldLabel>
        <Select id={`${id}-slot-${index}`} value={imageSlots[index] ?? ""} disabled={busy} onChange={(event) => setImageSlots((current) => { const next = [...current]; next[index] = event.target.value; return next; })}>
          <option value="">{t("templates.chooseSlot")}</option>{slots.map((slot) => <option key={slot.key} value={slot.key} disabled={imageSlots.some((key, pos) => key === slot.key && pos !== index)}>{workflowFieldLabel(slot)}</option>)}</Select></Field>) : null}
      </div>
    </Dialog> : null}
    {query.error ? <Button type="button" variant="outline" disabled={busy || query.isFetching} onClick={() => void client.invalidateQueries({ queryKey: ["third-party-prompts"] })}>{t("templates.refresh")}</Button> : null}
  </div>;
}

function ThirdPartyPromptSources({ kind }: { kind: MediaTemplateKind }) {
  const id = useId();
  const client = useQueryClient();
  const query = useQuery({ queryKey: ["third-party-sources"], queryFn: listThirdPartyPromptSources });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState(""); const [sourceId, setSourceId] = useState(""); const [url, setUrl] = useState("");
  async function mutate(source: ThirdPartyPromptSource, sync: boolean) {
    setBusy(true); setError(null);
    try {
      if (sync) { const result = await syncThirdPartyPromptSource(source.id); if (result.state === "FAILED") throw new Error(t("thirdParty.syncFailed")); }
      else await setThirdPartyPromptSource(source, !source.enabled);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("thirdParty.syncFailed"))); }
    finally { await client.invalidateQueries({ queryKey: ["third-party-sources"] }); await client.invalidateQueries({ queryKey: ["third-party-prompts"] }); setBusy(false); }
  }
  async function create() {
    setBusy(true); setError(null);
    try { await createThirdPartyPromptSource({ id: sourceId, name, url, targetKind: kind }); setCreating(false); setName(""); setSourceId(""); setUrl(""); await query.refetch(); }
    catch (failure) { setError(failure instanceof Error ? failure : new Error(t("thirdParty.invalid"))); }
    finally { setBusy(false); }
  }
  return <Panel title={t("thirdParty.manageSources")} description={t("thirdParty.dailyHint")}>
    {query.data?.items.filter((source) => source.targetKind === kind).map((source) => <div key={source.id} className="third-party-source">
      <div><strong>{source.name}</strong><p>{t("thirdParty.count", { "0": source.promptCount })} · {source.lastSyncedAt ? formatDate(source.lastSyncedAt) : t("thirdParty.neverSynced")}</p>
        {source.lastError ? <p>{t("thirdParty.syncFailed")}</p> : null}</div>
      <div className="ui-form-actions"><Button type="button" variant="outline" disabled={busy || !source.enabled || source.syncing} onClick={() => void mutate(source, true)}>{source.syncing ? t("thirdParty.syncing") : t("thirdParty.sync")}</Button>
        <Button type="button" variant="outline" disabled={busy} onClick={() => void mutate(source, false)}>{source.enabled ? t("thirdParty.disable") : t("thirdParty.enable")}</Button></div>
    </div>)}
    <Button type="button" variant="outline" disabled={busy} onClick={() => { setCreating(true); setError(null); }}>{t("thirdParty.addSource")}</Button>
    {error && !creating ? <Notice tone="danger">{error.message}</Notice> : null}
    {creating ? <Dialog title={t("thirdParty.addSource")} description={t("thirdParty.nativeHint")} busy={busy} compact onClose={() => setCreating(false)} onSubmit={(event) => { event.preventDefault(); void create(); }}
      footer={<><Button type="button" variant="outline" disabled={busy} onClick={() => setCreating(false)}>{t("common.cancel")}</Button><Button type="submit" disabled={busy}>{t("thirdParty.addSource")}</Button></>}>
      <FieldGroup>
        <Field><FieldLabel htmlFor={`${id}-source-id`}>{t("thirdParty.sourceId")}</FieldLabel><Input id={`${id}-source-id`} value={sourceId} required pattern="[a-z][a-z0-9_-]{0,79}" maxLength={80} onChange={(event) => setSourceId(event.target.value)} /></Field>
        <Field><FieldLabel htmlFor={`${id}-source-name`}>{t("thirdParty.sourceName")}</FieldLabel><Input id={`${id}-source-name`} value={name} required maxLength={160} onChange={(event) => setName(event.target.value)} /></Field>
        <Field><FieldLabel htmlFor={`${id}-feed-url`}>{t("thirdParty.feedUrl")}</FieldLabel><Input id={`${id}-feed-url`} type="url" value={url} required maxLength={4096} onChange={(event) => setUrl(event.target.value)} /></Field>
      </FieldGroup>
      {error ? <Notice tone="danger">{error.message}</Notice> : null}
    </Dialog> : null}
  </Panel>;
}
