import { useQuery, useQueryClient } from "@tanstack/react-query";
import { type FormEvent, useEffect, useState } from "react";
import { Link, Navigate } from "react-router";
import { ApiError, diagnoseLlmSettings, getCurrentUser, getLlmSettings, getSystemDiagnostics, replaceLlmSettings } from "../../shared/api/client";

/** Administrator-only editor; the credential lives only in this form until submission. */
export function LlmSettingsPage() {
  const queryClient = useQueryClient();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const settings = useQuery({
    queryKey: ["settings", "llm"],
    queryFn: getLlmSettings,
    enabled: currentUser.isSuccess,
    retry: false,
  });
  const mediaStatus = useQuery({
    queryKey: ["settings", "diagnostics"],
    queryFn: getSystemDiagnostics,
    enabled: currentUser.isSuccess,
    retry: false,
  });
  const [endpoint, setEndpoint] = useState("");
  const [modelId, setModelId] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);
  const [costAcknowledged, setCostAcknowledged] = useState(false);
  const [diagnosing, setDiagnosing] = useState(false);
  const [diagnosticError, setDiagnosticError] = useState("");
  const [diagnosed, setDiagnosed] = useState(false);

  useEffect(() => {
    if (settings.data) {
      setEndpoint(settings.data.endpoint ?? "");
      setModelId(settings.data.modelId ?? "");
    }
  }, [settings.data]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!settings.data || saving) return;
    setSaving(true);
    setError("");
    setSaved(false);
    try {
      const result = await replaceLlmSettings({
        expectedVersion: settings.data.version,
        endpoint: endpoint.trim(),
        modelId: modelId.trim(),
        apiKey,
      });
      setApiKey("");
      queryClient.setQueryData(["settings", "llm"], result);
      setSaved(true);
      setDiagnosed(false);
      setCostAcknowledged(false);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "保存失败，请重试。");
    } finally {
      setSaving(false);
    }
  }

  async function diagnose() {
    if (!settings.data?.configured || diagnosing || !costAcknowledged) return;
    setDiagnosing(true);
    setDiagnosticError("");
    setDiagnosed(false);
    try {
      const result = await diagnoseLlmSettings({
        expectedVersion: settings.data.version,
        acknowledgeCost: true,
      });
      queryClient.setQueryData(["settings", "llm"], result);
      setDiagnosed(true);
      setCostAcknowledged(false);
    } catch (cause) {
      setDiagnosticError(cause instanceof ApiError ? cause.message : "诊断失败，请重试。");
    } finally {
      setDiagnosing(false);
    }
  }

  if (currentUser.isError || (settings.error instanceof ApiError && settings.error.status === 401)
      || (mediaStatus.error instanceof ApiError && mediaStatus.error.status === 401)) {
    return <Navigate to="/login" replace />;
  }

  return (
    <main className="min-h-screen bg-[var(--canvas)] p-8 text-[var(--ink)]">
      <div className="mx-auto max-w-2xl">
        <Link className="text-sm underline" to="/projects">返回项目</Link>
        <h1 className="mt-6 text-3xl font-semibold">Provider 配置</h1>
        <p className="mt-3 text-sm text-[var(--muted)]">管理员配置 OpenAI 兼容模型端点。密钥仅在保存时提交，之后只能看到掩码。</p>
        <p className="mt-4 rounded-xl bg-amber-50 p-4 text-sm text-amber-900">切换到 configured 模式后，Agent 会读取已保存的配置。运行前须完成下方工具协议诊断；保存成功或假端点测试都不代表真实模型已验证。</p>
        {currentUser.isPending || settings.isPending ? <p className="mt-8">正在读取配置…</p> : null}
        {settings.isError && !(settings.error instanceof ApiError && settings.error.status === 401) ? (
          <p className="mt-8 rounded-xl bg-red-50 p-4 text-red-800" role="alert">读取配置失败。请刷新后重试。</p>
        ) : null}
        {settings.data ? (
          <>
          <form className="mt-8 space-y-5 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6" onSubmit={submit}>
            <h2 className="text-xl font-semibold">LLM 配置</h2>
            <p className="text-sm text-[var(--muted)]">{settings.data.configured ? `已保存版本 ${settings.data.version} · 密钥 ${settings.data.keyMask ?? "已设置"}` : "尚未配置模型"}</p>
            <label className="block text-sm font-medium">端点地址
              <input autoComplete="off" required type="url" value={endpoint} onChange={(event) => setEndpoint(event.target.value)} placeholder="https://api.example.com" />
            </label>
            <label className="block text-sm font-medium">模型 ID
              <input autoComplete="off" required value={modelId} onChange={(event) => setModelId(event.target.value)} placeholder="model-name" />
            </label>
            <label className="block text-sm font-medium">API Key（每次修改均需重新输入）
              <input autoComplete="new-password" required type="password" value={apiKey} onChange={(event) => setApiKey(event.target.value)} />
            </label>
            {error ? <p className="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{error}</p> : null}
            {saved ? <p className="rounded-xl bg-green-50 p-3 text-sm text-green-800" role="status">配置已加密保存，密钥输入已清空。</p> : null}
            <button className="primary-button" disabled={saving} type="submit">{saving ? "正在保存…" : "保存配置"}</button>
          </form>
          {settings.data.configured ? <section className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6">
            <h2 className="text-xl font-semibold">工具协议诊断</h2>
            <p className="mt-3 text-sm text-[var(--muted)]">当前版本 {settings.data.version}：{settings.data.toolCallingVerified ? "完整工具往返已验证" : "尚未验证，Agent Run 会被阻断"}。诊断只使用已保存的配置，不发送项目内容。</p>
            <p className="mt-2 text-sm text-[var(--muted)]">诊断最多向模型服务发送两次请求，可能产生费用。它验证工具请求、结果回填和下一轮响应，不验证视觉或输出质量。</p>
            <label className="mt-4 flex items-center gap-2 text-sm"><input className="m-0 h-4 w-4" type="checkbox" checked={costAcknowledged} onChange={(event) => setCostAcknowledged(event.target.checked)} />我确认此次诊断可能产生模型费用</label>
            {diagnosticError ? <p className="mt-3 rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{diagnosticError}</p> : null}
            {diagnosed ? <p className="mt-3 rounded-xl bg-green-50 p-3 text-sm text-green-800" role="status">已验证完整工具协议。</p> : null}
            <button className="secondary-button mt-4" type="button" disabled={diagnosing || saving || !costAcknowledged || endpoint !== settings.data.endpoint || modelId !== settings.data.modelId || apiKey !== ""} onClick={diagnose}>{diagnosing ? "正在诊断…" : "执行可能计费的诊断"}</button>
            {endpoint !== settings.data.endpoint || modelId !== settings.data.modelId || apiKey !== "" ? <p className="mt-2 text-sm text-[var(--muted)]">请先保存并清空未提交的密钥输入，再诊断已保存版本。</p> : null}
          </section> : null}
          </>
        ) : null}
        <section className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6">
          <h2 className="text-xl font-semibold">媒体服务配置</h2>
          <p className="mt-2 text-sm text-[var(--muted)]">媒体端点和固定模板目前由部署管理员通过服务端环境变量设置；此处只显示配置状态，不执行网络探测或付费生成。</p>
          {mediaStatus.isPending ? <p className="mt-4 text-sm">正在读取媒体状态…</p> : null}
          {mediaStatus.isError ? <p className="mt-4 text-sm text-red-800" role="alert">媒体配置状态读取失败，请刷新页面重试。</p> : null}
          {mediaStatus.data ? <p className="mt-4 text-sm">
            {mediaStatus.data.mediaMode === "MOCK" ? "Mock 模式" : "ComfyUI 模式"} · 图片{mediaStatus.data.imageConfigured ? "已配置" : "未配置"} · 视频{mediaStatus.data.videoConfigured ? "已配置" : "未配置"}
          </p> : null}
          <Link className="mt-4 inline-block text-sm underline" to="/settings/general">查看系统诊断</Link>
        </section>
      </div>
    </main>
  );
}
