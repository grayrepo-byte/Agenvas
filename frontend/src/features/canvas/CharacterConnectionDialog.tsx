import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useEffectEvent, useId, useRef, useState } from "react";
import {
  ApiError,
  CHARACTER_CLEAR_MODES,
  getMediaDraft,
  listCharacters,
  type CanvasItem,
  type MediaDraft,
} from "../../shared/api/client";
import {
  applyCharacterNodeBindings,
  connectCharacterNode,
  getCharacterApplicationReceipt,
  getCharacterConnectionReceipt,
  previewCharacterNodeBindings,
  type ApplyCharacterNodeBindings,
  type CharacterNodeBindingPreview,
  type ConnectCharacterNode,
  type PreviewCharacterNodeBindings,
} from "../../shared/api/characterNodes";
import { t, useLocale, type MessageKey } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel, FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { CharacterReferencePreview } from "../characters/CharacterReferencePicker";
import { flushCanvasEditors } from "./canvasEditingGuards";
import { useCharacterNodeRecovery } from "./characterNodeRecovery";
import { characterRequestUncertain } from "./CharacterCanvasCard";

const CHANGE_LABELS: Readonly<Record<string, MessageKey>> = {
  REFERENCES_ADDED: "character.referencesAdded",
  REFERENCES_REMOVED: "character.referencesRemoved",
  PROMPT_UPDATED: "character.promptUpdated",
  INPUT_MODE_CHANGED: "character.modeChanged",
  SPEAKER_CHANGED: "character.speakerChanged",
  KEEP_INPUTS: "character.inputsKept",
  MANUAL_PROMPT_PRESERVED: "character.manualPromptPreserved",
  MANUAL_REFERENCES_RETAINED: "character.manualReferencesRetained",
  CONNECTED_REFERENCES_RETAINED: "character.connectedReferencesRetained",
  SPEAKER_NOT_APPLIED: "character.speakerNotApplied",
  PROFILE_CHANGED: "character.profileChanged",
  DRAFT_CHANGED: "character.draftChanged",
  RESULT_CHANGED: "character.resultChanged",
  NOT_REVIEWED: "character.notReviewed",
  CHARACTER_ARCHIVED: "character.archivedHint",
};
function changeLabel(value: string) {
  const key = CHANGE_LABELS[value];
  return key ? t(key) : value;
}

