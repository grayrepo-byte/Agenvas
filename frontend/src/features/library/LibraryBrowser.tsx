import { useInfiniteQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { BookmarkSimple, FileText, MusicNotes, Video } from "@phosphor-icons/react";
import { libraryThumbnailUrl, listLibraryEntries, updateLibraryEntry, type Artifact, type LibraryCategory, type LibraryEntry, type LibrarySort } from "../../shared/api/client";
import { Select } from "../../shared/ui/Select";
import { CATEGORY_LABELS, KIND_LABELS, MAX_LIBRARY_NAME_LENGTH } from "./libraryLabels";
import "./Library.css";

/** One server-filtered browser is shared by the page, canvas drawer and reference picker. */
export function LibraryBrowser({ trash = false, kinds, onPick, disabled = false }: {
  trash?: boolean; kinds?: Artifact["kind"][]; onPick: (entry: LibraryEntry) => void; disabled?: boolean;
}) {
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
  return <section className="library-browser" aria-label="资产列表">
    <div className="library-categories" role="group" aria-label="按分类筛选">
      <button type="button" aria-pressed={!category} onClick={() => setCategory(undefined)}>全部</button>
      {Object.entries(CATEGORY_LABELS).map(([key, label]) => <button key={key} type="button" aria-pressed={category === key}
        onClick={() => setCategory(key as LibraryCategory)}>{label} ({counts[key] ?? 0})</button>)}
    </div>
    <div className="library-filters"><label>搜索资产<input type="search" maxLength={MAX_LIBRARY_NAME_LENGTH} value={query} placeholder="搜索名称" onChange={(event) => setQuery(event.target.value)} /></label>
      <label>媒体类型<Select value={kind ?? ""} onChange={(event) => setKind(event.target.value ? event.target.value as Artifact["kind"] : undefined)}>
        {!kinds ? <option value="">全部类型</option> : null}{(kinds ?? Object.keys(KIND_LABELS) as Artifact["kind"][]).map((value) => <option key={value} value={value}>{KIND_LABELS[value]}</option>)}</Select></label>
      <label>排序<Select value={sort} onChange={(event) => setSort(event.target.value as LibrarySort)}><option value="SAVED">最近保存</option><option value="NAME">名称</option><option value="UPDATED">最近修改</option></Select></label>
      <button type="button" aria-pressed={favorite} onClick={() => setFavorite(!favorite)}><BookmarkSimple size={16} />只看收藏</button>
    </div>
    {toggleFavorite.error ? <p role="alert">{toggleFavorite.error.message} 请刷新资产后重试。</p> : null}
    <button type="button" className="secondary-button" disabled={list.isFetching} onClick={() => void list.refetch()}>刷新资产</button>
    {list.isPending ? <p>正在读取资产…</p> : null}
    {list.error ? <div role="alert">{list.error.message}<button type="button" onClick={() => { if (list.isFetchNextPageError) void list.fetchNextPage(); else void list.refetch(); }}>重试读取资产</button></div> : null}
    {list.data ? <p className="library-result-count">{list.data.pages[0]?.total ?? 0} 个匹配资产</p> : null}
    <div className="library-grid">{list.data?.pages.flatMap((page) => page.items).map((entry) => <article className="library-card" key={entry.id}>
      <button type="button" aria-label={`查看 ${entry.name}`} disabled={disabled} onClick={() => onPick(entry)}>
        <div className="library-card-preview">{entry.hasThumbnail ? <img loading="lazy" src={libraryThumbnailUrl(entry.id)} alt="" />
          : entry.kind === "TEXT" ? <p>{String(entry.textContent?.text ?? "").slice(0, 180)}</p> : entry.kind === "AUDIO" ? <MusicNotes size={40} /> : <Video size={40} />}</div>
        <strong>{entry.name}</strong><small>{CATEGORY_LABELS[entry.category]} · {KIND_LABELS[entry.kind]}{entry.durationMs ? ` · ${(entry.durationMs / 1000).toFixed(1)} 秒` : ""}{entry.favorite ? " · 已收藏" : ""}</small>
        <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleDateString()}</time>
      </button>
      <button type="button" className="library-favorite" aria-label={`${entry.favorite ? "取消收藏" : "收藏"} ${entry.name}`} aria-pressed={entry.favorite}
        disabled={toggleFavorite.isPending || disabled} onClick={() => toggleFavorite.mutate(entry)}><BookmarkSimple size={17} weight={entry.favorite ? "fill" : "regular"} /></button>
    </article>)}</div>
    {list.data?.pages[0]?.total === 0 ? <div className="library-empty"><FileText size={32} /><p>{trash ? "回收站为空" : query || favorite ? "没有匹配的资产" : `还没有${category ? CATEGORY_LABELS[category] : ""}资产`}</p><p>在画布选中已完成的结果，点击“保存为资产”。</p></div> : null}
    {list.hasNextPage ? <button className="secondary-button" type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? "读取中…" : "加载更多资产"}</button> : null}
  </section>;
}
