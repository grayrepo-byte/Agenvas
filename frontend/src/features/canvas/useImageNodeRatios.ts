import { useQueries, type UseQueryResult } from "@tanstack/react-query";
import { useMemo } from "react";
import { type Asset, type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { imageAspectRatio } from "./imageNodeLayout";
import { assetMetadataQueryOptions, displayedMediaAssetId, mediaDraftQueryOptions } from "./mediaDisplay";

const EMPTY_ITEMS: CanvasItem[] = [];
const combineDrafts = (results: UseQueryResult<MediaDraft>[]) => results.map((result) => result.data);
const combineAssets = (results: UseQueryResult<Asset>[]) => results.map((result) => result.data);

/** Geometry derives from archived pixel metadata, never rounded thumbnails or DOM measurements. */
export function useImageNodeRatios(items: CanvasItem[] = EMPTY_ITEMS): Record<string, number> {
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
    return Object.fromEntries(displayedImages.flatMap(({ itemId, projectId, assetId }) => {
      const ratio = ratiosByAsset.get(`${projectId}:${assetId}`);
      return ratio === undefined ? [] : [[itemId, ratio]];
    }));
  }, [displayedImages, uniqueAssets, assets]);
}
