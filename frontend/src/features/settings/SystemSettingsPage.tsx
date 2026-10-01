import { t, useLocale } from "../../shared/i18n";
import { useQuery } from "@tanstack/react-query";
import { useId } from "react";
import { useSearchParams } from "react-router";
import { getCurrentUser } from "../../shared/api/client";
import { Panel } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { DebugModeSection } from "./DebugModeSection";
import { CallLogRetentionSection } from "./CallLogRetentionSection";
import { PasswordChangeSection } from "./PasswordChangeSection";
import { SystemDiagnosticsSection } from "./SystemDiagnosticsSection";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";
import "../../shared/ui/Dialog.css";
import "./SettingsPages.css";

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
  const id = useId();
  const [params, setParams] = useSearchParams();
  const candidate = params.get("tab");
  const selected = TABS.find((tab) => tab === candidate) ?? TABS[FIRST_TAB];
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const choose = (tab: SettingsTab) => { const next = new URLSearchParams(params); next.set("tab", tab); setParams(next); };
  return <PageShell title={t("系统设置")} description={t("按类别管理界面偏好、账户安全与调用日志，查看本地运行状态。") }>
    <div className="system-settings-tabs ui-tabs" role="tablist" aria-label={t("系统设置分类")}>
      {TABS.map((tab, index) => <button key={tab} type="button" role="tab" id={`${id}-${tab}-tab`}
        aria-selected={selected === tab} aria-controls={`${id}-${tab}-panel`} tabIndex={selected === tab ? 0 : -1}
        onClick={() => choose(tab)} onKeyDown={(event) => {
          if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
          event.preventDefault();
          const next = event.key === "Home" ? FIRST_TAB : event.key === "End" ? TABS.length - 1
            : (index + (event.key === "ArrowRight" ? 1 : TABS.length - 1)) % TABS.length;
          const nextTab = TABS[next];
          if (nextTab) { choose(nextTab); document.getElementById(`${id}-${nextTab}-tab`)?.focus(); }
        }}>{tabLabel(tab)}</button>)}
    </div>
    {TABS.map((tab) => <div key={tab} className="system-settings-panel ui-stack" role="tabpanel" id={`${id}-${tab}-panel`}
      aria-labelledby={`${id}-${tab}-tab`} hidden={selected !== tab}>
      {tab === "general" ? <Panel title={t("语言")} description={t("选择界面和服务端响应的语言。此偏好仅保存在当前浏览器。")}><LanguageSelect /></Panel> : null}
      {tab === "security" ? <PasswordChangeSection /> : null}
      {tab === "logs" ? <><CallLogRetentionSection enabled={currentUser.isSuccess && selected === tab} /><DebugModeSection enabled={currentUser.isSuccess && selected === tab} /></> : null}
      {tab === "diagnostics" ? <SystemDiagnosticsSection enabled={currentUser.isSuccess && selected === tab} /> : null}
    </div>)}
  </PageShell>;
}
