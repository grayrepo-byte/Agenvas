import { CaretDown, Stack } from "@/shared/ui/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  HTTP_STATUS,
  ApiError,
  listArtifactVersions,
  setArtifactResourceDefaultVersion,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { DropdownMenu, DropdownMenuGroup, DropdownMenuTrigger } from "../../shared/ui/primitives/dropdown-menu";
import { readContentText } from "./artifactContent";
import {
  CanvasVersionMenuContent,
  CanvasVersionMenuError,
  CanvasVersionMenuNotice,
  CanvasVersionMenuOption,
  CanvasVersionMenuStatus,
  VERSION_SOURCE_LABELS,
} from "./CanvasVersionMenu";
import type { VersionedArtifact } from "./versionedArtifact";

/** Selects the actual Artifact version used by the card and by new outgoing connections. */
export function TextVersionPicker({ artifact, disabled = false }: { artifact: VersionedArtifact; disabled?: boolean }) {
  useLocale();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const history = useQuery({
    queryKey: ["artifact-versions", artifact.projectId, artifact.id],
    queryFn: () => listArtifactVersions(artifact.projectId, artifact.id),
    enabled: open,
  });
  const select = useMutation({
    mutationFn: (versionId: string) =>
      setArtifactResourceDefaultVersion(artifact.projectId, artifact.id, versionId, artifact.version),
    onSuccess: async () => {
      setOpen(false);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId, artifact.id] }),
      ]);
    },
  });
  const refresh = useMutation({
    // Refresh only the server projections; selecting a result remains a separate user action.
    // Keep writes blocked until every projection settles, including when one read fails early.
    mutationFn: async () => {
      const results = await Promise.allSettled([
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }, { throwOnError: true }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }, { throwOnError: true }),
        history.refetch({ throwOnError: true }),
      ]);
      const failed = results.find(result => result.status === "rejected");
      if (failed?.status === "rejected") throw failed.reason;
    },
    onSuccess: () => select.reset(),
  });

  return (
    <DropdownMenu open={open} onOpenChange={setOpen} modal={false}>
      <div className="text-card-version-picker">
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            aria-expanded={open}
            aria-haspopup="menu"
            className="text-card-version-tag"
            type="button"
          >
            <Stack data-icon="inline-start" aria-hidden />
            {t("artifacts.versions.versionLabel", { "0": ` v${artifact.resourceDefaultVersion.versionNo}` })}
            <CaretDown data-icon="inline-end" aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        {open ? (
          <CanvasVersionMenuContent
            className="text-card-version-menu"
            aria-label={t("text.versions.title")}
            heading={t("artifacts.versions.title")}
            count={history.data?.items.length}
            footer={<p className="canvas-version-hint">{t("artifacts.versions.selectionHint")}</p>}
          >
            {disabled ? (
              <CanvasVersionMenuNotice>{t("text.versions.saveBeforeSelection")}</CanvasVersionMenuNotice>
            ) : null}
            {history.isPending ? <CanvasVersionMenuStatus label={t("common.versionLoading")} /> : null}
            {history.error ? (
              <CanvasVersionMenuError
                message={history.error instanceof ApiError ? history.error.message : t("artifacts.versions.loadFailed")}
                action={
                  <Button
                    variant="ghost"
                    size="sm"
                    type="button"
                    disabled={history.isFetching || refresh.isPending || select.isPending}
                    onClick={() => void history.refetch()}
                  >
                    {t("common.retryRead")}
                  </Button>
                }
              />
            ) : null}
            <DropdownMenuGroup className="canvas-version-list">
              {history.data?.items.map(version => (
                <CanvasVersionMenuOption
                  key={version.id}
                  className="canvas-version-option--text"
                  versionNo={version.versionNo}
                  selected={version.id === artifact.resourceDefaultVersionId}
                  summary={readContentText(version.content, "text") || t("text.card.emptyHint")}
                  source={t(VERSION_SOURCE_LABELS[version.createdByKind])}
                  createdAt={version.createdAt}
                  disabled={
                    disabled ||
                    select.isPending ||
                    refresh.isPending ||
                    !!select.error ||
                    version.id === artifact.resourceDefaultVersionId
                  }
                  onSelect={event => {
                    event.preventDefault();
                    if (disabled || select.isPending || refresh.isPending || select.error) return;
                    select.mutate(version.id);
                  }}
                />
              ))}
            </DropdownMenuGroup>
            {history.isSuccess && history.data.items.length === 0 ? (
              <p className="canvas-version-empty">{t("text.card.noVersion")}</p>
            ) : null}
            {select.isPending ? <CanvasVersionMenuStatus label={t("artifacts.versions.selecting")} /> : null}
            {select.error ? (
              <CanvasVersionMenuError
                message={
                  select.error instanceof ApiError && select.error.status === HTTP_STATUS.CONFLICT
                    ? t("text.versions.selectionConflict")
                    : select.error instanceof ApiError
                      ? select.error.message
                      : t("artifacts.versions.selectFailed")
                }
                action={
                  <Button
                    variant="ghost"
                    size="xs"
                    type="button"
                    disabled={refresh.isPending || select.isPending}
                    onClick={() => refresh.mutate()}
                  >
                    {t("media.versions.refresh")}
                  </Button>
                }
              />
            ) : null}
            {refresh.isPending ? <CanvasVersionMenuStatus label={t("common.versionLoading")} /> : null}
            {refresh.error ? (
              <CanvasVersionMenuError
                message={refresh.error instanceof Error ? refresh.error.message : t("artifacts.versions.loadFailed")}
              />
            ) : null}
          </CanvasVersionMenuContent>
        ) : null}
      </div>
    </DropdownMenu>
  );
}
