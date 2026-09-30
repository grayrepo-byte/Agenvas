import { DropdownMenu } from "../../shared/ui/DropdownMenu";
import { CaretDown, Stack } from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { ApiError, listArtifactVersions,
  setArtifactResourceDefaultVersion } from "../../shared/api/client";
import type { VersionedArtifact } from "./versionedArtifact";

/** Selects the actual Artifact version used by the card and by new outgoing connections. */
export function TextVersionPicker({ artifact, disabled = false }: {
  artifact: VersionedArtifact;
  disabled?: boolean;
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const anchor = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
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

  return <div className="text-card-version-picker" ref={anchor}>
    <button aria-expanded={open} aria-haspopup="menu" ref={trigger}
      className="content-card-chip text-card-version-tag" type="button"
      onClick={() => setOpen((value) => !value)}>
      <Stack size={12} aria-hidden />v{artifact.resourceDefaultVersion.versionNo}
      <CaretDown size={10} aria-hidden />
    </button>
    {open ? <DropdownMenu className="text-card-version-menu" role="menu" aria-label="文字版本"
      anchorRef={anchor} triggerRef={trigger} onDismiss={() => setOpen(false)} focusOnOpen>
      {disabled ? <p>请先保存或退出编辑，再切换版本。</p> : null}
      {history.isPending ? <p>正在读取版本…</p> : null}
      {history.error ? <p role="alert">{history.error instanceof ApiError
        ? history.error.message : "版本历史读取失败，请重试。"}</p> : null}
      {history.data?.items.map((version) => <button className="text-card-version-option"
        disabled={disabled || select.isPending || version.id === artifact.resourceDefaultVersionId}
        aria-current={version.id === artifact.resourceDefaultVersionId ? "true" : undefined}
        key={version.id} onClick={() => select.mutate(version.id)} role="menuitem" type="button">
        <span>v{version.versionNo}</span>
        <small>{version.id === artifact.resourceDefaultVersionId ? "当前选用" : version.createdByKind}</small>
      </button>)}
      {select.error ? <p role="alert">{select.error instanceof ApiError && select.error.status === 409
        ? "内容有冲突，未切换版本；请重新打开版本列表后重试。"
        : select.error instanceof ApiError ? select.error.message : "版本选用失败，请重试。"}</p> : null}
    </DropdownMenu> : null}
  </div>;
}
