import { CopySimple, LockSimple, PencilSimple, X } from "@/shared/ui/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useEffectEvent, useId, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ApiError, listCanvasItems, type Canvas, type CanvasItem, type CharacterProfile } from "../../shared/api/client";
import { createCharacterNode, getCharacterRevision, saveCharacterNodeSelection, type CharacterNodeSelection, type SaveCharacterNodeSelection } from "../../shared/api/characterNodes";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { CharacterReferencePreview } from "../characters/CharacterReferencePicker";
import { characterQueryOptions } from "../characters/characterQueries";
import { useCharacterNodeRecovery, type CharacterPlacementRecovery } from "./characterNodeRecovery";
import { sameCharacterSelection } from "./characterCanvas";
import { ArtifactCardFrame } from "./ArtifactCardFrame";

export const CHARACTER_DRAG_TYPE = "application/agenvas-character";
/** Transport errors leave the original request available for explicit reconciliation. */
export const characterRequestUncertain = (failure: unknown) => !(failure instanceof ApiError) || failure.status >= 500 || failure.status === 408;
export function defaultCharacterSelection(profile: CharacterProfile): CharacterNodeSelection {
  const look = profile.content.looks[0];
  const voiceOnly = !look && profile.content.voice !== null;
  return { characterId: profile.id, characterVersion: profile.version, lookId: look?.id ?? null,
    viewVersionIds: look?.views[0] ? [look.views[0].versionId] : [], useAppearance: !voiceOnly, useVoice: voiceOnly };
}

export function CharacterCanvasCard({ item, projectId, selected, toolbarVisible, children, onEdit, onDuplicate, onRemove, onToggleLocked, onPreviewTarget }: {
  item: CanvasItem; projectId: string; selected: boolean; toolbarVisible: boolean; children: ReactNode;
  onPreviewTarget: (targetId: string) => void; onEdit: () => void; onDuplicate: () => void; onRemove: () => void; onToggleLocked: () => void;
}) {
  useLocale();
  const node = item.characterNode;
  if (!node) return null;
  const snapshot = node.snapshot;
  return <ArtifactCardFrame title={item.title} kindLabel={t("character.title")} selected={selected}
    locked={item.locked} toolbarVisible={toolbarVisible} editableTitle={{ projectId, item }} toolbarStage="draft"
    toolbar={<>
      <Button type="button" variant="ghost" onClick={onEdit}><PencilSimple data-icon="inline-start" />{t("characterNode.fixedSelection")}</Button>
      <Button type="button" variant="ghost" onClick={onDuplicate}><CopySimple data-icon="inline-start" />{t("canvas.edit.duplicate")}</Button>
      <Button type="button" variant="ghost" onClick={onToggleLocked}><LockSimple data-icon="inline-start" />{t(item.locked ? "canvas.card.unlock" : "canvas.card.lock")}</Button>
      <Button type="button" variant="ghost" disabled={item.locked} title={t("characterNode.retained")} onClick={onRemove}><X data-icon="inline-start" />{t("canvas.card.remove")}</Button>
    </>}>
    <div className="flex h-full min-h-0 flex-col gap-2 overflow-auto p-3">
      <div className="flex flex-wrap items-center gap-2"><strong>{snapshot.name}</strong><Badge variant="outline">v{snapshot.characterVersion}</Badge>
        {node.newerVersionAvailable ? <Badge variant="secondary">{t("character.outdated")}</Badge> : null}</div>
      {snapshot.look ? <p className="text-sm">{snapshot.look.name}</p> : null}
      {snapshot.look?.views[0] ? <div className="nodrag nowheel"><CharacterReferencePreview projectId={projectId} versionId={snapshot.look.views[0].versionId} kind="IMAGE" /></div> : <p className="text-sm text-muted-foreground">{snapshot.description}</p>}
      <p className="text-sm">{t("characterNode.counts", { images: snapshot.useAppearance ? snapshot.look?.views.length ?? 0 : 0, audio: snapshot.useVoice && snapshot.voice?.versionId ? 1 : 0 })}</p>
      <p className="text-xs text-muted-foreground">{t("characterNode.selectionHint")}</p>
      {node.connections.map((connection) => <Button key={connection.id} type="button" size="sm" variant="outline" className="nodrag" onClick={() => onPreviewTarget(connection.targetCanvasItemId)}>{t("characterNode.connectPreview")}</Button>)}
    </div>{children}
  </ArtifactCardFrame>;
}

