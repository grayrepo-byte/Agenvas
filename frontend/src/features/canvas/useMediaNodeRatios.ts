import { useQueries, type UseQueryResult } from "@tanstack/react-query";
import { useMemo } from "react";
import { type Asset, type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { imageAspectRatio, imageDraftAspectRatio } from "./imageNodeLayout";
import { useCanvasStore } from "./canvasStore";
import { assetMetadataQueryOptions, displayedMediaAssetId, mediaDraftQueryOptions } from "./mediaDisplay";

const EMPTY_ITEMS: CanvasItem[] = [];
const combineDrafts = (results: UseQueryResult<MediaDraft>[]) => results.map((result) => result.data);
const combineAssets = (results: UseQueryResult<Asset>[]) => results.map((result) => result.data);

/** Images preview draft ratios; video results always fit the displayed asset's actual pixels. */
export function useMediaNodeRatios(items: CanvasItem[] = EMPTY_ITEMS): Record<string, number> {
  const localRatios = useCanvasStore((state) => state.imageRatioDrafts);
  const mediaItems = useMemo(() => items.flatMap((item) => item.artifact?.kind === "IMAGE" || item.artifact?.kind === "VIDEO"
    ? [{ item, artifact: item.artifact }] : []), [items]);
  const drafts = useQueries({ queries: mediaItems.map(({ item, artifact }) =>
    mediaDraftQueryOptions(artifact.projectId, item.id)),
    combine: combineDrafts });
  const uniqueAssets = useMemo(() => [...new Map(mediaItems.flatMap(({ item, artifact }, index) => {
    const assetId = displayedMediaAssetId(item, drafts[index]);
    return assetId ? [[`${artifact.projectId}:${assetId}`, { projectId: artifact.projectId, assetId }] as const] : [];
  })).values()], [mediaItems, drafts]);
  const assets = useQueries({ queries: uniqueAssets.map(({ projectId, assetId }) =>
    assetMetadataQueryOptions(projectId, assetId)), combine: combineAssets });
  return useMemo(() => {
    const ratiosByAsset = new Map(uniqueAssets.map(({ projectId, assetId }, index) =>
      [`${projectId}:${assetId}`, imageAspectRatio(assets[index])]));
    return Object.fromEntries(mediaItems.flatMap(({ item, artifact }, index) => {
      const draftRatio = artifact.kind === "IMAGE"
        ? imageDraftAspectRatio(localRatios[`${artifact.projectId}:${item.id}`]
          ?? drafts[index]?.parameters.aspectRatio) : undefined;
      const assetId = displayedMediaAssetId(item, drafts[index]);
      const ratio = draftRatio ?? (assetId ? ratiosByAsset.get(`${artifact.projectId}:${assetId}`) : undefined);
      return ratio === undefined ? [] : [[item.id, ratio]];
    }));
  }, [uniqueAssets, assets, mediaItems, drafts, localRatios]);
}
