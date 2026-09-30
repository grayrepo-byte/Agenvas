import { queryOptions, skipToken } from "@tanstack/react-query";
import { getAssetMetadata, getMediaDraft,
  type CanvasItem, type MediaDraft } from "../../shared/api/client";
import { readContentText } from "./artifactContent";

export function mediaDraftQueryOptions(projectId: string, canvasItemId: string) {
  return queryOptions({
    queryKey: ["media-draft", projectId, canvasItemId],
    queryFn: () => getMediaDraft(projectId, canvasItemId),
  });
}

export function assetMetadataQueryOptions(projectId: string, assetId: string | null) {
  return queryOptions({
    queryKey: ["asset-metadata", projectId, assetId],
    queryFn: assetId ? () => getAssetMetadata(projectId, assetId) : skipToken,
    staleTime: Infinity,
  });
}

export function isMediaDraftDisplayed(item: CanvasItem, draft?: MediaDraft): boolean {
  return item.selectedVersionId === null || draft?.displayMode === "DRAFT";
}

/** Preview and downloads describe the same result; explicit draft ratios may change its frame. */
export function displayedMediaAssetId(item: CanvasItem, draft?: MediaDraft): string | null {
  return isMediaDraftDisplayed(item, draft)
    ? null : readContentText(item.selectedVersion?.content, "assetId") || null;
}
