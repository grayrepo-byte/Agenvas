import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { listCharacters } from "../../shared/api/client";
import { getLibraryCharacter, importLibraryCharacter, previewLibraryCharacterImport, type LibraryCharacter, type LibraryTransferPreview, type LibraryTransferRequest } from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { FieldLegend, FieldSet } from "../../shared/ui/primitives/field";
import { ProjectCharacterProfilePreview } from "./ProjectCharacterProfilePreview";
import { Badge } from "../../shared/ui/primitives/badge";
import { Button } from "../../shared/ui/primitives/button";
import { CharacterLibraryProfilePreview } from "./CharacterLibraryProfilePreview";
import { CharacterLibraryBrowser } from "./CharacterLibraryBrowser";
import { cl } from "./characterLibraryMessages";
import { CharacterCommandState, useCharacterLibraryCommand } from "./useCharacterLibraryCommand";
const CHANGE_MESSAGES: Readonly<Record<string, Parameters<typeof cl>[0]>> = { CREATE_PROJECT_CHARACTER: "createChange", UPDATE_PROJECT_CHARACTER: "updateChange", COPY_INDEPENDENT_MEDIA: "copyMediaChange", EXISTING_SHOTS_REQUIRE_REVIEW: "reviewChange" };
/** A fixed preview and explicit confirmation are required for both new imports and manual synchronization. */
export function CharacterLibraryImportDialog({ projectId, source, characterId: targetId, expectedCharacterVersion = 0, onClose, onCompleted }: {
  projectId: string; source?: LibraryCharacter; characterId?: string; expectedCharacterVersion?: number; onClose: () => void; onCompleted?: (characterId: string) => void;
}) {
  useLocale(); const client = useQueryClient(); const [selected, setSelected] = useState(source ?? null); const [newId] = useState(() => crypto.randomUUID());
  const [preview, setPreview] = useState<LibraryTransferPreview | null>(null); const [loading, setLoading] = useState(false); const lock = useRef(false); const completed = useRef<string | null>(null);
  const [error, setError] = useState<Error | null>(null); const [repreview, setRepreview] = useState(false);
  const transfer = useCharacterLibraryCommand((input: LibraryTransferRequest & { commandId: string; previewHash: string }) => importLibraryCharacter(projectId, input));
  const finished = transfer.data?.status === "SUCCEEDED";
  useEffect(() => { if (!finished || !transfer.data || completed.current === transfer.data.id) return; completed.current = transfer.data.id;
    for (const queryKey of [["project-characters", projectId], ["character-library-links", projectId], ["artifacts", projectId], ["canvas", projectId], ["snapshot", projectId]]) void client.invalidateQueries({ queryKey });
    const characterId = transfer.data.result?.characterId;
    if (typeof characterId === "string") onCompleted?.(characterId);
  }, [finished, transfer.data, client, projectId, onCompleted]);
  async function previewNow() {
    if (!selected || lock.current || transfer.busy || transfer.unresolved) return;
    lock.current = true; setLoading(true); setError(null); setPreview(null); transfer.reset();
    try {
      let current = selected; let expected = expectedCharacterVersion;
      if (repreview) {
        current = await getLibraryCharacter(selected.id); setSelected(current);
        if (expected > 0 && targetId) { const index = await listCharacters(projectId); const target = index.characters.find((item) => item.id === targetId); if (!target || target.archived) throw new Error(cl("conflict")); expected = target.version; }
      }
      const result = await previewLibraryCharacterImport(projectId, { libraryId: current.id, libraryVersion: current.version, characterId: targetId ?? newId, expectedCharacterVersion: expected });
      setPreview(result); setRepreview(true);
    } catch (failure) { setError(failure instanceof Error ? failure : new Error(t("character.previewFailed"))); setRepreview(true); }
    finally { lock.current = false; setLoading(false); }
  }
  const busy = loading || transfer.busy;
  const confirmable = preview && (preview.expectedCharacterVersion === 0 || Boolean(preview.currentCharacter)) && !finished && !transfer.error && transfer.data?.status !== "FAILED";
  return <Dialog title={cl("importTitle")} description={cl("description")} busy={busy || transfer.unresolved} onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    {!source && !selected ? <CharacterLibraryBrowser onSelect={(profile) => { setSelected(profile); setPreview(null); }} /> : null}
    {selected ? <div className="flex flex-col gap-3"><strong>{selected.content.name}</strong><Badge variant="outline">{cl("snapshot")} · v{selected.version}</Badge><p className="text-sm">{selected.content.persona}</p>
      <Button type="button" variant="outline" disabled={busy || transfer.unresolved || finished || selected.archived} onClick={() => { void previewNow(); }}>{cl("preview")}</Button>
      {selected.archived ? <Alert><AlertDescription>{cl("archivedHint")}</AlertDescription></Alert> : null}
      {!source && !transfer.frozen ? <Button type="button" variant="ghost" disabled={busy} onClick={() => { setSelected(null); setPreview(null); }}>{cl("startAnother")}</Button> : null}
    </div> : null}
    {loading ? <p role="status">{t("common.loading")}</p> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}</AlertDescription></Alert> : null}
    {preview ? <Alert><AlertDescription><div className="flex flex-col gap-2">{preview.expectedCharacterVersion > 0 ? <p>{cl("overwriteHint")}</p> : null}
      <div className="grid min-w-0 gap-4 lg:grid-cols-2">{preview.currentCharacter ? <FieldSet aria-label={cl("before")}><FieldLegend>{cl("before")}</FieldLegend><ProjectCharacterProfilePreview projectId={projectId} profile={preview.currentCharacter} /></FieldSet> : null}
        <FieldSet aria-label={cl("after")}><FieldLegend>{cl("after")}</FieldLegend><CharacterLibraryProfilePreview content={preview.content} /></FieldSet></div>
      <strong>{cl("changes")}</strong>{preview.changes.map((change, index) => <p key={index}>{cl(CHANGE_MESSAGES[change] ?? "otherChange")}</p>)}<strong>{cl("affected")}</strong>
      {preview.affectedItems.length ? <ul>{preview.affectedItems.map((item) => <li key={item.canvasItemId}>{item.title}</li>)}</ul> : <p>{cl("noAffected")}</p>}<p>{cl("frozen")}</p>
      {confirmable ? <Button type="button" disabled={busy || transfer.frozen} onClick={() => transfer.start({ libraryId: preview.libraryId, libraryVersion: preview.libraryVersion, characterId: preview.characterId, expectedCharacterVersion: preview.expectedCharacterVersion, previewHash: preview.previewHash, commandId: crypto.randomUUID() })}>{cl("confirmImport")}</Button> : null}
    </div></AlertDescription></Alert> : null}
    <CharacterCommandState command={transfer} success={cl("imported")} />
  </Dialog>;
}
