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

function isMediaArtifactItem(item: CanvasItem): item is CanvasItem & { artifact: Artifact } {
  return item.artifact != null
    && (item.artifact.kind === "IMAGE" || item.artifact.kind === "VIDEO" || item.artifact.kind === "AUDIO");
}

/** The version this card presents; media cards deliberately ignore the resource-library default. */
export function canvasItemVersionId(item: CanvasItem): string | null {
  if (isMediaArtifactItem(item)) return item.selectedVersionId;
  return item.artifact?.resourceDefaultVersionId ?? null;
}

/** The materialized counterpart to [canvasItemVersionId]. */
export function canvasItemVersion(item: CanvasItem) {
  if (isMediaArtifactItem(item)) return item.selectedVersion;
  return item.artifact?.resourceDefaultVersion ?? null;
}
