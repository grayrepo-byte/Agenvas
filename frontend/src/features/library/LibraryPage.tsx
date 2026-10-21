import { ArrowCounterClockwise,ArrowUpRight,BookmarkSimple,FloppyDisk,FolderSimple,Images,Trash,UploadSimple } from "../../shared/ui/icons";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { useInfiniteQuery,useMutation,useQueryClient } from "@tanstack/react-query";
import { useId,useState } from "react";
import { Link } from "react-router";
import {
deleteLibraryEntry,getLibraryEntry,importLibraryEntry,libraryContentUrl,listProjects,setLibraryTrash,updateLibraryEntry,uploadLibraryEntry,
type ImportLibraryRequest,type LibraryCategory,type LibraryEntry
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { isAudioFile,MEDIA_FILE_ACCEPT } from "../../shared/mediaFiles";
import { Dialog } from "../../shared/ui/Dialog";
import { PageShell } from "../../shared/ui/PageShell";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { LibraryBrowser } from "./LibraryBrowser";
import { CATEGORY_LABELS,KIND_LABELS,MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { ResourceBrowser } from "./ResourceBrowser";
import { Separator } from "../../shared/ui/primitives/separator";

const DEFAULT_LIBRARY_DROP_COORDINATE = 80;

export function LibraryPage() {
  useLocale();
  const [selected, setSelected] = useState<LibraryEntry | null>(null);
  const [upload, setUpload] = useState(false);
  return <PageShell title={t("common.asset")} description={t("library.page.description")} actions={<Button variant="default" type="button" onClick={() => setUpload(true)}><UploadSimple data-icon="inline-start" aria-hidden />{t("library.page.upload")}</Button>}>
    <div className="library-page">
    <Tabs className="library-workspace" defaultValue="LIBRARY">
      <div className="library-navigation">
        <TabsList variant="line" aria-label={t("resources.navigation")}>
          <TabsTrigger value="LIBRARY"><BookmarkSimple data-icon="inline-start" aria-hidden />{t("library.title")}</TabsTrigger>
          <TabsTrigger value="RESOURCES"><Images data-icon="inline-start" aria-hidden />{t("resources.title")}</TabsTrigger>
          <TabsTrigger value="TRASH"><Trash data-icon="inline-start" aria-hidden />{t("library.page.trash")}</TabsTrigger>
        </TabsList>
        <Button asChild variant="ghost" size="sm"><Link to="/projects">{t("library.page.openCanvas")}<ArrowUpRight data-icon="inline-end" aria-hidden /></Link></Button>
      </div>
      <TabsContent value="LIBRARY"><LibraryBrowser presentation="page" mediaOnly onPick={setSelected} /></TabsContent>
      <TabsContent value="RESOURCES"><ResourceBrowser /></TabsContent>
      <TabsContent value="TRASH"><LibraryBrowser presentation="page" mediaOnly trash onPick={setSelected} /></TabsContent>
    </Tabs>
    {selected ? <LibraryDetail key={selected.id} entry={selected} onClose={() => setSelected(null)} /> : null}
    {upload ? <UploadWindow onClose={() => setUpload(false)} /> : null}
    </div>
  </PageShell>;
}
function LibraryDetail({ entry: initial, onClose }: { entry: LibraryEntry; onClose: () => void }) {
  useLocale();
  const formId = useId();
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
  return <Dialog className="library-detail-dialog" title={entry.name} description={`${CATEGORY_LABELS[entry.category]} · ${KIND_LABELS[entry.kind]}`} onClose={onClose} busy={busy} onSubmit={(event) => { event.preventDefault(); change.mutate("SAVE"); }}
    footer={<><Button variant="outline" type="button" disabled={busy} onClick={onClose}>{t("common.close")}</Button><Button variant="default" type="submit" disabled={busy || !name.trim()}><FloppyDisk data-icon="inline-start" aria-hidden />{t("library.page.saveInfo")}</Button></>}>
    <div className="library-detail-layout">
    <div className="library-detail-preview">
      <div className="library-detail-media">{entry.kind === "TEXT" ? <p className="library-text-preview">{String(entry.textContent?.text ?? "")}</p> : entry.kind === "IMAGE" ? <img className="library-save-preview" src={libraryContentUrl(entry.id)} alt={entry.name} /> : entry.kind === "AUDIO" ? <audio controls preload="none" src={libraryContentUrl(entry.id)} /> : <video className="library-save-preview" controls preload="metadata" src={libraryContentUrl(entry.id)} />}</div>
      <p className="library-detail-source"><FolderSimple size={14} aria-hidden />{t("library.page.source", { "0": entry.source.origin === "UPLOAD" ? t("library.page.localUpload") : String(entry.source.title ?? t("library.page.canvasResult")) })}</p>
    </div>
    <div className="library-detail-sidebar">
    <FieldGroup className="library-detail-fields">
      <Field><FieldLabel htmlFor={`${formId}-name`}>{t("library.page.assetName")}</FieldLabel><Input id={`${formId}-name`} required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={busy} onChange={(event) => setName(event.target.value)} /></Field>
      <Field><FieldLabel htmlFor={`${formId}-category`}>{t("library.page.category")}</FieldLabel><Select id={`${formId}-category`} value={category} disabled={busy} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></Field>
      <Field orientation="horizontal" className="library-detail-favorite"><Checkbox id={`${formId}-favorite`} checked={favorite} disabled={busy} onCheckedChange={(event) => setFavorite(event === true)} /><FieldLabel htmlFor={`${formId}-favorite`}>{t("library.page.favorite")}</FieldLabel></Field>
    </FieldGroup>
    {change.error ? <Notice tone="danger"><p>{t("common.inputPreserved", { "0": change.error.message })}</p><Button variant="outline" type="button" disabled={busy} onClick={() => change.mutate("REFRESH")}>{t("library.page.refreshInfo")}</Button></Notice> : null}
    <Separator />
    {entry.trashedAt ? <div className="library-detail-actions"><div className="ui-form-actions"><Button variant="outline" type="button" disabled={busy} onClick={() => change.mutate("RESTORE")}><ArrowCounterClockwise data-icon="inline-start" aria-hidden />{t("library.page.restore")}</Button><Button variant="ghost" type="button" disabled={busy} onClick={() => setConfirmDelete(true)}>{t("library.page.permanentDelete")}</Button></div>
      {confirmDelete ? <Notice tone="danger"><p>{t("library.page.permanentDeleteHint")}</p><div className="ui-form-actions"><Button variant="destructive" type="button" disabled={busy} onClick={() => change.mutate("DELETE")}>{t("library.page.confirmPermanentDelete")}</Button><Button variant="outline" type="button" onClick={() => setConfirmDelete(false)}>{t("library.page.cancelDelete")}</Button></div></Notice> : null}</div>
      : <div className="library-detail-actions"><FieldGroup><Field><FieldLabel htmlFor={`${formId}-project`}>{t("library.page.targetProject")}</FieldLabel><Select id={`${formId}-project`} value={project} disabled={busy || transfer.frozen} onChange={(event) => setProject(event.target.value)}><option value="">{t("library.page.chooseProject")}</option>{projects.data?.pages.flatMap((page) => page.items).filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></Field></FieldGroup>
        {projects.error ? <Notice tone="danger"><p>{projects.error.message}</p><Button variant="outline" type="button" onClick={() => void projects.refetch()}>{t("library.page.retryProjects")}</Button></Notice> : null}
        {projects.hasNextPage ? <Button variant="ghost" type="button" disabled={projects.isFetchingNextPage} onClick={() => void projects.fetchNextPage()}>{projects.isFetchingNextPage ? t("common.projectLoading") : t("projects.loadMore")}</Button> : null}
        <div className="ui-form-actions"><Button variant="outline" type="button" disabled={!project || busy || transfer.data?.status === "SUCCEEDED"} onClick={() => transfer.start({ entryId: entry.id, expectedVersion: entry.version, x: DEFAULT_LIBRARY_DROP_COORDINATE, y: DEFAULT_LIBRARY_DROP_COORDINATE })}>{t("canvas.place")}<ArrowUpRight data-icon="inline-end" aria-hidden /></Button><Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("TRASH")}><Trash data-icon="inline-start" aria-hidden />{t("library.page.moveToTrash")}</Button></div>
        <TransferState transfer={transfer} success={t("library.page.placed")} />{transfer.data?.status === "SUCCEEDED" ? <Button asChild variant="outline"><Link to={`/projects/${project}`}>{t("library.page.openProject")}<ArrowUpRight data-icon="inline-end" aria-hidden /></Link></Button> : null}</div>}
    </div></div>
  </Dialog>;
}
function UploadWindow({ onClose }: { onClose: () => void }) {
  useLocale();
  const formId = useId();
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState(""); const [category, setCategory] = useState<LibraryCategory>("OTHER");
  const client = useQueryClient();
  const transfer = useLibraryTransfer<Omit<Parameters<typeof uploadLibraryEntry>[0], "commandKey">>(uploadLibraryEntry);
  const done = transfer.data?.status === "SUCCEEDED";
  return <Dialog className="library-upload-dialog" title={t("library.page.upload")} onClose={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }} busy={transfer.working}
    onSubmit={(event) => { event.preventDefault(); if (file) transfer.start({ file, name: name.trim(), category,
      kind: isAudioFile(file) ? "AUDIO" : file.type === "video/mp4" ? "VIDEO" : "IMAGE" }); }}
    footer={<><Button variant="outline"  type="button" disabled={transfer.working} onClick={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }}>{done ? t("common.done") : t("common.cancel")}</Button>
      <Button variant="default"  type="submit" disabled={!file || !name.trim() || transfer.working || done}>{t("library.page.uploadAndSave")}</Button></>}>
    <div className="library-upload-guidance"><UploadSimple size={24} aria-hidden /><p>{t("library.page.uploadLimitsHint")}</p></div>
    <FieldGroup className="library-upload-fields">
      <Field className="library-upload-file"><FieldLabel htmlFor={`${formId}-file`}>{t("library.page.file")}</FieldLabel><Input id={`${formId}-file`} type="file" required accept={`${MEDIA_FILE_ACCEPT.IMAGE},${MEDIA_FILE_ACCEPT.VIDEO},${MEDIA_FILE_ACCEPT.AUDIO}`} disabled={transfer.frozen} onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); if (!name && selected) setName(selected.name.replace(/\.[^.]+$/, "")); }} /></Field>
      <Field><FieldLabel htmlFor={`${formId}-name`}>{t("library.page.assetName")}</FieldLabel><Input id={`${formId}-name`} required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={transfer.frozen} onChange={(event) => setName(event.target.value)} /></Field>
      <Field><FieldLabel htmlFor={`${formId}-category`}>{t("library.page.category")}</FieldLabel><Select id={`${formId}-category`} value={category} disabled={transfer.frozen} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></Field>
    </FieldGroup>
    <TransferState transfer={transfer} success={t("library.page.savedCategory", { "0": CATEGORY_LABELS[category] })} />
  </Dialog>;
}
