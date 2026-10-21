import { CaretRight,Cube,FilmStrip,FolderSimple,GearSix,HardDrives,ListMagnifyingGlass,PlugsConnected,ShieldCheck,SidebarSimple,SignOut,Sparkle,TerminalWindow,UserCircle } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useEffect,useRef,type ReactNode } from "react";
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
import { cn } from "../lib/utils";
import "./SettingsWorkspace.css";

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

/** Settings retain the same destinations and collapse state as the workspace. */
const SETTINGS_GROUPS = [
  { label: "ui.pageShell.workspace", paths: ["/projects", "/library", "/skills"] },
  { label: "settings.workspace.configuration", paths: ["/settings/providers", "/settings/media", "/settings/functions", "/settings/storage"] },
  { label: "settings.workspace.observability", paths: ["/settings/calls", "/settings/logs"] },
  { label: "settings.workspace.preferences", paths: ["/settings/general"] },
] as const;

/** Sidebar row hierarchy and collapse motion adapt Beautiful UI SidebarNav; all navigation is real. */
export function PageShell({ title, description, actions, children }: {
  title: string; description?: ReactNode; actions?: ReactNode; children: ReactNode;
}) {
  const locale = useLocale();
  const collapsed = useNavigationStore((state) => state.collapsed);
  const toggleNavigation = useNavigationStore((state) => state.toggle);
  const location = useLocation();
  const navigation = useRef<HTMLElement>(null);
  const activePath = location.pathname === "/settings/llm" ? "/settings/providers" : location.pathname;
  const isSettings = activePath.startsWith("/settings/");
  const isStudio = isSettings || activePath === "/projects" || activePath === "/library" || activePath === "/skills";
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const session = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const signOut = useMutation({ mutationFn: logout, onSuccess: () => {
    queryClient.clear();
    navigate("/login", { replace: true });
  } });
  // Enter each settings screen at its header without disturbing later edits or refreshes.
  useEffect(() => {
    if (isStudio && (document.documentElement.scrollTop > 0 || document.body.scrollTop > 0)) {
      window.scrollTo({ top: 0, left: 0, behavior: "instant" });
    }
  }, [activePath, isStudio, session.data?.id]);
  // Reveal the active destination in either sidebar direction, including after translation changes.
  useEffect(() => {
    if (isStudio) {
      navigation.current?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "instant" });
    }
  }, [activePath, isStudio, session.data?.id, locale]);

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

  const activeItem = NAVIGATION.find((item) => item.to === activePath);
  const PageIcon = activeItem?.icon ?? GearSix;
  const navigationItem = ({ to, label, icon: Icon }: typeof NAVIGATION[number]) => <Link key={to} to={to}
    className={cn("app-nav-item", activePath === to && "is-active")} aria-current={activePath === to ? "page" : undefined}
    aria-label={label} title={collapsed ? label : undefined}>
    <Icon size={18} aria-hidden /><span className="app-sidebar-copy">{label}</span>
    {isStudio && activePath === to ? <CaretRight className="settings-nav-chevron" size={12} aria-hidden /> : null}
  </Link>;
  return <div className={cn("app-page app-shell", collapsed && "app-shell--collapsed", isSettings && "app-shell--settings", isStudio && "app-shell--studio")}>
    <a className="app-skip-link" href="#page-content">{t("ui.pageShell.skipToContent")}</a>
    <aside className="app-sidebar" aria-label={t("ui.pageShell.sidebar")}>
      <div className="app-sidebar-brand"><Link to="/projects" aria-label={t("ui.pageShell.homeLabel")}>
        <BrandLogo className="app-brand-horizontal" decorative />
        <BrandLogo variant="square" className="app-brand-square" decorative />
      </Link><Button variant="ghost" className="app-sidebar-toggle" type="button" aria-label={collapsed ? t("ui.pageShell.expandNavigation") : t("ui.pageShell.collapseNavigation")}
        aria-expanded={!collapsed} aria-controls="app-navigation" onClick={toggleNavigation}><SidebarSimple size={18} /></Button></div>
      {!isStudio ? <span className="app-sidebar-section app-sidebar-copy">{t("ui.pageShell.workspace")}</span> : null}
      <nav ref={navigation} id="app-navigation" aria-label={t("ui.pageShell.mainNavigation")}>
        {isStudio ? SETTINGS_GROUPS.map((group) => <div className="settings-nav-group" key={group.label}>
          <span className="app-sidebar-section app-sidebar-copy">{t(group.label)}</span>
          {NAVIGATION.filter((item) => (group.paths as readonly string[]).includes(item.to)).map(navigationItem)}
        </div>) : NAVIGATION.map(navigationItem)}
      </nav>
      <div className="app-sidebar-footer"><LanguageSelect compact={collapsed} /><div className="app-user"><UserCircle size={23} aria-hidden />
        <div className="app-sidebar-copy"><strong>{session.data.loginName}</strong><span>{t("ui.pageShell.adminLabel")}</span></div></div>
        <Button variant="ghost" className="app-nav-item" type="button" disabled={signOut.isPending} onClick={() => signOut.mutate()}
          aria-label={signOut.isPending ? t("ui.pageShell.signingOut") : t("ui.pageShell.signOut")} title={collapsed ? t("ui.pageShell.signOut") : undefined}>
          <SignOut size={18} aria-hidden /><span className="app-sidebar-copy">{signOut.isPending ? t("ui.pageShell.signingOut") : t("ui.pageShell.signOut")}</span>
        </Button></div>
    </aside>
    <main className="app-page-main" id="page-content" tabIndex={-1}>
      {isStudio ? <div className="settings-workspace-topbar">
        <div className="settings-breadcrumb"><PageIcon size={16} aria-hidden /><span>{t(isSettings ? "ui.pageShell.settings" : "ui.pageShell.workspace")}</span><CaretRight size={12} aria-hidden /><span>{title}</span></div>
        <span className="settings-workspace-trust"><ShieldCheck size={15} aria-hidden />{t("settings.workspace.selfHosted")}</span>
      </div> : null}
      <header className="app-page-header">
        {isStudio ? <div className="settings-page-heading"><div className="settings-page-icon"><PageIcon size={27} weight="duotone" aria-hidden /></div><div>
          <p className="app-page-eyebrow">{t(isSettings ? "settings.workspace.eyebrow" : "ui.pageShell.workspace")}</p><h1>{title}</h1>
          {description ? <p className="app-page-description">{description}</p> : null}
        </div></div> : <div><p className="app-page-eyebrow">Agenvas / {t("ui.pageShell.workspace")}</p>
          <h1>{title}</h1>{description ? <p className="app-page-description">{description}</p> : null}</div>}
        {actions ? <div className="ui-form-actions">{actions}</div> : null}
      </header>
      {session.isError ? sessionError : null}
      {signOut.error ? <Notice tone="danger">{t("ui.pageShell.signOutFailed")}</Notice> : null}
      <div className="app-page-content">{children}</div>
    </main>
  </div>;
}
