import { useInfiniteQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { listProjects } from "../../shared/api/client";
import { archiveCharacterMedia, uploadCharacterMedia, type LibraryMediaSource } from "../../shared/api/characterLibrary";
import { t, useLocale } from "../../shared/i18n";
import { isAudioFile, isVideoFile, MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../shared/ui/primitives/tabs";
import { CharacterReferencePicker } from "../characters/CharacterReferencePicker";
import { LibraryBrowser } from "../library/LibraryBrowser";
import { cl } from "./characterLibraryMessages";
import { CharacterCommandState, useCharacterLibraryCommand } from "./useCharacterLibraryCommand";
type MediaRequest = { commandId: string; kind: "IMAGE" | "AUDIO"; file: File } | LibraryMediaSource;
/** Each choice archives independently before returning a library-owned media identity. */
export function CharacterLibraryMediaPicker({ kind, onSelect, onClose }: { kind: "IMAGE" | "AUDIO"; onSelect: (id: string) => void; onClose: () => void }) {
  useLocale(); const id = useId(); const [file, setFile] = useState<File | null>(null); const [project, setProject] = useState(""); const [canvas, setCanvas] = useState(false);
  const projects = useInfiniteQuery({ queryKey: ["projects", "character-library-sources"], queryFn: ({ pageParam }) => listProjects({ cursor: pageParam }), initialPageParam: undefined as string | undefined, getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  const transfer = useCharacterLibraryCommand<MediaRequest>((input) => "file" in input ? uploadCharacterMedia(input) : archiveCharacterMedia(input));
  const mediaId = transfer.data?.status === "SUCCEEDED" && typeof transfer.data.result?.mediaId === "string" ? transfer.data.result.mediaId : null;
  const frozen = transfer.frozen;
  const validFile = file && !isVideoFile(file) && (kind === "AUDIO" ? isAudioFile(file) : !isAudioFile(file) && (file.type.startsWith("image/") || /\.(png|jpe?g|webp|gif)$/i.test(file.name)));
  return <Dialog title={cl("chooseMedia")} description={cl("identityHint")} busy={transfer.busy || transfer.unresolved} onClose={onClose} onSubmit={(event) => event.preventDefault()}
    footer={mediaId ? <Button type="button" onClick={() => { onSelect(mediaId); onClose(); }}>{cl("select")}</Button> : undefined}>
    <CharacterCommandState command={transfer} />
    <Tabs defaultValue="LOCAL"><TabsList><TabsTrigger value="LOCAL">{cl("local")}</TabsTrigger><TabsTrigger value="ASSETS">{cl("assets")}</TabsTrigger><TabsTrigger value="CANVAS">{cl("canvas")}</TabsTrigger></TabsList>
      <TabsContent value="LOCAL"><FieldGroup><Field data-invalid={Boolean(file && !validFile)}><FieldLabel htmlFor={`${id}-file`}>{cl("file")}</FieldLabel><Input id={`${id}-file`} type="file" aria-invalid={Boolean(file && !validFile)} accept={MEDIA_FILE_ACCEPT[kind]} disabled={frozen} onChange={(event) => setFile(event.target.files?.[0] ?? null)} />{file && !validFile ? <FieldDescription>{cl("invalidMedia")}</FieldDescription> : null}</Field></FieldGroup><Button type="button" variant="outline" disabled={!validFile || frozen} onClick={() => { if (file && validFile) transfer.start({ commandId: crypto.randomUUID(), file, kind }); }}>{cl("upload")}</Button></TabsContent>
      <TabsContent value="ASSETS"><LibraryBrowser kinds={[kind]} disabled={frozen} onPick={(entry) => { if (entry.kind === kind) transfer.start({ commandId: crypto.randomUUID(), sourceType: "LIBRARY_ENTRY", entryId: entry.id, expectedEntryVersion: entry.version, projectId: null, versionId: null }); }} /></TabsContent>
      <TabsContent value="CANVAS"><FieldGroup><Field><FieldLabel htmlFor={`${id}-project`}>{cl("project")}</FieldLabel><Select id={`${id}-project`} disabled={frozen} value={project} onChange={(event) => setProject(event.target.value)}><option value="">{cl("chooseProject")}</option>{projects.data?.pages.flatMap((page) => page.items).map((entry) => <option key={entry.id} value={entry.id}>{entry.name}</option>)}</Select></Field></FieldGroup>
        {projects.error ? <Alert variant="destructive"><AlertDescription>{projects.error.message}<Button type="button" variant="outline" onClick={() => { void projects.refetch(); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
        {projects.hasNextPage ? <Button type="button" variant="ghost" disabled={projects.isFetchingNextPage} onClick={() => { void projects.fetchNextPage(); }}>{cl("loadMore")}</Button> : null}
        <Button type="button" variant="outline" disabled={!project || frozen} onClick={() => setCanvas(true)}>{cl("canvas")}</Button>
      </TabsContent>
    </Tabs>
    {transfer.data?.status === "FAILED" ? <Button type="button" variant="ghost" disabled={transfer.unresolved} onClick={transfer.reset}>{cl("startAnother")}</Button> : null}
    {canvas ? <CharacterReferencePicker projectId={project} kind={kind} onClose={() => setCanvas(false)} onSelect={(versionId) => transfer.start({ commandId: crypto.randomUUID(), sourceType: "PROJECT_VERSION", entryId: null, expectedEntryVersion: null, projectId: project, versionId })} /> : null}
  </Dialog>;
}
