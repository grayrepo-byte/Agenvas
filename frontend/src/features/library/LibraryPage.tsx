import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useInfiniteQuery,useMutation,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import {
deleteLibraryEntry,getLibraryEntry,importLibraryEntry,libraryContentUrl,listProjects,setLibraryTrash,updateLibraryEntry,uploadLibraryEntry,
type ImportLibraryRequest,type LibraryCategory,type LibraryEntry
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { isAudioFile,MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Dialog } from "../../shared/ui/Dialog";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { LibraryBrowser } from "./LibraryBrowser";
import { CATEGORY_LABELS,KIND_LABELS,MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { ResourceBrowser } from "./ResourceBrowser";

const DEFAULT_LIBRARY_DROP_COORDINATE = 80;

export function LibraryPage() {
  useLocale();
  const [selected, setSelected] = useState<LibraryEntry | null>(null);
  const [upload, setUpload] = useState(false);
  return <PageShell title={t("common.asset")} description={t("library.page.description")} actions={<Button variant="default"  type="button" onClick={() => setUpload(true)}>{t("library.page.upload")}</Button>}>
    <Tabs defaultValue="LIBRARY">
      <div className="library-navigation">
        <TabsList aria-label={t("resources.navigation")}>
          <TabsTrigger value="LIBRARY">{t("library.title")}</TabsTrigger>
          <TabsTrigger value="RESOURCES">{t("resources.title")}</TabsTrigger>
          <TabsTrigger value="TRASH">{t("library.page.trash")}</TabsTrigger>
        </TabsList>
        <Button asChild variant="ghost"><Link to="/projects">{t("library.page.openCanvas")}</Link></Button>
      </div>
      <TabsContent value="LIBRARY"><LibraryBrowser mediaOnly onPick={setSelected} /></TabsContent>
      <TabsContent value="RESOURCES"><ResourceBrowser /></TabsContent>
      <TabsContent value="TRASH"><LibraryBrowser mediaOnly trash onPick={setSelected} /></TabsContent>
    </Tabs>
    {selected ? <LibraryDetail key={selected.id} entry={selected} onClose={() => setSelected(null)} /> : null}
    {upload ? <UploadWindow onClose={() => setUpload(false)} /> : null}
  </PageShell>;
}
function LibraryDetail({ entry: initial, onClose }: { entry: LibraryEntry; onClose: () => void }) {
  useLocale();
  const [entry, setEntry] = useState(initial);
  const [name, setName] = useState(entry.name);
  const [category, setCategory] = useState(entry.category);
  const [favorite, setFavorite] = useState(entry.favorite);
  const [project, setProject] = useState("");
  const [confirmDelete, setConfirmDelete] = useState(false);
  const client = useQueryClient();
  const projects = useInfiniteQuery({ queryKey: ["projects", "library-target"], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listProjects({ cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined, enabled: !entry.trashedAt });
  const change = useMutation({ mutationFn: async (action: "SAVE" | "TRASH" | "RESTORE" | "DELETE" | "REFRESH"): Promise<LibraryEntry | void> => {
    if (action === "REFRESH") return getLibraryEntry(entry.id);
    if (action === "SAVE") return updateLibraryEntry(entry.id, { expectedVersion: entry.version, name: name.trim(), category, favorite });
    if (action === "DELETE") return deleteLibraryEntry(entry.id, entry.version);
    return setLibraryTrash(entry.id, entry.version, action === "RESTORE");
  }, onSuccess: (saved, action) => {
    void client.invalidateQueries({ queryKey: ["library"] });
    if (action === "DELETE" || action === "TRASH" || action === "RESTORE") onClose();
    else if (saved) { setEntry(saved); if (action === "REFRESH") { setName(saved.name); setCategory(saved.category); setFavorite(saved.favorite); } }
  } });
  const transfer = useLibraryTransfer<Omit<ImportLibraryRequest, "commandKey">>((request) => importLibraryEntry(project, request));
  const busy = change.isPending || transfer.working;
  return <Dialog title={entry.name} description={`${CATEGORY_LABELS[entry.category]} · ${KIND_LABELS[entry.kind]}`} onClose={onClose} busy={busy} onSubmit={(event) => { event.preventDefault(); change.mutate("SAVE"); }}
    footer={<><Button variant="outline"  type="button" disabled={busy} onClick={onClose}>{t("common.close")}</Button><Button variant="default"  type="submit" disabled={busy || !name.trim()}>{t("library.page.saveInfo")}</Button></>}>
    {entry.kind === "TEXT" ? <p className="library-text-preview">{String(entry.textContent?.text ?? "")}</p> : entry.kind === "IMAGE" ? <img className="library-save-preview" src={libraryContentUrl(entry.id)} alt={entry.name} /> : entry.kind === "AUDIO" ? <audio controls preload="none" src={libraryContentUrl(entry.id)} /> : <video className="library-save-preview" controls preload="metadata" src={libraryContentUrl(entry.id)} />}
    <Field><FieldLabel className="field block">{t("library.page.assetName")}<Input required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={busy} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("library.page.category")}<Select value={category} disabled={busy} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></FieldLabel></Field>
    <label><Checkbox  checked={favorite} disabled={busy} onCheckedChange={(event) => setFavorite(event === true)} />{t("library.page.favorite")}</label>
    <p>{t("library.page.source", { "0": entry.source.origin === "UPLOAD" ? t("library.page.localUpload") : String(entry.source.title ?? t("library.page.canvasResult")) })}</p>
    {change.error ? <div role="alert">{t("common.inputPreserved", { "0": change.error.message })}<Button variant="ghost" type="button" onClick={() => change.mutate("REFRESH")}>{t("library.page.refreshInfo")}</Button></div> : null}
    {entry.trashedAt ? <div className="ui-form-actions"><Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("RESTORE")}>{t("library.page.restore")}</Button><Button variant="ghost" type="button" disabled={busy} onClick={() => setConfirmDelete(true)}>{t("library.page.permanentDelete")}</Button>
      {confirmDelete ? <div role="alert">{t("library.page.permanentDeleteHint")}<Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("DELETE")}>{t("library.page.confirmPermanentDelete")}</Button><Button variant="ghost" type="button" onClick={() => setConfirmDelete(false)}>{t("library.page.cancelDelete")}</Button></div> : null}</div>
      : <><Field><FieldLabel className="field block">{t("library.page.targetProject")}<Select value={project} disabled={busy || transfer.frozen} onChange={(event) => setProject(event.target.value)}><option value="">{t("library.page.chooseProject")}</option>{projects.data?.pages.flatMap((page) => page.items).filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></FieldLabel></Field>
        {projects.error ? <p role="alert">{projects.error.message}<Button variant="ghost" type="button" onClick={() => void projects.refetch()}>{t("library.page.retryProjects")}</Button></p> : null}
        {projects.hasNextPage ? <Button variant="ghost" type="button" disabled={projects.isFetchingNextPage} onClick={() => void projects.fetchNextPage()}>{projects.isFetchingNextPage ? t("common.projectLoading") : t("projects.loadMore")}</Button> : null}
        <div className="ui-form-actions"><Button variant="ghost" type="button" disabled={!project || busy || transfer.data?.status === "SUCCEEDED"} onClick={() => transfer.start({ entryId: entry.id, expectedVersion: entry.version, x: DEFAULT_LIBRARY_DROP_COORDINATE, y: DEFAULT_LIBRARY_DROP_COORDINATE })}>{t("canvas.place")}</Button><Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("TRASH")}>{t("library.page.moveToTrash")}</Button></div>
        <TransferState transfer={transfer} success={t("library.page.placed")} />{transfer.data?.status === "SUCCEEDED" ? <Link to={`/projects/${project}`}>{t("library.page.openProject")}</Link> : null}</>}
  </Dialog>;
}
function UploadWindow({ onClose }: { onClose: () => void }) {
  useLocale();
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState(""); const [category, setCategory] = useState<LibraryCategory>("OTHER");
  const client = useQueryClient();
  const transfer = useLibraryTransfer<Omit<Parameters<typeof uploadLibraryEntry>[0], "commandKey">>(uploadLibraryEntry);
  const done = transfer.data?.status === "SUCCEEDED";
  return <Dialog title={t("library.page.upload")} onClose={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }} busy={transfer.working}
    onSubmit={(event) => { event.preventDefault(); if (file) transfer.start({ file, name: name.trim(), category,
      kind: isAudioFile(file) ? "AUDIO" : file.type === "video/mp4" ? "VIDEO" : "IMAGE" }); }}
    footer={<><Button variant="outline"  type="button" disabled={transfer.working} onClick={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }}>{done ? t("common.done") : t("common.cancel")}</Button>
      <Button variant="default"  type="submit" disabled={!file || !name.trim() || transfer.working || done}>{t("library.page.uploadAndSave")}</Button></>}>
    <p>{t("library.page.uploadLimitsHint")}</p>
    <Field><FieldLabel className="field block">{t("library.page.file")}<Input type="file" required accept={`${MEDIA_FILE_ACCEPT.IMAGE},${MEDIA_FILE_ACCEPT.VIDEO},${MEDIA_FILE_ACCEPT.AUDIO}`} disabled={transfer.frozen} onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); if (!name && selected) setName(selected.name.replace(/\.[^.]+$/, "")); }} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("library.page.assetName")}<Input required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={transfer.frozen} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("library.page.category")}<Select value={category} disabled={transfer.frozen} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></FieldLabel></Field>
    <TransferState transfer={transfer} success={t("library.page.savedCategory", { "0": CATEGORY_LABELS[category] })} />
  </Dialog>;
}
