import { Cube,FilmStrip,FolderSimple,GearSix,HardDrives,ListMagnifyingGlass,SidebarSimple,SignOut,TerminalWindow,UserCircle } from "@phosphor-icons/react";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link,Navigate,useLocation,useNavigate } from "react-router";
import { ApiError,getCurrentUser,logout } from "../api/client";
import { t,useLocale } from "../i18n";
import { LanguageSelect } from "../i18n/LanguageSelect";
import { BrandLogo } from "./BrandLogo";
import { LoadingState } from "./LoadingState";
import { Notice } from "./PagePrimitives";
import "./PageShell.css";
import { useNavigationStore } from "./navigationStore";
import { Button } from "./primitives/button";

const UNAUTHORIZED_STATUS = 401;
const NAVIGATION = [
  { to: "/projects", get label() { return t("项目"); }, icon: FolderSimple },
  { to: "/library", get label() { return t("资产"); }, icon: Cube },
  { to: "/settings/providers", get label() { return t("Provider 配置"); }, icon: Cube },
  { to: "/settings/media", get label() { return t("媒体配置"); }, icon: FilmStrip },
  { to: "/settings/storage", get label() { return t("资源存储"); }, icon: HardDrives },
  { to: "/settings/calls", get label() { return t("调用日志"); }, icon: ListMagnifyingGlass },
  { to: "/settings/logs", get label() { return t("系统日志"); }, icon: TerminalWindow },
  { to: "/settings/general", get label() { return t("系统设置"); }, icon: GearSix },
] as const;

/** Sidebar row hierarchy and collapse motion adapt Beautiful UI SidebarNav; all navigation is real. */
export function PageShell({ title, description, actions, children }: {
  title: string; description?: ReactNode; actions?: ReactNode; children: ReactNode;
}) {
  useLocale();
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
  if (session.isPending) return <main className="app-page app-page-loading"><LoadingState label={t("正在读取会话")} /></main>;
  const sessionError = <Notice tone="danger" title={session.data ? t("会话暂时无法刷新") : t("暂时无法读取会话")}>
    <p>{session.data ? t("当前页面与输入已保留，请检查服务连接后重试。") : t("请检查服务连接后重试。")}</p>
    <Button variant="outline"  type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>
      {session.isFetching ? t("正在重试…") : t("重试连接")}</Button>
  </Notice>;
  // A failed background refresh must not unmount forms and discard their drafts.
  if (!session.data) return <main className="app-page app-page-loading">{sessionError}</main>;

  const activePath = location.pathname === "/settings/llm" ? "/settings/providers" : location.pathname;
  return <div className={`app-page app-shell${collapsed ? " app-shell--collapsed" : ""}`}>
    <a className="app-skip-link" href="#page-content">{t("跳至页面内容")}</a>
    <aside className="app-sidebar" aria-label={t("应用侧栏")}>
      <div className="app-sidebar-brand"><Link to="/projects" aria-label={t("Agenvas 项目首页")}>
        <BrandLogo className="app-brand-horizontal" decorative />
        <BrandLogo variant="square" className="app-brand-square" decorative />
      </Link><Button variant="ghost" className="app-sidebar-toggle" type="button" aria-label={collapsed ? t("展开导航") : t("收起导航")}
        aria-expanded={!collapsed} aria-controls="app-navigation" onClick={toggleNavigation}><SidebarSimple size={18} /></Button></div>
      <span className="app-sidebar-section app-sidebar-copy">{t("工作空间")}</span>
      <nav id="app-navigation" aria-label={t("主导航")}>{NAVIGATION.map(({ to, label, icon: Icon }) => <Link key={to} to={to}
        className={`app-nav-item${activePath === to ? " is-active" : ""}`} aria-current={activePath === to ? "page" : undefined}
        aria-label={label} title={collapsed ? label : undefined}>
        <Icon size={18} aria-hidden /><span className="app-sidebar-copy">{label}</span>
      </Link>)}</nav>
      <div className="app-sidebar-footer"><LanguageSelect compact={collapsed} /><div className="app-user"><UserCircle size={23} aria-hidden />
        <div className="app-sidebar-copy"><strong>{session.data.loginName}</strong><span>{t("管理员 · 自托管")}</span></div></div>
        <Button variant="ghost" className="app-nav-item" type="button" disabled={signOut.isPending} onClick={() => signOut.mutate()}
          aria-label={signOut.isPending ? t("正在退出…") : t("退出登录")} title={collapsed ? t("退出登录") : undefined}>
          <SignOut size={18} aria-hidden /><span className="app-sidebar-copy">{signOut.isPending ? t("正在退出…") : t("退出登录")}</span>
        </Button></div>
    </aside>
    <main className="app-page-main" id="page-content" tabIndex={-1}>
      <header className="app-page-header"><div><p className="app-page-eyebrow">Agenvas / {activePath === "/projects" || activePath === "/library" ? t("工作空间") : t("设置")}</p>
        <h1>{title}</h1>{description ? <p className="app-page-description">{description}</p> : null}</div>
        {actions ? <div className="ui-form-actions">{actions}</div> : null}
      </header>
      {session.isError ? sessionError : null}
      {signOut.error ? <Notice tone="danger">{t("退出失败，请重试。当前会话仍然保留。")}</Notice> : null}
      <div className="app-page-content">{children}</div>
    </main>
  </div>;
}
