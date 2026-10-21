import { ArrowsClockwise,BookmarkSimple,Buildings,Cube,FileText,ImageSquare,Images,MusicNotes,PersonSimple,Video,type Icon } from "@/shared/ui/icons";
import { useInfiniteQuery,useMutation,useQueryClient } from "@tanstack/react-query";
import { useId,useState } from "react";
import { libraryThumbnailUrl,listLibraryEntries,updateLibraryEntry,type Artifact,type LibraryCategory,type LibraryEntry,type LibrarySort } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { cn } from "../../shared/lib/utils";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { ToggleGroup,ToggleGroupItem } from "../../shared/ui/primitives/toggle-group";
import "./Library.css";
import { CATEGORY_LABELS,KIND_LABELS,MAX_LIBRARY_NAME_LENGTH,MEDIA_ASSET_KINDS } from "./libraryLabels";

const CATEGORY_ICONS: Record<LibraryCategory, Icon> = { CHARACTER: PersonSimple, SCENE: Buildings, PROP: Cube, OTHER: Images };
const KIND_ICONS: Record<Artifact["kind"], Icon> = { TEXT: FileText, IMAGE: ImageSquare, VIDEO: Video, AUDIO: MusicNotes };

/** One server-filtered browser is shared by the page, canvas drawer and reference picker. */
export function LibraryBrowser({ trash = false, kinds, onPick, disabled = false, pickAction = "VIEW", mediaOnly = false, presentation = "picker" }: {
  mediaOnly?: boolean; trash?: boolean; kinds?: Artifact["kind"][]; onPick: (entry: LibraryEntry) => void; disabled?: boolean; pickAction?: "VIEW" | "CANVAS" | "REFERENCE";
  /** The standalone page has denser cards; canvas and reference pickers keep their own presentation. */
  presentation?: "page" | "picker";
}) {
  useLocale();
  const filterId = useId();
  const [category, setCategory] = useState<LibraryCategory | undefined>();
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<Artifact["kind"] | undefined>(kinds?.[0]);
  const [favorite, setFavorite] = useState(false);
  const [sort, setSort] = useState<LibrarySort>("SAVED");
  const filters = { trash, category, query, kind, favorite, sort, mediaOnly };
  const availableKinds = mediaOnly ? MEDIA_ASSET_KINDS : kinds ?? Object.keys(KIND_LABELS) as Artifact["kind"][];
  const list = useInfiniteQuery({ queryKey: ["library", filters], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listLibraryEntries({ ...filters, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  const client = useQueryClient();
  const toggleFavorite = useMutation({ mutationFn: (entry: LibraryEntry) => updateLibraryEntry(entry.id, { expectedVersion: entry.version, name: entry.name, category: entry.category, favorite: !entry.favorite }),
    onSuccess: () => client.invalidateQueries({ queryKey: ["library"] }) });
  const counts = list.data?.pages[0]?.categoryCounts ?? {};
  const page = presentation === "page";
  const emptyTitle = trash ? t("library.browser.trashEmpty") : query || favorite ? t("library.browser.emptySearch") : t("library.browser.emptyKind", { "0": category ? CATEGORY_LABELS[category] : "" });
  const retryRead = () => { if (list.isFetchNextPageError) void list.fetchNextPage(); else void list.refetch(); };
  return <section className={cn("library-browser", page && "library-browser--page")} aria-label={t("library.browser.list")}>
    <div className="library-controls">
    <ToggleGroup type="single" variant="outline" spacing={2} className="library-categories" aria-label={t("library.browser.categoryFilter")}
      value={category ?? "ALL"} onValueChange={(value) => { if (value) setCategory(value === "ALL" ? undefined : value as LibraryCategory); }}>
      <ToggleGroupItem value="ALL">{page ? <Images data-icon="inline-start" aria-hidden /> : null}{t("common.all")}</ToggleGroupItem>
      {Object.entries(CATEGORY_LABELS).map(([key, label]) => {
        const CategoryIcon = CATEGORY_ICONS[key as LibraryCategory];
        return <ToggleGroupItem key={key} value={key} aria-label={`${label} (${counts[key] ?? 0})`}>
          {page ? <><CategoryIcon data-icon="inline-start" aria-hidden />{label}<span className="library-category-count" aria-hidden>{counts[key] ?? 0}</span></> : <>{label} ({counts[key] ?? 0})</>}
        </ToggleGroupItem>;
      })}
    </ToggleGroup>
    <FieldGroup className="library-filters">
      <Field className="library-search-field"><FieldLabel htmlFor={`${filterId}-search`}>{t("library.browser.search")}</FieldLabel><Input id={`${filterId}-search`} type="search" maxLength={MAX_LIBRARY_NAME_LENGTH} value={query} placeholder={t("library.browser.nameSearch")} onChange={(event) => setQuery(event.target.value)} /></Field>
      <Field className="library-select-field"><FieldLabel htmlFor={`${filterId}-kind`}>{t("media.kind")}</FieldLabel><Select id={`${filterId}-kind`} value={kind ?? ""} onChange={(event) => setKind(event.target.value ? event.target.value as Artifact["kind"] : undefined)}>
        {!kinds ? <option value="">{t("common.allKinds")}</option> : null}{availableKinds.map((value) => <option key={value} value={value}>{KIND_LABELS[value]}</option>)}</Select></Field>
      <Field className="library-select-field"><FieldLabel htmlFor={`${filterId}-sort`}>{t("library.browser.sort")}</FieldLabel><Select id={`${filterId}-sort`} value={sort} onChange={(event) => setSort(event.target.value as LibrarySort)}><option value="SAVED">{t("library.browser.recentSaved")}</option><option value="NAME">{t("common.name")}</option><option value="UPDATED">{t("library.browser.recentModified")}</option></Select></Field>
      <div className="library-filter-actions">
        <Button variant="outline" type="button" aria-pressed={favorite} onClick={() => setFavorite(!favorite)}><BookmarkSimple data-icon="inline-start" />{t("library.browser.favoritesOnly")}</Button>
        <Button variant="outline" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>{page ? <ArrowsClockwise data-icon="inline-start" aria-hidden /> : null}{t("library.browser.refresh")}</Button>
      </div>
    </FieldGroup>
    </div>
    {toggleFavorite.error ? page ? <Notice tone="danger">{t("library.browser.staleAssetsHint", { "0": toggleFavorite.error.message })}</Notice> : <p role="alert">{t("library.browser.staleAssetsHint", { "0": toggleFavorite.error.message })}</p> : null}
    {list.isPending ? page ? <div className="library-loading"><LoadingState compact label={t("library.browser.loading")} /></div> : <p>{t("library.browser.loading")}</p> : null}
    {list.error ? page ? <Notice tone="danger"><p>{list.error.message}</p><Button variant="outline" type="button" disabled={list.isFetching} onClick={retryRead}>{t("library.browser.retry")}</Button></Notice>
      : <div role="alert">{list.error.message}<Button variant="ghost" type="button" onClick={retryRead}>{t("library.browser.retry")}</Button></div> : null}
    <div className="library-grid">{list.data?.pages.flatMap((page) => page.items).filter((entry) => !mediaOnly || MEDIA_ASSET_KINDS.some((kind) => kind === entry.kind)).map((entry) => {
      const KindIcon = KIND_ICONS[entry.kind];
      return <article className="library-card" data-kind={page ? entry.kind : undefined} key={entry.id}>
      <Button variant="ghost" type="button" aria-label={pickAction === "CANVAS" ? t("library.browser.placeNamed", { "0": entry.name }) : pickAction === "REFERENCE" ? t("library.browser.referenceNamed", { "0": entry.name }) : t("library.browser.viewNamed", { "0": entry.name })} disabled={disabled} onClick={() => onPick(entry)}>
        <div className="library-card-preview">{entry.kind === "AUDIO" ? <MusicNotes size={40} /> : entry.hasThumbnail ? <img loading="lazy" src={libraryThumbnailUrl(entry.id)} alt="" />
          : entry.kind === "TEXT" ? <p>{String(entry.textContent?.text ?? "").slice(0, 180)}</p> : page ? <KindIcon size={40} /> : <Video size={40} />}
          {page ? <><span className="library-card-kind"><KindIcon size={12} aria-hidden />{KIND_LABELS[entry.kind]}</span>
            {entry.durationMs ? <span className="library-card-duration">{new Intl.NumberFormat(getFormatLocale(), { style: "unit", unit: "second", unitDisplay: "short", minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(entry.durationMs / 1000)}</span> : null}</> : null}
        </div>
        {page ? <div className="library-card-copy"><strong title={entry.name}>{entry.name}</strong><div className="library-card-meta"><span>{CATEGORY_LABELS[entry.category]}</span>
          <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleDateString(getFormatLocale())}</time></div></div>
          : <><strong>{entry.name}</strong><small>{CATEGORY_LABELS[entry.category]} · {KIND_LABELS[entry.kind]}{entry.durationMs ? t("library.browser.durationSuffix", { "0": (entry.durationMs / 1000).toFixed(1) }) : ""}{entry.favorite ? t("library.browser.favoriteSuffix") : ""}</small>
          <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleDateString(getFormatLocale())}</time></>}
      </Button>
      <Button variant="ghost" type="button" className="library-favorite" aria-label={`${entry.favorite ? t("library.browser.unfavorite") : t("library.browser.favorite")} ${entry.name}`} aria-pressed={entry.favorite}
        disabled={toggleFavorite.isPending || disabled} onClick={() => toggleFavorite.mutate(entry)}><BookmarkSimple size={17} weight={entry.favorite ? "fill" : "regular"} /></Button>
    </article>;
    })}</div>
    {list.data?.pages[0]?.total === 0 ? page ? <EmptyState icon={<Images />} title={emptyTitle} description={t("library.browser.saveHint")} />
      : <div className="library-empty"><FileText size={32} /><p>{emptyTitle}</p><p>{t("library.browser.saveHint")}</p></div> : null}
    {list.hasNextPage ? <Button variant="outline" className={page ? "library-load-more" : undefined} type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? t("common.loading") : t("library.browser.loadMore")}</Button> : null}
  </section>;
}
