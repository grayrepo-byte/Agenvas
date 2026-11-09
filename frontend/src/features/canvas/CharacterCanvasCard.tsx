import { CopySimple, LockSimple, PencilSimple, X, UserCircle, ImageSquare, SpeakerHigh, LinkSimple, VideoCamera, CheckCircle } from "@/shared/ui/icons";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useEffectEvent, useId, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ApiError, listCanvasItems, type Canvas, type CanvasItem, type CharacterProfile } from "../../shared/api/client";
import { createCharacterNode, getCharacterRevision, saveCharacterNodeSelection, type CharacterNodeSelection, type SaveCharacterNodeSelection } from "../../shared/api/characterNodes";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldDescription, FieldGroup, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { CharacterReferenceImage } from "../characters/CharacterReferencePicker";
import { characterQueryOptions } from "../characters/characterQueries";
import { useCharacterNodeRecovery, type CharacterPlacementRecovery } from "./characterNodeRecovery";
import { sameCharacterSelection } from "./characterCanvas";
import { ArtifactCardFrame } from "./ArtifactCardFrame";
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuTrigger, DropdownMenuLabel } from "../../shared/ui/primitives/dropdown-menu";
import { characterLibraryLinksOptions } from "../character-library/characterLibraryQueries";
import "./CharacterCanvasCard.css";

export const CHARACTER_DRAG_TYPE = "application/agenvas-character";
/** Transport errors leave the original request available for explicit reconciliation. */
export const characterRequestUncertain = (failure: unknown) => !(failure instanceof ApiError) || failure.status >= 500 || failure.status === 408;
export function defaultCharacterSelection(profile: CharacterProfile): CharacterNodeSelection {
  const look = profile.content.looks[0];
  const voiceOnly = !look && profile.content.voice !== null;
  return { characterId: profile.id, characterVersion: profile.version, lookId: look?.id ?? null,
    viewVersionIds: look?.views[0] ? [look.views[0].versionId] : [], useAppearance: !voiceOnly, useVoice: voiceOnly };
}

