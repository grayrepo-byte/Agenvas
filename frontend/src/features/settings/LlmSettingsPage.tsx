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
      setError(cause instanceof ApiError ? cause.message : t("保存失败，请重试。"));
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
      setDiagnosticError(cause instanceof ApiError ? cause.message : t("诊断失败，请重试。"));
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

  return <PageShell title={t("Provider 配置")} description={t("管理 Agent 使用的文本模型、安全凭据与工具调用能力。")}>
    {snapshot ? <SummaryStrip items={[
      { label: t("当前模型"), value: snapshot.modelId ?? t("尚未配置"), detail: t("OpenAI 兼容端点") },
      { label: t("工具协议"), value: snapshot.toolCallingVerified ? t("已验证") : t("待验证"), detail: t("诊断使用已保存的配置") },
      { label: t("配置版本"), value: `v${snapshot.version}`, detail: snapshot.configured ? t("凭据已加密保存") : t("添加端点与凭据后保存") },
    ]} /> : null}
    <div className="settings-page-layout">
      <div className="ui-stack">
        {settings.isPending ? <LoadingState label={t("正在读取配置…")} /> : null}
        {settings.isError ? <Notice tone="danger" title={t("读取配置失败")}>
          <p>{t("暂时无法获取模型配置，请重试。")}</p>
          <Button variant="outline"  type="button" disabled={settings.isFetching} onClick={() => { void settings.refetch(); }}>{t("重新读取配置")}</Button>
        </Notice> : null}
        {snapshot ? <>
          <Panel title={t("LLM 配置")} description={t("管理员配置 OpenAI 兼容模型端点。")} actions={<StatusBadge tone={snapshot.configured ? "success" : "neutral"}>{snapshot.configured ? t("已配置") : t("未配置")}</StatusBadge>}>
            <form className="ui-form" onSubmit={submit} aria-busy={saving}>
              <div className="settings-config-summary">
                <span>{snapshot.configured ? t("已保存版本 {0} · 密钥 {1}", { "0": snapshot.version, "1": snapshot.keyMask ?? t("已设置") }) : t("尚未配置模型")}</span>
                <span className="ui-muted">{t("密钥仅在提交时发送，读取时只显示掩码。")}</span>
              </div>
              {hasRemoteUpdate ? <Notice tone="warning" title={t("已有更新的配置")}>
                <p>{t("已保留当前输入；继续保存仍使用开始编辑时的版本。载入最新配置会替换当前草稿并清空密钥。")}</p>
                <Button variant="outline"  type="button" disabled={busy} onClick={() => { setDraft(null); setApiKey(""); setError(""); setAcknowledgedVersion(null); }}>{t("载入最新配置")}</Button>
              </Notice> : null}
              <Field><FieldLabel className="ui-field block">{t("端点地址")}<Input autoComplete="off" required type="url" disabled={busy} value={endpoint} onChange={(event) => editDraft("endpoint", event.target.value)} placeholder="https://api.example.com" />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("模型 ID")}<Input autoComplete="off" required disabled={busy} value={modelId} onChange={(event) => editDraft("modelId", event.target.value)} placeholder="model-name" />
              </FieldLabel></Field>
              <Field><FieldLabel className="ui-field block">{t("API Key（每次修改均需重新输入）")}<Input autoComplete="new-password" required type="password" disabled={busy} value={apiKey} onChange={(event) => {
                  if (base && !draft) setDraft({ base, endpoint, modelId });
                  setApiKey(event.target.value); setSaved(false); setAcknowledgedVersion(null);
                }} />
              </FieldLabel></Field>
              {error ? <Notice tone="danger"><p>{error}</p><p>{t("密钥输入已清空，重试前请重新输入。")}</p></Notice> : null}
              {saved ? <Notice tone="success">{t("配置已加密保存，密钥输入已清空。")}</Notice> : null}
              <div className="ui-form-actions settings-save-bar">
                {saving ? <LoadingState compact label={t("正在保存配置…")} /> : <span className="ui-muted">{hasUnsavedChanges ? t("有未保存的修改") : t("保存后需验证当前版本的工具协议。")}</span>}
                <div className="ui-form-actions">
                  {hasUnsavedChanges ? <Button variant="ghost"  type="button" disabled={busy} onClick={() => {
                    setDraft(null); setApiKey(""); setError(""); setSaved(false); setAcknowledgedVersion(null);
                  }}>{t("撤销修改")}</Button> : null}
                  <Button variant="default"  disabled={busy} type="submit"><FloppyDisk size={16} aria-hidden />{saving ? t("正在保存…") : t("保存配置")}</Button>
                </div>
              </div>
            </form>
          </Panel>
        </> : null}
      </div>
      <aside className="ui-stack">
        {snapshot?.configured ? <Panel title={t("工具协议诊断")} description={t("只使用已保存的配置，不发送项目内容。")} actions={<StatusBadge tone={snapshot.toolCallingVerified ? "success" : "warning"}>{snapshot.toolCallingVerified ? t("已验证") : t("待验证")}</StatusBadge>}>
          <div className="ui-stack">
            <p className="settings-status-line"><ShieldCheck size={20} aria-hidden />{t("当前版本 {0}：{1}。", { "0": snapshot.version, "1": snapshot.toolCallingVerified ? t("完整工具往返已验证") : t("尚未验证，Agent Run 会被阻断") })}</p>
            <p className="ui-muted">{t("诊断最多向模型服务发送两次请求，可能产生费用。它验证工具请求、结果回填和下一轮响应，不验证视觉或输出质量。")}</p>
            <label className="settings-consent"><Checkbox  checked={costAcknowledged} disabled={busy || hasUnsavedChanges || hasRemoteUpdate} onCheckedChange={(event) => setAcknowledgedVersion(event === true ? snapshot.version : null)} /><span>{t("我确认此次诊断可能产生模型费用")}</span></label>
            {diagnosticError ? <Notice tone="danger">{diagnosticError}</Notice> : null}
            {diagnosed ? <Notice tone="success">{t("已验证完整工具协议。")}</Notice> : null}
            {diagnosing ? <LoadingState compact label={t("正在验证工具协议…")} /> : null}
            <div className="ui-form-actions"><span className="ui-muted">{hasUnsavedChanges || hasRemoteUpdate ? t("请先保存并清空未提交的密钥输入，再诊断已保存版本。") : t("每次诊断都需要确认费用。")}</span><Button variant="outline"  type="button" disabled={busy || !costAcknowledged || hasUnsavedChanges || hasRemoteUpdate} onClick={diagnose}>{diagnosing ? t("正在诊断…") : t("执行可能计费的诊断")}</Button></div>
          </div>
        </Panel> : null}
        <Notice title={t("配置与验证")}>
          {t("已保存的配置在真实模型模式下生效。运行前请完成工具协议诊断；保存配置本身不会验证服务连通性。")}</Notice>
        <Panel title={t("媒体服务配置")} description={t("管理媒体连接、固定能力和默认值。")}>
          <div className="ui-stack">
            <p className="ui-muted">{t("下方为当前运行模式的配置状态，读取状态不会执行付费生成。")}</p>
            {mediaStatus.isPending ? <LoadingState compact label={t("正在读取媒体状态…")} /> : null}
            {mediaStatus.isError ? <Notice tone="danger"><p>{t("媒体配置状态读取失败。")}</p><Button variant="outline"  type="button" disabled={mediaStatus.isFetching} onClick={() => { void mediaStatus.refetch(); }}>{t("重新读取媒体状态")}</Button></Notice> : null}
            {mediaStatus.data ? <p className="settings-mode-summary">{t("{0} · 图片{1} · 视频{2}", { "0": mediaStatus.data.mediaMode === "MOCK" ? t("Mock 模式") : t("ComfyUI 模式"), "1": mediaStatus.data.imageConfigured ? t("已配置") : t("未配置"), "2": mediaStatus.data.videoConfigured ? t("已配置") : t("未配置") })}</p> : null}
            <Link className="settings-nav-link" to="/settings/media">{t("管理媒体连接")}<ArrowUpRight size={16} aria-hidden /></Link>
            <Link className="settings-nav-link" to="/settings/general?tab=diagnostics">{t("查看系统诊断")}<ArrowUpRight size={16} aria-hidden /></Link>
          </div>
        </Panel>
      </aside>
    </div>
  </PageShell>;
}
