import type { Artifact, CanvasItem } from "../../shared/api/client";

/** A canvas projection with a real media/text artifact, excluding character-only items. */
export type ArtifactCanvasItem = CanvasItem & { artifact: Artifact };
export function hasCanvasArtifact(item: CanvasItem): item is ArtifactCanvasItem {
  return item.subjectType === "ARTIFACT" && item.artifact !== null;
}

/** An Artifact with an archived content version, safe for exact-version references. */
export type VersionedArtifact = Artifact & {
  resourceDefaultVersionId: string;
  resourceDefaultVersion: NonNullable<Artifact["resourceDefaultVersion"]>;
};

export function hasCurrentVersion(artifact: Artifact | null | undefined): artifact is VersionedArtifact {
  return artifact?.resourceDefaultVersionId != null && artifact.resourceDefaultVersion != null;
}

/** The version this card presents; media cards deliberately ignore the resource-library default. */
export function canvasItemVersionId(item: CanvasItem): string | null {
  if (!item.artifact) return null;
  if (item.artifact.kind !== "TEXT") return item.selectedVersionId;
  return item.artifact.resourceDefaultVersionId ?? null;
}
