import { useQueries, type UseQueryResult } from "@tanstack/react-query";
import { useMemo } from "react";
import { type Asset, type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { imageAspectRatio, imageDraftAspectRatio } from "./imageNodeLayout";
import { useCanvasStore } from "./canvasStore";
import { assetMetadataQueryOptions, displayedMediaAssetId, mediaDraftQueryOptions } from "./mediaDisplay";

const EMPTY_ITEMS: CanvasItem[] = [];
const combineDrafts = (results: UseQueryResult<MediaDraft>[]) => results.map((result) => result.data);
const combineAssets = (results: UseQueryResult<Asset>[]) => results.map((result) => result.data);

/** Explicit draft ratios preview the next frame; AUTO falls back to archived pixel metadata. */
export function useImageNodeRatios(items: CanvasItem[] = EMPTY_ITEMS): Record<string, number> {
  const localRatios = useCanvasStore((state) => state.imageRatioDrafts);
  const images = useMemo(() => items.flatMap((item) => item.artifact?.kind === "IMAGE"
    ? [{ item, artifact: item.artifact }] : []), [items]);
  const drafts = useQueries({ queries: images.map(({ item, artifact }) =>
    mediaDraftQueryOptions(artifact.projectId, item.id)),
    combine: combineDrafts });
  const displayedImages = useMemo(() => {
    return images.flatMap(({ item, artifact }, index) => {
      const assetId = displayedMediaAssetId(item, drafts[index]);
      return assetId ? [{ itemId: item.id, projectId: artifact.projectId, assetId }] : [];
    });
  }, [images, drafts]);
  const uniqueAssets = useMemo(() => [...new Map(displayedImages.map((image) =>
    [`${image.projectId}:${image.assetId}`, image])).values()], [displayedImages]);
  const assets = useQueries({ queries: uniqueAssets.map(({ projectId, assetId }) =>
    assetMetadataQueryOptions(projectId, assetId)), combine: combineAssets });
  return useMemo(() => {
    const ratiosByAsset = new Map(uniqueAssets.map(({ projectId, assetId }, index) =>
      [`${projectId}:${assetId}`, imageAspectRatio(assets[index])]));
    const assetsByItem = new Map(displayedImages.map(({ itemId, projectId, assetId }) =>
      [itemId, ratiosByAsset.get(`${projectId}:${assetId}`)]));
    return Object.fromEntries(images.flatMap(({ item, artifact }, index) => {
      const ratio = imageDraftAspectRatio(localRatios[`${artifact.projectId}:${item.id}`]
        ?? drafts[index]?.parameters.aspectRatio) ?? assetsByItem.get(item.id);
      return ratio === undefined ? [] : [[item.id, ratio]];
    }));
  }, [displayedImages, uniqueAssets, assets, images, drafts, localRatios]);
}
