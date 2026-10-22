import { useQuery } from "@tanstack/react-query";
import { Navigate, useSearchParams } from "react-router";
import { Cpu, ImagesSquare, ListMagnifyingGlass, LockSimple, PaintBrush, SlidersHorizontal, TextT, Translate, type Icon } from "../../shared/ui/icons";
import { ApiError, HTTP_STATUS, getCurrentUser } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";
import "../../shared/ui/Dialog.css";
import { Notice, Panel } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";
import { PageShell } from "../../shared/ui/PageShell";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { CallLogRetentionSection } from "./CallLogRetentionSection";
import { DebugModeSection } from "./DebugModeSection";
import { PromptManagementSection } from "./PromptManagementSection";
import { PasswordChangeSection } from "./PasswordChangeSection";
import { McpTokensSection } from "./McpTokensSection";
import "./SettingsPages.css";
import "./SystemSettingsPage.css";
import { SystemDiagnosticsSection } from "./SystemDiagnosticsSection";
import { MediaStyleSettingsSection } from "./MediaStyleSettingsSection";
import { SystemMediaTemplatesSection } from "../templates/MediaTemplatePicker";

const TABS = ["general", "prompts", "styles", "security", "logs", "diagnostics", "templates"] as const;
type SettingsTab = typeof TABS[number];
const FIRST_TAB = 0;
const TAB_ICONS: Record<SettingsTab, Icon> = {
  general: SlidersHorizontal,
  prompts: TextT,
  styles: PaintBrush,
  security: LockSimple,
  logs: ListMagnifyingGlass,
  diagnostics: Cpu,
  templates: ImagesSquare,
};
function tabLabel(tab: SettingsTab): string {
  switch (tab) {
    case "general": return t("settings.general.general");
    case "prompts": return t("prompts.title");
    case "styles": return t("styles.title");
    case "security": return t("settings.general.security");
    case "logs": return t("common.callLogs");
    case "diagnostics": return t("settings.general.diagnostics");
    case "templates": return t("templates.systemTitle");
  }
}
function tabDescription(tab: SettingsTab): string {
  switch (tab) {
    case "general": return t("settings.general.languageHint");
    case "prompts": return t("settings.general.promptsHint");
    case "styles": return t("settings.general.stylesHint");
    case "security": return t("settings.general.securityHint");
    case "logs": return t("settings.general.logsHint");
    case "diagnostics": return t("settings.general.diagnosticsHint");
    case "templates": return t("settings.general.templatesHint");
  }
}

/** Tab panels remain mounted to preserve unsaved drafts; reads are enabled by category. */
export function SystemSettingsPage() {
  useLocale();
  const [params, setParams] = useSearchParams();
  const candidate = params.get("tab");
  const selected = TABS.find((tab) => tab === candidate) ?? TABS[FIRST_TAB];
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const choose = (tab: SettingsTab) => { const next = new URLSearchParams(params); next.set("tab", tab); setParams(next); };
  if (currentUser.error instanceof ApiError && currentUser.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <PageShell title={t("common.systemSettings")} description={t("settings.general.description") }>
    {currentUser.isError ? <Notice tone="danger"><p>{t("settings.shared.sessionHint")}</p><Button variant="outline" type="button" disabled={currentUser.isFetching} onClick={() => { void currentUser.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    <Tabs className="system-settings-workspace" orientation="vertical" value={selected} onValueChange={(value) => { const tab = TABS.find((item) => item === value); if (tab) choose(tab); }}>
    <div className="system-settings-navigation">
      <div className="system-settings-navigation-intro"><h2>{t("settings.general.categories")}</h2><p>{t("settings.general.navigationHint")}</p></div>
      <TabsList className="system-settings-tabs" variant="line" aria-label={t("settings.general.categories")}>
        {TABS.map((tab) => {
          const CategoryIcon = TAB_ICONS[tab];
          return <TabsTrigger className="system-settings-tab" key={tab} value={tab} aria-label={tabLabel(tab)}>
            <CategoryIcon data-icon="inline-start" aria-hidden /><span>{tabLabel(tab)}<small aria-hidden>{tabDescription(tab)}</small></span>
          </TabsTrigger>;
        })}
      </TabsList>
    </div>
    <div className="system-settings-content">
    <header className="system-settings-category-heading"><h2>{tabLabel(selected)}</h2><p>{tabDescription(selected)}</p></header>
    {TABS.map((tab) => <TabsContent key={tab} value={tab} forceMount className="system-settings-panel ui-stack" data-category={tab} hidden={selected !== tab}>
      {tab === "general" ? <Panel className="system-settings-language-panel">
        <div className="system-settings-language-intro">
          <span className="system-settings-language-symbol" aria-hidden><Translate weight="light" /></span>
          <div><h2>{t("settings.general.language")}</h2><p>{t("settings.general.languageHint")}</p></div>
        </div>
        <div className="system-settings-language-control"><LanguageSelect /></div>
      </Panel> : null}
      {tab === "styles" ? <MediaStyleSettingsSection enabled={currentUser.isSuccess && selected === tab} /> : null}
      {tab === "prompts" ? <PromptManagementSection enabled={currentUser.isSuccess && selected === tab} /> : null}
      {tab === "security" ? <><PasswordChangeSection /><McpTokensSection key={currentUser.data?.id} userId={currentUser.data?.id} enabled={currentUser.isSuccess && selected === tab} /></> : null}
      {tab === "logs" ? <><CallLogRetentionSection enabled={currentUser.isSuccess && selected === tab} /><DebugModeSection enabled={currentUser.isSuccess && selected === tab} /></> : null}
      {tab === "diagnostics" ? <SystemDiagnosticsSection enabled={currentUser.isSuccess && selected === tab} /> : null}
      {tab === "templates" ? <SystemMediaTemplatesSection enabled={currentUser.data?.role === "ADMIN" && selected === tab} /> : null}
    </TabsContent>)}
    </div>
    </Tabs>
  </PageShell>;
}
