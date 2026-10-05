import { ArrowsClockwise,CheckCircle,Cpu,Database,HardDrives,Plugs } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getSystemDiagnostics } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { Button } from "../../shared/ui/primitives/button";


/** Only the diagnostics tab reads local diagnostic state; it never calls Providers. */
export function SystemDiagnosticsSection({ enabled }: { enabled: boolean }) {
  useLocale();
  const diagnostics = useQuery({ queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, enabled, retry: false });
  if (diagnostics.error instanceof ApiError && diagnostics.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const snapshot = diagnostics.data;
  return <div className="ui-stack">
    <div className="ui-form-actions"><Button variant="outline"  type="button" disabled={diagnostics.isFetching || !enabled}
      onClick={() => void diagnostics.refetch()}><ArrowsClockwise size={16} aria-hidden />{diagnostics.isFetching ? t("settings.diagnostics.checking") : t("settings.diagnostics.refresh")}</Button></div>
      {diagnostics.isPending && enabled ? <LoadingState label={t("settings.diagnostics.loading")} /> : null}
      {diagnostics.isError ? <Notice tone="danger" title={t("settings.diagnostics.loadFailed")}><p>{t("settings.diagnostics.databaseUnavailable")}</p>{snapshot ? <p>{t("settings.diagnostics.staleHint")}</p> : null}</Notice> : null}
      {snapshot ? <>
        <div className="ui-toolbar"><StatusBadge>{t("settings.diagnostics.localChecks")}</StatusBadge><span className="ui-muted">{t("settings.diagnostics.checkedAt")}<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleString(getFormatLocale())}</time></span>{diagnostics.isFetching ? <LoadingState compact label={t("settings.diagnostics.updating")} /> : null}</div>
        <div className="diagnostics-stats">
          <StatusCard title={t("settings.diagnostics.database")} icon={<Database size={20} aria-hidden />} tone={snapshot.database === "AVAILABLE" ? "success" : "danger"} badge={snapshot.database === "AVAILABLE" ? t("settings.diagnostics.healthy") : t("settings.diagnostics.unavailable")} value={snapshot.database === "AVAILABLE" ? t("settings.diagnostics.readable") : t("settings.diagnostics.unavailable")} description={t("settings.diagnostics.databaseHint")} />
          <StatusCard title={t("settings.diagnostics.localDirectories")} icon={<HardDrives size={20} aria-hidden />} tone={snapshot.storage === "AVAILABLE" ? "success" : "warning"} badge={snapshot.storage === "AVAILABLE" ? t("settings.diagnostics.pathHealthy") : t("settings.diagnostics.needsInspection")} value={snapshot.storage === "AVAILABLE" ? t("settings.diagnostics.pathCheckHealthy") : t("settings.diagnostics.pathCheckFailed")} description={<>{t("settings.diagnostics.localScopeHint")}<Link to="/settings/storage">{t("settings.diagnostics.manageStorage")}</Link></>} />
          <StatusCard title={t("models.text")} icon={<Cpu size={20} aria-hidden />} tone={snapshot.llmMode === "MOCK" ? "neutral" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? "success" : "warning"} badge={snapshot.llmMode === "MOCK" ? "Mock" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? t("settings.diagnostics.protocolVerified") : t("models.pendingValidation")} value={`${modeLabel(snapshot.llmMode)} · ${snapshot.llmConfigured ? t("common.configured") : t("models.unconfigured")}${snapshot.llmMode === "CONFIGURED" ? ` · ${snapshot.llmToolCallingVerified ? t("settings.diagnostics.toolsVerified") : t("settings.diagnostics.toolsUnverified")}` : ""}`} description={t("settings.diagnostics.savedConfigHint")} />
          <StatusCard title={t("settings.diagnostics.mediaService")} icon={<Plugs size={20} aria-hidden />} tone={snapshot.mediaMode === "MOCK" ? "neutral" : snapshot.imageConfigured && snapshot.videoConfigured ? "success" : "warning"} badge={snapshot.mediaMode === "MOCK" ? "Mock" : t("settings.diagnostics.configurationStatus")} value={t("settings.llm.providerSummary", { "0": modeLabel(snapshot.mediaMode), "1": snapshot.imageConfigured ? t("common.configured") : t("models.unconfigured"), "2": snapshot.videoConfigured ? t("common.configured") : t("models.unconfigured") })} description={t("settings.diagnostics.configurationHint")} />
        </div>
      </> : null}
{snapshot ? <Panel title={t("settings.diagnostics.recentIssues")} description={t("settings.diagnostics.issuePrivacyHint")}>
          {snapshot.recentErrors.length === 0 ? <EmptyState icon={<CheckCircle size={28} aria-hidden />} title={t("settings.diagnostics.issuesEmpty")} description={t("settings.diagnostics.noRecentIssues")} /> :
            <ul className="diagnostics-errors">{snapshot.recentErrors.map((item) => <li key={item.status}><StatusBadge tone={item.status === "FAILED" ? "danger" : "warning"}>{t("settings.diagnostics.issueCount", { "0": errorLabel(item.status), "1": item.count })}</StatusBadge><time dateTime={item.lastAt}>{t("settings.diagnostics.updated", { "0": new Date(item.lastAt).toLocaleString(getFormatLocale()) })}</time></li>)}</ul>}
        </Panel> : null}
  </div>;
}

function StatusCard({ title, value, icon, description, tone, badge }: {
  title: string; value: string; icon: ReactNode; description: ReactNode; tone: "neutral" | "success" | "warning" | "danger"; badge: string;
}) {
  useLocale();
  return <Panel title={title} actions={<StatusBadge tone={tone}>{badge}</StatusBadge>}>
    <div className="diagnostics-stat-heading"><span className="ui-icon-tile">{icon}</span><p className="diagnostics-stat-value">{value}</p></div>
    <p className="diagnostics-stat-note">{description}</p>
  </Panel>;
}

function modeLabel(mode: string): string {
  return mode === "MOCK" ? t("models.mockMode") : t("settings.diagnostics.modelConfigured");
}

function errorLabel(status: string): string {
  return { FAILED: t("common.failed"), UNKNOWN: t("common.unknown"), BLOCKED: t("tasks.status.blocked") }[status] ?? t("settings.diagnostics.abnormal");
}
