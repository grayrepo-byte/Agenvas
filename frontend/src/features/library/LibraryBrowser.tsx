import { BookmarkSimple,FileText,MusicNotes,Video } from "@phosphor-icons/react";
import { useInfiniteQuery,useMutation,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { libraryThumbnailUrl,listLibraryEntries,updateLibraryEntry,type Artifact,type LibraryCategory,type LibraryEntry,type LibrarySort } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import "./Library.css";
import { CATEGORY_LABELS,KIND_LABELS,MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";

/** One server-filtered browser is shared by the page, canvas drawer and reference picker. */
export function LibraryBrowser({ trash = false, kinds, onPick, disabled = false }: {
  trash?: boolean; kinds?: Artifact["kind"][]; onPick: (entry: LibraryEntry) => void; disabled?: boolean;
}) {
  useLocale();
  const [category, setCategory] = useState<LibraryCategory | undefined>();
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<Artifact["kind"] | undefined>(kinds?.[0]);
  const [favorite, setFavorite] = useState(false);
  const [sort, setSort] = useState<LibrarySort>("SAVED");
  const filters = { trash, category, query, kind, favorite, sort };
  const list = useInfiniteQuery({ queryKey: ["library", filters], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listLibraryEntries({ ...filters, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  const client = useQueryClient();
  const toggleFavorite = useMutation({ mutationFn: (entry: LibraryEntry) => updateLibraryEntry(entry.id, { expectedVersion: entry.version, name: entry.name, category: entry.category, favorite: !entry.favorite }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["library"] }) });
  const counts = list.data?.pages[0]?.categoryCounts ?? {};
  return <section className="library-browser" aria-label={t("资产列表")}>
    <div className="library-categories" role="group" aria-label={t("按分类筛选")}>
      <Button variant="ghost" type="button" aria-pressed={!category} onClick={() => setCategory(undefined)}>{t("全部")}</Button>
      {Object.entries(CATEGORY_LABELS).map(([key, label]) => <Button variant="ghost" key={key} type="button" aria-pressed={category === key}
        onClick={() => setCategory(key as LibraryCategory)}>{label} ({counts[key] ?? 0})</Button>)}
    </div>
    <div className="library-filters"><label>{t("搜索资产")}<Input type="search" maxLength={MAX_LIBRARY_NAME_LENGTH} value={query} placeholder={t("搜索名称")} onChange={(event) => setQuery(event.target.value)} /></label>
      <label>{t("媒体类型")}<Select value={kind ?? ""} onChange={(event) => setKind(event.target.value ? event.target.value as Artifact["kind"] : undefined)}>
        {!kinds ? <option value="">{t("全部类型")}</option> : null}{(kinds ?? Object.keys(KIND_LABELS) as Artifact["kind"][]).map((value) => <option key={value} value={value}>{KIND_LABELS[value]}</option>)}</Select></label>
      <label>{t("排序")}<Select value={sort} onChange={(event) => setSort(event.target.value as LibrarySort)}><option value="SAVED">{t("最近保存")}</option><option value="NAME">{t("名称")}</option><option value="UPDATED">{t("最近修改")}</option></Select></label>
      <Button variant="ghost" type="button" aria-pressed={favorite} onClick={() => setFavorite(!favorite)}><BookmarkSimple size={16} />{t("只看收藏")}</Button>
    </div>
    {toggleFavorite.error ? <p role="alert">{t("{0} 请刷新资产后重试。", { "0": toggleFavorite.error.message })}</p> : null}
    <Button variant="outline" type="button"  disabled={list.isFetching} onClick={() => void list.refetch()}>{t("刷新资产")}</Button>
    {list.isPending ? <p>{t("正在读取资产…")}</p> : null}
    {list.error ? <div role="alert">{list.error.message}<Button variant="ghost" type="button" onClick={() => { if (list.isFetchNextPageError) void list.fetchNextPage(); else void list.refetch(); }}>{t("重试读取资产")}</Button></div> : null}
    {list.data ? <p className="library-result-count">{t("{0} 个匹配资产", { "0": list.data.pages[0]?.total ?? 0 })}</p> : null}
    <div className="library-grid">{list.data?.pages.flatMap((page) => page.items).map((entry) => <article className="library-card" key={entry.id}>
      <Button variant="ghost" type="button" aria-label={t("查看 {0}", { "0": entry.name })} disabled={disabled} onClick={() => onPick(entry)}>
        <div className="library-card-preview">{entry.hasThumbnail ? <img loading="lazy" src={libraryThumbnailUrl(entry.id)} alt="" />
          : entry.kind === "TEXT" ? <p>{String(entry.textContent?.text ?? "").slice(0, 180)}</p> : entry.kind === "AUDIO" ? <MusicNotes size={40} /> : <Video size={40} />}</div>
        <strong>{entry.name}</strong><small>{CATEGORY_LABELS[entry.category]} · {KIND_LABELS[entry.kind]}{entry.durationMs ? t(" · {0} 秒", { "0": (entry.durationMs / 1000).toFixed(1) }) : ""}{entry.favorite ? t(" · 已收藏") : ""}</small>
        <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleDateString(getFormatLocale())}</time>
      </Button>
      <Button variant="ghost" type="button" className="library-favorite" aria-label={`${entry.favorite ? t("取消收藏") : t("收藏")} ${entry.name}`} aria-pressed={entry.favorite}
        disabled={toggleFavorite.isPending || disabled} onClick={() => toggleFavorite.mutate(entry)}><BookmarkSimple size={17} weight={entry.favorite ? "fill" : "regular"} /></Button>
    </article>)}</div>
    {list.data?.pages[0]?.total === 0 ? <div className="library-empty"><FileText size={32} /><p>{trash ? t("回收站为空") : query || favorite ? t("没有匹配的资产") : t("还没有{0}资产", { "0": category ? CATEGORY_LABELS[category] : "" })}</p><p>{t("在画布选中已完成的结果，点击“保存为资产”。")}</p></div> : null}
    {list.hasNextPage ? <Button variant="outline"  type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? t("读取中…") : t("加载更多资产")}</Button> : null}
  </section>;
}
