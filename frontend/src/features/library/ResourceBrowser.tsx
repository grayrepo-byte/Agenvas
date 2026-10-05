import { Images,MusicNotes,Video } from "@phosphor-icons/react";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useId,useState } from "react";
import { Link } from "react-router";
import { assetContentUrl,assetThumbnailUrl,listResources,type ResourceResult } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Empty,EmptyDescription,EmptyHeader,EmptyMedia,EmptyTitle } from "../../shared/ui/primitives/empty";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { KIND_LABELS,MAX_LIBRARY_NAME_LENGTH,MEDIA_ASSET_KINDS } from "./libraryLabels";
import "./Library.css";

type MediaKind = typeof MEDIA_ASSET_KINDS[number];

function mediaId(resource: ResourceResult): string | undefined {
  return "assetId" in resource.content ? resource.content.assetId : undefined;
}

/** Every exact result remains browsable, including versions which are no longer selected on a card. */
export function ResourceBrowser() {
  useLocale();
  const id = useId();
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<MediaKind | undefined>();
  const [selected, setSelected] = useState<ResourceResult | null>(null);
  const filters = { query, kind };
  const list = useInfiniteQuery({ queryKey: ["resources", filters], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listResources({ ...filters, cursor: pageParam }),
    getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  const items = list.data?.pages.flatMap((page) => page.items).filter((resource) => MEDIA_ASSET_KINDS.some((kind) => kind === resource.kind)) ?? [];
  return <section className="library-browser" aria-label={t("resources.title")}>
    <p className="library-resource-description">{t("resources.description")}</p>
    <FieldGroup className="library-filters">
      <Field className="library-search-field"><FieldLabel htmlFor={`${id}-search`}>{t("resources.search")}</FieldLabel>
        <Input id={`${id}-search`} type="search" maxLength={MAX_LIBRARY_NAME_LENGTH} value={query} placeholder={t("resources.searchPlaceholder")} onChange={(event) => setQuery(event.target.value)} /></Field>
      <Field className="library-select-field"><FieldLabel htmlFor={`${id}-kind`}>{t("media.kind")}</FieldLabel>
        <Select id={`${id}-kind`} value={kind ?? ""} onChange={(event) => setKind(event.target.value ? event.target.value as MediaKind : undefined)}>
          <option value="">{t("common.allKinds")}</option>{MEDIA_ASSET_KINDS.map((value) => <option key={value} value={value}>{KIND_LABELS[value]}</option>)}
        </Select></Field>
      <div className="library-filter-actions"><Button variant="outline" type="button" disabled={list.isFetching} onClick={() => void list.refetch()}>{t("resources.refresh")}</Button></div>
    </FieldGroup>
    {list.isPending ? <p role="status">{t("resources.loading")}</p> : null}
    {list.error ? <div role="alert">{list.error.message}<Button variant="outline" type="button" disabled={list.isFetching}
      onClick={() => { if (list.isFetchNextPageError) void list.fetchNextPage(); else void list.refetch(); }}>{t("resources.retry")}</Button></div> : null}
    <div className="library-grid">{items.map((resource) => {
      const assetId = mediaId(resource);
      return <article className="library-card" key={resource.versionId}>
        <Button variant="ghost" type="button" aria-label={t("resources.view", { "0": resource.title, "1": resource.versionNo })} onClick={() => setSelected(resource)}>
          <div className="library-card-preview">{resource.kind === "AUDIO" ? <MusicNotes size={40} /> : assetId && (resource.kind === "IMAGE" || resource.kind === "VIDEO")
            ? <img loading="lazy" src={assetThumbnailUrl(resource.projectId, assetId)} alt="" />
            : <Video size={40} />}</div>
          <strong>{resource.title}</strong><small>{KIND_LABELS[resource.kind]} · v{resource.versionNo}</small>
          <small>{resource.projectName}</small><time dateTime={resource.createdAt}>{new Date(resource.createdAt).toLocaleString(getFormatLocale())}</time>
        </Button>
      </article>;
    })}</div>
    {list.isSuccess && !items.length ? <Empty><EmptyHeader><EmptyMedia variant="icon"><Images /></EmptyMedia>
      <EmptyTitle>{query || kind ? t("resources.emptySearch") : t("resources.empty")}</EmptyTitle><EmptyDescription>{t("resources.emptyHint")}</EmptyDescription>
    </EmptyHeader></Empty> : null}
    {list.hasNextPage ? <Button variant="outline" className="library-load-more" type="button" disabled={list.isFetchingNextPage}
      onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? t("common.loading") : t("resources.loadMore")}</Button> : null}
    {selected ? <ResourceDetail resource={selected} onClose={() => setSelected(null)} /> : null}
  </section>;
}

function ResourceDetail({ resource, onClose }: { resource: ResourceResult; onClose: () => void }) {
  const assetId = mediaId(resource);
  const contentUrl = assetId ? assetContentUrl(resource.projectId, assetId) : undefined;
  return <Dialog title={resource.title} description={`${resource.projectName} · ${KIND_LABELS[resource.kind]} · v${resource.versionNo}`}
    onClose={onClose} onSubmit={(event) => event.preventDefault()}
    footer={<><Button variant="outline" asChild><Link to={`/projects/${resource.projectId}`}>{t("resources.openProject")}</Link></Button>
      <Button asChild><a href={contentUrl} download={resource.title}>{t("resources.download")}</a></Button></>}>
    {resource.kind === "AUDIO" ? <audio controls preload="none" src={contentUrl} /> : resource.kind === "IMAGE" ? <img className="library-save-preview" src={contentUrl} alt={resource.title} />
      : <video className="library-save-preview" controls preload="metadata" src={contentUrl} />}
  </Dialog>;
}
