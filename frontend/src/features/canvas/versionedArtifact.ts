import type { Artifact, CanvasItem } from "../../shared/api/client";

/** An Artifact with an archived content version, safe for exact-version references. */
export type VersionedArtifact = Artifact & {
  resourceDefaultVersionId: string;
  resourceDefaultVersion: NonNullable<Artifact["resourceDefaultVersion"]>;
};

export function hasCurrentVersion(artifact: Artifact | null | undefined):
  artifact is VersionedArtifact {
  return artifact?.resourceDefaultVersionId != null && artifact.resourceDefaultVersion != null;
}

/** The version this card presents; media cards deliberately ignore the resource-library default. */
export function canvasItemVersionId(item: CanvasItem): string | null {
  if (!item.artifact) return null;
  return item.artifact.kind === "IMAGE" || item.artifact.kind === "VIDEO"
    ? item.selectedVersionId
    : item.artifact.resourceDefaultVersionId;
}

/** The materialized counterpart to [canvasItemVersionId]. */
export function canvasItemVersion(item: CanvasItem) {
  if (!item.artifact) return null;
  return item.artifact.kind === "IMAGE" || item.artifact.kind === "VIDEO"
    ? item.selectedVersion
    : item.artifact.resourceDefaultVersion;
}
