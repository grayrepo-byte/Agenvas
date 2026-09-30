import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Cube, FilmStrip, FolderSimple, GearSix, ListMagnifyingGlass, SidebarSimple, SignOut, UserCircle } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { ApiError, getCurrentUser, logout } from "../api/client";
import { LoadingState } from "./LoadingState";
import { Notice } from "./PagePrimitives";
import { useNavigationStore } from "./navigationStore";
import "./PageShell.css";

const UNAUTHORIZED_STATUS = 401;
const NAVIGATION = [
  { to: "/projects", label: "项目", icon: FolderSimple },
  { to: "/settings/providers", label: "Provider 配置", icon: Cube },
  { to: "/settings/media", label: "媒体配置", icon: FilmStrip },
  { to: "/settings/calls", label: "调用日志", icon: ListMagnifyingGlass },
  { to: "/settings/general", label: "系统诊断", icon: GearSix },
] as const;

/** Sidebar row hierarchy and collapse motion adapt Beautiful UI SidebarNav; all navigation is real. */
export function PageShell({ title, description, actions, children }: {
  title: string; description?: ReactNode; actions?: ReactNode; children: ReactNode;
}) {
  const collapsed = useNavigationStore((state) => state.collapsed);
  const toggleNavigation = useNavigationStore((state) => state.toggle);
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const signOut = useMutation({ mutationFn: logout, onSuccess: () => {
    queryClient.clear();
    navigate("/login", { replace: true });
  } });

  if (session.error instanceof ApiError && session.error.status === UNAUTHORIZED_STATUS) {
    return <Navigate to="/login" replace />;
  }
  if (session.isPending) return <main className="app-page app-page-loading"><LoadingState label="正在读取会话" /></main>;
  const sessionError = <Notice tone="danger" title={session.data ? "会话暂时无法刷新" : "暂时无法读取会话"}>
    <p>{session.data ? "当前页面与输入已保留，请检查服务连接后重试。" : "请检查服务连接后重试。"}</p>
    <button className="secondary-button" type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>
      {session.isFetching ? "正在重试…" : "重试连接"}</button>
  </Notice>;
  // A failed background refresh must not unmount forms and discard their drafts.
  if (!session.data) return <main className="app-page app-page-loading">{sessionError}</main>;

  const activePath = location.pathname === "/settings/llm" ? "/settings/providers" : location.pathname;
  return <div className={`app-page app-shell${collapsed ? " app-shell--collapsed" : ""}`}>
    <a className="app-skip-link" href="#page-content">跳至页面内容</a>
    <aside className="app-sidebar" aria-label="应用侧栏">
      <div className="app-sidebar-brand"><Link to="/projects" aria-label="Agenvas 项目首页">
        <span className="app-brand-mark" aria-hidden><Cube size={21} weight="duotone" /></span>
        <span className="app-sidebar-copy">Agenvas</span>
      </Link><button className="app-sidebar-toggle" type="button" aria-label={collapsed ? "展开导航" : "收起导航"}
        aria-expanded={!collapsed} aria-controls="app-navigation" onClick={toggleNavigation}><SidebarSimple size={18} /></button></div>
      <span className="app-sidebar-section app-sidebar-copy">工作空间</span>
      <nav id="app-navigation" aria-label="主导航">{NAVIGATION.map(({ to, label, icon: Icon }) => <Link key={to} to={to}
        className={`app-nav-item${activePath === to ? " is-active" : ""}`} aria-current={activePath === to ? "page" : undefined}
        aria-label={label} title={collapsed ? label : undefined}>
        <Icon size={18} aria-hidden /><span className="app-sidebar-copy">{label}</span>
      </Link>)}</nav>
      <div className="app-sidebar-footer"><div className="app-user"><UserCircle size={23} aria-hidden />
        <div className="app-sidebar-copy"><strong>{session.data.loginName}</strong><span>管理员 · 自托管</span></div></div>
        <button className="app-nav-item" type="button" disabled={signOut.isPending} onClick={() => signOut.mutate()}
          aria-label={signOut.isPending ? "正在退出…" : "退出登录"} title={collapsed ? "退出登录" : undefined}>
          <SignOut size={18} aria-hidden /><span className="app-sidebar-copy">{signOut.isPending ? "正在退出…" : "退出登录"}</span>
        </button></div>
    </aside>
    <main className="app-page-main" id="page-content" tabIndex={-1}>
      <header className="app-page-header"><div><p className="app-page-eyebrow">Agenvas / {activePath === "/projects" ? "工作空间" : "设置"}</p>
        <h1>{title}</h1>{description ? <p className="app-page-description">{description}</p> : null}</div>
        {actions ? <div className="ui-form-actions">{actions}</div> : null}
      </header>
      {session.isError ? sessionError : null}
      {signOut.error ? <Notice tone="danger">退出失败，请重试。当前会话仍然保留。</Notice> : null}
      <div className="app-page-content">{children}</div>
    </main>
  </div>;
}
