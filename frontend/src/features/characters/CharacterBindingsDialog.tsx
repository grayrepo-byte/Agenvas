import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { applyCharacterBindings, CHARACTER_CLEAR_MODES, listCanvasItems, previewCharacterBindings, reviewCharacterBindings, type CharacterBindingPreview, type CharacterBindingSelection, type MediaDraft, type PreviewCharacterBindings } from "../../shared/api/client";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { useMediaDraftSession } from "../canvas/useMediaDraftSession";
import { CharacterReferencePreview } from "./CharacterReferencePicker";
import { CharacterManagerDialog } from "./CharacterManagerDialog";
import { characterBindingStale, characterQueryOptions } from "./characterQueries";

type Session = ReturnType<typeof useMediaDraftSession>;
type Prepared = { request: PreviewCharacterBindings; result: CharacterBindingPreview; before: MediaDraft; stateHash: string };
const CHANGE_LABELS: Readonly<Record<string, MessageKey>> = {
  REFERENCES_ADDED: "character.referencesAdded", REFERENCES_REMOVED: "character.referencesRemoved", PROMPT_UPDATED: "character.promptUpdated",
  INPUT_MODE_CHANGED: "character.modeChanged", SPEAKER_CHANGED: "character.speakerChanged", KEEP_INPUTS: "character.inputsKept",
  MANUAL_PROMPT_PRESERVED: "character.manualPromptPreserved", MANUAL_REFERENCES_RETAINED: "character.manualReferencesRetained", CONNECTED_REFERENCES_RETAINED: "character.connectedReferencesRetained",
  PROFILE_CHANGED: "character.profileChanged", DRAFT_CHANGED: "character.draftChanged", RESULT_CHANGED: "character.resultChanged",
  SPEAKER_NOT_APPLIED: "character.speakerNotApplied", NOT_REVIEWED: "character.notReviewed", CHARACTER_ARCHIVED: "character.archivedHint",
};
function changeLabel(value: string) { const key = CHANGE_LABELS[value]; return key ? t(key) : value; }

