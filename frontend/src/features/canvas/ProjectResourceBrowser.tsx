import { FileText, ImageSquare, Images, MusicNotes, VideoCamera } from "@/shared/ui/icons";
import { useInfiniteQuery, useMutation } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import {
  applyCanvasCommands,
  assetContentUrl,
  assetThumbnailUrl,
  createArtifact,
  listProjectResources,
  type Canvas,
  type ResourceResult,
} from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "../../shared/ui/primitives/empty";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";
import { KIND_LABELS, MAX_LIBRARY_NAME_LENGTH } from "../library/libraryLabels";
import { readContentText } from "./artifactContent";
import { MediaPreviewDialog } from "./MediaPreviewDialog";
import {
  AUDIO_CARD_HEIGHT,
  AUDIO_CARD_WIDTH,
  VISUAL_MEDIA_CARD_HEIGHT,
  VISUAL_MEDIA_CARD_WIDTH,
} from "./mediaNodeActions";
import "./ProjectResourceBrowser.css";

type PendingPlacement = {
  versionId: string;
  itemId: string;
  commandKey: string;
  artifactId?: string;
  x: number;
  y: number;
  zIndex: number;
};
const TEXT_RESULT_CARD_SIZE = { width: 420, height: 270 };

/** A failed image keeps its result identity and remains available for preview or placement. */
function ResultThumbnail({ resource }: { resource: ResourceResult }) {
  const [failed, setFailed] = useState(false);
  const assetId = readContentText(resource.content, "assetId");
  const KindIcon = resource.kind === "AUDIO" ? MusicNotes : resource.kind === "VIDEO" ? VideoCamera : ImageSquare;
  return (
    <span className="project-resource-thumbnail" aria-hidden="true">
      {resource.kind === "TEXT" ? (
        <span className="project-resource-text">{readContentText(resource.content, "text")}</span>
      ) : resource.kind !== "AUDIO" && assetId && !failed ? (
        <img
          src={assetThumbnailUrl(resource.projectId, assetId)}
          loading="lazy"
          decoding="async"
          alt=""
          onError={() => setFailed(true)}
        />
      ) : (
        <KindIcon size={32} />
      )}
    </span>
  );
}