export function CharacterCanvasCard({ item, projectId, selected, toolbarVisible, children, onEdit, onDuplicate, onRemove, onToggleLocked, onPreviewTarget, onConnectTarget, readOnly = false }: {
  item: CanvasItem; projectId: string; selected: boolean; toolbarVisible: boolean; children: ReactNode;
  onPreviewTarget: (targetId: string) => void; onConnectTarget: (targetId: string) => void; readOnly?: boolean; onEdit: () => void; onDuplicate: () => void; onRemove: () => void; onToggleLocked: () => void;
}) {
  useLocale();
  const targetDescriptionId = useId();
  const targetTrigger = useRef<HTMLButtonElement>(null);
  const [targetPickerOpen, setTargetPickerOpen] = useState(false);
  const queuedAction = useRef<(() => void) | null>(null);
  const chooseAction = (action: () => void) => { queuedAction.current = action; setTargetPickerOpen(false); };
  const canvas = useQuery({ queryKey: ["canvas", projectId], queryFn: () => listCanvasItems(projectId), retry: false });
  const links = useQuery(characterLibraryLinksOptions(projectId));
  const node = item.characterNode;
  if (!node) return null;
  const libraryUpdate = links.data?.items.find((link) => link.characterId === item.subjectId)?.hasUpdate;
  const targets = canvas.data?.items.filter((target) => target.artifact?.kind === "IMAGE" || target.artifact?.kind === "VIDEO") ?? [];
  const snapshot = node.snapshot;
  const portrait = snapshot.look?.views[0];
  const imageCount = snapshot.useAppearance ? snapshot.look?.views.length ?? 0 : 0;
  const audioCount = snapshot.useVoice && snapshot.voice?.versionId ? 1 : 0;
  const targetPicker = <DropdownMenu open={targetPickerOpen} onOpenChange={setTargetPickerOpen}><DropdownMenuTrigger asChild>
    <Button ref={targetTrigger} className="nodrag nowheel" type="button" variant="media-overlay" size="icon-lg" aria-label={t("characterNode.chooseTarget")} title={t("characterNode.chooseTarget")}><LinkSimple aria-hidden="true" /></Button>
  </DropdownMenuTrigger>
    <DropdownMenuContent side={readOnly ? "bottom" : "right"} align="start" sideOffset={12} sticky="always" collisionPadding={12} className="character-node-target-menu" aria-describedby={targetDescriptionId} onCloseAutoFocus={(event) => {
      const action = queuedAction.current;
      if (!action) return;
      // Open dialogs after the menu focus scope closes, with a persistent return target.
      event.preventDefault(); queuedAction.current = null; targetTrigger.current?.focus(); action();
    }}>
      <DropdownMenuLabel>{t("characterNode.chooseTarget")}</DropdownMenuLabel>
      <p id={targetDescriptionId} className="character-node-menu-hint">{t("characterNode.targetHint")}</p>
      <DropdownMenuGroup>{targets.length ? targets.map((target) => {
        const connected = node.connections.some((connection) => connection.targetCanvasItemId === target.id);
        return <DropdownMenuItem key={target.id} disabled={target.locked || item.locked || readOnly} title={target.title} aria-description={connected ? t("characterNode.alreadyConnected") : undefined} onSelect={() => chooseAction(() => connected ? onPreviewTarget(target.id) : onConnectTarget(target.id))}>
          {target.artifact?.kind === "VIDEO" ? <VideoCamera aria-hidden="true" /> : <ImageSquare aria-hidden="true" />}<span className="truncate">{target.title}</span>{connected ? <CheckCircle aria-hidden="true" /> : null}
        </DropdownMenuItem>;
      }) : <DropdownMenuItem disabled>{t("characterNode.noTarget")}</DropdownMenuItem>}</DropdownMenuGroup>
    </DropdownMenuContent>
  </DropdownMenu>;
  return <ArtifactCardFrame title={item.title} kindLabel={t("character.title")} selected={selected}
    locked={item.locked} toolbarVisible={toolbarVisible} editableTitle={{ projectId, item }}
    titleIcon={<UserCircle />} toolbarStageLabel={t("characterNode.referenceSource")} className="character-canvas-card"
    toolbar={<>
      <Button type="button" variant="ghost" onClick={onEdit}><PencilSimple data-icon="inline-start" />{t("characterNode.fixedSelection")}</Button>
      <Button type="button" variant="ghost" disabled={readOnly} onClick={onDuplicate}><CopySimple data-icon="inline-start" />{t("canvas.edit.duplicate")}</Button>
      <Button type="button" variant="ghost" disabled={readOnly} onClick={onToggleLocked}><LockSimple data-icon="inline-start" />{t(item.locked ? "canvas.card.unlock" : "canvas.card.lock")}</Button>
      <Button type="button" variant="ghost" disabled={item.locked || readOnly} title={t("characterNode.retained")} onClick={onRemove}><X data-icon="inline-start" />{t("canvas.card.remove")}</Button>
    </>}>
    <div className="character-node-surface" data-picker-open={targetPickerOpen}>
      {portrait ? <CharacterReferenceImage projectId={projectId} versionId={portrait.versionId} alt={t("characterNode.portraitAlt", { name: snapshot.name })} />
        : <div className="character-reference-fallback" role="img" aria-label={t("characterNode.noPortrait")}><UserCircle aria-hidden="true" /><span>{t(snapshot.useVoice && !snapshot.useAppearance ? "characterNode.voiceOnly" : "characterNode.textOnly")}</span></div>}
      <div className="character-node-edge-actions nodrag nowheel">
        <Button type="button" variant="media-overlay" size="icon-lg" onClick={onEdit} aria-label={t("characterNode.configure")} title={t("characterNode.configure")}><PencilSimple aria-hidden="true" /></Button>
        {targetPicker}
      </div>
      <div className="character-node-caption">
        <div className="character-node-caption-heading"><strong title={snapshot.name}>{snapshot.name}</strong><span title={t("characterNode.fixedVersion", { version: snapshot.characterVersion })}>v{snapshot.characterVersion}</span></div>
        <div className="character-node-caption-meta"><span title={snapshot.look?.name}>{snapshot.look?.name ?? t("characterNode.lookFallback")}</span>
          {node.newerVersionAvailable ? <span>{t("characterNode.updateAvailable")}</span> : libraryUpdate ? <span title={t("characterNode.libraryUpdateHint")}>{t("characterNode.libraryUpdate")}</span> : null}
        </div>
        <p className="character-node-caption-counts"><ImageSquare aria-hidden="true" /><span>{t("characterNode.counts", { images: imageCount, audio: audioCount })}</span>{snapshot.useVoice ? <SpeakerHigh aria-label={t("character.useVoice")} /> : null}{node.connections.length ? <><span aria-hidden="true">·</span><span>{t("characterNode.connections", { count: node.connections.length })}</span></> : null}</p>
      </div>
    </div>{children}
  </ArtifactCardFrame>;
}

