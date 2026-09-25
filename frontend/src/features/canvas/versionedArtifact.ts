import type { Artifact } from "../../shared/api/client";

/** An Artifact with an archived content version, safe for exact-version references. */
export type VersionedArtifact = Artifact & {
  currentVersionId: string;
  currentVersion: NonNullable<Artifact["currentVersion"]>;
};

export function hasCurrentVersion(artifact: Artifact | null | undefined):
  artifact is VersionedArtifact {
  return artifact?.currentVersionId != null && artifact.currentVersion != null;
}