/** Selection drafts live until success; refreshing a CAS never discards the visible choices. */
export function CharacterNodeEditor({ projectId, item, readOnly, onClose }: {
  projectId: string; item: CanvasItem; readOnly: boolean; onClose: () => void;
}) {
  useLocale();
  const node = item.characterNode;
  if (!node) return null;
  return <CharacterNodeEditorForm key={item.id} projectId={projectId} item={item} node={node} readOnly={readOnly} onClose={onClose} />;
}
function CharacterNodeEditorForm({ projectId, item, node, readOnly, onClose }: {
  projectId: string; item: CanvasItem; node: NonNullable<CanvasItem["characterNode"]>; readOnly: boolean; onClose: () => void;
}) {
  const id = useId();
  const client = useQueryClient();
  const index = useQuery(characterQueryOptions(projectId));
  const profile = index.data?.characters.find((entry) => entry.id === node.selection.characterId);
  const [selection, setSelection] = useState(node.selection);
  const needsRevision = profile !== undefined && profile.version !== selection.characterVersion;
  const revision = useQuery({ queryKey: ["project-character-revision", projectId, selection.characterId, selection.characterVersion],
    queryFn: () => getCharacterRevision(projectId, selection.characterId, selection.characterVersion), enabled: needsRevision, retry: false });
  const fixedProfile = needsRevision ? revision.data : profile;
  const [discardOpen, setDiscardOpen] = useState(false);
  const dirty = !sameCharacterSelection(selection, node.selection);
  const requestClose = () => { if (dirty) setDiscardOpen(true); else onClose(); };
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<SaveCharacterNodeSelection | null>(null);
  const blocked = readOnly || item.locked || !index.isSuccess || !profile || profile.archived || !fixedProfile;
  const looks = fixedProfile?.content.looks ?? [];
  const look = looks.find((entry) => entry.id === selection.lookId);
  const change = (patch: Partial<CharacterNodeSelection>) => { setSelection((current) => ({ ...current, ...patch })); setError(null); pending.current = null; };
  async function save() {
    if (busy || blocked && !uncertain) return;
    if (!selection.useAppearance && !selection.useVoice) { setError(new Error(t("characterNode.invalidUse"))); return; }
    pending.current ??= { expectedItemVersion: item.version, expectedSelectionVersion: node.version, selection };
    setBusy(true); setError(null);
    try {
      const request = pending.current;
      // Selection updates have no command receipt. After an uncertain response, read
      // the projection first and acknowledge an already committed identical request.
      const fresh = uncertain ? (await listCanvasItems(projectId)).items.find((entry) => entry.id === item.id) : undefined;
      const committed = fresh?.characterNode && sameCharacterSelection(fresh.characterNode.selection, request.selection) ? fresh.characterNode : null;
      if (!committed && blocked) {
        pending.current = null; setUncertain(false); setError(new Error(t("media.editor.editingLocked"))); return;
      }
      const saved = committed ?? await saveCharacterNodeSelection(projectId, item.id, request);
      client.setQueryData<Canvas>(["canvas", projectId], (current) => current ? { ...current, items: current.items.map((entry) => entry.id === item.id ? { ...entry, characterNode: saved } : entry) } : current);
      pending.current = null; setUncertain(false); onClose();
    } catch (failure) {
      const unknown = characterRequestUncertain(failure); setUncertain(unknown);
      if (!unknown) pending.current = null;
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
    } finally { setBusy(false); }
  }
  return <><Dialog title={t("characterNode.fixedSelection")} description={t("characterNode.selectionHint")} busy={busy || uncertain} onClose={requestClose}
    onSubmit={(event) => { event.preventDefault(); void save(); }} footer={<Button type="submit" disabled={busy || blocked && !uncertain}>{t(uncertain ? "characterNode.reconcileAction" : "character.confirmSave")}</Button>}>
    <p>{node.snapshot.name} · v{selection.characterVersion}</p>
    {index.isPending || needsRevision && revision.isPending ? <p role="status">{t("common.loading")}</p> : null}
    <fieldset disabled={blocked || busy || uncertain} className="flex flex-col gap-4">
      {profile && selection.characterVersion !== profile.version ? <Alert><AlertDescription>{t("character.selectionOutdated")}<Button type="button" variant="outline" onClick={() => change(defaultCharacterSelection(profile))}>{t("character.useLatest")}</Button></AlertDescription></Alert> : null}
      <FieldGroup><Field><FieldLabel htmlFor={`${id}-look`}>{t("character.lookName")}</FieldLabel>
        <Select id={`${id}-look`} value={selection.lookId ?? ""} onChange={(event) => change({ lookId: event.target.value || null, viewVersionIds: [] })}>
          <option value="">{t("character.noLook")}</option>{looks.map((entry) => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
        </Select></Field>
        <Field orientation="horizontal"><Checkbox id={`${id}-appearance`} checked={selection.useAppearance} onCheckedChange={(checked) => change({ useAppearance: checked === true })} /><FieldLabel htmlFor={`${id}-appearance`}>{t("character.useAppearance")}</FieldLabel></Field>
        <Field orientation="horizontal"><Checkbox id={`${id}-voice`} checked={selection.useVoice} disabled={!fixedProfile?.content.voice} onCheckedChange={(checked) => change({ useVoice: checked === true })} /><FieldLabel htmlFor={`${id}-voice`}>{t("character.useVoice")}</FieldLabel></Field>
      </FieldGroup>
      <FieldSet><FieldLegend>{t("characterNode.views")}</FieldLegend><FieldDescription>{t("characterNode.noViews")}</FieldDescription>
        {(look?.views ?? []).map((view) => <Field key={view.versionId} orientation="horizontal"><Checkbox id={`${id}-${view.versionId}`} checked={selection.viewVersionIds.includes(view.versionId)} onCheckedChange={(checked) => change({ viewVersionIds: checked === true ? [...selection.viewVersionIds, view.versionId] : selection.viewVersionIds.filter((value) => value !== view.versionId) })} /><FieldLabel htmlFor={`${id}-${view.versionId}`}>{view.purpose}<CharacterReferencePreview projectId={projectId} versionId={view.versionId} kind="IMAGE" /></FieldLabel></Field>)}
      </FieldSet>
    </fieldset>
    {blocked ? <Alert><AlertDescription>{t(profile?.archived ? "characterNode.archived" : "media.editor.editingLocked")}</AlertDescription></Alert> : null}
    {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}</AlertDescription></Alert> : null}
    {needsRevision && revision.error ? <Alert variant="destructive"><AlertDescription>{revision.error.message}<Button type="button" variant="outline" onClick={() => { void revision.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}{uncertain ? <p>{t("characterNode.reconcile")}</p> : <Button type="button" variant="outline" disabled={busy} onClick={() => { void Promise.all([index.refetch(), client.invalidateQueries({ queryKey: ["canvas", projectId] })]); }}>{t("characterNode.refreshSelection")}</Button>}</AlertDescription></Alert> : null}
  </Dialog>{discardOpen ? <Dialog compact title={t("common.unsavedChanges")} description={t("characterNode.discardHint")} onClose={() => setDiscardOpen(false)}
    onSubmit={(event) => event.preventDefault()} footer={<>
      <Button type="button" variant="outline" onClick={() => setDiscardOpen(false)}>{t("common.continueEditing")}</Button>
      <Button type="button" variant="destructive" onClick={onClose}>{t("characterNode.discard")}</Button>
    </>}>{null}</Dialog> : null}</>;
}

export function CharacterNodePlacementDialog({ projectId, point, zIndex, readOnly, onPlaced, onManage, onClose, initialProfileId }: {
  initialProfileId?: string; projectId: string; point: { x: number; y: number }; zIndex: number; readOnly: boolean;
  onPlaced: (item: CanvasItem) => void; onManage: () => void; onClose: () => void;
}) {
  useLocale();
  const index = useQuery(characterQueryOptions(projectId));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const pending = useRef<CharacterPlacementRecovery | null>(useCharacterNodeRecovery.getState().placements[projectId] ?? null);
  const [uncertain, setUncertain] = useState(pending.current !== null);
  async function place(profile?: CharacterProfile) {
    if (busy || readOnly || uncertain || profile?.archived) return;
    if (!pending.current && profile) pending.current = { id: crypto.randomUUID(), request: { selection: defaultCharacterSelection(profile),
      ...point, width: 320, height: 300, zIndex, groupId: null, locked: false } };
    if (!pending.current) return;
    setBusy(true); setError(null);
    try {
      const item = await createCharacterNode(projectId, pending.current.id, pending.current.request);
      pending.current = null; useCharacterNodeRecovery.getState().clearPlacement(projectId); setUncertain(false); onPlaced(item);
    } catch (failure) {
      const unknown = characterRequestUncertain(failure); setUncertain(unknown);
      if (unknown && pending.current) useCharacterNodeRecovery.getState().retainPlacement(projectId, pending.current);
      else { pending.current = null; useCharacterNodeRecovery.getState().clearPlacement(projectId); }
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
    }
    finally { setBusy(false); }
  }
  async function reconcilePlacement() {
    const frozen = pending.current;
    if (busy || !frozen) return;
    setBusy(true); setError(null);
    try {
      // A creation may have committed before another window locks its node or
      // archives the project. Read its original identity; never repeat the PUT.
      const canvas = await listCanvasItems(projectId);
      const item = canvas.items.find((entry) => entry.id === frozen.id && entry.subjectType === "CHARACTER"
        && entry.subjectId === frozen.request.selection.characterId && entry.characterNode
        && sameCharacterSelection(entry.characterNode.selection, frozen.request.selection));
      if (!item) { setError(new Error(t("characterNode.receiptMissing"))); return; }
      useCharacterNodeRecovery.getState().clearPlacement(projectId);
      pending.current = null; setUncertain(false); onPlaced(item);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("character.loadFailed"))); }
    finally { setBusy(false); }
  }
  const started = useRef(false);
  const placeInitial = useEffectEvent(() => {
    const profile = index.data?.characters.find((entry) => entry.id === initialProfileId);
    if (profile) void place(profile);
  });
  useEffect(() => {
    if (!initialProfileId || !index.isSuccess || started.current) return;
    started.current = true;
    placeInitial();
  }, [initialProfileId, index.isSuccess]);
  return <Dialog title={t("characterNode.place")} description={t("characterNode.placeHint")} busy={busy || uncertain} onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    <Button type="button" variant="outline" disabled={busy || uncertain} onClick={onManage}>{t("character.manage")}</Button>
    {index.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}<Button type="button" variant="ghost" onClick={() => { void index.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
    {(index.data?.characters ?? []).filter((profile) => !profile.archived).map((profile) => <div key={profile.id} draggable={!readOnly && !busy && !uncertain}
      onDragStart={(event) => { event.dataTransfer.setData(CHARACTER_DRAG_TYPE, profile.id); event.dataTransfer.effectAllowed = "copy"; window.requestAnimationFrame(onClose); }} className="flex items-center justify-between gap-3 rounded-lg border p-3">
      <span>{profile.content.name} · v{profile.version}</span><Button type="button" variant="outline" disabled={readOnly || busy || uncertain} onClick={() => { void place(profile); }}>{t("canvas.place")}</Button>
    </div>)}
    {index.isSuccess && !index.data.characters.some((profile) => !profile.archived) ? <Empty><EmptyHeader><EmptyDescription>{t("character.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
    {uncertain ? <Alert><AlertDescription><p>{t("characterNode.reconcile")}</p><code className="break-all">{pending.current?.id}</code>
      <Button type="button" variant="outline" disabled={busy} onClick={() => { void reconcilePlacement(); }}>{t("characterNode.reconcileAction")}</Button>
      <Button type="button" variant="outline" disabled={busy} onClick={onClose}>{t("characterNode.pendingExit")}</Button>
    </AlertDescription></Alert> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}</AlertDescription></Alert> : null}
  </Dialog>;
}
