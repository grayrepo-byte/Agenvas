import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { useInfiniteQuery,useMutation,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import {
deleteLibraryEntry,getLibraryEntry,importLibraryEntry,libraryContentUrl,listProjects,setLibraryTrash,updateLibraryEntry,uploadLibraryEntry,
type ImportLibraryRequest,type LibraryCategory,type LibraryEntry
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { LibraryBrowser } from "./LibraryBrowser";
import { CATEGORY_LABELS,KIND_LABELS,MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import { TransferState,useLibraryTransfer } from "./useLibraryTransfer";

const DEFAULT_LIBRARY_DROP_COORDINATE = 80;

export function LibraryPage() {
  useLocale();
  const [trash, setTrash] = useState(false);
  const [selected, setSelected] = useState<LibraryEntry | null>(null);
  const [upload, setUpload] = useState(false);
  return <PageShell title={t("资产")} description={t("将创作结果按用途整理，在自己的不同项目中复用。")} actions={<Button variant="default"  type="button" onClick={() => setUpload(true)}>{t("上传资产")}</Button>}>
    <div className="library-tabs"><Button variant="ghost" type="button" aria-pressed={!trash} onClick={() => setTrash(false)}>{t("我的资产")}</Button><Button variant="ghost" type="button" aria-pressed={trash} onClick={() => setTrash(true)}>{t("回收站")}</Button><Link to="/projects">{t("去画布创作")}</Link></div>
    <LibraryBrowser trash={trash} onPick={setSelected} />
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
    footer={<><Button variant="outline"  type="button" disabled={busy} onClick={onClose}>{t("关闭")}</Button><Button variant="default"  type="submit" disabled={busy || !name.trim()}>{t("保存信息")}</Button></>}>
    {entry.kind === "TEXT" ? <p className="library-text-preview">{String(entry.textContent?.text ?? "")}</p> : entry.kind === "IMAGE" ? <img className="library-save-preview" src={libraryContentUrl(entry.id)} alt={entry.name} /> : entry.kind === "AUDIO" ? <audio controls preload="none" src={libraryContentUrl(entry.id)} /> : <video className="library-save-preview" controls preload="metadata" src={libraryContentUrl(entry.id)} />}
    <Field><FieldLabel className="field block">{t("资产名称")}<Input required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={busy} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("资产分类")}<Select value={category} disabled={busy} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></FieldLabel></Field>
    <label><Checkbox  checked={favorite} disabled={busy} onCheckedChange={(event) => setFavorite(event === true)} />{t("收藏资产")}</label>
    <p>{t("来源：{0}", { "0": entry.source.origin === "UPLOAD" ? t("本地上传") : String(entry.source.title ?? t("画布结果")) })}</p>
    {change.error ? <div role="alert">{t("{0} 输入已保留。", { "0": change.error.message })}<Button variant="ghost" type="button" onClick={() => change.mutate("REFRESH")}>{t("载入最新信息")}</Button></div> : null}
    {entry.trashedAt ? <div className="ui-form-actions"><Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("RESTORE")}>{t("恢复资产")}</Button><Button variant="ghost" type="button" disabled={busy} onClick={() => setConfirmDelete(true)}>{t("永久删除")}</Button>
      {confirmDelete ? <div role="alert">{t("永久删除后无法恢复。已导入项目中的内容仍会保留。")}<Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("DELETE")}>{t("确认永久删除")}</Button><Button variant="ghost" type="button" onClick={() => setConfirmDelete(false)}>{t("取消删除")}</Button></div> : null}</div>
      : <><Field><FieldLabel className="field block">{t("目标项目")}<Select value={project} disabled={busy || transfer.frozen} onChange={(event) => setProject(event.target.value)}><option value="">{t("选择项目")}</option>{projects.data?.pages.flatMap((page) => page.items).filter((item) => item.status === "ACTIVE").map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</Select></FieldLabel></Field>
        {projects.error ? <p role="alert">{projects.error.message}<Button variant="ghost" type="button" onClick={() => void projects.refetch()}>{t("重试项目列表")}</Button></p> : null}
        {projects.hasNextPage ? <Button variant="ghost" type="button" disabled={projects.isFetchingNextPage} onClick={() => void projects.fetchNextPage()}>{projects.isFetchingNextPage ? t("正在读取项目…") : t("加载更多项目")}</Button> : null}
        <div className="ui-form-actions"><Button variant="ghost" type="button" disabled={!project || busy || transfer.data?.status === "SUCCEEDED"} onClick={() => transfer.start({ entryId: entry.id, expectedVersion: entry.version, x: DEFAULT_LIBRARY_DROP_COORDINATE, y: DEFAULT_LIBRARY_DROP_COORDINATE })}>{t("放到画布")}</Button><Button variant="ghost" type="button" disabled={busy} onClick={() => change.mutate("TRASH")}>{t("移入回收站")}</Button></div>
        <TransferState transfer={transfer} success={t("已放到目标项目画布")} />{transfer.data?.status === "SUCCEEDED" ? <Link to={`/projects/${project}`}>{t("打开目标项目")}</Link> : null}</>}
  </Dialog>;
}
function UploadWindow({ onClose }: { onClose: () => void }) {
  useLocale();
  const [file, setFile] = useState<File | null>(null);
  const [name, setName] = useState(""); const [category, setCategory] = useState<LibraryCategory>("OTHER");
  const client = useQueryClient();
  const transfer = useLibraryTransfer<Omit<Parameters<typeof uploadLibraryEntry>[0], "commandKey">>(uploadLibraryEntry);
  const done = transfer.data?.status === "SUCCEEDED";
  return <Dialog title={t("上传资产")} onClose={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }} busy={transfer.working}
    onSubmit={(event) => { event.preventDefault(); if (file) transfer.start({ file, name: name.trim(), category,
      kind: file.type.startsWith("audio/") ? "AUDIO" : file.type === "video/mp4" ? "VIDEO" : "IMAGE" }); }}
    footer={<><Button variant="outline"  type="button" disabled={transfer.working} onClick={() => { void client.invalidateQueries({ queryKey: ["library"] }); onClose(); }}>{done ? t("完成") : t("取消")}</Button>
      <Button variant="default"  type="submit" disabled={!file || !name.trim() || transfer.working || done}>{t("上传并保存")}</Button></>}>
    <p>{t("图片 PNG/JPEG/WebP：20 MiB、40 MP；音频 MP3/WAV/OGG Opus：50 MiB、10 分钟；MP4 视频：50 MiB、40 MP、60 秒。均按实际解码校验。")}</p>
    <Field><FieldLabel className="field block">{t("资源文件")}<Input type="file" required accept="image/png,image/jpeg,image/webp,audio/mpeg,audio/wav,audio/ogg,video/mp4" disabled={transfer.frozen} onChange={(event) => { const selected = event.target.files?.[0] ?? null; setFile(selected); if (!name && selected) setName(selected.name.replace(/\.[^.]+$/, "")); }} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("资产名称")}<Input required maxLength={MAX_LIBRARY_NAME_LENGTH} value={name} disabled={transfer.frozen} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
    <Field><FieldLabel className="field block">{t("资产分类")}<Select value={category} disabled={transfer.frozen} onChange={(event) => setCategory(event.target.value as LibraryCategory)}>{Object.entries(CATEGORY_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</Select></FieldLabel></Field>
    <TransferState transfer={transfer} success={t("已保存到我的资产 · {0}", { "0": CATEGORY_LABELS[category] })} />
  </Dialog>;
}