/** Each entry is an immutable result, rather than a node identity or a resource default. */
export function ProjectResourceBrowser({
  projectId,
  position,
  zIndex,
  onPlaced,
  onPlacing,
  onPlaceError,
}: {
  projectId: string;
  position: () => { x: number; y: number };
  zIndex: number;
  onPlaced: (canvas: Canvas, itemId: string) => void;
  onPlacing?: () => void;
  onPlaceError?: (error: Error) => void;
}) {
  useLocale();
  const id = useId();
  const [query, setQuery] = useState("");
  const [preview, setPreview] = useState<ResourceResult | null>(null);
  const pending = useRef(new Map<string, PendingPlacement>());
  const list = useInfiniteQuery({
    queryKey: ["project-resources", projectId, { query }],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listProjectResources(projectId, { query, cursor: pageParam }),
    getNextPageParam: page => page.nextCursor ?? undefined,
    retry: false,
  });
  const resources = list.data?.pages.flatMap(page => page.items) ?? [];
  const place = useMutation({
    mutationFn: async (resource: ResourceResult) => {
      // Reuse the exact item, content and position after an uncertain HTTP response.
      if (!pending.current.has(resource.versionId))
        pending.current.set(resource.versionId, {
          versionId: resource.versionId,
          itemId: crypto.randomUUID(),
          commandKey: crypto.randomUUID(),
          ...position(),
          zIndex,
        });
      const plan = pending.current.get(resource.versionId)!;
      if (resource.kind === "TEXT" && !plan.artifactId) {
        // Text is editable at the Artifact level. Copy the fixed revision so placement cannot change its source.
        if (!("text" in resource.content)) throw new Error(t("api.errors.projectResourcesUnavailable"));
        const copied = await createArtifact(
          projectId,
          { kind: "TEXT", title: resource.title, content: resource.content },
          plan.commandKey,
        );
        plan.artifactId = copied.id;
      }
      const size =
        resource.kind === "TEXT"
          ? TEXT_RESULT_CARD_SIZE
          : resource.kind === "AUDIO"
            ? { width: AUDIO_CARD_WIDTH, height: AUDIO_CARD_HEIGHT }
            : { width: VISUAL_MEDIA_CARD_WIDTH, height: VISUAL_MEDIA_CARD_HEIGHT };
      const canvas = await applyCanvasCommands(projectId, [
        {
          type: "PLACE_ARTIFACT",
          itemId: plan.itemId,
          artifactId: plan.artifactId ?? resource.artifactId,
          ...(resource.kind === "TEXT" ? {} : { versionId: resource.versionId }),
          x: plan.x,
          y: plan.y,
          zIndex: plan.zIndex,
          ...size,
          locked: false,
        },
      ]);
      return { canvas, itemId: plan.itemId, versionId: resource.versionId };
    },
    onMutate: onPlacing,
    onError: onPlaceError,
    onSuccess: ({ canvas, itemId, versionId }) => {
      pending.current.delete(versionId);
      onPlaced(canvas, itemId);
    },
  });
  const placeButton = (resource: ResourceResult) => (
    <Button
      variant="outline"
      size="sm"
      type="button"
      disabled={place.isPending}
      aria-label={t("canvas.resources.placeResult", { "0": resource.title, "1": resource.versionNo })}
      onClick={() => place.mutate(resource)}
    >
      {place.isPending && place.variables?.versionId === resource.versionId ? t("common.loading") : t("canvas.place")}
    </Button>
  );
  const placementError = place.error ? <p role="alert">{place.error.message}</p> : null;
  const previewAssetId = preview ? readContentText(preview.content, "assetId") : "";
  return (
    <section className="project-resource-browser" aria-label={t("canvas.workspace.resourceTitle")}>
      <p className="project-resource-hint">{t("canvas.resources.hint")}</p>
      <FieldGroup>
        <Field>
          <FieldLabel htmlFor={`${id}-search`}>{t("canvas.workspace.searchResources")}</FieldLabel>
          <Input
            id={`${id}-search`}
            type="search"
            maxLength={MAX_LIBRARY_NAME_LENGTH}
            value={query}
            placeholder={t("canvas.workspace.searchPlaceholder")}
            onChange={event => setQuery(event.target.value)}
          />
        </Field>
      </FieldGroup>
      {list.isPending ? <p role="status">{t("canvas.workspace.resourcesLoading")}</p> : null}
      {list.error ? (
        <div role="alert">
          {list.error.message}
          <Button
            variant="outline"
            type="button"
            disabled={list.isFetching}
            onClick={() => {
              if (list.isFetchNextPageError) void list.fetchNextPage();
              else void list.refetch();
            }}
          >
            {t("resources.retry")}
          </Button>
        </div>
      ) : null}
      <div className="project-resource-grid">
        {resources.map(resource => (
          <article className="project-resource-card" key={resource.versionId}>
            <Button
              className="project-resource-preview"
              variant="ghost"
              type="button"
              aria-label={t("resources.view", { "0": resource.title, "1": resource.versionNo })}
              onClick={() => setPreview(resource)}
            >
              <ResultThumbnail resource={resource} />
            </Button>
            <div className="project-resource-copy">
              <strong title={resource.title}>{resource.title}</strong>
              <span>
                {KIND_LABELS[resource.kind]} · v{resource.versionNo}
              </span>
              <time dateTime={resource.createdAt}>
                {new Date(resource.createdAt).toLocaleString(getFormatLocale())}
              </time>
              {placeButton(resource)}
            </div>
          </article>
        ))}
      </div>
      {list.isSuccess && !resources.length ? (
        <Empty>
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <Images />
            </EmptyMedia>
            <EmptyTitle>{query ? t("resources.emptySearch") : t("resources.empty")}</EmptyTitle>
            <EmptyDescription>{t("canvas.resources.emptyHint")}</EmptyDescription>
          </EmptyHeader>
        </Empty>
      ) : null}
      {list.hasNextPage ? (
        <Button variant="outline" type="button" disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>
          {list.isFetchingNextPage ? t("common.loading") : t("resources.loadMore")}
        </Button>
      ) : null}
      {placementError}
      {preview && (preview.kind === "IMAGE" || preview.kind === "VIDEO") ? (
        <MediaPreviewDialog
          kind={preview.kind === "IMAGE" ? "image" : "video"}
          title={preview.title}
          sourceUrl={assetContentUrl(projectId, previewAssetId)}
          posterSrc={assetThumbnailUrl(projectId, previewAssetId)}
          onClose={() => setPreview(null)}
        />
      ) : null}
      {preview && (preview.kind === "AUDIO" || preview.kind === "TEXT") ? (
        <Dialog
          title={preview.title}
          description={`${KIND_LABELS[preview.kind]} · v${preview.versionNo}`}
          busy={place.isPending}
          onClose={() => setPreview(null)}
          onSubmit={event => event.preventDefault()}
          footer={placeButton(preview)}
        >
          {preview.kind === "AUDIO" ? (
            <audio controls preload="none" src={assetContentUrl(projectId, previewAssetId)} />
          ) : (
            <p className="project-resource-text-detail">
              <FileText aria-hidden />
              {readContentText(preview.content, "text")}
            </p>
          )}
          {placementError}
        </Dialog>
      ) : null}
    </section>
  );
}
