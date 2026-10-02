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
      {t("版本{0}", { "0": ` v${artifact.resourceDefaultVersion.versionNo}` })}
      <CaretDown data-icon="inline-end" aria-hidden />
    </Button></DropdownMenuTrigger>
    {open ? <DropdownMenuContent aria-labelledby={undefined} onEscapeKeyDown={(event) => event.stopPropagation()} className="text-card-version-menu" role="menu" aria-label={t("文字版本")}><DropdownMenuGroup>
      {disabled ? <p>{t("请先保存或退出编辑，再切换版本。")}</p> : null}
      {history.isPending ? <p>{t("正在读取版本…")}</p> : null}
      {history.error ? <p role="alert">{history.error instanceof ApiError
        ? history.error.message : t("版本历史读取失败，请重试。")}</p> : null}
      {history.data?.items.map((version) => <DropdownMenuItem className="text-card-version-option" disabled={disabled || select.isPending || version.id === artifact.resourceDefaultVersionId} aria-current={version.id === artifact.resourceDefaultVersionId ? "true" : undefined} role="menuitem" key={version.id} onSelect={(event) => { event.preventDefault(); select.mutate(version.id); }}>
        <span>v{version.versionNo}</span>
        <small>{version.id === artifact.resourceDefaultVersionId ? t("当前选用") : version.createdByKind}</small>
      </DropdownMenuItem>)}
      {select.error ? <p role="alert">{select.error instanceof ApiError && select.error.status === HTTP_STATUS.CONFLICT
        ? t("内容有冲突，未切换版本；请重新打开版本列表后重试。")
        : select.error instanceof ApiError ? select.error.message : t("版本选用失败，请重试。")}</p> : null}
    </DropdownMenuGroup></DropdownMenuContent> : null}
  </div></DropdownMenu>;
}
