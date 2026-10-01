import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router";
import { getCurrentUser } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";
import "../../shared/ui/Dialog.css";
import { Panel } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Tabs,TabsContent,TabsList,TabsTrigger } from "../../shared/ui/primitives/tabs";
import { CallLogRetentionSection } from "./CallLogRetentionSection";
import { DebugModeSection } from "./DebugModeSection";
import { PasswordChangeSection } from "./PasswordChangeSection";
import "./SettingsPages.css";
import { SystemDiagnosticsSection } from "./SystemDiagnosticsSection";

const TABS = ["general", "security", "logs", "diagnostics"] as const;
type SettingsTab = typeof TABS[number];
const FIRST_TAB = 0;
function tabLabel(tab: SettingsTab): string {
  switch (tab) {
    case "general": return t("常规");
    case "security": return t("账户安全");
    case "logs": return t("调用日志");
    case "diagnostics": return t("系统诊断");
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
  return <PageShell title={t("系统设置")} description={t("按类别管理界面偏好、账户安全与调用日志，查看本地运行状态。") }>
    <Tabs value={selected} onValueChange={(value) => { const tab = TABS.find((item) => item === value); if (tab) choose(tab); }}>
    <TabsList className="system-settings-tabs w-full" aria-label={t("系统设置分类")}>
      {TABS.map((tab) => <TabsTrigger key={tab} value={tab}>{tabLabel(tab)}</TabsTrigger>)}
    </TabsList>
    {TABS.map((tab) => <TabsContent key={tab} value={tab} forceMount className="system-settings-panel ui-stack" hidden={selected !== tab}>
      {tab === "general" ? <Panel title={t("语言")} description={t("选择界面和服务端响应的语言。此偏好仅保存在当前浏览器。")}><LanguageSelect /></Panel> : null}
      {tab === "security" ? <PasswordChangeSection /> : null}
      {tab === "logs" ? <><CallLogRetentionSection enabled={currentUser.isSuccess && selected === tab} /><DebugModeSection enabled={currentUser.isSuccess && selected === tab} /></> : null}
      {tab === "diagnostics" ? <SystemDiagnosticsSection enabled={currentUser.isSuccess && selected === tab} /> : null}
    </TabsContent>)}
    </Tabs>
  </PageShell>;
}
