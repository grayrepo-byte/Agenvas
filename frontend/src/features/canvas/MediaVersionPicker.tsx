import { CaretDown,CaretRight,FilmStrip,ImageSquare,MusicNotes,Stack } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useRef,useState } from "react";
import {
ApiError,assetThumbnailUrl,selectCanvasMediaVersion,
type CanvasItem
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { CanvasVersionMenuContent,CanvasVersionMenuError,CanvasVersionMenuOption,CanvasVersionMenuStatus,VERSION_SOURCE_LABELS } from "./CanvasVersionMenu";
import { readContentText } from "./artifactContent";
import { mediaVersionsQueryOptions } from "./mediaDisplay";
import { VideoComparisonDialog } from "./VideoComparisonDialog";
import "./MediaVersionPicker.css";

const MIN_SELECTABLE_VERSION_COUNT = 1;
const FIRST_NODE_VERSION_NO = 1;
/** A failed thumbnail keeps its reserved space and does not hide the version's identity. */
function VersionThumbnail({ projectId, assetId, kind }: {
  projectId: string;
  assetId: string;
  kind: CanvasItem["artifact"]["kind"];
}) {
  const [failedAssetId, setFailedAssetId] = useState<string>();
  const FallbackIcon = kind === "AUDIO" ? MusicNotes : kind === "VIDEO" ? FilmStrip : ImageSquare;
  return <span className="media-version-thumbnail" aria-hidden="true">
    {kind !== "AUDIO" && assetId && failedAssetId !== assetId
      ? <img loading="lazy" decoding="async" alt="" src={assetThumbnailUrl(projectId, assetId)}
        onError={() => setFailedAssetId(assetId)} />
      : <FallbackIcon className="media-version-thumbnail-icon" />}
  </span>;
}

/** Card-local result selection never restores inputs or changes a sibling's result. */
export function MediaVersionPicker({ projectId, item }: { projectId: string; item: CanvasItem }) {
  useLocale();
  const [open, setOpen] = useState(false);
  const [comparing, setComparing] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const toggle = useRef<HTMLButtonElement>(null);
  const client = useQueryClient();
  const history = useQuery(mediaVersionsQueryOptions(projectId, item.id));
  // Artifact numbers are audit identifiers shared by sibling nodes. Display only this node's sequence.
  const versions = [...(history.data?.items ?? [])]
    .sort((a, b) => a.versionNo - b.versionNo)
    .map((version, index) => ({ version, nodeVersionNo: index + FIRST_NODE_VERSION_NO }))
    .reverse();
  // Even a single saved result must be reachable when automatic selection was prevented.
  const canSelect = versions.length >= MIN_SELECTABLE_VERSION_COUNT;
  const menuOpen = open && canSelect;
  const selectedNumber = versions.find(({ version }) => version.id === item.selectedVersionId)?.nodeVersionNo;
  const select = useMutation({
    mutationFn: (versionId: string) => selectCanvasMediaVersion(projectId, item.id,
      { versionId, expectedVersion: item.version }),
    onSuccess: async () => {
      setOpen(false);
      await Promise.all([
        client.invalidateQueries({ queryKey: ["canvas", projectId] }),
        client.invalidateQueries({ queryKey: ["media-draft", projectId, item.id] }),
        client.invalidateQueries({ queryKey: ["snapshot", projectId] }),
      ]);
    },
  });

  if (!canSelect) return history.error ? <div className="media-version-picker" role="alert">
    {t("media.versions.loadFailed")}<Button variant="ghost" type="button" disabled={history.isFetching}
      onClick={() => void history.refetch()}>{t("common.retryRead")}</Button>
  </div> : null;

  const video = item.artifact.kind === "VIDEO";
  return <><DropdownMenu open={menuOpen} onOpenChange={setOpen} modal={false}><div className="media-version-picker" ref={anchor}
    onKeyDown={(event) => {
      if (event.key === "Escape") { event.stopPropagation(); setOpen(false); toggle.current?.focus(); }
    }}>
    <DropdownMenuTrigger asChild><Button variant="ghost" type="button" ref={toggle} aria-expanded={menuOpen} aria-haspopup="menu"
      onPointerDown={() => select.reset()}>
      <Stack size={17} aria-hidden="true" />{t("artifacts.versions.versionLabel", { "0": selectedNumber !== undefined ? ` v${selectedNumber}` : "" })}<CaretDown size={12} aria-hidden="true" />
    </Button></DropdownMenuTrigger>
    {menuOpen ? <CanvasVersionMenuContent className="media-version-menu" aria-label={t("media.versions.title")}
      heading={t("media.versions.history")} count={versions.length} footer={<>
        {video ? <DropdownMenuGroup><DropdownMenuItem className="media-version-compare" onSelect={() => { setOpen(false); setComparing(true); }}>
          <FilmStrip aria-hidden="true" /><span>{t("video.review.compare")}</span><CaretRight aria-hidden="true" />
        </DropdownMenuItem></DropdownMenuGroup> : null}
        <p className="canvas-version-hint">{t("media.versions.selectionHint")}</p>
      </>}>
      {history.isPending ? <CanvasVersionMenuStatus label={t("common.versionLoading")} /> : null}
      {history.error ? <CanvasVersionMenuError message={t("media.versions.loadFailed")} action={
        <Button variant="ghost" size="sm" type="button" disabled={history.isFetching}
          onClick={() => void history.refetch()}>{t("common.retryRead")}</Button>} /> : null}
      <DropdownMenuGroup className="canvas-version-list">
        {versions.map(({ version, nodeVersionNo }) => <CanvasVersionMenuOption key={version.id}
          versionNo={nodeVersionNo} selected={version.id === item.selectedVersionId}
          summary={readContentText(version.content, "prompt") || t(VERSION_SOURCE_LABELS[version.createdByKind])}
          createdAt={version.createdAt} disabled={select.isPending}
          preview={<VersionThumbnail projectId={projectId} assetId={readContentText(version.content, "assetId")} kind={item.artifact.kind} />}
          onSelect={(event) => { event.preventDefault(); select.mutate(version.id); }} />)}
      </DropdownMenuGroup>
      {select.isPending ? <CanvasVersionMenuStatus label={t("media.versions.selecting")} /> : null}
      {select.error ? <CanvasVersionMenuError message={select.error instanceof ApiError
        ? select.error.message : t("media.versions.selectFailed")} action={
        <Button variant="ghost" size="sm" type="button" onClick={() => {
          void client.invalidateQueries({ queryKey: ["canvas", projectId] });
          void history.refetch();
        }}>{t("media.versions.refresh")}</Button>} /> : null}
    </CanvasVersionMenuContent> : null}
  </div></DropdownMenu>
    {comparing ? <VideoComparisonDialog projectId={projectId} title={item.title} versions={versions}
      selectedVersionId={item.selectedVersionId} onClose={() => { setComparing(false); toggle.current?.focus(); }} /> : null}
  </>;
}
