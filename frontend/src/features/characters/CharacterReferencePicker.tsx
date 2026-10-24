import { useQueries, useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { assetContentUrl, assetThumbnailUrl, listArtifacts, listArtifactVersions, listCanvasItems } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Empty, EmptyDescription, EmptyHeader } from "../../shared/ui/primitives/empty";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Input } from "../../shared/ui/primitives/input";

/** Resolve only this project's immutable versions; personal-library transfers remain a separate import operation. */
export function CharacterReferencePicker({ projectId, kind, onSelect, onClose }: {
  projectId: string; kind: "IMAGE" | "AUDIO"; onSelect: (versionId: string) => void; onClose: () => void;
}) {
  useLocale();
  const id = useId();
  const [scope, setScope] = useState("CANVAS");
  const [search, setSearch] = useState("");
  const resources = useQuery({ queryKey: ["artifacts", projectId], queryFn: () => listArtifacts(projectId), retry: false });
  const canvas = useQuery({ queryKey: ["canvas", projectId], queryFn: () => listCanvasItems(projectId), retry: false });
  const artifacts = (resources.data?.items ?? []).filter((artifact) => artifact.kind === kind);
  const histories = useQueries({ queries: artifacts.map((artifact) => ({
    queryKey: ["artifact-versions", projectId, artifact.id], queryFn: () => listArtifactVersions(projectId, artifact.id), retry: false,
  })) });
  const selected = new Set(canvas.data?.items.flatMap((item) => item.selectedVersionId ? [item.selectedVersionId] : []));
  const versions = artifacts.flatMap((artifact, index) => (histories[index]?.data?.items ?? []).map((version) => ({ ...version, title: artifact.title })))
    .filter((version) => (scope === "RESOURCES" || selected.has(version.id)) && version.title.toLocaleLowerCase().includes(search.toLocaleLowerCase()));
  const loading = resources.isPending || canvas.isPending || histories.some((history) => history.isPending);
  const error = resources.error ?? canvas.error ?? histories.find((history) => history.error)?.error;
  return <Dialog title={t(kind === "IMAGE" ? "character.chooseImage" : "character.chooseAudio")} description={t("character.projectReferencesOnly")}
    onClose={onClose} onSubmit={(event) => event.preventDefault()}>
    <FieldGroup>
      <Field><FieldLabel htmlFor={`${id}-scope`}>{t("character.referenceSource")}</FieldLabel>
        <Select id={`${id}-scope`} value={scope} onChange={(event) => setScope(event.target.value)}>
          <option value="CANVAS">{t("character.canvasReferences")}</option><option value="RESOURCES">{t("character.projectResources")}</option>
        </Select></Field>
      <Field><FieldLabel htmlFor={`${id}-search`}>{t("character.searchReferences")}</FieldLabel><Input id={`${id}-search`} value={search} onChange={(event) => setSearch(event.target.value)} /></Field>
    </FieldGroup>
    {loading ? <p role="status">{t("common.loading")}</p> : null}
    {error ? <Alert variant="destructive"><AlertDescription>{error.message}<Button type="button" variant="ghost" onClick={() => { void resources.refetch(); void canvas.refetch(); histories.forEach((history) => { void history.refetch(); }); }}>{t("common.retry")}</Button></AlertDescription></Alert> : null}
    <div className="grid grid-cols-2 gap-3">
      {versions.map((version) => {
        const assetId = "assetId" in version.content ? version.content.assetId : undefined;
        return <div className="flex min-w-0 flex-col gap-2 rounded-lg border p-2" key={version.id}>
          {assetId && kind === "IMAGE" ? <img className="aspect-square w-full rounded object-contain" src={assetThumbnailUrl(projectId, assetId)} alt={version.title} /> : null}
          {assetId && kind === "AUDIO" ? <audio className="w-full" controls preload="none" src={assetContentUrl(projectId, assetId)} aria-label={version.title} /> : null}
          <span className="truncate text-sm">{version.title} · v{version.versionNo}</span>
          <Button type="button" variant="outline" size="sm" onClick={() => { onSelect(version.id); onClose(); }}>{t("character.selectVersion")}</Button>
        </div>;
      })}
    </div>
    {!loading && !versions.length ? <Empty><EmptyHeader><EmptyDescription>{t("character.noReferences")}</EmptyDescription></EmptyHeader></Empty> : null}
  </Dialog>;
}

/** Selected references retain exact identity and resolve preview media through project-authorized endpoints. */
export function CharacterReferencePreview({ projectId, versionId, kind }: { projectId: string; versionId: string; kind: "IMAGE" | "AUDIO" | "VIDEO" }) {
  const resources = useQuery({ queryKey: ["artifacts", projectId], queryFn: () => listArtifacts(projectId) });
  const artifacts = (resources.data?.items ?? []).filter((artifact) => artifact.kind === kind);
  const histories = useQueries({ queries: artifacts.map((artifact) => ({ queryKey: ["artifact-versions", projectId, artifact.id], queryFn: () => listArtifactVersions(projectId, artifact.id) })) });
  const version = histories.flatMap((history, index) => (history.data?.items ?? []).map((entry) => ({ ...entry, title: artifacts[index]?.title ?? "" }))).find((candidate) => candidate.id === versionId);
  const assetId = version && "assetId" in version.content ? version.content.assetId : undefined;
  return <div className="flex min-w-0 flex-col gap-1">
    <span className="text-sm">{version?.title || t("character.referenceUnavailable")} · {t(kind === "IMAGE" ? "common.image" : kind === "AUDIO" ? "common.audio" : "common.video")}{version ? ` · v${version.versionNo}` : ""}</span>
    {assetId && kind === "IMAGE" ? <img className="size-20 rounded object-contain" src={assetThumbnailUrl(projectId, assetId)} alt={t("character.imageReference")} /> : null}
    {assetId && kind === "AUDIO" ? <audio className="w-full" controls preload="none" src={assetContentUrl(projectId, assetId)} aria-label={t("character.audioReference")} /> : null}
    {assetId && kind === "VIDEO" ? <video className="w-full" controls preload="metadata" src={assetContentUrl(projectId, assetId)} aria-label={version?.title} /> : null}
    <details><summary className="text-xs text-muted-foreground">{t("character.exactVersion")}</summary><small className="break-all text-muted-foreground">{versionId}</small></details>
  </div>;
}
