import { queryOptions, skipToken } from "@tanstack/react-query";
import { getAssetMetadata, getMediaDraft, type Artifact, type MediaDraft } from "../../shared/api/client";
import { readContentText } from "./artifactContent";

export function mediaDraftQueryOptions(artifact: Artifact) {
  return queryOptions({
    queryKey: ["media-draft", artifact.projectId, artifact.id],
    queryFn: () => getMediaDraft(artifact.projectId, artifact.id),
  });
}

export function assetMetadataQueryOptions(projectId: string, assetId: string | null) {
  return queryOptions({
    queryKey: ["asset-metadata", projectId, assetId],
    queryFn: assetId ? () => getAssetMetadata(projectId, assetId) : skipToken,
    staleTime: Infinity,
  });
}

export function isMediaDraftDisplayed(artifact: Artifact, draft?: MediaDraft): boolean {
  return artifact.currentVersionId === null || draft?.displayMode === "DRAFT";
}

/** Preview, node geometry and downloads must all describe the same displayed result. */
export function displayedMediaAssetId(artifact: Artifact, draft?: MediaDraft): string | null {
  return isMediaDraftDisplayed(artifact, draft)
    ? null : readContentText(artifact.currentVersion?.content, "assetId") || null;
}
