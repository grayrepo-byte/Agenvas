import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";
import { Link } from "react-router";
import { listProjects } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Notice } from "../../shared/ui/PagePrimitives";
import { Select } from "../../shared/ui/Select";
import { Button } from "../../shared/ui/primitives/button";
import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { ReferenceCatalog } from "./ReferenceCatalog";

const PLACEMENT = { x: 80, y: 80 };
/** Placement is an explicit user action; browsing and selecting a project never imports images. */
export function ReferenceCatalogPage() {
  useLocale(); const id = useId(); const client = useQueryClient();
  const [projectId, setProjectId] = useState(""); const [busy, setBusy] = useState(false); const [placedProject, setPlacedProject] = useState<string | null>(null);
  const projects = useInfiniteQuery({ queryKey: ["projects", "reference-target"], initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => listProjects({ cursor: pageParam }), getNextPageParam: (page) => page.nextCursor ?? undefined, retry: false });
  return <ReferenceCatalog projectId={projectId} placement={PLACEMENT} actionLabel={t("reference.place")} onBusy={setBusy} onApply={async (image) => {
    if (!image.canvasItemId) throw new Error(t("reference.placeFailed"));
    setPlacedProject(projectId); void client.invalidateQueries({ queryKey: ["canvas", projectId] });
    void client.invalidateQueries({ queryKey: ["artifacts", projectId] });
  }}>
    <Field className="reference-target"><FieldLabel htmlFor={id}>{t("reference.project")}</FieldLabel><Select id={id} value={projectId} disabled={busy} onChange={(event) => setProjectId(event.target.value)}><option value="">{t("reference.chooseProject")}</option>{projects.data?.pages.flatMap((page) => page.items).filter((project) => project.status === "ACTIVE").map((project) => <option key={project.id} value={project.id}>{project.name}</option>)}</Select></Field>
    {projects.error ? <Notice tone="danger"><p>{projects.error.message}</p><Button type="button" variant="outline" onClick={() => void projects.refetch()}>{t("common.retry")}</Button></Notice> : null}
    {projects.hasNextPage ? <Button type="button" variant="ghost" disabled={projects.isFetchingNextPage} onClick={() => void projects.fetchNextPage()}>{t("projects.loadMore")}</Button> : null}
    {placedProject ? <Notice tone="success"><p>{t("reference.placed")}</p><Button asChild variant="outline"><Link to={`/projects/${placedProject}`}>{t("library.page.openProject")}</Link></Button></Notice> : null}
  </ReferenceCatalog>;
}