/** Selection drafts live until success; refreshing a CAS never discards the visible choices. */
export function CharacterNodeEditor({ projectId, item, readOnly, readOnlyHint, onClose }: {
  projectId: string; item: CanvasItem; readOnly: boolean; readOnlyHint?: string; onClose: () => void;
}) {
  useLocale();
  const node = item.characterNode;
  if (!node) return null;
  return <CharacterNodeEditorForm key={item.id} projectId={projectId} item={item} node={node} readOnly={readOnly} readOnlyHint={readOnlyHint} onClose={onClose} />;
}
function CharacterNodeEditorForm({ projectId, item, node, readOnly, readOnlyHint, onClose }: {
  projectId: string; item: CanvasItem; node: NonNullable<CanvasItem["characterNode"]>; readOnly: boolean; readOnlyHint?: string; onClose: () => void;
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
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const errorAlert = useRef<HTMLDivElement>(null);
  useEffect(() => { if (error) errorAlert.current?.focus(); }, [error]);
  const [uncertain, setUncertain] = useState(false);
  const pending = useRef<SaveCharacterNodeSelection | null>(null);
  const blocked = readOnly || item.locked || !index.isSuccess || !profile || profile.archived || !fixedProfile;
  const looks = fixedProfile?.content.looks ?? [];
  const look = looks.find((entry) => entry.id === selection.lookId);
  const change = (patch: Partial<CharacterNodeSelection>) => { setSelection((current) => ({ ...current, ...patch })); setError(null); pending.current = null; };
  async function save() {
    if (busyRef.current || blocked && !uncertain) return;
    if (!selection.useAppearance && !selection.useVoice) { setError(new Error(t("characterNode.invalidUse"))); return; }
    pending.current ??= { expectedItemVersion: item.version, expectedSelectionVersion: node.version, selection };
    busyRef.current = true; setBusy(true); setError(null);
    try {
      const request = pending.current;
      // Selection updates have no command receipt. After an uncertain response, read
      // the projection first and acknowledge an already committed identical request.
      const fresh = uncertain ? (await listCanvasItems(projectId)).items.find((entry) => entry.id === item.id) : undefined;
      const committed = fresh?.characterNode && sameCharacterSelection(fresh.characterNode.selection, request.selection) ? fresh.characterNode : null;
      if (!committed && blocked) {
        pending.current = null; setUncertain(false); setError(new Error(readOnlyHint ?? t("media.editor.editingLocked"))); return;
      }
      const saved = committed ?? await saveCharacterNodeSelection(projectId, item.id, request);
      client.setQueryData<Canvas>(["canvas", projectId], (current) => current ? { ...current, items: current.items.map((entry) => entry.id === item.id ? { ...entry, characterNode: saved } : entry) } : current);
      pending.current = null; setUncertain(false); onClose();
    } catch (failure) {
      const unknown = characterRequestUncertain(failure); setUncertain(unknown);
      if (!unknown) pending.current = null;
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
    } finally { busyRef.current = false; setBusy(false); }
  }
  return <><Dialog className="character-node-editor" title={t("characterNode.fixedSelection")} description={t("characterNode.selectionHint")} busy={busy || uncertain} onClose={requestClose}
    onSubmit={(event) => { event.preventDefault(); void save(); }} footer={<Button type="submit" disabled={busy || blocked && !uncertain}>{t(uncertain ? "characterNode.reconcileAction" : "character.confirmSave")}</Button>}>
    <p>{fixedProfile?.content.name ?? node.snapshot.name} · v{selection.characterVersion}</p>
    {fixedProfile?.content.description ? <details className="character-node-editor-description"><summary>{t("character.characterDescription")}</summary><p>{fixedProfile.content.description}</p></details> : null}
    {blocked ? <Alert><AlertDescription>{profile?.archived ? t("characterNode.archived") : readOnlyHint ?? t("media.editor.editingLocked")}</AlertDescription></Alert> : null}
    {index.isPending || needsRevision && revision.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {error ? <Alert ref={errorAlert} tabIndex={-1} variant="destructive"><AlertDescription>{error.message}{uncertain ? <p>{t("characterNode.reconcile")}</p> : error instanceof ApiError ? <Button type="button" variant="outline" disabled={busy} onClick={() => { void Promise.all([index.refetch(), client.invalidateQueries({ queryKey: ["canvas", projectId] })]); }}>{t("characterNode.refreshSelection")}</Button> : null}</AlertDescription></Alert> : null}
    <fieldset disabled={blocked || busy || uncertain} className="flex flex-col gap-4">
      {profile && selection.characterVersion !== profile.version ? <Alert><AlertDescription className="character-node-update-prompt"><span>{t("character.selectionOutdated")}</span><Button type="button" variant="outline" onClick={() => change(defaultCharacterSelection(profile))}>{t("character.useLatest")}</Button></AlertDescription></Alert> : null}
      <FieldGroup className="character-node-editor-fields"><Field><FieldLabel htmlFor={`${id}-look`}>{t("character.lookName")}</FieldLabel>
        <Select id={`${id}-look`} value={selection.lookId ?? ""} onChange={(event) => change({ lookId: event.target.value || null, viewVersionIds: [] })}>
          <option value="">{t("character.noLook")}</option>{looks.map((entry) => <option key={entry.id} value={entry.id}>{entry.name}</option>)}
        </Select></Field>
        <div className="character-node-use-options"><Field orientation="horizontal"><Checkbox id={`${id}-appearance`} checked={selection.useAppearance} onCheckedChange={(checked) => change({ useAppearance: checked === true })} /><FieldLabel htmlFor={`${id}-appearance`}>{t("character.useAppearance")}</FieldLabel></Field>
        <Field orientation="horizontal"><Checkbox id={`${id}-voice`} checked={selection.useVoice} disabled={!fixedProfile?.content.voice} onCheckedChange={(checked) => change({ useVoice: checked === true })} /><FieldLabel htmlFor={`${id}-voice`}>{t("character.useVoice")}</FieldLabel></Field></div>
      </FieldGroup>
      <FieldSet><FieldLegend>{t("characterNode.views")}</FieldLegend><FieldDescription>{t("characterNode.noViews")}</FieldDescription>
        {!look?.views.length ? <p className="text-sm text-muted-foreground">{t("characterNode.emptyViews")}</p> : <div className="character-node-view-options">{look.views.map((view) => <Field key={view.versionId} className="character-node-view-option" data-selected={selection.viewVersionIds.includes(view.versionId)}>
          <div className="character-node-view-image"><CharacterReferenceImage projectId={projectId} versionId={view.versionId} alt={view.purpose} /></div>
          <div className="character-node-view-label"><Checkbox id={`${id}-${view.versionId}`} checked={selection.viewVersionIds.includes(view.versionId)} onCheckedChange={(checked) => change({ viewVersionIds: checked === true ? [...selection.viewVersionIds, view.versionId] : selection.viewVersionIds.filter((value) => value !== view.versionId) })} /><FieldLabel htmlFor={`${id}-${view.versionId}`}>{view.purpose}</FieldLabel></div>
          {view.description ? <p className="character-node-view-description">{view.description}</p> : null}
        </Field>)}</div>}
      </FieldSet>
    </fieldset>

    {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}</AlertDescription></Alert> : null}
    {needsRevision && revision.error ? <Alert variant="destructive"><AlertDescription>{revision.error.message}<Button type="button" variant="outline" onClick={() => { void revision.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}

  </Dialog>{discardOpen ? <Dialog compact title={t("common.unsavedChanges")} description={t("characterNode.discardHint")} onClose={() => setDiscardOpen(false)}
    onSubmit={(event) => event.preventDefault()} footer={<>
      <Button type="button" variant="outline" onClick={() => setDiscardOpen(false)}>{t("common.continueEditing")}</Button>
      <Button type="button" variant="destructive" onClick={onClose}>{t("characterNode.discard")}</Button>
    </>}>{null}</Dialog> : null}</>;
}

export function CharacterNodePlacementDialog({ projectId, point, zIndex, readOnly, readOnlyHint, onPlaced, onManage, onClose, initialProfileId }: {
  initialProfileId?: string; projectId: string; point: { x: number; y: number }; zIndex: number; readOnly: boolean; readOnlyHint?: string;
  onPlaced: (item: CanvasItem) => void; onManage: () => void; onClose: () => void;
}) {
  useLocale();
  const index = useQuery(characterQueryOptions(projectId));
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const pending = useRef<CharacterPlacementRecovery | null>(useCharacterNodeRecovery.getState().placements[projectId] ?? null);
  const [uncertain, setUncertain] = useState(pending.current !== null);
  async function place(profile?: CharacterProfile) {
    if (busyRef.current || readOnly || uncertain || profile?.archived) return;
    if (!pending.current && profile) pending.current = { id: crypto.randomUUID(), request: { selection: defaultCharacterSelection(profile),
      ...point, width: 320, height: 300, zIndex, groupId: null, locked: false } };
    if (!pending.current) return;
    busyRef.current = true; setBusy(true); setError(null);
    try {
      const item = await createCharacterNode(projectId, pending.current.id, pending.current.request);
      pending.current = null; useCharacterNodeRecovery.getState().clearPlacement(projectId); setUncertain(false); onPlaced(item);
    } catch (failure) {
      const unknown = characterRequestUncertain(failure); setUncertain(unknown);
      if (unknown && pending.current) useCharacterNodeRecovery.getState().retainPlacement(projectId, pending.current);
      else { pending.current = null; useCharacterNodeRecovery.getState().clearPlacement(projectId); }
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
    }
    finally { busyRef.current = false; setBusy(false); }
  }
  async function reconcilePlacement() {
    const frozen = pending.current;
    if (busyRef.current || !frozen) return;
    busyRef.current = true; setBusy(true); setError(null);
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
    finally { busyRef.current = false; setBusy(false); }
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
  return <Dialog className="character-node-picker" title={t("characterNode.place")} description={t("characterNode.placeHint")} busy={busy || uncertain} onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    <Button type="button" variant="outline" disabled={busy || uncertain} onClick={onManage}>{t("character.manage")}</Button>
    {index.isPending ? <p role="status">{t("common.loading")}</p> : null}
    {index.error ? <Alert variant="destructive"><AlertDescription>{index.error.message}<Button type="button" variant="ghost" onClick={() => { void index.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
    {readOnly && readOnlyHint ? <Alert><AlertDescription>{readOnlyHint}</AlertDescription></Alert> : null}
    {(index.data?.characters ?? []).filter((profile) => !profile.archived).map((profile) => <div key={profile.id} draggable={!readOnly && !busy && !uncertain}
      onDragStart={(event) => { event.dataTransfer.setData(CHARACTER_DRAG_TYPE, profile.id); event.dataTransfer.effectAllowed = "copy"; window.requestAnimationFrame(onClose); }} className="character-node-picker-row">
      <div className="character-node-picker-portrait">{profile.content.looks[0]?.views[0] ? <CharacterReferenceImage projectId={projectId} versionId={profile.content.looks[0].views[0].versionId} alt={t("characterNode.portraitAlt", { name: profile.content.name })} /> : <UserCircle aria-hidden="true" />}</div>
      <div className="character-node-picker-info"><strong>{profile.content.name}</strong><span>{profile.content.looks[0]?.name ?? t(profile.content.voice ? "characterNode.voiceOnly" : "characterNode.textOnly")} · v{profile.version}</span></div><Button type="button" variant="outline" disabled={readOnly || busy || uncertain} onClick={() => { void place(profile); }}>{t("canvas.place")}</Button>
    </div>)}
    {index.isSuccess && !index.data.characters.some((profile) => !profile.archived) ? <Empty><EmptyHeader><EmptyDescription>{t("character.empty")}</EmptyDescription></EmptyHeader></Empty> : null}
    {uncertain ? <Alert><AlertDescription><p>{t("characterNode.reconcile")}</p><code className="break-all">{pending.current?.id}</code>
      <Button type="button" variant="outline" disabled={busy} onClick={() => { void reconcilePlacement(); }}>{t("characterNode.reconcileAction")}</Button>
      <Button type="button" variant="outline" disabled={busy} onClick={onClose}>{t("characterNode.pendingExit")}</Button>
    </AlertDescription></Alert> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}</AlertDescription></Alert> : null}
  </Dialog>;
}
