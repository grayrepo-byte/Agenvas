import { t } from "../../shared/i18n";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { HTTP_STATUS,ApiError, getArtifact, type Artifact } from "../../shared/api/client";
import { hasCurrentVersion, type VersionedArtifact } from "./versionedArtifact";

type ArtifactRevisionOptions<Fields, Revision> = {
  artifact: VersionedArtifact;
  readFields: (artifact: VersionedArtifact) => Fields;
  saveRevision: (base: VersionedArtifact, revision: Revision) => Promise<Artifact>;
};

/** Pins the editable version and CAS base together until save or an explicit reload replaces them. */
export function useArtifactRevision<Fields extends object, Revision>({
  artifact, readFields, saveRevision,
}: ArtifactRevisionOptions<Fields, Revision>) {
  const queryClient = useQueryClient();
  const [base, setBase] = useState(artifact);
  const [fields, setFields] = useState(() => readFields(artifact));
  const dirty = JSON.stringify(fields) !== JSON.stringify(readFields(base));

  function acceptVersion(latest: VersionedArtifact) {
    setBase(latest);
    setFields(readFields(latest));
  }

  const save = useMutation({
    mutationFn: (revision: Revision) => saveRevision(base, revision),
    onSuccess: async (saved) => {
      if (hasCurrentVersion(saved)) acceptVersion(saved);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", base.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", base.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["artifact-versions", base.projectId, base.id] }),
      ]);
    },
  });
  const reload = useMutation({
    mutationFn: async () => {
      const latest = await getArtifact(base.projectId, base.id);
      if (!hasCurrentVersion(latest)) throw new Error(t("artifacts.revision.versionMissing"));
      return latest;
    },
    onSuccess: (latest) => { acceptVersion(latest); save.reset(); },
  });
  const busy = save.isPending || reload.isPending;

  useEffect(() => {
    // Remote updates may refresh a clean editor, but never rewrite an unsaved draft or its CAS base.
    if (artifact.id !== base.id || (artifact.version > base.version && !dirty && !busy)) {
      setBase(artifact);
      setFields(readFields(artifact));
    }
  }, [artifact, base.id, base.version, dirty, busy, readFields]);

  const status = {
    dirty, busy, saving: save.isPending, saved: save.isSuccess,
    newerAvailable: artifact.version > base.version,
    conflict: save.error instanceof ApiError && save.error.status === HTTP_STATUS.CONFLICT,
    error: save.error, reloadError: reload.error,
  };
  return {
    base, fields, status,
    edit: (patch: Partial<Fields>) => setFields((current) => ({ ...current, ...patch })),
    save: save.mutate,
    saveAsync: save.mutateAsync,
    reload: () => reload.mutate(),
  };
}
