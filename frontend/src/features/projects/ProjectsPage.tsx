import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Archive,ArrowClockwise,ArrowRight,Folder,MagnifyingGlass,PencilSimple,Plus,Trash,X } from "@/shared/ui/icons";
import { useInfiniteQuery,useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { type FormEvent,useId,useRef,useState } from "react";
import { Link,useNavigate } from "react-router";
import {
ApiError,
HTTP_STATUS,
archiveProject,
createProject,
deleteProject,
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
import { Dialog,DialogContent,DialogDescription,DialogFooter,DialogHeader,DialogTitle,DialogTrigger } from "../../shared/ui/primitives/dialog";
import { Select } from "../../shared/ui/Select";
import { cn } from "../../shared/lib/utils";
import "./ProjectsPage.css";

const PROJECT_PAGE_SIZE = 100;
const PROJECT_NAME_MAX_LENGTH = 120;
const ASPECT_LABELS: Record<Project["aspectRatio"], string> = {
  get LANDSCAPE_16_9() { return t("projects.landscapeRatio"); }, get PORTRAIT_9_16() { return t("projects.portraitRatio"); }, get SQUARE_1_1() { return t("projects.squareRatio"); },
};
const ASPECT_RATIOS: Record<Project["aspectRatio"], string> = {
  LANDSCAPE_16_9: "16:9", PORTRAIT_9_16: "9:16", SQUARE_1_1: "1:1",
};

/** Project discovery and commands remain scoped to the authenticated owner. */
export function ProjectsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const searchScopeId = useId();
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
    onSuccess: (project) => {
      setName("");
      queryClient.setQueryData(["projects", project.id], project);
      // A list refresh must not hold up entry into the project just created.
      void queryClient.invalidateQueries({ queryKey: ["projects"], exact: false });
      navigate(`/projects/${project.id}`);
    },
  });
  const loadedProjects = projects.data?.pages.flatMap((page) => page.items) ?? [];
  const query = search.trim().toLocaleLowerCase();
  const visibleProjects = loadedProjects.filter((project) => project.name.toLocaleLowerCase().includes(query));
  const searchScope = projects.hasNextPage
    ? t("projects.searchMoreHint", { "0": loadedProjects.length })
    : t("projects.searchCompleteHint", { "0": loadedProjects.length });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (create.isPending || !name.trim()) return;
    create.mutate({ name: name.trim(), aspectRatio });
  }

  return (
    <PageShell title={t("common.project")} description={t("projects.description")} actions={<Button onClick={() => nameInput.current?.focus()} type="button"><Plus data-icon="inline-start" aria-hidden="true" />{t("projects.newProject")}</Button>}>
      <div className="projects-layout">
        <section className="projects-library" aria-label={t("projects.title")}>
          <div className="projects-list-heading"><h2>{t("projects.title")}</h2><StatusBadge>{t("projects.loadedCount", { "0": loadedProjects.length })}</StatusBadge>{query ? <span className="ui-muted">{t("projects.matchCount", { "0": visibleProjects.length })}</span> : null}</div>
          <div className="projects-toolbar">
            <label className="projects-search ui-search-field"><MagnifyingGlass size={17} aria-hidden="true" /><Input variant="ghost" aria-label={t("projects.search")} aria-describedby={query && projects.data ? searchScopeId : undefined} placeholder={t("projects.searchPlaceholder")} type="search" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
            <div className="projects-filter-actions"><label className="projects-archive-filter"><Checkbox checked={includeArchived} onCheckedChange={(event) => setIncludeArchived(event === true)} />{t("projects.showArchived")}</label>
              <Button variant="outline" size="icon" aria-label={t("projects.refresh")} disabled={projects.isFetching} onClick={() => void projects.refetch()} type="button"><ArrowClockwise aria-hidden="true" /></Button></div>
          </div>
          {query && projects.data ? <p id={searchScopeId} className="projects-search-scope ui-muted" role="status">{searchScope}</p> : null}
          {projects.isPending ? <LoadingState label={t("common.projectLoading")} /> : null}
          {projects.error ? <Notice title={t("projects.loadFailed")} tone="danger"><p>{errorMessage(projects.error)}</p><Button variant="outline"  disabled={projects.isFetching} onClick={() => void (projects.isFetchNextPageError ? projects.fetchNextPage() : projects.refetch())} type="button">{t("common.retryRead")}</Button></Notice> : null}
          {projects.isSuccess && loadedProjects.length === 0 ? <EmptyState icon={<Folder size={28} aria-hidden="true" />} title={t("projects.emptyTitle")} description={t("projects.emptyHint")} action={<Button variant="outline"  onClick={() => nameInput.current?.focus()} type="button"><Plus size={15} aria-hidden="true" />{t("projects.newProject")}</Button>} /> : null}
          {loadedProjects.length > 0 && visibleProjects.length === 0 ? <EmptyState icon={<MagnifyingGlass size={28} aria-hidden="true" />} title={projects.hasNextPage ? t("projects.emptyLoadedSearch") : t("projects.emptySearch")} description={projects.hasNextPage ? t("projects.searchLoadMoreHint") : t("projects.searchScopeHint")} action={<Button variant="outline"  onClick={() => setSearch("")} type="button">{t("projects.clearSearch")}</Button>} /> : null}
          <div className="projects-grid">{visibleProjects.map((project) => <ProjectCard key={project.id} project={project} />)}</div>
          {projects.hasNextPage ? <div className="projects-load-more"><Button variant="outline"  disabled={projects.isFetching} onClick={() => void projects.fetchNextPage()} type="button">{projects.isFetchingNextPage ? t("projects.loading") : t("projects.loadMore")}</Button></div> : null}
        </section>
        <Panel className="projects-create" title={t("projects.newProject")} description={t("projects.newProjectHint")} actions={<span className="projects-create-symbol" aria-hidden="true"><Plus size={19} /></span>}>
          <div className="projects-create-preview"><ProjectFrame aspectRatio={aspectRatio} /><span>{ASPECT_LABELS[aspectRatio]}</span></div>
          <form className="ui-form" onSubmit={submit} aria-busy={create.isPending}>
            <FieldGroup className="projects-create-fields">
              <Field><FieldLabel className="ui-field block"><span>{t("projects.name")}</span><Input ref={nameInput} disabled={create.isPending} maxLength={PROJECT_NAME_MAX_LENGTH} placeholder={t("projects.namePlaceholder")} required value={name} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
              <Field><FieldLabel className="ui-field block"><span>{t("projects.aspectRatio")}</span><Select disabled={create.isPending} value={aspectRatio} onChange={(event) => setAspectRatio(event.target.value as Project["aspectRatio"])}>{Object.entries(ASPECT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></FieldLabel></Field>
            </FieldGroup>
            {create.error ? <Notice tone="danger">{errorMessage(create.error)}</Notice> : null}
            <Button disabled={create.isPending || !name.trim()} type="submit"><Plus data-icon="inline-start" aria-hidden="true" />{create.isPending ? t("common.creating") : t("projects.create")}</Button>
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
  const nameInput = useRef<HTMLInputElement>(null);
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(project.name);
  const [editVersion, setEditVersion] = useState(project.version);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteVersion, setDeleteVersion] = useState(project.version);
  const archived = project.status === "ARCHIVED";
  const rename = useMutation({
    mutationFn: (nextName: string) => updateProject(project.id, { expectedVersion: editVersion, name: nextName }),
    onSuccess: async () => {
      setEditing(false);
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
    onError: (error) => {
      // Refresh the server name/version while retaining the draft and its CAS baseline.
      if (error instanceof ApiError && error.status === HTTP_STATUS.CONFLICT) {
        void queryClient.invalidateQueries({ queryKey: ["projects"] });
      }
    },
  });
  const archive = useMutation({ mutationFn: () => archiveProject(project), onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects"] }) });
  const remove = useMutation({
    mutationFn: () => deleteProject(project.id, deleteVersion),
    onSuccess: async () => {
      setDeleteOpen(false);
      queryClient.removeQueries({ predicate: (query) => query.queryKey.includes(project.id) });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["projects"] }),
        queryClient.invalidateQueries({ queryKey: ["resources"] }),
      ]);
    },
  });
  const pending = rename.isPending || archive.isPending || remove.isPending;

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
    <article className={cn("project-card", archived && "project-card--archived")} aria-label={project.name}>
      <header className="project-card-cover" aria-hidden="true"><span className="project-card-folder"><Folder size={17} /></span><ProjectFrame aspectRatio={project.aspectRatio} /></header>
      <div className="project-card-body">
        <div className="project-card-status"><span>{ASPECT_LABELS[project.aspectRatio]}</span><StatusBadge tone={archived ? "neutral" : "success"}>{archived ? t("projects.archived") : t("projects.active")}</StatusBadge></div>
      {editing ? (
        <form className="ui-form project-rename" onSubmit={submit} aria-busy={rename.isPending}>
          <Field><FieldLabel className="ui-field block"><span>{t("projects.newName")}</span><Input ref={nameInput} autoFocus disabled={pending} maxLength={PROJECT_NAME_MAX_LENGTH} required value={name} onChange={(event) => setName(event.target.value)} /></FieldLabel></Field>
          {rename.error ? <Notice tone="danger">{errorMessage(rename.error)}</Notice> : null}
          {project.version !== editVersion ? <Notice tone="warning"><p>{t("projects.conflict")}</p><p>{t("projects.latestName", { "0": project.name })}</p><Button variant="outline" size="sm" disabled={pending} type="button" onClick={() => {
            // Adopting the visible server version preserves input; saving is a separate action.
            setEditVersion(project.version);
            rename.reset();
            nameInput.current?.focus({ preventScroll: true });
          }}>{t("projects.continueRename")}</Button></Notice> : null}
          <div className="ui-form-actions"><Button disabled={pending || archived || !name.trim() || project.version !== editVersion} type="submit">{rename.isPending ? t("common.savingProgress") : t("projects.saveName")}</Button><Button variant="ghost" disabled={pending} onClick={() => setEditing(false)} type="button"><X data-icon="inline-start" aria-hidden="true" />{t("common.cancel")}</Button></div>
        </form>
      ) : <><h3>{project.name}</h3><p className="project-card-meta"><time dateTime={project.updatedAt}>{t("projects.updated", { "0": formatDate(project.updatedAt, { month: "short", day: "numeric" }) })}</time></p></>}
      {archive.error ? <Notice tone="danger">{errorMessage(archive.error)}</Notice> : null}
      {!archived && !editing ? <footer className="project-card-actions"><Button variant="ghost" size="sm" className="project-open" asChild><Link to={`/projects/${project.id}`}>{t("projects.openCanvas")}<ArrowRight data-icon="inline-end" aria-hidden="true" /></Link></Button><Button variant="ghost" size="icon-sm" aria-label={t("projects.renameNamed", { "0": project.name })} disabled={pending} onClick={startEditing} type="button"><PencilSimple aria-hidden="true" /></Button><Button variant="ghost" size="icon-sm" aria-label={t("projects.archiveNamed", { "0": project.name })} disabled={pending} onClick={() => archive.mutate()} type="button"><Archive aria-hidden="true" /></Button></footer> : null}
      {archived ? <footer className="project-card-actions">
        <Dialog open={deleteOpen} onOpenChange={(open) => { if (!remove.isPending) setDeleteOpen(open); }}>
          <DialogTrigger asChild><Button variant="ghost" size="sm" disabled={pending}
            aria-label={t("projects.deleteNamed", { "0": project.name })}
            onClick={() => { setDeleteVersion(project.version); remove.reset(); }} type="button">
            <Trash data-icon="inline-start" aria-hidden="true" />{t("projects.delete")}
          </Button></DialogTrigger>
          <DialogContent showCloseButton={false} aria-busy={remove.isPending}>
            <DialogHeader><DialogTitle>{t("projects.deleteTitle", { "0": project.name })}</DialogTitle>
              <DialogDescription>{t("projects.deleteDescription")}</DialogDescription></DialogHeader>
            {remove.error ? <Notice tone="danger">{errorMessage(remove.error)}</Notice> : null}
            {project.version !== deleteVersion ? <Notice tone="warning">{t("projects.deleteConflict")}</Notice> : null}
            <DialogFooter>
              <Button variant="outline" disabled={remove.isPending} onClick={() => setDeleteOpen(false)} type="button">{t("common.cancel")}</Button>
              <Button variant="destructive" disabled={pending || project.version !== deleteVersion}
                onClick={() => { if (!pending && project.version === deleteVersion) remove.mutate(); }} type="button">
                {remove.isPending ? t("projects.deleting") : t("projects.confirmDelete")}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </footer> : null}
      {archive.isPending ? <LoadingState label={t("projects.archiving")} compact /> : null}
      </div>
    </article>
  );
}

/** A neutral frame study communicates the saved format without inventing project artwork. */
function ProjectFrame({ aspectRatio }: { aspectRatio: Project["aspectRatio"] }) {
  return <div className="project-frame-study" data-aspect={aspectRatio} aria-hidden="true"><div className="project-frame-outline"><span className="project-frame-center" /><span className="project-frame-ratio">{ASPECT_RATIOS[aspectRatio]}</span></div></div>;
}

function errorMessage(error: Error): string {
  return error instanceof ApiError ? error.message : t("projects.actionFailed");
}
