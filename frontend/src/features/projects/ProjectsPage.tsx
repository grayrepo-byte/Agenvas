import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { Archive,ArrowClockwise,ArrowRight,Folder,MagnifyingGlass,PencilSimple,Plus,X } from "@/shared/ui/icons";
import { useInfiniteQuery,useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { type FormEvent,useRef,useState } from "react";
import { Link } from "react-router";
import {
ApiError,
archiveProject,
createProject,
getCurrentUser,
listProjects,
type Project,
updateProject,
} from "../../shared/api/client";
import { formatDate,t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import "./ProjectsPage.css";

const PROJECT_PAGE_SIZE = 100;
const PROJECT_NAME_MAX_LENGTH = 120;
const ASPECT_LABELS: Record<Project["aspectRatio"], string> = {
  get LANDSCAPE_16_9() { return t("projects.landscapeRatio"); }, get PORTRAIT_9_16() { return t("projects.portraitRatio"); }, get SQUARE_1_1() { return t("projects.squareRatio"); },
};


/** Project discovery and commands remain scoped to the authenticated owner. */
export function ProjectsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const nameInput = useRef<HTMLInputElement>(null);
  const [includeArchived, setIncludeArchived] = useState(false);
  const [search, setSearch] = useState("");
  const [name, setName] = useState("");
  const [aspectRatio, setAspectRatio] = useState<Project["aspectRatio"]>("LANDSCAPE_16_9");
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const projects = useInfiniteQuery({
    queryKey: ["projects", { includeArchived }],
    queryFn: ({ pageParam }) => listProjects({ includeArchived, limit: PROJECT_PAGE_SIZE, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: currentUser.isSuccess,
    retry: false,
  });
  const create = useMutation({
    mutationFn: createProject,
    onSuccess: async () => {
      setName("");
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
  });
  const loadedProjects = projects.data?.pages.flatMap((page) => page.items) ?? [];
  const query = search.trim().toLocaleLowerCase();
  const visibleProjects = loadedProjects.filter((project) => project.name.toLocaleLowerCase().includes(query));

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (create.isPending || !name.trim()) return;
    create.mutate({ name: name.trim(), aspectRatio });
  }

  return (
    <PageShell title={t("common.project")} description={t("projects.description")} actions={<Button variant="default"  onClick={() => nameInput.current?.focus()} type="button"><Plus size={16} aria-hidden="true" />{t("projects.newProject")}</Button>}>
      <div className="projects-layout">
        <section className="projects-library" aria-label={t("projects.title")}>
          <div className="projects-toolbar">
            <label className="projects-search"><MagnifyingGlass size={17} aria-hidden="true" /><Input aria-label={t("projects.search")} placeholder={t("projects.searchPlaceholder")} type="search" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
            <label className="projects-archive-filter"><Checkbox checked={includeArchived} onCheckedChange={(event) => setIncludeArchived(event === true)}  />{t("projects.showArchived")}</label>
            <Button variant="ghost"  aria-label={t("projects.refresh")} disabled={projects.isFetching} onClick={() => void projects.refetch()} type="button"><ArrowClockwise size={17} aria-hidden="true" /></Button>
          </div>
          <div className="projects-list-heading"><h2>{t("projects.title")}</h2><StatusBadge>{t("projects.loadedCount", { "0": loadedProjects.length })}</StatusBadge>{query ? <span className="ui-muted">{t("projects.matchCount", { "0": visibleProjects.length })}</span> : null}</div>
          {projects.isPending ? <LoadingState label={t("common.projectLoading")} /> : null}
          {projects.error ? <Notice title={t("projects.loadFailed")} tone="danger"><p>{errorMessage(projects.error)}</p><Button variant="outline"  disabled={projects.isFetching} onClick={() => void (projects.isFetchNextPageError ? projects.fetchNextPage() : projects.refetch())} type="button">{t("common.retryRead")}</Button></Notice> : null}
          {projects.isSuccess && loadedProjects.length === 0 ? <EmptyState icon={<Folder size={28} aria-hidden="true" />} title={t("projects.emptyTitle")} description={t("projects.emptyHint")} action={<Button variant="outline"  onClick={() => nameInput.current?.focus()} type="button"><Plus size={15} aria-hidden="true" />{t("projects.newProject")}</Button>} /> : null}
          {loadedProjects.length > 0 && visibleProjects.length === 0 ? <EmptyState icon={<MagnifyingGlass size={28} aria-hidden="true" />} title={t("projects.emptySearch")} description={t("projects.searchScopeHint")} action={<Button variant="outline"  onClick={() => setSearch("")} type="button">{t("projects.clearSearch")}</Button>} /> : null}
          <div className="projects-grid">{visibleProjects.map((project) => <ProjectCard key={project.id} project={project} />)}</div>
          {projects.hasNextPage ? <div className="projects-load-more"><Button variant="outline"  disabled={projects.isFetching} onClick={() => void projects.fetchNextPage()} type="button">{projects.isFetchingNextPage ? t("projects.loading") : t("projects.loadMore")}</Button></div> : null}
        </section>
        <Panel className="projects-create" title={t("projects.newProject")} description={t("projects.newProjectHint")}>
          <form className="ui-form" onSubmit={submit} aria-busy={create.isPending}>
            <Field><FieldLabel className="ui-field block"><span>{t("projects.name")}</span><Input ref={nameInput} disabled={create.isPending} maxLength={PROJECT_NAME_MAX_LENGTH} placeholder={t("projects.namePlaceholder")} required value={name} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
            <Field><FieldLabel className="ui-field block"><span>{t("projects.aspectRatio")}</span><Select disabled={create.isPending} value={aspectRatio} onChange={(event) => setAspectRatio(event.target.value as Project["aspectRatio"])}>{Object.entries(ASPECT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></FieldLabel></Field>
            {create.error ? <Notice tone="danger">{errorMessage(create.error)}</Notice> : null}
            <Button variant="default"  disabled={create.isPending || !name.trim()} type="submit"><Plus size={16} aria-hidden="true" />{create.isPending ? t("common.creating") : t("projects.create")}</Button>
            {create.isSuccess ? <Notice tone="success">{t("projects.created")}</Notice> : null}
          </form>
        </Panel>
      </div>
    </PageShell>
  );
}

/** Each card owns its command state, so concurrent errors stay beside their target. */
function ProjectCard({ project }: { project: Project }) {
  useLocale();
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(project.name);
  const [editVersion, setEditVersion] = useState(project.version);
  const archived = project.status === "ARCHIVED";
  const rename = useMutation({
    mutationFn: (nextName: string) => updateProject(project.id, { expectedVersion: editVersion, name: nextName }),
    onSuccess: async () => {
      setEditing(false);
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
  });
  const archive = useMutation({ mutationFn: () => archiveProject(project), onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects"] }) });
  const pending = rename.isPending || archive.isPending;

  function startEditing() {
    setName(project.name);
    setEditVersion(project.version);
    rename.reset();
    setEditing(true);
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!name.trim() || pending || archived || project.version !== editVersion) return;
    rename.mutate(name.trim());
  }

  return (
    <article className="project-card" aria-label={project.name}>
      <header className="project-card-top"><span className={`project-card-mark project-card-mark-${project.aspectRatio.toLowerCase()}`}><span aria-hidden="true" /></span><StatusBadge tone={archived ? "neutral" : "success"}>{archived ? t("projects.archived") : t("projects.active")}</StatusBadge></header>
      {editing ? (
        <form className="ui-form project-rename" onSubmit={submit} aria-busy={rename.isPending}>
          <Field><FieldLabel className="ui-field block"><span>{t("projects.newName")}</span><Input autoFocus disabled={pending} maxLength={PROJECT_NAME_MAX_LENGTH} required value={name} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
          {rename.error ? <Notice tone="danger">{errorMessage(rename.error)}</Notice> : null}
          {project.version !== editVersion ? <Notice tone="warning">{t("projects.conflict")}</Notice> : null}
          <div className="ui-form-actions"><Button variant="default"  disabled={pending || archived || !name.trim() || project.version !== editVersion} type="submit">{rename.isPending ? t("common.savingProgress") : t("projects.saveName")}</Button><Button variant="ghost"  disabled={pending} onClick={() => setEditing(false)} type="button"><X size={14} aria-hidden="true" />{t("common.cancel")}</Button></div>
        </form>
      ) : <><h3>{project.name}</h3><p className="project-card-meta">{ASPECT_LABELS[project.aspectRatio]}<span aria-hidden="true">·</span><time dateTime={project.updatedAt}>{t("projects.updated", { "0": formatDate(project.updatedAt, { month: "short", day: "numeric" }) })}</time></p></>}
      {archive.error ? <Notice tone="danger">{errorMessage(archive.error)}</Notice> : null}
      {!archived && !editing ? <footer className="project-card-actions"><Link className="project-open" to={`/projects/${project.id}`}>{t("projects.openCanvas")}<ArrowRight size={15} aria-hidden="true" /></Link><Button variant="ghost"  aria-label={t("projects.renameNamed", { "0": project.name })} disabled={pending} onClick={startEditing} type="button"><PencilSimple size={16} aria-hidden="true" /></Button><Button variant="ghost"  aria-label={t("projects.archiveNamed", { "0": project.name })} disabled={pending} onClick={() => archive.mutate()} type="button"><Archive size={16} aria-hidden="true" /></Button></footer> : null}
      {archive.isPending ? <LoadingState label={t("projects.archiving")} compact /> : null}
    </article>
  );
}

function errorMessage(error: Error): string {
  return error instanceof ApiError ? error.message : t("projects.actionFailed");
}
