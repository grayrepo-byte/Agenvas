import { CaretDown,Stack } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
HTTP_STATUS,ApiError,listArtifactVersions,
setArtifactResourceDefaultVersion
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu,DropdownMenuContent,DropdownMenuGroup,DropdownMenuItem,DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import type { VersionedArtifact } from "./versionedArtifact";

/** Selects the actual Artifact version used by the card and by new outgoing connections. */
export function TextVersionPicker({ artifact, disabled = false }: {
  artifact: VersionedArtifact;
  disabled?: boolean;
}) {
  useLocale();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const history = useQuery({
    queryKey: ["artifact-versions", artifact.projectId, artifact.id],
    queryFn: () => listArtifactVersions(artifact.projectId, artifact.id),
    enabled: open,
  });
  const select = useMutation({
    mutationFn: (versionId: string) => setArtifactResourceDefaultVersion(
      artifact.projectId, artifact.id, versionId, artifact.version),
    onSuccess: async () => {
      setOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId,
          artifact.id] }),
      ]);
    },
  });

  return <DropdownMenu open={open} onOpenChange={setOpen} modal={false}><div className="text-card-version-picker">
    <DropdownMenuTrigger asChild><Button variant="ghost" aria-expanded={open} aria-haspopup="menu"
      className="text-card-version-tag" type="button">
      <Stack data-icon="inline-start" aria-hidden />
      {t("artifacts.versions.versionLabel", { "0": ` v${artifact.resourceDefaultVersion.versionNo}` })}
      <CaretDown data-icon="inline-end" aria-hidden />
    </Button></DropdownMenuTrigger>
    {open ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="text-card-version-menu" role="menu" aria-label={t("text.versions.title")}><DropdownMenuGroup>
      {disabled ? <p>{t("text.versions.saveBeforeSelection")}</p> : null}
      {history.isPending ? <p>{t("common.versionLoading")}</p> : null}
      {history.error ? <p role="alert">{history.error instanceof ApiError
        ? history.error.message : t("artifacts.versions.loadFailed")}</p> : null}
      {history.data?.items.map((version) => <DropdownMenuItem className="text-card-version-option" disabled={disabled || select.isPending || version.id === artifact.resourceDefaultVersionId} aria-current={version.id === artifact.resourceDefaultVersionId ? "true" : undefined} role="menuitem" key={version.id} onSelect={(event) => { event.preventDefault(); select.mutate(version.id); }}>
        <span>v{version.versionNo}</span>
        <small>{version.id === artifact.resourceDefaultVersionId ? t("artifacts.versions.selected") : version.createdByKind}</small>
      </DropdownMenuItem>)}
      {select.error ? <p role="alert">{select.error instanceof ApiError && select.error.status === HTTP_STATUS.CONFLICT
        ? t("text.versions.selectionConflict")
        : select.error instanceof ApiError ? select.error.message : t("artifacts.versions.selectFailed")}</p> : null}
    </DropdownMenuGroup></DropdownMenuContent> : null}
  </div></DropdownMenu>;
}
