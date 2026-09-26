import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUpRight, FloppyDisk, ShieldCheck } from "@phosphor-icons/react";
import { type FormEvent, useState } from "react";
import { Link, Navigate } from "react-router";
import { ApiError, diagnoseLlmSettings, getCurrentUser, getLlmSettings, getSystemDiagnostics, replaceLlmSettings, type LlmSettings } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import "./SettingsPages.css";

const UNAUTHORIZED_STATUS = 401;
const CONFLICT_STATUS = 409;

type ConfigurationDraft = { base: LlmSettings; endpoint: string; modelId: string };

/** The edit base stays pinned while typing; background reads cannot replace a draft or its CAS version. */
export function LlmSettingsPage() {
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
      setError(cause instanceof ApiError ? cause.message : "保存失败，请重试。");
      if (cause instanceof ApiError && cause.status === CONFLICT_STATUS) void settings.refetch();
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
      setDiagnosticError(cause instanceof ApiError ? cause.message : "诊断失败，请重试。");
      if (cause instanceof ApiError && cause.status === CONFLICT_STATUS) void settings.refetch();
    } finally {
      setAcknowledgedVersion(null);
      setDiagnosing(false);
    }
  }

  if ((settings.error instanceof ApiError && settings.error.status === UNAUTHORIZED_STATUS)
      || (mediaStatus.error instanceof ApiError && mediaStatus.error.status === UNAUTHORIZED_STATUS)) {
    return <Navigate to="/login" replace />;
  }

  return <PageShell title="Provider 配置" description="管理 Agent 使用的文本模型、安全凭据与工具调用能力。">
    <div className="settings-page-layout">
      <div className="ui-stack">
        {settings.isPending ? <LoadingState label="正在读取配置…" /> : null}
        {settings.isError ? <Notice tone="danger" title="读取配置失败">
          <p>暂时无法获取模型配置，请重试。</p>
          <button className="secondary-button" type="button" disabled={settings.isFetching} onClick={() => { void settings.refetch(); }}>重新读取配置</button>
        </Notice> : null}
        {snapshot ? <>
          <Panel title="LLM 配置" description="管理员配置 OpenAI 兼容模型端点。" actions={<StatusBadge tone={snapshot.configured ? "success" : "neutral"}>{snapshot.configured ? "已配置" : "未配置"}</StatusBadge>}>
            <form className="ui-form" onSubmit={submit} aria-busy={saving}>
              <div className="settings-config-summary">
                <span>{snapshot.configured ? `已保存版本 ${snapshot.version} · 密钥 ${snapshot.keyMask ?? "已设置"}` : "尚未配置模型"}</span>
                <span className="ui-muted">密钥仅在提交时发送，读取时只显示掩码。</span>
              </div>
              {hasRemoteUpdate ? <Notice tone="warning" title="已有更新的配置">
                <p>已保留当前输入；继续保存仍使用开始编辑时的版本。载入最新配置会替换当前草稿并清空密钥。</p>
                <button className="secondary-button" type="button" disabled={busy} onClick={() => { setDraft(null); setApiKey(""); setError(""); setAcknowledgedVersion(null); }}>载入最新配置</button>
              </Notice> : null}
              <label className="ui-field">端点地址
                <input autoComplete="off" required type="url" disabled={busy} value={endpoint} onChange={(event) => editDraft("endpoint", event.target.value)} placeholder="https://api.example.com" />
              </label>
              <label className="ui-field">模型 ID
                <input autoComplete="off" required disabled={busy} value={modelId} onChange={(event) => editDraft("modelId", event.target.value)} placeholder="model-name" />
              </label>
              <label className="ui-field">API Key（每次修改均需重新输入）
                <input autoComplete="new-password" required type="password" disabled={busy} value={apiKey} onChange={(event) => {
                  if (base && !draft) setDraft({ base, endpoint, modelId });
                  setApiKey(event.target.value); setSaved(false); setAcknowledgedVersion(null);
                }} />
              </label>
              {error ? <Notice tone="danger"><p>{error}</p><p>密钥输入已清空，重试前请重新输入。</p></Notice> : null}
              {saved ? <Notice tone="success">配置已加密保存，密钥输入已清空。</Notice> : null}
              <div className="ui-form-actions">
                {saving ? <LoadingState compact label="正在保存配置…" /> : <span className="ui-muted">保存后需验证当前版本的工具协议。</span>}
                <button className="primary-button" disabled={busy} type="submit"><FloppyDisk size={16} aria-hidden />{saving ? "正在保存…" : "保存配置"}</button>
              </div>
            </form>
          </Panel>
          {snapshot.configured ? <Panel title="工具协议诊断" description="只使用已保存的配置，不发送项目内容。" actions={<StatusBadge tone={snapshot.toolCallingVerified ? "success" : "warning"}>{snapshot.toolCallingVerified ? "已验证" : "待验证"}</StatusBadge>}>
            <div className="ui-stack">
              <p className="settings-status-line"><ShieldCheck size={20} aria-hidden />当前版本 {snapshot.version}：{snapshot.toolCallingVerified ? "完整工具往返已验证" : "尚未验证，Agent Run 会被阻断"}。</p>
              <p className="ui-muted">诊断最多向模型服务发送两次请求，可能产生费用。它验证工具请求、结果回填和下一轮响应，不验证视觉或输出质量。</p>
              <label className="settings-consent"><input type="checkbox" checked={costAcknowledged} disabled={busy || hasUnsavedChanges || hasRemoteUpdate} onChange={(event) => setAcknowledgedVersion(event.target.checked ? snapshot.version : null)} /><span>我确认此次诊断可能产生模型费用</span></label>
              {diagnosticError ? <Notice tone="danger">{diagnosticError}</Notice> : null}
              {diagnosed ? <Notice tone="success">已验证完整工具协议。</Notice> : null}
              {diagnosing ? <LoadingState compact label="正在验证工具协议…" /> : null}
              <div className="ui-form-actions"><span className="ui-muted">{hasUnsavedChanges || hasRemoteUpdate ? "请先保存并清空未提交的密钥输入，再诊断已保存版本。" : "每次诊断都需要确认费用。"}</span><button className="secondary-button" type="button" disabled={busy || !costAcknowledged || hasUnsavedChanges || hasRemoteUpdate} onClick={diagnose}>{diagnosing ? "正在诊断…" : "执行可能计费的诊断"}</button></div>
            </div>
          </Panel> : null}
        </> : null}
      </div>
      <aside className="ui-stack">
        <Notice title="配置与验证">
          已保存的配置在真实模型模式下生效。运行前请完成工具协议诊断；保存配置本身不会验证服务连通性。
        </Notice>
        <Panel title="媒体服务配置" description="管理媒体连接、固定能力和默认值。">
          <div className="ui-stack">
            <p className="ui-muted">下方为当前运行模式的配置状态，读取状态不会执行付费生成。</p>
            {mediaStatus.isPending ? <LoadingState compact label="正在读取媒体状态…" /> : null}
            {mediaStatus.isError ? <Notice tone="danger"><p>媒体配置状态读取失败。</p><button className="secondary-button" type="button" disabled={mediaStatus.isFetching} onClick={() => { void mediaStatus.refetch(); }}>重新读取媒体状态</button></Notice> : null}
            {mediaStatus.data ? <p className="settings-mode-summary">{mediaStatus.data.mediaMode === "MOCK" ? "Mock 模式" : "ComfyUI 模式"} · 图片{mediaStatus.data.imageConfigured ? "已配置" : "未配置"} · 视频{mediaStatus.data.videoConfigured ? "已配置" : "未配置"}</p> : null}
            <Link className="settings-nav-link" to="/settings/media">管理媒体连接<ArrowUpRight size={16} aria-hidden /></Link>
            <Link className="settings-nav-link" to="/settings/general">查看系统诊断<ArrowUpRight size={16} aria-hidden /></Link>
          </div>
        </Panel>
      </aside>
    </div>
  </PageShell>;
}
