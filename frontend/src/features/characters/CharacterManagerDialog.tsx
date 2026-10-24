import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { archiveCharacter, listCanvasItems, previewCharacterChange, saveCharacter, type CharacterChangePreview, type CharacterContent, type CharacterLook, type CharacterProfile, type SaveCharacter } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { VoiceLibrary } from "../canvas/VoiceLibrary";
import { VOICES } from "../canvas/voiceCatalog";
import { CharacterReferencePicker, CharacterReferencePreview } from "./CharacterReferencePicker";
import { characterBindingStale, characterQueryOptions } from "./characterQueries";

const emptyContent = (): CharacterContent => ({ name: "", description: "", looks: [], voice: null });
const newLook = (): CharacterLook => ({ id: crypto.randomUUID(), name: "", clothing: "", expression: "", description: "", views: [] });
type Editor = { id: string; version: number; archived: boolean; content: CharacterContent };
type Confirmation = { mode: "SAVE" | "ARCHIVE"; preview: CharacterChangePreview; request: SaveCharacter };

export function CharacterManagerDialog({ projectId, onClose }: { projectId: string; onClose: () => void }) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const index = useQuery(characterQueryOptions(projectId));
  const canvas = useQuery({ queryKey: ["canvas", projectId], queryFn: () => listCanvasItems(projectId), retry: false });
  const [editor, setEditor] = useState<Editor | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [picker, setPicker] = useState<{ kind: "IMAGE"; lookId: string } | { kind: "AUDIO" } | null>(null);
  const [voicesOpen, setVoicesOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const [saved, setSaved] = useState(false);
  function updateContent(change: (content: CharacterContent) => CharacterContent) {
    setEditor((current) => current ? { ...current, content: change(current.content) } : null);
    setConfirmation(null); setSaved(false);
  }
  function updateLook(lookId: string, change: (look: CharacterLook) => CharacterLook) {
    updateContent((content) => ({ ...content, looks: content.looks.map((look) => look.id === lookId ? change(look) : look) }));
  }
  function selectProfile(profile: CharacterProfile) {
    setEditor({ id: profile.id, version: profile.version, archived: profile.archived, content: structuredClone(profile.content) });
    setError(null); setConfirmation(null); setSaved(false);
  }
  async function operation(mode: "SAVE" | "ARCHIVE", confirmed = false) {
    if (!editor || busyRef.current) return;
    busyRef.current = true; setBusy(true); setError(null); setSaved(false);
    try {
      const request = confirmed && confirmation ? confirmation.request : { commandId: crypto.randomUUID(), expectedVersion: editor.version, content: editor.content };
      if (!confirmed) {
        const preview = await previewCharacterChange(projectId, editor.id, request);
        setConfirmation({ mode, preview, request });
        return;
      }
      const profile = mode === "ARCHIVE"
        ? await archiveCharacter(projectId, editor.id, { commandId: request.commandId, expectedVersion: request.expectedVersion })
        : await saveCharacter(projectId, editor.id, request);
      await client.invalidateQueries({ queryKey: characterQueryOptions(projectId).queryKey });
      selectProfile(profile); setSaved(true);
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
      // The editable profile and all exact selections survive authentication and CAS failures.
      setConfirmation(null);
    } finally { busyRef.current = false; setBusy(false); }
  }
  async function refreshVersion() {
    if (busyRef.current || !editor) return;
    busyRef.current = true; setBusy(true);
    try {
      const fresh = await index.refetch({ throwOnError: true });
      const current = fresh.data?.characters.find((profile) => profile.id === editor.id);
      if (current) setEditor((value) => value ? { ...value, version: current.version, archived: current.archived } : value);
      setConfirmation(null); setError(null);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("character.loadFailed"))); }
    finally { busyRef.current = false; setBusy(false); }
  }
  const content = editor?.content;
  const appearances = index.data?.bindings.filter((binding) => binding.snapshots.some((snapshot) => snapshot.characterId === editor?.id)) ?? [];
  return <Dialog title={t("character.title")} description={t("character.description")} busy={busy} onClose={onClose}
    onSubmit={(event) => { event.preventDefault(); void operation("SAVE"); }} footer={editor && !editor.archived ? <>
      <Button type="button" variant="outline" disabled={busy || editor.version === 0} onClick={() => { void operation("ARCHIVE"); }}>{t("character.archive")}</Button>
      <Button type="submit" disabled={busy || !content?.name.trim() || content.looks.some((look) => !look.name.trim() || look.views.some((view) => !view.purpose.trim()))}>{t("character.previewSave")}</Button>
    </> : undefined}>
    <fieldset disabled={busy} className="flex min-w-0 flex-col gap-4">
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={() => { setEditor({ id: crypto.randomUUID(), version: 0, archived: false, content: emptyContent() }); setError(null); setConfirmation(null); setSaved(false); }}>{t("character.create")}</Button>
        {(index.data?.characters ?? []).map((profile) => <Button type="button" key={profile.id} variant={editor?.id === profile.id ? "secondary" : "ghost"} onClick={() => selectProfile(profile)}>{profile.content.name}{profile.archived ? <Badge variant="outline">{t("character.archived")}</Badge> : null}</Button>)}
      </div>
      {index.isPending ? <p role="status">{t("common.loading")}</p> : null}
      {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}<Button type="button" variant="ghost" onClick={() => { void index.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
      {!index.isPending && !index.data?.characters.length && !editor ? <Empty><EmptyHeader><EmptyDescription>{t("character.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
      {editor?.archived ? <Alert><AlertDescription>{t("character.archivedHint")}</AlertDescription></Alert> : null}
      {editor && appearances.length ? <FieldSet><FieldLegend>{t("character.appearances")}</FieldLegend>{appearances.map((binding) => {
        const snapshot = binding.snapshots.find((entry) => entry.characterId === editor.id);
        return <div className="flex flex-wrap items-center gap-2 text-sm" key={binding.canvasItemId}><span>{canvas.data?.items.find((item) => item.id === binding.canvasItemId)?.title ?? binding.canvasItemId} · v{snapshot?.characterVersion}{snapshot?.look ? ` · ${snapshot.look.name}` : ""}</span><Badge variant="outline">{t(characterBindingStale(binding, index.data?.characters ?? []) ? "character.outdated" : binding.needsReview ? "character.needsReview" : "character.reviewedStatus")}</Badge></div>;
      })}</FieldSet> : null}
      {content ? <FieldGroup>
        <Field><FieldLabel htmlFor={`${id}-name`}>{t("character.name")}</FieldLabel><Input id={`${id}-name`} maxLength={160} required disabled={editor?.archived} value={content.name} onChange={(event) => updateContent((value) => ({ ...value, name: event.target.value }))} /></Field>
        <Field><FieldLabel htmlFor={`${id}-description`}>{t("character.characterDescription")}</FieldLabel><Textarea id={`${id}-description`} maxLength={4000} disabled={editor?.archived} value={content.description} onChange={(event) => updateContent((value) => ({ ...value, description: event.target.value }))} /></Field>
        <FieldSet disabled={editor?.archived}><FieldLegend>{t("character.looks")}</FieldLegend><FieldDescription>{t("character.looksHint")}</FieldDescription>
          {content.looks.map((look, lookIndex) => <FieldSet className="rounded-lg border p-3" key={look.id}>
            <FieldLegend>{t("character.lookNumber", { "0": lookIndex + 1 })}</FieldLegend>
            <FieldGroup>
              <Field><FieldLabel htmlFor={`${id}-${look.id}-name`}>{t("character.lookName")}</FieldLabel><Input id={`${id}-${look.id}-name`} required maxLength={160} value={look.name} onChange={(event) => updateLook(look.id, (value) => ({ ...value, name: event.target.value }))} /></Field>
              <Field><FieldLabel htmlFor={`${id}-${look.id}-clothing`}>{t("character.clothing")}</FieldLabel><Input id={`${id}-${look.id}-clothing`} maxLength={2000} value={look.clothing} onChange={(event) => updateLook(look.id, (value) => ({ ...value, clothing: event.target.value }))} /></Field>
              <Field><FieldLabel htmlFor={`${id}-${look.id}-expression`}>{t("character.expression")}</FieldLabel><Input id={`${id}-${look.id}-expression`} maxLength={2000} value={look.expression} onChange={(event) => updateLook(look.id, (value) => ({ ...value, expression: event.target.value }))} /></Field>
              <Field><FieldLabel htmlFor={`${id}-${look.id}-description`}>{t("character.lookDescription")}</FieldLabel><Textarea id={`${id}-${look.id}-description`} maxLength={4000} value={look.description} onChange={(event) => updateLook(look.id, (value) => ({ ...value, description: event.target.value }))} /></Field>
              {look.views.map((view, viewIndex) => <FieldGroup className="rounded-lg border p-2" key={`${view.versionId}-${viewIndex}`}>
                <CharacterReferencePreview projectId={projectId} versionId={view.versionId} kind="IMAGE" />
                <Field><FieldLabel htmlFor={`${id}-${look.id}-${viewIndex}-purpose`}>{t("character.imagePurpose")}</FieldLabel><Input id={`${id}-${look.id}-${viewIndex}-purpose`} required maxLength={120} value={view.purpose} onChange={(event) => updateLook(look.id, (value) => ({ ...value, views: value.views.map((entry, index) => index === viewIndex ? { ...entry, purpose: event.target.value } : entry) }))} /></Field>
                <Field><FieldLabel htmlFor={`${id}-${look.id}-${viewIndex}-description`}>{t("character.referenceDescription")}</FieldLabel><Input id={`${id}-${look.id}-${viewIndex}-description`} maxLength={2000} value={view.description} onChange={(event) => updateLook(look.id, (value) => ({ ...value, views: value.views.map((entry, index) => index === viewIndex ? { ...entry, description: event.target.value } : entry) }))} /></Field>
                <Button type="button" variant="ghost" onClick={() => updateLook(look.id, (value) => ({ ...value, views: value.views.filter((_, index) => index !== viewIndex) }))}>{t("character.removeReference")}</Button>
              </FieldGroup>)}
              <div className="flex flex-wrap gap-2"><Button type="button" variant="outline" disabled={look.views.length >= 14} onClick={() => setPicker({ kind: "IMAGE", lookId: look.id })}>{t("character.addImage")}</Button>
                <Button type="button" variant="ghost" onClick={() => updateContent((value) => ({ ...value, looks: value.looks.filter((entry) => entry.id !== look.id) }))}>{t("character.removeLook")}</Button></div>
            </FieldGroup>
          </FieldSet>)}
          <Button type="button" variant="outline" disabled={content.looks.length >= 20} onClick={() => updateContent((value) => ({ ...value, looks: [...value.looks, newLook()] }))}>{t("character.addLook")}</Button>
        </FieldSet>
        <FieldSet disabled={editor?.archived}><FieldLegend>{t("character.voice")}</FieldLegend>
          {content.voice?.versionId ? <CharacterReferencePreview projectId={projectId} versionId={content.voice.versionId} kind="AUDIO" /> : null}
          <div className="flex flex-wrap gap-2"><Button type="button" variant="outline" onClick={() => setPicker({ kind: "AUDIO" })}>{t("character.chooseAudio")}</Button>
            <Button type="button" variant="outline" onClick={() => setVoicesOpen(true)}>{t("character.chooseSpeaker")}</Button>
            {content.voice ? <Button type="button" variant="ghost" onClick={() => updateContent((value) => ({ ...value, voice: null }))}>{t("character.clearVoice")}</Button> : null}</div>
          {content.voice?.speakerId ? <p className="text-sm">{VOICES.find((voice) => voice.id === content.voice?.speakerId)?.name ?? content.voice.speakerId} · {content.voice.speakerId}</p> : null}
          <Field><FieldLabel htmlFor={`${id}-voice-description`}>{t("character.voiceDescription")}</FieldLabel><Textarea id={`${id}-voice-description`} disabled={!content.voice?.versionId && !content.voice?.speakerId} maxLength={2000} value={content.voice?.description ?? ""} onChange={(event) => updateContent((value) => ({ ...value, voice: { versionId: value.voice?.versionId ?? null, speakerId: value.voice?.speakerId ?? null, description: event.target.value } }))} /></Field>
        </FieldSet>
      </FieldGroup> : null}
    </fieldset>
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}<Button type="button" variant="outline" disabled={busy} onClick={() => { void refreshVersion(); }}>{t("character.refreshKeepDraft")}</Button></AlertDescription></Alert> : null}
    {saved ? <p role="status">{t("character.saved")}</p> : null}
    {confirmation ? <Alert><AlertDescription><div className="flex flex-col gap-2">
      <strong>{t(confirmation.mode === "ARCHIVE" ? "character.archiveImpact" : "character.changeImpact")}</strong>
      {confirmation.preview.affectedItems.length ? <ul>{confirmation.preview.affectedItems.map((item) => <li key={item.canvasItemId}>{item.title} · {item.canvasItemId}</li>)}</ul> : <p>{t("character.noAffectedShots")}</p>}
      <p>{t("character.frozenInputsHint")}</p>
      <div className="flex gap-2"><Button type="button" disabled={busy} onClick={() => { void operation(confirmation.mode, true); }}>{t("character.confirmSave")}</Button><Button type="button" variant="ghost" disabled={busy} onClick={() => setConfirmation(null)}>{t("common.cancel")}</Button></div>
    </div></AlertDescription></Alert> : null}
    {picker ? <CharacterReferencePicker projectId={projectId} kind={picker.kind} onClose={() => setPicker(null)} onSelect={(versionId) => {
      if (picker.kind === "IMAGE") updateLook(picker.lookId, (look) => ({ ...look, views: look.views.some((view) => view.versionId === versionId) ? look.views : [...look.views, { versionId, purpose: "", description: "" }] }));
      else updateContent((value) => ({ ...value, voice: { versionId, speakerId: value.voice?.speakerId ?? null, description: value.voice?.description ?? "" } }));
    }} /> : null}
    {voicesOpen ? <Dialog title={t("character.chooseSpeaker")} onClose={() => setVoicesOpen(false)} onSubmit={(event) => event.preventDefault()}><VoiceLibrary selected={content?.voice?.speakerId ?? ""} containerRef={null} onClose={() => setVoicesOpen(false)} onSelect={(speakerId) => { updateContent((value) => ({ ...value, voice: !speakerId && !value.voice?.versionId ? null : { versionId: value.voice?.versionId ?? null, speakerId: speakerId || null, description: value.voice?.description ?? "" } })); setVoicesOpen(false); }} /></Dialog> : null}
  </Dialog>;
}
