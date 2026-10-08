import { ImageSquare, Plus, Trash } from "@/shared/ui/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { deleteMediaTemplate, importMediaTemplate, listMediaTemplates, type MediaCapability,
  type MediaTemplate, type MediaTemplateImport, type MediaTemplateKind } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Tabs, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import type { MediaDraftFields } from "../canvas/mediaDraftCapability";
import { workflowDefinition } from "../canvas/workflowDraft";
import { workflowFieldLabel } from "../canvas/workflowFieldPresentation";
import { MediaTemplateForm, type TemplateSeedImage } from "./MediaTemplateForm";
import { templateApplicationError, templateImageSlots, templatePromptEnabled, templateSeedPrompt, type TemplateApplyOptions } from "./templateApplication";
import "./MediaTemplates.css";
import { ThirdPartyPromptCatalog } from "./ThirdPartyPromptCatalog";

export type TemplatePickerContext = { projectId: string; targetKind: MediaTemplateKind;
  fields: MediaDraftFields; capability?: MediaCapability; seedImages: TemplateSeedImage[];
  onApply: (imported: MediaTemplateImport, options: TemplateApplyOptions) => Promise<void>;
  onClose: () => void; onBusy: (busy: boolean) => void };

/** Reusable catalog: creators see their own plus system templates; settings edits system templates. */
function MediaTemplateCatalog({ context, system = false, enabled = true }: {
  context?: TemplatePickerContext; system?: boolean; enabled?: boolean;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const [kind, setKind] = useState<MediaTemplateKind>(context?.targetKind ?? "IMAGE");
  const query = useQuery({ queryKey: ["media-templates", system ? "SYSTEM" : "VISIBLE", kind],
    queryFn: () => listMediaTemplates(kind, system), enabled });
  const [tab, setTab] = useState("all");
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<MediaTemplate | null>(null);
  const [editing, setEditing] = useState<MediaTemplate | "NEW" | null>(null);
  const [deleting, setDeleting] = useState<MediaTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [mode, setMode] = useState<TemplateApplyOptions["videoInputMode"]>(context?.fields.videoInputMode === "TEXT"
    ? null : context?.fields.videoInputMode ?? null);
  const [imageSlots, setImageSlots] = useState<string[]>([]);
  // Retain the same import key after a lost response; changing template version creates a new intent.
  const importKeys = useRef(new Map<string, string>());
  const options: TemplateApplyOptions = { videoInputMode: mode, imageSlots };
  const workflow = workflowDefinition(context?.capability);
  const promptEnabled = !context || templatePromptEnabled(context.capability, context.fields);
  const applyError = context && selected ? templateApplicationError(context.targetKind, context.fields,
    context.capability, selected.images.length, options, selected.prompt) : null;
  const slots = context ? templateImageSlots(context.capability, { ...context.fields,
    prompt: promptEnabled ? selected?.prompt ?? context.fields.prompt : context.fields.prompt }) : [];
  const items = (query.data ?? []).filter((template) => template.targetKind === kind
    && (system || tab !== "mine" || template.scope === "PERSONAL")
    && `${template.name}\n${template.prompt}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()));
  function select(template: MediaTemplate) { setSelected(template); setImageSlots([]); setError(null); }
  async function apply() {
    if (!context || !selected || busy || applyError) return;
    if (selected.targetKind !== context.targetKind) { setError(new Error(t("templates.kindMismatch"))); return; }
    setBusy(true); context.onBusy(true); setError(null);
    try {
      const intent = `${selected.id}:${selected.version}`;
      const commandKey = importKeys.current.get(intent) ?? crypto.randomUUID(); importKeys.current.set(intent, commandKey);
      const imported = await importMediaTemplate(context.projectId, selected, commandKey);
      if (imported.targetKind !== context.targetKind) throw new Error(t("templates.kindMismatch"));
      await context.onApply(imported, options);
      context.onClose();
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.applyFailed"))); }
    finally { setBusy(false); context.onBusy(false); }
  }
  async function remove() {
    if (!deleting || busy) return;
    setBusy(true); setError(null);
    try {
      await deleteMediaTemplate(deleting);
      if (selected?.id === deleting.id) setSelected(null);
      setDeleting(null); await client.invalidateQueries({ queryKey: ["media-templates"] });
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("templates.deleteFailed"))); }
    finally { setBusy(false); }
  }
  return <div className="media-template-catalog">
    <div className="media-template-toolbar">
      {system ? <Field><FieldLabel htmlFor={`${id}-kind`}>{t("templates.targetKind")}</FieldLabel><Select id={`${id}-kind`} value={kind} disabled={busy}
        onChange={(event) => { setKind(event.target.value as MediaTemplateKind); setSelected(null); }}><option value="IMAGE">{t("templates.imageTitle")}</option><option value="VIDEO">{t("templates.videoTitle")}</option></Select></Field>
        : <Tabs value={tab} onValueChange={(value) => { setTab(value); setSelected(null); }}><TabsList aria-label={t("templates.categories")}>
          <TabsTrigger value="all" disabled={busy}>{t("templates.all")}</TabsTrigger><TabsTrigger value="mine" disabled={busy}>{t("templates.mine")}</TabsTrigger>
          <TabsTrigger value="third-party" disabled={busy}>{t("thirdParty.catalog")}</TabsTrigger></TabsList></Tabs>}
      <Input type="search" aria-label={t("templates.search")} value={search} disabled={busy} placeholder={t("templates.search")}
        onChange={(event) => setSearch(event.target.value)} />
      <Button type="button" variant="outline" disabled={busy} onClick={() => { setEditing("NEW"); setError(null); }}><Plus data-icon="inline-start" />{system ? t("templates.createSystem") : t("templates.create")}</Button>
    </div>
    {system ? <Tabs value={tab === "third-party" ? "third-party" : "local"} onValueChange={(value) => { setTab(value === "third-party" ? value : "all"); setSelected(null); }}>
      <TabsList aria-label={t("thirdParty.catalog")}><TabsTrigger value="local" disabled={busy}>{t("thirdParty.local")}</TabsTrigger><TabsTrigger value="third-party" disabled={busy}>{t("thirdParty.catalog")}</TabsTrigger></TabsList>
    </Tabs> : null}
    {tab === "third-party" ? <ThirdPartyPromptCatalog key={kind} kind={kind} system={system} context={context ? { ...context, onBusy: (next) => { setBusy(next); context.onBusy(next); } } : undefined} /> : <>
    {query.isPending ? <LoadingState compact label={t("templates.loading")} /> : null}
    {query.error ? <Notice tone="danger">{query.error.message}<Button type="button" variant="outline" disabled={query.isFetching || busy} onClick={() => void query.refetch()}>{t("templates.refresh")}</Button></Notice> : null}
    {!query.isPending && !items.length ? <EmptyState icon={<ImageSquare />} title={t("templates.empty")} description={t("templates.emptyHint")} /> : null}
    <div className="media-template-grid">
      {items.map((template) => <Button key={template.id} type="button" variant="outline" className="media-template-card" aria-pressed={selected?.id === template.id}
        aria-label={template.name}
        disabled={busy} onClick={() => select(template)}>
        {template.images[0] ? <img src={template.images[0].thumbnailUrl} alt="" loading="lazy" /> : <span className="media-template-card-prompt">{template.prompt}</span>}
        <span className="media-template-card-label"><strong>{template.name}</strong><small>{template.scope === "SYSTEM" ? t("templates.system") : t("templates.personal")} · {t("templates.imageCount", { "0": template.images.length })}</small></span>
      </Button>)}
    </div>
    {selected ? <Panel title={selected.name} className="media-template-preview" description={t("templates.previewHint")}>
      <p className="media-template-preview-prompt" aria-disabled={!promptEnabled}>{selected.prompt}</p>
      <div className="media-template-preview-images">{selected.images.map((image, index) => <img key={image.id} src={image.thumbnailUrl} alt={t("templates.numberedImage", { "0": index + 1 })} />)}</div>
      {context ? <>
        {!promptEnabled && selected.images.length > 0 ? <Notice tone="info">{t("media.workflow.noPrompt")}</Notice> : null}
        {promptEnabled || selected.images.length > 0 ? <Notice tone={selected.images.length ? "warning" : "info"}>{selected.images.length
          ? t(promptEnabled ? "templates.replacementHint" : "templates.imagesOnlyHint", { "0": context.fields.mediaInputs.length, "1": selected.images.length }) : t("templates.promptOnlyHint")}</Notice> : null}
        {selected.images.length > 0 && context.targetKind === "VIDEO" && !workflow ? <Field>
          <FieldLabel htmlFor={`${id}-mode`}>{t("templates.videoMode")}</FieldLabel><Select id={`${id}-mode`} value={mode ?? ""} disabled={busy}
            onChange={(event) => setMode(event.target.value as TemplateApplyOptions["videoInputMode"])}><option value="">{t("templates.chooseImageMode")}</option>
            {context.capability?.supportedVideoInputModes.filter((value) => value !== "TEXT").map((value) => <option key={value} value={value}>{value === "START_END" ? t("common.startEndFrames") : t("common.mixedReference")}</option>)}</Select>
        </Field> : null}
        {selected.images.length > 0 && workflow ? <FieldGroup>
          <p>{t("templates.slotHint")}</p>{selected.images.map((image, index) => <Field key={image.id}><FieldLabel htmlFor={`${id}-slot-${index}`}>{t("templates.numberedImage", { "0": index + 1 })}</FieldLabel>
            <Select id={`${id}-slot-${index}`} value={imageSlots[index] ?? ""} disabled={busy} onChange={(event) => setImageSlots((current) => { const next = [...current]; next[index] = event.target.value; return next; })}>
              <option value="">{t("templates.chooseSlot")}</option>{slots.map((slot) => <option key={slot.key} value={slot.key} disabled={imageSlots.some((key, position) => key === slot.key && position !== index)}>{workflowFieldLabel(slot)}</option>)}</Select></Field>)}</FieldGroup> : null}
        {applyError ? <Notice tone="warning">{applyError}</Notice> : null}
      </> : null}
      <div className="ui-form-actions">
        {(system || selected.scope === "PERSONAL") ? <><Button variant="outline" type="button" disabled={busy} onClick={() => { setEditing(selected); setError(null); }}>{t("templates.edit")}</Button>
          <Button variant="outline" type="button" disabled={busy} onClick={() => { setDeleting(selected); setError(null); }}><Trash data-icon="inline-start" />{t("templates.delete")}</Button></> : null}
        {context ? <Button type="button" disabled={busy || Boolean(applyError)} onClick={() => void apply()}>{busy ? t("templates.applying") : t("templates.use")}</Button> : null}
      </div>
    </Panel> : null}
    {error && !deleting ? <Notice tone="danger">{error.message}<Button type="button" variant="outline" disabled={busy || query.isFetching} onClick={() => { setSelected(null); void query.refetch(); }}>{t("templates.refresh")}</Button></Notice> : null}
    </>}
    {editing ? <MediaTemplateForm template={editing === "NEW" ? undefined : editing} scope={system ? "SYSTEM" : "PERSONAL"} targetKind={kind}
      lockTargetKind={Boolean(context)} seedPrompt={context ? templateSeedPrompt(context.fields) : undefined} seedImages={context?.seedImages} projectId={context?.projectId}
      onClose={() => setEditing(null)} onSaved={(saved) => { setEditing(null); setSelected(saved); if (system) setKind(saved.targetKind); }} /> : null}
    {deleting ? <Dialog title={t("templates.delete")} onClose={() => setDeleting(null)} onSubmit={(event) => { event.preventDefault(); void remove(); }} busy={busy} compact
      footer={<><Button type="button" variant="outline" disabled={busy} onClick={() => setDeleting(null)}>{t("common.cancel")}</Button><Button variant="destructive" type="submit" disabled={busy}>{t("templates.delete")}</Button></>}>
      <p>{t("templates.deleteHint", { "0": deleting.name })}</p>{error ? <Notice tone="danger">{error.message}</Notice> : null}
    </Dialog> : null}
  </div>;
}

export function MediaTemplatePicker(context: TemplatePickerContext) {
  useLocale();
  const [busy, setBusy] = useState(false);
  return <Dialog title={context.targetKind === "IMAGE" ? t("templates.imageTitle") : t("templates.videoTitle")}
    description={t("templates.pickerHint")} className="media-template-picker" busy={busy} onClose={context.onClose} onSubmit={(event) => event.preventDefault()}>
    <MediaTemplateCatalog context={{ ...context, onBusy: (next) => { setBusy(next); context.onBusy(next); } }} />
  </Dialog>;
}

export function SystemMediaTemplatesSection({ enabled }: { enabled: boolean }) {
  useLocale();
  return <Panel title={t("templates.systemTitle")} description={t("templates.systemHint")}><MediaTemplateCatalog system enabled={enabled} /></Panel>;
}
