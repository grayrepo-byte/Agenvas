import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
HTTP_STATUS,ApiError,listArtifactVersions,setArtifactResourceDefaultVersion,
type Artifact
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { CreativeSkillSource } from "../skills/CreativeSkillSource";
import { Button } from "../../shared/ui/primitives/button";

/** On-demand resource history for text; media nodes intentionally expose no version switching. */
export function ArtifactVersionHistory({ artifact }: { artifact: Artifact }) {
  useLocale();
  if (artifact.kind !== "TEXT") return null;
  return <TextArtifactVersionHistory artifact={artifact} />;
}

function TextArtifactVersionHistory({ artifact }: { artifact: Artifact }) {
  useLocale();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const history = useQuery({
    queryKey: ["artifact-versions", artifact.projectId, artifact.id],
    queryFn: () => listArtifactVersions(artifact.projectId, artifact.id),
    enabled: open,
  });
  const selectedVersionId = artifact.resourceDefaultVersionId;
  const select = useMutation({
    mutationFn: (versionId: string) => setArtifactResourceDefaultVersion(
      artifact.projectId, artifact.id, versionId, artifact.version),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId,
        artifact.id] });
    },
  });

  return <details className="nodrag nowheel mt-3 border-t border-[var(--line)] pt-2 text-xs"
    onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary className="cursor-pointer font-semibold">{t("artifacts.versions.title")}</summary>
    <p className="mt-2 text-[var(--muted)]">{t("artifacts.versions.selectionHint")}</p>
    {history.isPending ? <p className="mt-2">{t("common.versionLoading")}</p> : null}
    {history.error ? <p className="mt-2 text-red-700" role="alert">
      {history.error instanceof ApiError ? history.error.message : t("artifacts.versions.loadFailed")}
    </p> : null}
    {history.data ? <ol className="mt-2 space-y-2">
      {history.data.items.map((version) => <li className="rounded-lg border border-[var(--line)] p-2"
        key={version.id}>
        <CreativeSkillSource source={version.frozenInput?.creativeSkill} />
        <span>v{version.versionNo}{version.baseVersionId
          ? t("artifacts.versions.parentVersionSuffix", { "0": history.data.items.find((candidate) => candidate.id === version.baseVersionId)?.versionNo ?? "?" })
          : ""} · {version.createdByKind} · {version.createdAt}</span>
        {version.id === artifact.resourceDefaultVersionId
          ? <span className="ml-2">{t("artifacts.versions.resourceDefault")}</span> : null}
        {version.id === selectedVersionId ? null
          : <Button variant="ghost" className="node-action ml-2" disabled={select.isPending}
            onClick={() => select.mutate(version.id)} type="button">
            {select.isPending && select.variables === version.id ? t("artifacts.versions.selecting") : t("artifacts.versions.select")}
          </Button>}
      </li>)}
    </ol> : null}
    {select.error ? <p className="mt-2 text-red-700" role="alert">
      {select.error instanceof ApiError && select.error.status === HTTP_STATUS.CONFLICT
        ? t("artifacts.versions.selectionConflict")
        : select.error instanceof ApiError ? select.error.message : t("artifacts.versions.selectFailed")}
    </p> : null}
  </details>;
}
