import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowUpRight,FloppyDisk,ShieldCheck } from "@phosphor-icons/react";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { useState,type FormEvent } from "react";
import { Link,Navigate } from "react-router";
import { HTTP_STATUS,ApiError,diagnoseLlmSettings,getCurrentUser,getLlmSettings,getSystemDiagnostics,replaceLlmSettings,type LlmSettings } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice,Panel,StatusBadge,SummaryStrip } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import "./SettingsPages.css";


type ConfigurationDraft = { base: LlmSettings; endpoint: string; modelId: string };

/** The edit base stays pinned while typing; background reads cannot replace a draft or its CAS version. */
export function LlmSettingsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const settings = useQuery({
    queryKey: ["settings", "llm"], queryFn: getLlmSettings, enabled: currentUser.isSuccess, retry: false,
  });
  const mediaStatus = useQuery({
    queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, enabled: currentUser.isSuccess, retry: false,
  });
  const [draft, setDraft] = useState<ConfigurationDraft | null>(null);
  const [apiKey, setApiKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);
  const [acknowledgedVersion, setAcknowledgedVersion] = useState<number | null>(null);
  const [diagnosing, setDiagnosing] = useState(false);
  const [diagnosticError, setDiagnosticError] = useState("");
  const [diagnosedVersion, setDiagnosedVersion] = useState<number | null>(null);
  const snapshot = settings.data;
  const costAcknowledged = !!snapshot && acknowledgedVersion === snapshot.version;
  const diagnosed = !!snapshot?.toolCallingVerified && diagnosedVersion === snapshot.version;
  const base = draft?.base ?? snapshot;
  const endpoint = draft?.endpoint ?? snapshot?.endpoint ?? "";
  const modelId = draft?.modelId ?? snapshot?.modelId ?? "";
  const hasRemoteUpdate = !!draft && !!snapshot && draft.base.version !== snapshot.version;
  const hasUnsavedChanges = endpoint !== (snapshot?.endpoint ?? "") || modelId !== (snapshot?.modelId ?? "") || apiKey !== "";
  const busy = saving || diagnosing;

  function editDraft(field: "endpoint" | "modelId", value: string) {
    if (!base) return;
    setDraft({ base, endpoint, modelId, [field]: value });
    setSaved(false);
    setAcknowledgedVersion(null);
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!base || busy) return;
    setSaving(true);
    setError("");
    setSaved(false);
    try {
      const result = await replaceLlmSettings({ expectedVersion: base.version, endpoint: endpoint.trim(), modelId: modelId.trim(), apiKey });
      queryClient.setQueryData(["settings", "llm"], result);
      setDraft(null);
      setSaved(true);
      setDiagnosedVersion(null);
      setAcknowledgedVersion(null);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : t("settings.shared.saveFailed"));
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) void settings.refetch();
    } finally {
      setApiKey("");
      setSaving(false);
    }
  }

  async function diagnose() {
    if (!snapshot?.configured || busy || !costAcknowledged || hasUnsavedChanges || hasRemoteUpdate) return;
    setDiagnosing(true);
    setDiagnosticError("");
    setDiagnosedVersion(null);
    try {
      const result = await diagnoseLlmSettings({ expectedVersion: snapshot.version, acknowledgeCost: true });
      queryClient.setQueryData(["settings", "llm"], result);
      if (result.version === snapshot.version && result.toolCallingVerified) setDiagnosedVersion(result.version);
    } catch (cause) {
      setDiagnosticError(cause instanceof ApiError ? cause.message : t("settings.llm.diagnosticFailed"));
      if (cause instanceof ApiError && cause.status === HTTP_STATUS.CONFLICT) void settings.refetch();
    } finally {
      setAcknowledgedVersion(null);
      setDiagnosing(false);
    }
  }

  if ((settings.error instanceof ApiError && settings.error.status === HTTP_STATUS.UNAUTHORIZED)
      || (mediaStatus.error instanceof ApiError && mediaStatus.error.status === HTTP_STATUS.UNAUTHORIZED)) {
    return <Navigate to="/login" replace />;
  }

  return <PageShell title={t("settings.providerTitle")} description={t("settings.llm.description")}>
    {snapshot ? <SummaryStrip items={[
      { label: t("settings.llm.currentModel"), value: snapshot.modelId ?? t("settings.llm.notConfigured"), detail: t("settings.llm.openAiEndpoint") },
      { label: t("settings.llm.protocol"), value: snapshot.toolCallingVerified ? t("settings.llm.verified") : t("models.pendingValidation"), detail: t("settings.llm.diagnosticConfigHint") },
      { label: t("settings.llm.configVersion"), value: `v${snapshot.version}`, detail: snapshot.configured ? t("settings.llm.credentialsEncrypted") : t("settings.llm.setupHint") },
    ]} /> : null}
    <div className="settings-page-layout">
      <div className="ui-stack">
        {settings.isPending ? <LoadingState label={t("settings.llm.loading")} /> : null}
        {settings.isError ? <Notice tone="danger" title={t("settings.llm.loadFailed")}>
          <p>{t("settings.llm.settingsUnavailable")}</p>
          <Button variant="outline"  type="button" disabled={settings.isFetching} onClick={() => { void settings.refetch(); }}>{t("settings.llm.refreshSettings")}</Button>
        </Notice> : null}
        {snapshot ? <>
          <Panel title={t("settings.llm.llmConfiguration")} description={t("settings.llm.endpointHint")} actions={<StatusBadge tone={snapshot.configured ? "success" : "neutral"}>{snapshot.configured ? t("common.configured") : t("models.unconfigured")}</StatusBadge>}>
            <form className="ui-form" onSubmit={submit} aria-busy={saving}>
              <div className="settings-config-summary">
                <span>{snapshot.configured ? t("settings.llm.savedVersion", { "0": snapshot.version, "1": snapshot.keyMask ?? t("settings.llm.set") }) : t("settings.llm.modelMissing")}</span>
                <span className="ui-muted">{t("settings.llm.credentialsHint")}</span>
              </div>
              {hasRemoteUpdate ? <Notice tone="warning" title={t("settings.llm.conflictTitle")}>
                <p>{t("settings.llm.conflictHint")}</p>
                <Button variant="outline"  type="button" disabled={busy} onClick={() => { setDraft(null); setApiKey(""); setError(""); setAcknowledgedVersion(null); }}>{t("settings.shared.refreshConfig")}</Button>
              </Notice> : null}
              <Field><FieldLabel className="ui-field block">{t("settings.llm.endpoint")}<Input autoComplete="off" required type="url" disabled={busy} value={endpoint} onChange={(event) => editDraft("endpoint", event.target.value)} placeholder="https://api.example.com" />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("settings.llm.modelId")}<Input autoComplete="off" required disabled={busy} value={modelId} onChange={(event) => editDraft("modelId", event.target.value)} placeholder="model-name" />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("settings.llm.apiKey")}<Input autoComplete="new-password" required type="password" disabled={busy} value={apiKey} onChange={(event) => {
                  if (base && !draft) setDraft({ base, endpoint, modelId });
                  setApiKey(event.target.value); setSaved(false); setAcknowledgedVersion(null);
                }} />
              </FieldLabel></Field>
              {error ? <Notice tone="danger"><p>{error}</p><p>{t("settings.llm.keyClearedHint")}</p></Notice> : null}
              {saved ? <Notice tone="success">{t("settings.llm.saved")}</Notice> : null}
              <div className="ui-form-actions settings-save-bar">
                {saving ? <LoadingState compact label={t("settings.llm.saving")} /> : <span className="ui-muted">{hasUnsavedChanges ? t("common.unsavedChanges") : t("settings.llm.toolValidationRequired")}</span>}
                <div className="ui-form-actions">
                  {hasUnsavedChanges ? <Button variant="ghost"  type="button" disabled={busy} onClick={() => {
                    setDraft(null); setApiKey(""); setError(""); setSaved(false); setAcknowledgedVersion(null);
                  }}>{t("settings.llm.revert")}</Button> : null}
                  <Button variant="default"  disabled={busy} type="submit"><FloppyDisk size={16} aria-hidden />{saving ? t("common.savingProgress") : t("common.saveConfig")}</Button>
                </div>
              </div>
            </form>
          </Panel>
        </> : null}
      </div>
      <aside className="ui-stack">
        {snapshot?.configured ? <Panel title={t("settings.llm.protocolDiagnostics")} description={t("settings.llm.savedConfigHint")} actions={<StatusBadge tone={snapshot.toolCallingVerified ? "success" : "warning"}>{snapshot.toolCallingVerified ? t("settings.llm.verified") : t("models.pendingValidation")}</StatusBadge>}>
          <div className="ui-stack">
            <p className="settings-status-line"><ShieldCheck size={20} aria-hidden />{t("settings.llm.versionStatus", { "0": snapshot.version, "1": snapshot.toolCallingVerified ? t("settings.llm.toolRoundtripVerified") : t("settings.llm.toolsUnverified") })}</p>
            <p className="ui-muted">{t("settings.llm.diagnosticScopeHint")}</p>
            <label className="settings-consent"><Checkbox  checked={costAcknowledged} disabled={busy || hasUnsavedChanges || hasRemoteUpdate} onCheckedChange={(event) => setAcknowledgedVersion(event === true ? snapshot.version : null)} /><span>{t("settings.llm.confirmDiagnosticCost")}</span></label>
            {diagnosticError ? <Notice tone="danger">{diagnosticError}</Notice> : null}
            {diagnosed ? <Notice tone="success">{t("settings.llm.protocolVerified")}</Notice> : null}
            {diagnosing ? <LoadingState compact label={t("settings.llm.protocolChecking")} /> : null}
            <div className="ui-form-actions"><span className="ui-muted">{hasUnsavedChanges || hasRemoteUpdate ? t("settings.llm.saveBeforeDiagnostic") : t("settings.llm.costConfirmationHint")}</span><Button variant="outline"  type="button" disabled={busy || !costAcknowledged || hasUnsavedChanges || hasRemoteUpdate} onClick={diagnose}>{diagnosing ? t("settings.llm.diagnosing") : t("settings.llm.runDiagnostic")}</Button></div>
          </div>
        </Panel> : null}
        <Notice title={t("settings.llm.configurationAndValidation")}>
          {t("settings.llm.realModelHint")}</Notice>
        <Panel title={t("settings.llm.mediaConfiguration")} description={t("settings.llm.mediaManagementHint")}>
          <div className="ui-stack">
            <p className="ui-muted">{t("settings.llm.statusHint")}</p>
            {mediaStatus.isPending ? <LoadingState compact label={t("settings.llm.mediaStatusLoading")} /> : null}
            {mediaStatus.isError ? <Notice tone="danger"><p>{t("settings.llm.mediaStatusFailed")}</p><Button variant="outline"  type="button" disabled={mediaStatus.isFetching} onClick={() => { void mediaStatus.refetch(); }}>{t("settings.llm.refreshMediaStatus")}</Button></Notice> : null}
            {mediaStatus.data ? <p className="settings-mode-summary">{t("settings.llm.providerSummary", { "0": mediaStatus.data.mediaMode === "MOCK" ? t("models.mockMode") : t("settings.diagnostics.modelConfigured"), "1": mediaStatus.data.imageConfigured ? t("common.configured") : t("models.unconfigured"), "2": mediaStatus.data.videoConfigured ? t("common.configured") : t("models.unconfigured") })}</p> : null}
            <Link className="settings-nav-link" to="/settings/media">{t("settings.llm.manageMedia")}<ArrowUpRight size={16} aria-hidden /></Link>
            <Link className="settings-nav-link" to="/settings/general?tab=diagnostics">{t("settings.llm.viewDiagnostics")}<ArrowUpRight size={16} aria-hidden /></Link>
          </div>
        </Panel>
      </aside>
    </div>
  </PageShell>;
}
