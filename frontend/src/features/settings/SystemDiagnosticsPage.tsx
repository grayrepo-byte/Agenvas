import { useQuery } from "@tanstack/react-query";
import { Link, Navigate } from "react-router";
import { ApiError, getCurrentUser, getSystemDiagnostics } from "../../shared/api/client";
import { PasswordChangeSection } from "./PasswordChangeSection";

/** General settings: local read-only diagnostics plus explicit password rotation. */
export function SystemDiagnosticsPage() {
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const diagnostics = useQuery({
    queryKey: ["settings", "diagnostics"],
    queryFn: getSystemDiagnostics,
    enabled: currentUser.isSuccess,
    retry: false,
  });

  if (currentUser.isError || (diagnostics.error instanceof ApiError && diagnostics.error.status === 401)) {
    return <Navigate to="/login" replace />;
  }

  const snapshot = diagnostics.data;
  return <main className="min-h-screen bg-[var(--canvas)] p-8 text-[var(--ink)]">
    <div className="mx-auto max-w-3xl">
      <Link className="text-sm underline" to="/projects">返回项目</Link>
      <div className="mt-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold">系统诊断</h1>
          <p className="mt-2 text-sm text-[var(--muted)]">只读取本地状态；不会连接模型或媒体服务，也不会发起生成。</p>
        </div>
        <button className="secondary-button" type="button" disabled={diagnostics.isFetching || !currentUser.isSuccess}
          onClick={() => { void diagnostics.refetch(); }}>{diagnostics.isFetching ? "正在检查…" : "刷新状态"}</button>
      </div>
      {currentUser.isPending || diagnostics.isPending ? <p className="mt-8" role="status">正在读取诊断…</p> : null}
      {diagnostics.isError ? <p className="mt-8 rounded-xl bg-red-50 p-4 text-red-800" role="alert">读取诊断失败；数据库或会话可能不可用。请稍后重试。</p> : null}
      {snapshot ? <>
        <p className="mt-6 text-xs text-[var(--muted)]">检查时间：{new Date(snapshot.checkedAt).toLocaleString()}</p>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <StatusCard title="数据库" value={snapshot.database === "AVAILABLE" ? "可读取" : "不可用"} />
          <StatusCard title="媒体存储" value={snapshot.storage === "AVAILABLE" ? "路径检查正常（未试写）" : "路径检查异常"} />
          <StatusCard title="文本模型" value={`${modeLabel(snapshot.llmMode)} · ${snapshot.llmConfigured ? "已配置" : "未配置"}${snapshot.llmMode === "CONFIGURED" ? ` · ${snapshot.llmToolCallingVerified ? "工具协议已验证" : "工具协议未验证"}` : ""}`} />
          <StatusCard title="媒体服务" value={`${modeLabel(snapshot.mediaMode)} · 图片${snapshot.imageConfigured ? "已配置" : "未配置"} · 视频${snapshot.videoConfigured ? "已配置" : "未配置"}`} />
        </div>
        <section className="mt-6 rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-6">
          <h2 className="text-xl font-semibold">近七天任务异常</h2>
          <p className="mt-2 text-sm text-[var(--muted)]">仅显示失败、待核对或阻断任务的数量和最近更新时间；不展示素材、路径或原始错误。</p>
          {snapshot.recentErrors.length === 0 ? <p className="mt-4 text-sm">暂无异常任务记录。</p> :
            <ul className="mt-4 space-y-2">{snapshot.recentErrors.map((item) =>
              <li className="rounded-xl bg-white/70 p-3 text-sm" key={item.status}>
                {errorLabel(item.status)}：{item.count} 项 · 最近更新 {new Date(item.lastAt).toLocaleString()}
              </li>)}</ul>}
        </section>
      </> : null}
      {currentUser.isSuccess ? <PasswordChangeSection /> : null}
    </div>
  </main>;
}

/** Uniform status card keeps config state visually distinct from a live connectivity test. */
function StatusCard({ title, value }: { title: string; value: string }) {
  return <section className="rounded-3xl border border-[var(--line)] bg-[var(--panel)] p-5">
    <h2 className="text-sm text-[var(--muted)]">{title}</h2>
    <p className="mt-2 font-medium">{value}</p>
  </section>;
}

function modeLabel(mode: string): string {
  return mode === "MOCK" ? "Mock 模式" : mode === "COMFYUI" ? "ComfyUI 配置" : "已配置模型模式";
}

function errorLabel(status: string): string {
  return { FAILED: "失败", UNKNOWN: "待核对", BLOCKED: "已阻断" }[status] ?? "异常";
}