/** Preview, explicit draft application, and result review are three separate operations. */
export function CharacterBindingsDialog({ projectId, itemId, session, readOnly, onBusy, onClose }: {
  projectId: string; itemId: string; session: Session; readOnly: boolean; onBusy: (busy: boolean) => void; onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const index = useQuery(characterQueryOptions(projectId));
  const canvas = useQuery({ queryKey: ["canvas", projectId], queryFn: () => listCanvasItems(projectId) });
  const binding = index.data?.bindings.find((entry) => entry.canvasItemId === itemId);
  const characters = index.data?.characters ?? [];
  const selectedResult = canvas.data?.items.find((item) => item.id === itemId)?.selectedVersionId ?? null;
  const [selections, setSelections] = useState<CharacterBindingSelection[]>([]);
  const initialized = useRef(false);
  const [clearMode, setClearMode] = useState<PreviewCharacterBindings["clearMode"]>(CHARACTER_CLEAR_MODES.KEEP_INPUTS);
  const [prepared, setPrepared] = useState<Prepared | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [managerOpen, setManagerOpen] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const commandId = useRef(crypto.randomUUID());
  useEffect(() => {
    if (initialized.current || !index.isSuccess) return;
    initialized.current = true;
    setSelections((binding?.snapshots ?? []).map((snapshot) => ({ characterId: snapshot.characterId, characterVersion: snapshot.characterVersion,
      lookId: snapshot.look?.id ?? null, useAppearance: snapshot.useAppearance, useVoice: snapshot.useVoice })));
  }, [index.isSuccess, binding]);
  useEffect(() => () => onBusy(false), [onBusy]);
  const stateHash = JSON.stringify({ fields: session.state.fields, version: session.state.expectedVersion,
    bindingVersion: binding?.version ?? 0, profiles: characters.map((profile) => [profile.id, profile.version, profile.archived]), selectedResult });
  const previewCurrent = prepared?.stateHash === stateHash;
  function changeSelections(next: CharacterBindingSelection[]) { setSelections(next); setPrepared(null); setNotice(null); commandId.current = crypto.randomUUID(); }
  async function withBusy(work: () => Promise<void>) {
    if (busyRef.current) return;
    busyRef.current = true; setBusy(true); onBusy(true); setError(null); setNotice(null);
    try { await work(); } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("character.applyFailed"))); }
    finally { busyRef.current = false; setBusy(false); onBusy(false); }
  }
  async function flushDraft(): Promise<MediaDraft> {
    await session.awaitPendingSave();
    const latest = session.getLatest();
    if (session.isReadOnly() || readOnly) throw new Error(t("media.editor.editingLocked"));
    if (!latest.fields || latest.expectedVersion === null) throw new Error(t("media.editor.waitForDraft"));
    // A failed CAS remains visible to the user; applying a profile never overwrites that draft.
    if (latest.error) throw latest.error;
    if (latest.dirty) return await session.save.mutateAsync({ ...latest.fields, expectedVersion: latest.expectedVersion });
    const saved = client.getQueryData<MediaDraft>(session.queryKey);
    if (!saved || saved.version !== latest.expectedVersion) throw new Error(t("character.previewExpired"));
    return saved;
  }
  async function preview() {
    await withBusy(async () => {
      const before = await flushDraft();
      const request: PreviewCharacterBindings = { expectedBindingVersion: binding?.version ?? 0, expectedDraftVersion: before.version, selections, clearMode };
      const result = await previewCharacterBindings(projectId, itemId, request);
      const latest = session.getLatest();
      setPrepared({ request, result, before, stateHash: JSON.stringify({ fields: latest.fields, version: latest.expectedVersion,
        bindingVersion: binding?.version ?? 0, profiles: characters.map((profile) => [profile.id, profile.version, profile.archived]), selectedResult }) });
      commandId.current = crypto.randomUUID();
    });
  }
  async function apply() {
    if (!prepared || !previewCurrent) return;
    await withBusy(async () => {
      const finish = session.beginBusinessWrite();
      let failure: Error | null = null;
      try {
        if (session.isReadOnly() || readOnly) throw new Error(t("media.editor.editingLocked"));
        await session.awaitPendingSave();
        const saved = await applyCharacterBindings(projectId, itemId, { ...prepared.request, commandId: commandId.current, previewHash: prepared.result.previewHash });
        session.acceptSaved(saved.draft);
        client.setQueryData(characterQueryOptions(projectId).queryKey, (current) => current ? { ...current, bindings: [...current.bindings.filter((entry) => entry.canvasItemId !== itemId), saved.binding] } : current);
        setPrepared(null); setNotice(t("character.appliedDraftOnly"));
        await Promise.all([client.invalidateQueries({ queryKey: ["canvas-connections", projectId] }), client.invalidateQueries({ queryKey: ["canvas", projectId] }), client.invalidateQueries({ queryKey: characterQueryOptions(projectId).queryKey })]);
      } catch (caught) { failure = caught instanceof Error ? caught : new Error(t("character.applyFailed")); setPrepared(null); throw failure; }
      finally { finish(failure); }
    });
  }
  async function review() {
    if (!binding || !selectedResult) return;
    await withBusy(async () => {
      const draft = await flushDraft();
      await reviewCharacterBindings(projectId, itemId, { expectedBindingVersion: binding.version, expectedDraftVersion: draft.version, selectedVersionId: selectedResult });
      await client.invalidateQueries({ queryKey: characterQueryOptions(projectId).queryKey });
      setNotice(t("character.reviewed"));
    });
  }
  async function refreshForRetry() {
    await withBusy(async () => {
      await index.refetch({ throwOnError: true });
      await canvas.refetch({ throwOnError: true });
      if (!await session.refreshVersion()) throw session.getLatest().error ?? new Error(t("character.loadFailed"));
      // This explicit retry keeps the visible local fields. The next preview saves them
      // against the refreshed CAS version before proposing any character contribution.
      session.edit((fields) => fields);
      setPrepared(null);
    });
  }
  const stale = binding && characterBindingStale(binding, characters);
  return <Dialog title={t("character.bindingsTitle")} description={t("character.bindingsDescription")} busy={busy} onClose={onClose} onSubmit={(event) => { event.preventDefault(); void preview(); }}
    footer={<><Button type="submit" variant="outline" disabled={busy || readOnly || !index.isSuccess}>{t("character.previewApply")}</Button><Button type="button" disabled={busy || readOnly || !prepared || !previewCurrent} onClick={() => { void apply(); }}>{t("character.applyDraft")}</Button></>}>
    <fieldset className="flex min-w-0 flex-col gap-4" disabled={busy}>
      <Button type="button" variant="outline" onClick={() => setManagerOpen(true)}>{t("character.manage")}</Button>
      {index.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}<Button type="button" variant="ghost" onClick={() => { void index.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
      {binding?.snapshots.length ? <FieldSet><FieldLegend>{t("character.appliedVersions")}</FieldLegend>
        {binding.snapshots.map((snapshot) => <div key={snapshot.characterId} className="flex flex-wrap items-center gap-2 text-sm"><span>{snapshot.name} · v{snapshot.characterVersion}{snapshot.look ? ` · ${snapshot.look.name}` : ""}</span><Badge variant={stale || binding.needsReview ? "secondary" : "outline"}>{t(stale ? "character.outdated" : binding.needsReview ? "character.needsReview" : "character.reviewedStatus")}</Badge></div>)}
        {binding.reviewReasons?.map((reason) => <p key={reason} className="text-sm text-muted-foreground">{changeLabel(reason)}</p>)}
        <p className="break-all text-sm text-muted-foreground">{t("character.currentResult", { "0": selectedResult ?? t("character.noResult") })}</p>
        {!selectedResult ? <p className="text-sm text-muted-foreground">{t("character.reviewNeedsResult")}</p> : null}
        <Button type="button" variant="outline" disabled={busy || readOnly || !selectedResult || Boolean(stale)} onClick={() => { void review(); }}>{t("character.reviewResult")}</Button>
      </FieldSet> : null}
      {!characters.some((profile) => !profile.archived) && index.isSuccess ? <Empty><EmptyHeader><EmptyDescription>{t("character.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
      <FieldGroup>{characters.map((profile) => {
        const selection = selections.find((entry) => entry.characterId === profile.id);
        const update = (changes: Partial<CharacterBindingSelection>) => changeSelections(selections.map((entry) => entry.characterId === profile.id ? { ...entry, ...changes } : entry));
        return <FieldSet key={profile.id} className="rounded-lg border p-3"><FieldLegend>{profile.content.name} · v{profile.version}</FieldLegend>
          <Field orientation="horizontal"><Checkbox id={`${id}-${profile.id}`} disabled={profile.archived && !selection || readOnly} checked={Boolean(selection)} onCheckedChange={(checked) => changeSelections(checked ? [...selections, { characterId: profile.id, characterVersion: profile.version, lookId: profile.content.looks[0]?.id ?? null, useAppearance: true, useVoice: false }] : selections.filter((entry) => entry.characterId !== profile.id))} /><FieldLabel htmlFor={`${id}-${profile.id}`}>{t(profile.archived ? "character.archived" : "character.include")}</FieldLabel></Field>
          {selection ? <FieldGroup>
            {selection.characterVersion !== profile.version ? <Alert><AlertDescription>{t("character.selectionOutdated")}<Button type="button" variant="outline" disabled={profile.archived || readOnly} onClick={() => update({ characterVersion: profile.version, lookId: profile.content.looks.some((look) => look.id === selection.lookId) ? selection.lookId : profile.content.looks[0]?.id ?? null })}>{t("character.useLatest")}</Button></AlertDescription></Alert> : null}
            <Field><FieldLabel htmlFor={`${id}-${profile.id}-look`}>{t("character.lookName")}</FieldLabel><Select id={`${id}-${profile.id}-look`} disabled={readOnly || profile.archived} value={selection.lookId ?? ""} onChange={(event) => update({ lookId: event.target.value || null })}><option value="">{t("character.noLook")}</option>{profile.content.looks.map((look) => <option key={look.id} value={look.id}>{look.name}</option>)}</Select></Field>
            <Field orientation="horizontal"><Checkbox id={`${id}-${profile.id}-appearance`} disabled={readOnly || profile.archived} checked={selection.useAppearance} onCheckedChange={(checked) => update({ useAppearance: Boolean(checked) })} /><FieldLabel htmlFor={`${id}-${profile.id}-appearance`}>{t("character.useAppearance")}</FieldLabel></Field>
            <Field orientation="horizontal"><Checkbox id={`${id}-${profile.id}-voice`} disabled={readOnly || profile.archived} checked={selection.useVoice} onCheckedChange={(checked) => update({ useVoice: Boolean(checked) })} /><FieldLabel htmlFor={`${id}-${profile.id}-voice`}>{t("character.useVoice")}</FieldLabel></Field>
          </FieldGroup> : null}
        </FieldSet>;
      })}</FieldGroup>
      <Field><FieldLabel htmlFor={`${id}-clear`}>{t("character.removalMode")}</FieldLabel><Select id={`${id}-clear`} value={clearMode} disabled={readOnly || selections.length > 0} onChange={(event) => { setClearMode(event.target.value as PreviewCharacterBindings["clearMode"]); setPrepared(null); }}><option value={CHARACTER_CLEAR_MODES.KEEP_INPUTS}>{t("character.keepInputs")}</option><option value={CHARACTER_CLEAR_MODES.REMOVE_OWNED}>{t("character.removeOwned")}</option></Select><FieldDescription>{t("character.removalHint")}</FieldDescription></Field>
      <Button type="button" variant="ghost" disabled={readOnly} onClick={() => changeSelections([])}>{t("character.unlinkAll")}</Button>
      {readOnly ? <Alert><AlertDescription>{t("media.editor.editingLocked")}</AlertDescription></Alert> : null}
    </fieldset>
    {prepared ? <FieldSet><FieldLegend>{t("character.actualChanges")}</FieldLegend>
      {!previewCurrent ? <Alert variant="destructive"><AlertDescription>{t("character.previewExpired")}</AlertDescription></Alert> : null}
      <ul>{prepared.result.changes.map((change, index) => <li key={`${change}-${index}`}>{changeLabel(change)}</li>)}</ul>
      {prepared.result.warnings.map((warning, index) => <Alert key={`${warning}-${index}`}><AlertDescription>{changeLabel(warning)}</AlertDescription></Alert>)}
      <Field><FieldLabel htmlFor={`${id}-before-prompt`}>{t("character.promptBefore")}</FieldLabel><Textarea id={`${id}-before-prompt`} readOnly value={prepared.before.prompt} /></Field>
      <Field><FieldLabel htmlFor={`${id}-candidate-prompt`}>{t("character.promptAfter")}</FieldLabel><Textarea id={`${id}-candidate-prompt`} readOnly value={prepared.result.candidateDraft.prompt} /></Field>
      <p className="text-sm">{t("character.modePreview", { "0": prepared.before.videoInputMode ?? "—", "1": prepared.result.candidateDraft.videoInputMode ?? "—" })}</p>
      <p className="text-sm">{t("character.speakerPreview", { "0": prepared.before.parameters?.speaker ?? "—", "1": prepared.result.candidateDraft.parameters?.speaker ?? "—" })}</p>
      <FieldSet><FieldLegend>{t("character.referencesAfter")}</FieldLegend>{prepared.result.candidateDraft.mediaInputs.map((input) => <div className="flex flex-col gap-1 rounded-lg border p-2" key={`${input.role}-${input.versionId}`}><p className="text-sm">{prepared.result.snapshots.flatMap((snapshot) => snapshot.look?.views ?? []).find((view) => view.versionId === input.versionId)?.purpose ?? t(input.role === "AUDIO_REFERENCE" ? "character.audioReference" : "character.imageReference")}</p><CharacterReferencePreview projectId={projectId} versionId={input.versionId} kind={input.role === "AUDIO_REFERENCE" ? "AUDIO" : input.role === "VIDEO_REFERENCE" ? "VIDEO" : "IMAGE"} /></div>)}</FieldSet>
      <FieldSet><FieldLegend>{t("character.referencesRemoved")}</FieldLegend>{prepared.before.mediaInputs.filter((input) => !prepared.result.candidateDraft.mediaInputs.some((next) => next.versionId === input.versionId && next.role === input.role)).map((input) => <div className="flex flex-col gap-1 rounded-lg border p-2" key={`${input.role}-${input.versionId}`}><p className="text-sm">{prepared.result.snapshots.flatMap((snapshot) => snapshot.look?.views ?? []).find((view) => view.versionId === input.versionId)?.purpose ?? t(input.role === "AUDIO_REFERENCE" ? "character.audioReference" : "character.imageReference")}</p><CharacterReferencePreview projectId={projectId} versionId={input.versionId} kind={input.role === "AUDIO_REFERENCE" ? "AUDIO" : input.role === "VIDEO_REFERENCE" ? "VIDEO" : "IMAGE"} /></div>)}</FieldSet>
    </FieldSet> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}<Button type="button" variant="outline" disabled={busy || readOnly} onClick={() => { void refreshForRetry(); }}>{t("character.refreshKeepDraft")}</Button><p>{t("character.refreshKeepDraftHint")}</p></AlertDescription></Alert> : null}
    {notice ? <p role="status">{notice}</p> : null}
    {managerOpen ? <CharacterManagerDialog projectId={projectId} onClose={() => setManagerOpen(false)} /> : null}
  </Dialog>;
}
