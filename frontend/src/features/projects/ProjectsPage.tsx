import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router";
import {
  ApiError,
  archiveProject,
  createProject,
  getCurrentUser,
  listProjects,
  logout,
  type Project,
  updateProject,
} from "../../shared/api/client";

/** Owner-scoped project list with create, rename, aspect update, and archive commands. */
export function ProjectsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [includeArchived, setIncludeArchived] = useState(false);
  const [name, setName] = useState("");
  const [aspectRatio, setAspectRatio] = useState<Project["aspectRatio"]>("LANDSCAPE_16_9");
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const projects = useQuery({
    queryKey: ["projects", { includeArchived }],
    queryFn: () => listProjects({ includeArchived, limit: 100 }),
    enabled: currentUser.isSuccess,
  });
  const create = useMutation({
    mutationFn: createProject,
    onSuccess: async () => {
      setName("");
      await queryClient.invalidateQueries({ queryKey: ["projects"] });
    },
  });
  const archive = useMutation({
    mutationFn: archiveProject,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects"] }),
  });
  const rename = useMutation({
    mutationFn: ({ project, nextName }: { project: Project; nextName: string }) =>
      updateProject(project.id, { expectedVersion: project.version, name: nextName }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["projects"] }),
  });
  const logoutRequest = useMutation({
    mutationFn: logout,
    onSuccess: () => {
      queryClient.clear();
      navigate("/login", { replace: true });
    },
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    create.mutate({ name, aspectRatio });
  }

  if (currentUser.isError) return <Navigate to="/login" replace />;

  return (
    <main className="min-h-screen bg-[var(--canvas)] p-8 text-[var(--ink)]">
      <header className="mx-auto flex max-w-6xl items-center justify-between">
        <div>
          <p className="text-sm text-[var(--muted)]">Agent Canvas</p>
          <h1 className="text-3xl font-semibold">项目</h1>
        </div>
        <div className="flex items-center gap-4">
          <span className="text-sm text-[var(--muted)]">{currentUser.data?.loginName}</span>
          <Link className="secondary-button" to="/settings/providers">Provider 配置</Link>
          <Link className="secondary-button" to="/settings/general">系统诊断</Link>
          <button className="secondary-button" disabled={logoutRequest.isPending} onClick={() => logoutRequest.mutate()} type="button">退出登录</button>
        </div>
      </header>

      <div className="mx-auto mt-10 grid max-w-6xl gap-8 lg:grid-cols-[20rem_1fr]">
        <form className="h-fit rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6" onSubmit={submit}>
          <h2 className="text-xl font-semibold">新建项目</h2>
          <label className="mt-5 block text-sm font-medium">项目名称<input maxLength={120} required value={name} onChange={(event) => setName(event.target.value)} /></label>
          <label className="mt-4 block text-sm font-medium">画幅
            <select className="mt-2 w-full rounded-xl border border-[var(--line)] bg-white p-3" value={aspectRatio} onChange={(event) => setAspectRatio(event.target.value as Project["aspectRatio"])}>
              <option value="LANDSCAPE_16_9">横屏 16:9</option>
              <option value="PORTRAIT_9_16">竖屏 9:16</option>
              <option value="SQUARE_1_1">方形 1:1</option>
            </select>
          </label>
          {create.error ? <MutationError error={create.error} /> : null}
          <button className="primary-button mt-5 w-full" disabled={create.isPending} type="submit">{create.isPending ? "正在创建…" : "创建项目"}</button>
        </form>

        <section>
          <div className="flex items-center justify-between">
            <h2 className="text-xl font-semibold">我的项目</h2>
            <label className="flex items-center gap-2 text-sm text-[var(--muted)]"><input className="m-0 h-4 w-4" checked={includeArchived} onChange={(event) => setIncludeArchived(event.target.checked)} type="checkbox" />显示已归档</label>
          </div>
          {projects.isPending ? <p className="mt-8 text-[var(--muted)]">正在读取项目…</p> : null}
          {projects.error ? <MutationError error={projects.error} /> : null}
          {projects.data?.items.length === 0 ? <div className="mt-6 rounded-3xl border border-dashed border-[var(--line)] bg-white/50 p-12 text-center text-[var(--muted)]">还没有项目。从左侧创建第一个三镜头创作空间。</div> : null}
          <div className="mt-6 grid gap-4 sm:grid-cols-2">
            {projects.data?.items.map((project) => (
              <ProjectCard key={project.id} project={project} onArchive={() => archive.mutate(project)} onRename={(nextName) => rename.mutate({ project, nextName })} />
            ))}
          </div>
          {archive.error ? <MutationError error={archive.error} /> : null}
          {rename.error ? <MutationError error={rename.error} /> : null}
        </section>
      </div>
    </main>
  );
}

function ProjectCard({ project, onArchive, onRename }: { project: Project; onArchive: () => void; onRename: (name: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(project.name);
  const archived = project.status === "ARCHIVED";
  return (
    <article className="rounded-3xl border border-[var(--line)] bg-white/70 p-5">
      {editing ? (
        <form onSubmit={(event) => { event.preventDefault(); onRename(name); setEditing(false); }}>
          <input autoFocus maxLength={120} required value={name} onChange={(event) => setName(event.target.value)} />
          <div className="mt-3 flex gap-2"><button className="primary-button" type="submit">保存</button><button className="secondary-button" onClick={() => setEditing(false)} type="button">取消</button></div>
        </form>
      ) : (
        <><div className="flex items-start justify-between gap-3"><h3 className="text-lg font-semibold">{project.name}</h3><span className="rounded-full bg-slate-100 px-2 py-1 text-xs">{archived ? "已归档" : "进行中"}</span></div>
          <p className="mt-2 text-sm text-[var(--muted)]">{aspectLabel(project.aspectRatio)} · 版本 {project.version}</p>
          {!archived ? <div className="mt-5 flex flex-wrap gap-2"><Link className="primary-button" to={`/projects/${project.id}`}>打开画布</Link><button className="secondary-button" onClick={() => setEditing(true)} type="button">重命名</button><button className="secondary-button" onClick={onArchive} type="button">归档</button></div> : null}</>
      )}
    </article>
  );
}

function aspectLabel(aspect: Project["aspectRatio"]): string {
  return { LANDSCAPE_16_9: "横屏 16:9", PORTRAIT_9_16: "竖屏 9:16", SQUARE_1_1: "方形 1:1" }[aspect];
}

function MutationError({ error }: { error: Error }) {
  const message = error instanceof ApiError ? error.message : "操作失败，请重试。";
  return <p className="mt-4 rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{message}</p>;
}