type Prepared = { result: CharacterNodeBindingPreview; before: MediaDraft; request: ApplyCharacterNodeBindings };
/** Topology and fixed-selection changes are revalidated server-side when the user explicitly applies. */
export function CharacterConnectionDialog({
  projectId,
  source,
  target,
  readOnly,
  readOnlyHint,
  onClose,
}: {
  projectId: string;
  source?: CanvasItem;
  target: CanvasItem;
  readOnly: boolean;
  readOnlyHint?: string;
  onClose: () => void;
}) {
  useLocale();
  const client = useQueryClient();
  const id = useId();
  const recoveryKey = `${projectId}:${target.id}`;
  const restored = useRef(useCharacterNodeRecovery.getState().pending[recoveryKey]);
  const connection = useRef<ConnectCharacterNode | null>(
    restored.current?.kind === "connection"
      ? restored.current.request
      : restored.current
        ? null
        : source?.characterNode && !source.characterNode.connections.some(edge => edge.targetCanvasItemId === target.id)
          ? {
              connectionId: crypto.randomUUID(),
              sourceCanvasItemId: source.id,
              targetCanvasItemId: target.id,
              expectedSourceItemVersion: source.version,
              expectedSourceSelectionVersion: source.characterNode.version,
              expectedTargetItemVersion: target.version,
            }
          : null,
  );
  const connected = useRef(false);
  const started = useRef(false);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [prepared, setPrepared] = useState<Prepared | null>(null);
  const [uncertain, setUncertain] = useState<"connection" | "apply" | null>(restored.current?.kind ?? null);
  const [error, setError] = useState<Error | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const blocked = readOnly || target.locked || source?.locked === true;
  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: ["canvas", projectId] }),
      client.invalidateQueries({ queryKey: ["canvas-connections", projectId] }),
      client.invalidateQueries({ queryKey: ["project-characters", projectId] }),
      client.invalidateQueries({ queryKey: ["media-draft", projectId, target.id] }),
    ]);
  }
  function retainConnection(request: ConnectCharacterNode) {
    useCharacterNodeRecovery.getState().retain(recoveryKey, { kind: "connection", request });
    setUncertain("connection");
  }
  function retainApplication(request: ApplyCharacterNodeBindings) {
    useCharacterNodeRecovery.getState().retain(recoveryKey, { kind: "apply", request });
    setUncertain("apply");
  }
  function acknowledge() {
    useCharacterNodeRecovery.getState().clear(recoveryKey);
    setUncertain(null);
  }
  async function preview() {
    if (busyRef.current || blocked || uncertain) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    setPrepared(null);
    setNotice(null);
    let connecting = false;
    try {
      // Registered editors flush their visible fields first; a failed local save blocks preview.
      await flushCanvasEditors(projectId, [target.id]);
      if (connection.current && !connected.current) {
        connecting = true;
        await connectCharacterNode(projectId, connection.current);
        connected.current = true;
        connecting = false;
        acknowledge();
        await refresh();
      }
      const [before, index] = await Promise.all([getMediaDraft(projectId, target.id), listCharacters(projectId)]);
      const request: PreviewCharacterNodeBindings = {
        expectedBindingVersion: index.bindings.find(binding => binding.canvasItemId === target.id)?.version ?? 0,
        expectedDraftVersion: before.version,
        clearMode: CHARACTER_CLEAR_MODES.REMOVE_OWNED,
      };
      const result = await previewCharacterNodeBindings(projectId, target.id, request);
      setPrepared({
        result,
        before,
        request: { ...request, commandId: crypto.randomUUID(), previewHash: result.previewHash },
      });
    } catch (failure) {
      if (connecting && characterRequestUncertain(failure) && connection.current) retainConnection(connection.current);
      else acknowledge();
      setError(failure instanceof Error ? failure : new Error(t("character.previewFailed")));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  const initialPreview = useEffectEvent(() => {
    if (!restored.current) void preview();
  });
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    initialPreview();
    // Mount captures the exact gesture. A new gesture receives a fresh keyed dialog.
  }, []);
  async function apply() {
    if (busyRef.current || blocked || uncertain || !prepared) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const saved = await applyCharacterNodeBindings(projectId, target.id, prepared.request);
      acknowledge();
      // Replay may return an older receipt. Adopt only the current server draft, never
      // replace local edits with the historical receipt's frozen draft.
      const current = await getMediaDraft(projectId, target.id);
      const latest = current.version >= saved.draft.version ? current : saved.draft;
      await client.cancelQueries({ queryKey: ["media-draft", projectId, target.id], exact: true });
      client.setQueryData(["media-draft", projectId, target.id], latest);
      setPrepared(null);
      setNotice(t("character.appliedDraftOnly"));
      await refresh();
    } catch (failure) {
      if (characterRequestUncertain(failure)) retainApplication(prepared.request);
      else {
        acknowledge();
        setPrepared(null);
      }
      setError(failure instanceof Error ? failure : new Error(t("character.applyFailed")));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  async function reconcile() {
    const pending = useCharacterNodeRecovery.getState().pending[recoveryKey];
    if (busyRef.current || !pending) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      if (pending.kind === "connection") {
        await getCharacterConnectionReceipt(projectId, pending.request.connectionId);
        // A historical receipt does not imply the edge still exists. Refresh topology
        // without recreating it or preparing another application automatically.
        connected.current = true;
        acknowledge();
        setPrepared(null);
        setNotice(t("common.saved"));
      } else {
        const saved = await getCharacterApplicationReceipt(projectId, target.id, pending.request.commandId);
        acknowledge();
        setPrepared(null);
        setNotice(t("character.appliedDraftOnly"));
        // Receipt lookup remains useful if the current target was subsequently deleted.
        // A failed projection read must not turn a confirmed command back into UNKNOWN.
        try {
          const current = await getMediaDraft(projectId, target.id);
          const latest = current.version >= saved.draft.version ? current : saved.draft;
          await client.cancelQueries({ queryKey: ["media-draft", projectId, target.id], exact: true });
          client.setQueryData(["media-draft", projectId, target.id], latest);
        } catch (failure) {
          setError(failure instanceof Error ? failure : new Error(t("character.loadFailed")));
        }
      }
      await refresh();
    } catch (failure) {
      // 404 means no readable receipt yet, not proof that the original request failed.
      // Keep its identity for later reads; closing explicitly retains this recovery.
      setError(failure instanceof Error ? failure : new Error(t("character.loadFailed")));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  const pending = useCharacterNodeRecovery(state => state.pending[recoveryKey]);
  const pendingId = pending?.kind === "connection" ? pending.request.connectionId : pending?.request.commandId;
  const candidate = prepared?.result.characterPreview;
  return (
    <Dialog
      title={t("characterNode.connectPreview")}
      description={t("characterNode.connectHint")}
      busy={busy || uncertain !== null}
      onClose={onClose}
      onSubmit={event => {
        event.preventDefault();
        if (uncertain === "connection") void reconcile();
        else void preview();
      }}
      footer={
        <>
          <Button
            type="submit"
            variant="outline"
            disabled={busy || (blocked && uncertain !== "connection") || uncertain === "apply"}
          >
            {t(uncertain === "connection" ? "characterNode.reconcileAction" : "character.previewApply")}
          </Button>
          <Button
            type="button"
            disabled={
              busy ||
              (blocked && uncertain !== "apply") ||
              (!prepared && uncertain !== "apply") ||
              uncertain === "connection"
            }
            onClick={() => {
              if (uncertain === "apply") void reconcile();
              else void apply();
            }}
          >
            {t(uncertain === "apply" ? "characterNode.reconcileAction" : "character.applyDraft")}
          </Button>
          {uncertain ? (
            <Button type="button" variant="outline" disabled={busy} onClick={onClose}>
              {t("characterNode.pendingExit")}
            </Button>
          ) : null}
        </>
      }
    >
      <p>
        {source?.characterNode?.snapshot.name ?? t("character.title")} → {target.title}
      </p>
      {busy ? <p role="status">{t("common.loading")}</p> : null}
      <Alert>
        <AlertDescription>{t("characterNode.retained")}</AlertDescription>
      </Alert>
      {candidate ? (
        <FieldSet>
          <FieldLegend>{t("character.actualChanges")}</FieldLegend>
          <p>
            {t("characterNode.counts", {
              images: candidate.candidateDraft.mediaInputs.filter(
                input => input.role !== "AUDIO_REFERENCE" && input.role !== "VIDEO_REFERENCE",
              ).length,
              audio: candidate.candidateDraft.mediaInputs.filter(input => input.role === "AUDIO_REFERENCE").length,
            })}
          </p>
          <p>
            {t("character.modePreview", {
              "0": prepared.before.videoInputMode ?? "—",
              "1": candidate.candidateDraft.videoInputMode ?? "—",
            })}
          </p>
          <p>
            {t("character.speakerPreview", {
              "0": prepared.before.parameters.speaker ?? "—",
              "1": candidate.candidateDraft.parameters.speaker ?? "—",
            })}
          </p>
          <ul>
            {candidate.changes.map((change, index) => (
              <li key={`${change}-${index}`}>{changeLabel(change)}</li>
            ))}
          </ul>
          {candidate.warnings.map((warning, index) => (
            <Alert key={`${warning}-${index}`}>
              <AlertDescription>{changeLabel(warning)}</AlertDescription>
            </Alert>
          ))}
          <Field>
            <FieldLabel htmlFor={`${id}-before`}>{t("character.promptBefore")}</FieldLabel>
            <Textarea id={`${id}-before`} readOnly value={prepared.before.prompt} />
          </Field>
          <Field>
            <FieldLabel htmlFor={`${id}-after`}>{t("character.promptAfter")}</FieldLabel>
            <Textarea id={`${id}-after`} readOnly value={candidate.candidateDraft.prompt} />
          </Field>
          <FieldSet>
            <FieldLegend>{t("character.referencesAfter")}</FieldLegend>
            {candidate.candidateDraft.mediaInputs.map(input => (
              <div key={`${input.role}-${input.versionId}`} className="flex flex-col gap-1 rounded-lg border p-2">
                <span>{input.role}</span>
                <CharacterReferencePreview
                  projectId={projectId}
                  versionId={input.versionId}
                  kind={
                    input.role === "AUDIO_REFERENCE" ? "AUDIO" : input.role === "VIDEO_REFERENCE" ? "VIDEO" : "IMAGE"
                  }
                />
              </div>
            ))}
          </FieldSet>
        </FieldSet>
      ) : !busy ? (
        <p>{t("characterNode.previewRequired")}</p>
      ) : null}
      {blocked ? (
        <Alert>
          <AlertDescription>{readOnlyHint ?? t("media.editor.editingLocked")}</AlertDescription>
        </Alert>
      ) : null}
      {uncertain ? (
        <Alert>
          <AlertDescription>
            <p>{t("characterNode.reconcile")}</p>
            <p>{t("characterNode.pendingSaved")}</p>
            {error instanceof ApiError && error.status === 404 ? <p>{t("characterNode.receiptMissing")}</p> : null}
            <code className="break-all">{pendingId}</code>
          </AlertDescription>
        </Alert>
      ) : null}
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      ) : null}
      {notice ? <p role="status">{notice}</p> : null}
    </Dialog>
  );
}
