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
export function LibraryBrowser({ trash = false, kinds, onPick, disabled = false, pickAction = "VIEW" }: {
  trash?: boolean; kinds?: Artifact["kind"][]; onPick: (entry: LibraryEntry) => void; disabled?: boolean; pickAction?: "VIEW" | "CANVAS" | "REFERENCE";
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
  return <section className="library-browser" aria-label={t("library.browser.list")}>
    <div className="library-categories" role="group" aria-label={t("library.browser.categoryFilter")}>
      <Button variant="ghost" type="button" aria-pressed={!category} onClick={() => setCategory(undefined)}>{t("common.all")}</Button>
      {Object.entries(CATEGORY_LABELS).map(([key, label]) => <Button variant="ghost" key={key} type="button" aria-pressed={category === key}
        onClick={() => setCategory(key as LibraryCategory)}>{label} ({counts[key] ?? 0})</Button>)}
    </div>
    <div className="library-filters"><label>{t("library.browser.search")}<Input type="search" maxLength={MAX_LIBRARY_NAME_LENGTH} value={query} placeholder={t("library.browser.nameSearch")} onChange={(event) => setQuery(event.target.value)} /></label>
      <label>{t("media.kind")}<Select value={kind ?? ""} onChange={(event) => setKind(event.target.value ? event.target.value as Artifact["kind"] : undefined)}>
        {!kinds ? <option value="">{t("common.allKinds")}</option> : null}{(kinds ?? Object.keys(KIND_LABELS) as Artifact["kind"][]).map((value) => <option key={value} value={value}>{KIND_LABELS[value]}</option>)}</Select></label>
      <label>{t("library.browser.sort")}<Select value={sort} onChange={(event) => setSort(event.target.value as LibrarySort)}><option value="SAVED">{t("library.browser.recentSaved")}</option><option value="NAME">{t("common.name")}</option><option value="UPDATED">{t("library.browser.recentModified")}</option></Select></label>
      <Button variant="ghost" type="button" aria-pressed={favorite} onClick={() => setFavorite(!favorite)}><BookmarkSimple size={16} />{t("library.browser.favoritesOnly")}</Button>
    </div>
    {toggleFavorite.error ? <p role="alert">{t("library.browser.staleAssetsHint", { "0": toggleFavorite.error.message })}</p> : null}
    <Button variant="outline" type="button"  disabled={list.isFetching} onClick={() => void list.refetch()}>{t("library.browser.refresh")}</Button>
    {list.isPending ? <p>{t("library.browser.loading")}</p> : null}
    {list.error ? <div role="alert">{list.error.message}<Button variant="ghost" type="button" onClick={() => { if (list.isFetchNextPageError) void list.fetchNextPage(); else void list.refetch(); }}>{t("library.browser.retry")}</Button></div> : null}
    {list.data ? <p className="library-result-count">{t("library.browser.matchCount", { "0": list.data.pages[0]?.total ?? 0 })}</p> : null}
    <div className="library-grid">{list.data?.pages.flatMap((page) => page.items).map((entry) => <article className="library-card" key={entry.id}>
      <Button variant="ghost" type="button" aria-label={pickAction === "CANVAS" ? t("library.browser.placeNamed", { "0": entry.name }) : pickAction === "REFERENCE" ? t("library.browser.referenceNamed", { "0": entry.name }) : t("library.browser.viewNamed", { "0": entry.name })} disabled={disabled} onClick={() => onPick(entry)}>
        <div className="library-card-preview">{entry.hasThumbnail ? <img loading="lazy" src={libraryThumbnailUrl(entry.id)} alt="" />
          : entry.kind === "TEXT" ? <p>{String(entry.textContent?.text ?? "").slice(0, 180)}</p> : entry.kind === "AUDIO" ? <MusicNotes size={40} /> : <Video size={40} />}</div>
        <strong>{entry.name}</strong><small>{CATEGORY_LABELS[entry.category]} · {KIND_LABELS[entry.kind]}{entry.durationMs ? t("library.browser.durationSuffix", { "0": (entry.durationMs / 1000).toFixed(1) }) : ""}{entry.favorite ? t("library.browser.favoriteSuffix") : ""}</small>
        <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleDateString(getFormatLocale())}</time>
      </Button>
      <Button variant="ghost" type="button" className="library-favorite" aria-label={`${entry.favorite ? t("library.browser.unfavorite") : t("library.browser.favorite")} ${entry.name}`} aria-pressed={entry.favorite}
        disabled={toggleFavorite.isPending || disabled} onClick={() => toggleFavorite.mutate(entry)}><BookmarkSimple size={17} weight={entry.favorite ? "fill" : "regular"} /></Button>
    </article>)}</div>
    {list.data?.pages[0]?.total === 0 ? <div className="library-empty"><FileText size={32} /><p>{trash ? t("library.browser.trashEmpty") : query || favorite ? t("library.browser.emptySearch") : t("library.browser.emptyKind", { "0": category ? CATEGORY_LABELS[category] : "" })}</p><p>{t("library.browser.saveHint")}</p></div> : null}
    {list.hasNextPage ? <Button variant="outline"  type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? t("common.loading") : t("library.browser.loadMore")}</Button> : null}
  </section>;
}
