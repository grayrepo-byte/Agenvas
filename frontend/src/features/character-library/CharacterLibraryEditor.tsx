import { useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import {
  archiveLibraryCharacter,
  characterLibraryMediaUrl,
  getLibraryCharacter,
  saveLibraryCharacter,
  type LibraryCharacter,
  type LibraryCharacterContent,
  type LibraryCharacterLook,
  type LibraryCharacterView,
} from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { VoiceLibrary } from "../canvas/VoiceLibrary";
import { getVoiceDisplayName, VOICES } from "../canvas/voiceCatalog";
import { DiscardCharacterLibraryDialog } from "./DiscardCharacterLibraryDialog";
import { CharacterLibraryMediaPicker } from "./CharacterLibraryMediaPicker";
import { cl } from "./characterLibraryMessages";
import { uncertainFailure } from "./useCharacterLibraryCommand";
const emptyContent = (): LibraryCharacterContent => ({
  name: "",
  persona: "",
  appearance: "",
  identityViews: [],
  looks: [],
  voice: null,
});
const newLook = (): LibraryCharacterLook => ({
  id: crypto.randomUUID(),
  name: "",
  clothing: "",
  expression: "",
  description: "",
  views: [],
});
type SaveIntent =
  | { mode: "SAVE"; commandId: string; expectedVersion: number; content: LibraryCharacterContent }
  | { mode: "ARCHIVE"; commandId: string; expectedVersion: number };
export function CharacterLibraryEditor({ initial, onClose }: { initial?: LibraryCharacter; onClose: () => void }) {
  useLocale();
  const id = useId();
  const [profileId] = useState(() => initial?.id ?? crypto.randomUUID());
  const [version, setVersion] = useState(initial?.version ?? 0);
  const [content, setContent] = useState<LibraryCharacterContent>(() =>
    structuredClone(initial?.content ?? emptyContent()),
  );
  const baseline = useRef(JSON.stringify(initial?.content ?? emptyContent()));
  const [discardOpen, setDiscardOpen] = useState(false);
  const [archived, setArchived] = useState(initial?.archived ?? false);
  const [archiveConfirm, setArchiveConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const intent = useRef<SaveIntent | null>(null);
  const [unknown, setUnknown] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [saved, setSaved] = useState(false);
  const [picker, setPicker] = useState<"IDENTITY" | "AUDIO" | { lookId: string } | null>(null);
  const [voices, setVoices] = useState(false);
  const client = useQueryClient();
  const frozen = busy || unknown || archived;
  const dirty = JSON.stringify(content) !== baseline.current;
  function requestClose() {
    if (busy || unknown) return;
    if (dirty) setDiscardOpen(true);
    else onClose();
  }
  const selectedVoice = VOICES.find(voice => voice.id === content.voice?.speakerId);
  function change(update: (value: LibraryCharacterContent) => LibraryCharacterContent) {
    setContent(update);
    setSaved(false);
    setArchiveConfirm(false);
  }
  function changeLook(lookId: string, update: (value: LibraryCharacterLook) => LibraryCharacterLook) {
    change(current => ({ ...current, looks: current.looks.map(look => (look.id === lookId ? update(look) : look)) }));
  }
  async function submit(mode: "SAVE" | "ARCHIVE") {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    setSaved(false);
    const fixed =
      intent.current ??
      (mode === "SAVE"
        ? { mode, commandId: crypto.randomUUID(), expectedVersion: version, content: structuredClone(content) }
        : { mode, commandId: crypto.randomUUID(), expectedVersion: version });
    intent.current = fixed;
    let acknowledged = false;
    try {
      const result =
        fixed.mode === "SAVE"
          ? await saveLibraryCharacter(profileId, {
              commandId: fixed.commandId,
              expectedVersion: fixed.expectedVersion,
              content: fixed.content,
            })
          : await archiveLibraryCharacter(profileId, {
              commandId: fixed.commandId,
              expectedVersion: fixed.expectedVersion,
            });
      acknowledged = true;
      // A replay can return an older receipt; reconcile with the current server revision.
      const current = await getLibraryCharacter(profileId);
      const latest = current.version > result.version ? current : result;
      baseline.current = JSON.stringify(latest.content);
      setVersion(latest.version);
      setArchived(latest.archived);
      setContent(structuredClone(latest.content));
      setSaved(true);
      setUnknown(false);
      intent.current = null;
      setArchiveConfirm(false);
      void client.invalidateQueries({ queryKey: ["character-library"] });
      void client.invalidateQueries({ queryKey: ["character-library-links"] });
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(t("character.saveFailed")));
      setUnknown(acknowledged || uncertainFailure(failure));
      if (!acknowledged && !uncertainFailure(failure)) intent.current = null;
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function refresh() {
    if (lock.current || unknown) return;
    lock.current = true;
    setBusy(true);
    try {
      const latest = await getLibraryCharacter(profileId);
      setVersion(latest.version);
      setArchived(latest.archived);
      setError(null);
      intent.current = null;
      setArchiveConfirm(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error(t("character.loadFailed")));
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  function viewsEditor(views: LibraryCharacterView[], update: (views: LibraryCharacterView[]) => void, prefix: string) {
    return views.map((view, index) => (
      <FieldGroup className="rounded-lg border p-3" key={`${view.mediaId}-${index}`}>
        <img
          className="size-24 rounded object-contain"
          src={characterLibraryMediaUrl(view.mediaId, true)}
          alt={view.purpose}
        />
        <Field>
          <FieldLabel htmlFor={`${id}-${prefix}-${index}-purpose`}>{cl("purpose")}</FieldLabel>
          <Input
            id={`${id}-${prefix}-${index}-purpose`}
            required
            maxLength={prefix === "identity" ? 101 : 120}
            value={view.purpose}
            onChange={event =>
              update(views.map((item, i) => (i === index ? { ...item, purpose: event.target.value } : item)))
            }
          />
        </Field>
        <Field>
          <FieldLabel htmlFor={`${id}-${prefix}-${index}-description`}>{cl("mediaDescription")}</FieldLabel>
          <Input
            id={`${id}-${prefix}-${index}-description`}
            maxLength={2000}
            value={view.description}
            onChange={event =>
              update(views.map((item, i) => (i === index ? { ...item, description: event.target.value } : item)))
            }
          />
        </Field>
        <Button type="button" variant="ghost" onClick={() => update(views.filter((_, i) => i !== index))}>
          {cl("remove")}
        </Button>
      </FieldGroup>
    ));
  }
  const descriptionLength =
    content.persona.trim().length + (content.appearance.trim() ? 14 + content.appearance.trim().length : 0);
  const identityFull =
    content.identityViews.length >= 14 ||
    content.looks.some(look => new Set([...content.identityViews, ...look.views].map(view => view.mediaId)).size >= 14);
  const invalid =
    descriptionLength > 4000 ||
    !content.name.trim() ||
    content.looks.some(look => !look.name.trim() || look.views.some(view => !view.purpose.trim())) ||
    content.identityViews.some(view => !view.purpose.trim());
  return (
    <Dialog
      title={initial ? cl("edit") : cl("create")}
      description={cl("description")}
      busy={busy || unknown}
      onClose={requestClose}
      onSubmit={event => {
        event.preventDefault();
        if (!frozen) void submit("SAVE");
      }}
      footer={
        !archived ? (
          <>
            <Button
              type="button"
              variant="outline"
              disabled={busy || unknown || version === 0}
              onClick={() => setArchiveConfirm(true)}
            >
              {cl("archive")}
            </Button>
            <Button type="submit" disabled={frozen || invalid}>
              {cl("save")}
            </Button>
          </>
        ) : undefined
      }
    >
      {archived ? (
        <Alert>
          <AlertDescription>{cl("archivedHint")}</AlertDescription>
        </Alert>
      ) : null}
      <fieldset disabled={frozen} className="flex min-w-0 flex-col gap-4">
        <FieldGroup>
          <Field>
            <FieldLabel htmlFor={`${id}-name`}>{t("character.name")}</FieldLabel>
            <Input
              id={`${id}-name`}
              required
              maxLength={160}
              value={content.name}
              onChange={event => change(value => ({ ...value, name: event.target.value }))}
            />
          </Field>
          <Field>
            <FieldLabel htmlFor={`${id}-persona`}>{cl("persona")}</FieldLabel>
            <Textarea
              id={`${id}-persona`}
              maxLength={4000}
              value={content.persona}
              onChange={event => change(value => ({ ...value, persona: event.target.value }))}
            />
          </Field>
          <Field data-invalid={descriptionLength > 4000}>
            <FieldLabel htmlFor={`${id}-appearance`}>{cl("appearance")}</FieldLabel>
            <Textarea
              id={`${id}-appearance`}
              aria-invalid={descriptionLength > 4000}
              maxLength={4000}
              value={content.appearance}
              onChange={event => change(value => ({ ...value, appearance: event.target.value }))}
            />
            <FieldDescription>{cl("textLimit")}</FieldDescription>
          </Field>
          <FieldSet>
            <FieldLegend>{cl("identity")}</FieldLegend>
            <FieldDescription>{cl("identityHint")}</FieldDescription>
            {viewsEditor(
              content.identityViews,
              identityViews => change(value => ({ ...value, identityViews })),
              "identity",
            )}
            <Button type="button" variant="outline" disabled={identityFull} onClick={() => setPicker("IDENTITY")}>
              {cl("addIdentity")}
            </Button>
          </FieldSet>
          <FieldSet>
            <FieldLegend>{t("character.looks")}</FieldLegend>
            {content.looks.map(look => (
              <FieldSet className="rounded-lg border p-3" key={look.id}>
                <FieldLegend>{look.name || cl("look")}</FieldLegend>
                <FieldGroup>
                  <Field>
                    <FieldLabel htmlFor={`${id}-${look.id}-name`}>{t("character.lookName")}</FieldLabel>
                    <Input
                      id={`${id}-${look.id}-name`}
                      required
                      maxLength={160}
                      value={look.name}
                      onChange={event => changeLook(look.id, value => ({ ...value, name: event.target.value }))}
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`${id}-${look.id}-clothing`}>{t("character.clothing")}</FieldLabel>
                    <Input
                      id={`${id}-${look.id}-clothing`}
                      maxLength={2000}
                      value={look.clothing}
                      onChange={event => changeLook(look.id, value => ({ ...value, clothing: event.target.value }))}
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`${id}-${look.id}-expression`}>{t("character.expression")}</FieldLabel>
                    <Input
                      id={`${id}-${look.id}-expression`}
                      maxLength={2000}
                      value={look.expression}
                      onChange={event => changeLook(look.id, value => ({ ...value, expression: event.target.value }))}
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor={`${id}-${look.id}-description`}>{t("character.lookDescription")}</FieldLabel>
                    <Textarea
                      id={`${id}-${look.id}-description`}
                      maxLength={4000}
                      value={look.description}
                      onChange={event => changeLook(look.id, value => ({ ...value, description: event.target.value }))}
                    />
                  </Field>
                  {viewsEditor(look.views, views => changeLook(look.id, value => ({ ...value, views })), look.id)}
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      disabled={new Set([...content.identityViews, ...look.views].map(view => view.mediaId)).size >= 14}
                      onClick={() => setPicker({ lookId: look.id })}
                    >
                      {cl("addImage")}
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      onClick={() =>
                        change(value => ({ ...value, looks: value.looks.filter(entry => entry.id !== look.id) }))
                      }
                    >
                      {t("character.removeLook")}
                    </Button>
                  </div>
                </FieldGroup>
              </FieldSet>
            ))}
            <Button
              type="button"
              variant="outline"
              disabled={content.looks.length >= 20}
              onClick={() => change(value => ({ ...value, looks: [...value.looks, newLook()] }))}
            >
              {cl("addLook")}
            </Button>
          </FieldSet>
          <FieldSet>
            <FieldLegend>{cl("voice")}</FieldLegend>
            {content.voice?.mediaId ? (
              <audio controls preload="none" src={characterLibraryMediaUrl(content.voice.mediaId)} />
            ) : null}
            <div className="flex flex-wrap gap-2">
              <Button type="button" variant="outline" onClick={() => setPicker("AUDIO")}>
                {cl("audio")}
              </Button>
              <Button type="button" variant="outline" onClick={() => setVoices(true)}>
                {cl("speaker")}
              </Button>
              {content.voice ? (
                <Button type="button" variant="ghost" onClick={() => change(value => ({ ...value, voice: null }))}>
                  {cl("clearVoice")}
                </Button>
              ) : null}
            </div>
            {content.voice?.speakerId ? (
              <p>
                {selectedVoice ? getVoiceDisplayName(selectedVoice) : content.voice.speakerId} ·{" "}
                {content.voice.speakerId}
              </p>
            ) : null}
            <Field>
              <FieldLabel htmlFor={`${id}-voice`}>{cl("voiceDescription")}</FieldLabel>
              <Textarea
                id={`${id}-voice`}
                disabled={!content.voice}
                maxLength={2000}
                value={content.voice?.description ?? ""}
                onChange={event =>
                  change(value => ({
                    ...value,
                    voice: value.voice ? { ...value.voice, description: event.target.value } : null,
                  }))
                }
              />
            </Field>
          </FieldSet>
        </FieldGroup>
      </fieldset>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>
            {t("common.inputPreserved", { "0": error.message })}
            {unknown ? (
              <>
                <p>{cl("unknown")}</p>
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    void submit("SAVE");
                  }}
                >
                  {cl("verify")}
                </Button>
              </>
            ) : (
              <>
                <p>{cl("conflict")}</p>
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => {
                    void refresh();
                  }}
                >
                  {cl("refresh")}
                </Button>
              </>
            )}
          </AlertDescription>
        </Alert>
      ) : null}
      {saved ? <p role="status">{cl("saved")}</p> : null}
      {archiveConfirm ? (
        <Alert>
          <AlertDescription>
            {cl("archiveHint")}
            {dirty ? <p>{cl("discardHint")}</p> : null}
            <Button
              type="button"
              variant="destructive"
              disabled={busy || unknown}
              onClick={() => {
                void submit("ARCHIVE");
              }}
            >
              {cl("confirmArchive")}
            </Button>
            <Button type="button" variant="ghost" onClick={() => setArchiveConfirm(false)}>
              {t("common.cancel")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {picker ? (
        <CharacterLibraryMediaPicker
          kind={picker === "AUDIO" ? "AUDIO" : "IMAGE"}
          onClose={() => setPicker(null)}
          onSelect={mediaId => {
            if (picker === "AUDIO")
              change(value => ({
                ...value,
                voice: {
                  mediaId,
                  speakerId: value.voice?.speakerId ?? null,
                  description: value.voice?.description ?? "",
                },
              }));
            else if (picker === "IDENTITY")
              change(value => ({
                ...value,
                identityViews: value.identityViews.some(view => view.mediaId === mediaId)
                  ? value.identityViews
                  : [...value.identityViews, { mediaId, purpose: "", description: "" }],
              }));
            else
              changeLook(picker.lookId, look => ({
                ...look,
                views: look.views.some(view => view.mediaId === mediaId)
                  ? look.views
                  : [...look.views, { mediaId, purpose: "", description: "" }],
              }));
          }}
        />
      ) : null}
      {discardOpen ? (
        <DiscardCharacterLibraryDialog onContinue={() => setDiscardOpen(false)} onDiscard={onClose} />
      ) : null}
      {voices ? (
        <Dialog title={cl("speaker")} onClose={() => setVoices(false)} onSubmit={event => event.preventDefault()}>
          <VoiceLibrary
            selected={content.voice?.speakerId ?? ""}
            containerRef={null}
            onClose={() => setVoices(false)}
            onSelect={speakerId => {
              change(value => ({
                ...value,
                voice:
                  !speakerId && !value.voice?.mediaId
                    ? null
                    : {
                        mediaId: value.voice?.mediaId ?? null,
                        speakerId: speakerId || null,
                        description: value.voice?.description ?? "",
                      },
              }));
              setVoices(false);
            }}
          />
        </Dialog>
      ) : null}
    </Dialog>
  );
}
