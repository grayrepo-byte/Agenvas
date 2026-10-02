import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
HTTP_STATUS,ApiError,listArtifactVersions,setArtifactResourceDefaultVersion,
type Artifact
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
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
    <summary className="cursor-pointer font-semibold">{t("版本历史与选用")}</summary>
    <p className="mt-2 text-[var(--muted)]">{t("选用旧版本会改变资源库默认内容，不修改历史版本。")}</p>
    {history.isPending ? <p className="mt-2">{t("正在读取版本…")}</p> : null}
    {history.error ? <p className="mt-2 text-red-700" role="alert">
      {history.error instanceof ApiError ? history.error.message : t("版本历史读取失败，请重试。")}
    </p> : null}
    {history.data ? <ol className="mt-2 space-y-2">
      {history.data.items.map((version) => <li className="rounded-lg border border-[var(--line)] p-2"
        key={version.id}>
        <span>v{version.versionNo}{version.baseVersionId
          ? t(" · 基于 v{0}", { "0": history.data.items.find((candidate) => candidate.id === version.baseVersionId)?.versionNo ?? "?" })
          : ""} · {version.createdByKind} · {version.createdAt}</span>
        {version.id === artifact.resourceDefaultVersionId
          ? <span className="ml-2">{t("资源默认")}</span> : null}
        {version.id === selectedVersionId ? null
          : <Button variant="ghost" className="node-action ml-2" disabled={select.isPending}
            onClick={() => select.mutate(version.id)} type="button">
            {select.isPending && select.variables === version.id ? t("选用中…") : t("选用此版本")}
          </Button>}
      </li>)}
    </ol> : null}
    {select.error ? <p className="mt-2 text-red-700" role="alert">
      {select.error instanceof ApiError && select.error.status === HTTP_STATUS.CONFLICT
        ? t("内容有冲突，未切换版本；请核对当前版本后重试。")
        : select.error instanceof ApiError ? select.error.message : t("版本选用失败，请重试。")}
    </p> : null}
  </details>;
}
