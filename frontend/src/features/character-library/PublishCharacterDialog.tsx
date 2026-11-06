import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { type CharacterProfile } from "../../shared/api/client";
import { publishProjectCharacter } from "../../shared/api/characterLibrary";
import { useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { CharacterReferencePicker, CharacterReferencePreview } from "../characters/CharacterReferencePicker";
import { cl } from "./characterLibraryMessages";
import { CharacterCommandState, useCharacterLibraryCommand } from "./useCharacterLibraryCommand";
export function PublishCharacterDialog({ projectId, profile, onClose }: { projectId: string; profile: CharacterProfile; onClose: () => void }) {
  useLocale(); const [libraryId] = useState(() => crypto.randomUUID()); const [identityVersions, setIdentityVersions] = useState<string[]>([]); const [picker, setPicker] = useState(false); const client = useQueryClient(); const completed = useRef(false);
  const transfer = useCharacterLibraryCommand((request: Parameters<typeof publishProjectCharacter>[1]) => publishProjectCharacter(libraryId, request));
  useEffect(() => { if (transfer.data?.status === "SUCCEEDED" && !completed.current) { completed.current = true; void client.invalidateQueries({ queryKey: ["character-library"] }); } }, [transfer.data, client]);
  return <Dialog title={cl("publish")} description={cl("publishHint")} busy={transfer.busy || transfer.unresolved} onClose={onClose} onSubmit={(event) => { event.preventDefault(); transfer.start({ commandId: crypto.randomUUID(), expectedVersion: 0, sourceProjectId: projectId, sourceCharacterId: profile.id, sourceCharacterVersion: profile.version, identityVersionIds: identityVersions }); }}
    footer={<Button type="submit" disabled={transfer.frozen || profile.archived}>{cl("publish")}</Button>}>
    <strong>{profile.content.name} · v{profile.version}</strong><p>{profile.content.description}</p>
    <fieldset disabled={transfer.frozen} className="flex flex-col gap-3"><strong>{cl("identity")}</strong>{identityVersions.map((versionId) => <div className="flex flex-col gap-2" key={versionId}><CharacterReferencePreview projectId={projectId} versionId={versionId} kind="IMAGE" /><Button type="button" variant="ghost" onClick={() => setIdentityVersions((current) => current.filter((id) => id !== versionId))}>{cl("remove")}</Button></div>)}<Button type="button" variant="outline" disabled={identityVersions.length >= 14} onClick={() => setPicker(true)}>{cl("addIdentity")}</Button></fieldset>
    <CharacterCommandState command={transfer} success={cl("published")} />
    {picker ? <CharacterReferencePicker projectId={projectId} kind="IMAGE" onClose={() => setPicker(false)} onSelect={(versionId) => setIdentityVersions((current) => current.includes(versionId) ? current : [...current, versionId])} /> : null}
  </Dialog>;
}
