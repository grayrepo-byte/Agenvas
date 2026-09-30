import { Select } from "../../shared/ui/Select";
import { Archive, ArrowClockwise, ArrowRight, Folder, MagnifyingGlass, PencilSimple, Plus, X } from "@phosphor-icons/react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useRef, useState } from "react";
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
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import "./ProjectsPage.css";

const PROJECT_PAGE_SIZE = 100;
const PROJECT_NAME_MAX_LENGTH = 120;
const ASPECT_LABELS: Record<Project["aspectRatio"], string> = {
  LANDSCAPE_16_9: "横屏 16:9", PORTRAIT_9_16: "竖屏 9:16", SQUARE_1_1: "方形 1:1",
};
const UPDATED_DATE_FORMAT = new Intl.DateTimeFormat("zh-CN", { month: "short", day: "numeric" });

/** Project discovery and commands remain scoped to the authenticated owner. */
export function ProjectsPage() {
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
    <PageShell title="项目" description="从一个想法开始，回到你的创作空间。" actions={<button className="primary-button" onClick={() => nameInput.current?.focus()} type="button"><Plus size={16} aria-hidden="true" />新建项目</button>}>
      <div className="projects-layout">
        <section className="projects-library" aria-label="我的项目">
          <div className="projects-toolbar">
            <label className="projects-search"><MagnifyingGlass size={17} aria-hidden="true" /><input aria-label="搜索已加载项目" placeholder="搜索已加载项目…" type="search" value={search} onChange={(event) => setSearch(event.target.value)} /></label>
            <label className="projects-archive-filter"><input checked={includeArchived} onChange={(event) => setIncludeArchived(event.target.checked)} type="checkbox" />显示已归档</label>
            <button className="ghost-button" aria-label="刷新项目" disabled={projects.isFetching} onClick={() => void projects.refetch()} type="button"><ArrowClockwise size={17} aria-hidden="true" /></button>
          </div>
          <div className="projects-list-heading"><h2>我的项目</h2><StatusBadge>{loadedProjects.length} 个已加载</StatusBadge>{query ? <span className="ui-muted">{visibleProjects.length} 个匹配</span> : null}</div>
          {projects.isPending ? <LoadingState label="正在读取项目…" /> : null}
          {projects.error ? <Notice title="项目读取失败" tone="danger"><p>{errorMessage(projects.error)}</p><button className="secondary-button" disabled={projects.isFetching} onClick={() => void (projects.isFetchNextPageError ? projects.fetchNextPage() : projects.refetch())} type="button">重试读取</button></Notice> : null}
          {projects.isSuccess && loadedProjects.length === 0 ? <EmptyState icon={<Folder size={28} aria-hidden="true" />} title="你的第一个项目，从这里开始" description="创建项目，把灵感、素材与生成结果放在同一张画布上。" action={<button className="secondary-button" onClick={() => nameInput.current?.focus()} type="button"><Plus size={15} aria-hidden="true" />新建项目</button>} /> : null}
          {loadedProjects.length > 0 && visibleProjects.length === 0 ? <EmptyState icon={<MagnifyingGlass size={28} aria-hidden="true" />} title="没有匹配的项目" description="搜索仅涵盖已加载项目，试试其他名称。" action={<button className="secondary-button" onClick={() => setSearch("")} type="button">清除搜索</button>} /> : null}
          <div className="projects-grid">{visibleProjects.map((project) => <ProjectCard key={project.id} project={project} />)}</div>
          {projects.hasNextPage ? <div className="projects-load-more"><button className="secondary-button" disabled={projects.isFetching} onClick={() => void projects.fetchNextPage()} type="button">{projects.isFetchingNextPage ? "正在加载…" : "加载更多项目"}</button></div> : null}
        </section>
        <Panel className="projects-create" title="新建项目" description="为新的创作留一张空白画布。">
          <form className="ui-form" onSubmit={submit} aria-busy={create.isPending}>
            <label className="ui-field"><span>项目名称</span><input ref={nameInput} disabled={create.isPending} maxLength={PROJECT_NAME_MAX_LENGTH} placeholder="给你的作品起个名字" required value={name} onChange={(event) => setName(event.target.value)} /></label>
            <label className="ui-field"><span>画幅</span><Select disabled={create.isPending} value={aspectRatio} onChange={(event) => setAspectRatio(event.target.value as Project["aspectRatio"])}>{Object.entries(ASPECT_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></label>
            {create.error ? <Notice tone="danger">{errorMessage(create.error)}</Notice> : null}
            <button className="primary-button" disabled={create.isPending || !name.trim()} type="submit"><Plus size={16} aria-hidden="true" />{create.isPending ? "正在创建…" : "创建项目"}</button>
            {create.isSuccess ? <Notice tone="success">项目已创建，可从列表打开画布。</Notice> : null}
          </form>
        </Panel>
      </div>
    </PageShell>
  );
}

/** Each card owns its command state, so concurrent errors stay beside their target. */
function ProjectCard({ project }: { project: Project }) {
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
      <header className="project-card-top"><span className={`project-card-mark project-card-mark-${project.aspectRatio.toLowerCase()}`}><span aria-hidden="true" /></span><StatusBadge tone={archived ? "neutral" : "success"}>{archived ? "已归档" : "进行中"}</StatusBadge></header>
      {editing ? (
        <form className="ui-form project-rename" onSubmit={submit} aria-busy={rename.isPending}>
          <label className="ui-field"><span>项目新名称</span><input autoFocus disabled={pending} maxLength={PROJECT_NAME_MAX_LENGTH} required value={name} onChange={(event) => setName(event.target.value)} /></label>
          {rename.error ? <Notice tone="danger">{errorMessage(rename.error)}</Notice> : null}
          {project.version !== editVersion ? <Notice tone="warning">项目已更新。请取消后重新编辑，以载入最新名称和版本。</Notice> : null}
          <div className="ui-form-actions"><button className="primary-button" disabled={pending || archived || !name.trim() || project.version !== editVersion} type="submit">{rename.isPending ? "正在保存…" : "保存名称"}</button><button className="ghost-button" disabled={pending} onClick={() => setEditing(false)} type="button"><X size={14} aria-hidden="true" />取消</button></div>
        </form>
      ) : <><h3>{project.name}</h3><p className="project-card-meta">{ASPECT_LABELS[project.aspectRatio]}<span aria-hidden="true">·</span><time dateTime={project.updatedAt}>{UPDATED_DATE_FORMAT.format(new Date(project.updatedAt))}更新</time></p></>}
      {archive.error ? <Notice tone="danger">{errorMessage(archive.error)}</Notice> : null}
      {!archived && !editing ? <footer className="project-card-actions"><Link className="project-open" to={`/projects/${project.id}`}>打开画布<ArrowRight size={15} aria-hidden="true" /></Link><button className="ghost-button" aria-label={`重命名 ${project.name}`} disabled={pending} onClick={startEditing} type="button"><PencilSimple size={16} aria-hidden="true" /></button><button className="ghost-button" aria-label={`归档 ${project.name}`} disabled={pending} onClick={() => archive.mutate()} type="button"><Archive size={16} aria-hidden="true" /></button></footer> : null}
      {archive.isPending ? <LoadingState label="正在归档…" compact /> : null}
    </article>
  );
}

function errorMessage(error: Error): string {
  return error instanceof ApiError ? error.message : "操作失败，请重试。";
}
