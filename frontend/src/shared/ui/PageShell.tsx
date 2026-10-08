import { Cube,FilmStrip,FolderSimple,GearSix,HardDrives,ListMagnifyingGlass,PlugsConnected,SidebarSimple,SignOut,Sparkle,TerminalWindow,UserCircle } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link,Navigate,useLocation,useNavigate } from "react-router";
import { HTTP_STATUS,ApiError,getCurrentUser,logout } from "../api/client";
import { t,useLocale } from "../i18n";
import { LanguageSelect } from "../i18n/LanguageSelect";
import { BrandLogo } from "./BrandLogo";
import { LoadingState } from "./LoadingState";
import { Notice } from "./PagePrimitives";
import "./PageShell.css";
import { useNavigationStore } from "./navigationStore";
import { Button } from "./primitives/button";

const NAVIGATION = [
  { to: "/projects", get label() { return t("common.project"); }, icon: FolderSimple },
  { to: "/library", get label() { return t("common.asset"); }, icon: Cube },
  { to: "/skills", get label() { return t("skills.title"); }, icon: Sparkle },
  { to: "/settings/providers", get label() { return t("settings.providerTitle"); }, icon: PlugsConnected },
  { to: "/settings/media", get label() { return t("ui.pageShell.mediaSettings"); }, icon: FilmStrip },
  { to: "/settings/functions", get label() { return t("media.functions.title"); }, icon: Sparkle },
  { to: "/settings/storage", get label() { return t("settings.storage.title"); }, icon: HardDrives },
  { to: "/settings/calls", get label() { return t("common.callLogs"); }, icon: ListMagnifyingGlass },
  { to: "/settings/logs", get label() { return t("settings.systemLogs.title"); }, icon: TerminalWindow },
  { to: "/settings/general", get label() { return t("common.systemSettings"); }, icon: GearSix },
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

  if (session.error instanceof ApiError && session.error.status === HTTP_STATUS.UNAUTHORIZED) {
    return <Navigate to="/login" replace />;
  }
  if (session.isPending) return <main className="app-page app-page-loading"><LoadingState label={t("ui.pageShell.sessionLoading")} /></main>;
  const sessionError = <Notice tone="danger" title={session.data ? t("ui.pageShell.sessionRefreshFailed") : t("ui.pageShell.sessionUnavailable")}>
    <p>{session.data ? t("ui.pageShell.sessionRetryHint") : t("ui.pageShell.connectionRetryHint")}</p>
    <Button variant="outline"  type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>
      {session.isFetching ? t("common.retrying") : t("ui.pageShell.reconnect")}</Button>
  </Notice>;
  // A failed background refresh must not unmount forms and discard their drafts.
  if (!session.data) return <main className="app-page app-page-loading">{sessionError}</main>;

  const activePath = location.pathname === "/settings/llm" ? "/settings/providers" : location.pathname;
  return <div className={`app-page app-shell${collapsed ? " app-shell--collapsed" : ""}`}>
    <a className="app-skip-link" href="#page-content">{t("ui.pageShell.skipToContent")}</a>
    <aside className="app-sidebar" aria-label={t("ui.pageShell.sidebar")}>
      <div className="app-sidebar-brand"><Link to="/projects" aria-label={t("ui.pageShell.homeLabel")}>
        <BrandLogo className="app-brand-horizontal" decorative />
        <BrandLogo variant="square" className="app-brand-square" decorative />
      </Link><Button variant="ghost" className="app-sidebar-toggle" type="button" aria-label={collapsed ? t("ui.pageShell.expandNavigation") : t("ui.pageShell.collapseNavigation")}
        aria-expanded={!collapsed} aria-controls="app-navigation" onClick={toggleNavigation}><SidebarSimple size={18} /></Button></div>
      <span className="app-sidebar-section app-sidebar-copy">{t("ui.pageShell.workspace")}</span>
      <nav id="app-navigation" aria-label={t("ui.pageShell.mainNavigation")}>{NAVIGATION.map(({ to, label, icon: Icon }) => <Link key={to} to={to}
        className={`app-nav-item${activePath === to ? " is-active" : ""}`} aria-current={activePath === to ? "page" : undefined}
        aria-label={label} title={collapsed ? label : undefined}>
        <Icon size={18} aria-hidden /><span className="app-sidebar-copy">{label}</span>
      </Link>)}</nav>
      <div className="app-sidebar-footer"><LanguageSelect compact={collapsed} /><div className="app-user"><UserCircle size={23} aria-hidden />
        <div className="app-sidebar-copy"><strong>{session.data.loginName}</strong><span>{t("ui.pageShell.adminLabel")}</span></div></div>
        <Button variant="ghost" className="app-nav-item" type="button" disabled={signOut.isPending} onClick={() => signOut.mutate()}
          aria-label={signOut.isPending ? t("ui.pageShell.signingOut") : t("ui.pageShell.signOut")} title={collapsed ? t("ui.pageShell.signOut") : undefined}>
          <SignOut size={18} aria-hidden /><span className="app-sidebar-copy">{signOut.isPending ? t("ui.pageShell.signingOut") : t("ui.pageShell.signOut")}</span>
        </Button></div>
    </aside>
    <main className="app-page-main" id="page-content" tabIndex={-1}>
      <header className="app-page-header"><div><p className="app-page-eyebrow">Agenvas / {activePath === "/projects" || activePath === "/library" || activePath === "/skills" ? t("ui.pageShell.workspace") : t("ui.pageShell.settings")}</p>
        <h1>{title}</h1>{description ? <p className="app-page-description">{description}</p> : null}</div>
        {actions ? <div className="ui-form-actions">{actions}</div> : null}
      </header>
      {session.isError ? sessionError : null}
      {signOut.error ? <Notice tone="danger">{t("ui.pageShell.signOutFailed")}</Notice> : null}
      <div className="app-page-content">{children}</div>
    </main>
  </div>;
}
