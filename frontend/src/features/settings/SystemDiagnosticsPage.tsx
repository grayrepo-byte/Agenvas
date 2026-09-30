import { useQuery } from "@tanstack/react-query";
import { ArrowsClockwise, CheckCircle, Cpu, Database, HardDrives, Plugs } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { Navigate } from "react-router";
import { ApiError, getCurrentUser, getSystemDiagnostics } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { DebugModeSection } from "./DebugModeSection";
import { PasswordChangeSection } from "./PasswordChangeSection";
import "./SettingsPages.css";

const UNAUTHORIZED_STATUS = 401;

/** System preferences and local diagnostics; refreshing never calls a paid Provider. */
export function SystemDiagnosticsPage() {
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const diagnostics = useQuery({
    queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, enabled: currentUser.isSuccess, retry: false,
  });

  if (diagnostics.error instanceof ApiError && diagnostics.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;

  const snapshot = diagnostics.data;
  return <PageShell title="系统设置" description="管理密码与 debug 模式，查看本地运行状态。诊断刷新不会连接模型或媒体服务。" actions={
    <button className="secondary-button" type="button" disabled={diagnostics.isFetching || !currentUser.isSuccess} onClick={() => { void diagnostics.refetch(); }}><ArrowsClockwise size={16} aria-hidden />{diagnostics.isFetching ? "正在检查…" : "刷新状态"}</button>
  }>
    <div className="ui-stack">
      {diagnostics.isPending ? <LoadingState label="正在读取诊断…" /> : null}
      {diagnostics.isError ? <Notice tone="danger" title="读取诊断失败"><p>数据库或会话可能不可用。请稍后重试。</p>{snapshot ? <p>下面保留上一次读取的状态，尚未更新。</p> : null}</Notice> : null}
      {snapshot ? <>
        <div className="ui-toolbar"><StatusBadge>本地检查</StatusBadge><span className="ui-muted">检查时间：<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleString()}</time></span>{diagnostics.isFetching ? <LoadingState compact label="正在更新状态…" /> : null}</div>
        <div className="diagnostics-stats">
          <StatusCard title="数据库" icon={<Database size={20} aria-hidden />} tone={snapshot.database === "AVAILABLE" ? "success" : "danger"} badge={snapshot.database === "AVAILABLE" ? "正常" : "不可用"} value={snapshot.database === "AVAILABLE" ? "可读取" : "不可用"} description="检查应用能否读取本地数据库。" />
          <StatusCard title="媒体存储" icon={<HardDrives size={20} aria-hidden />} tone={snapshot.storage === "AVAILABLE" ? "success" : "warning"} badge={snapshot.storage === "AVAILABLE" ? "路径正常" : "需检查"} value={snapshot.storage === "AVAILABLE" ? "路径检查正常（未试写）" : "路径检查异常"} description="仅检查存储路径，不代表已完成写入测试。" />
          <StatusCard title="文本模型" icon={<Cpu size={20} aria-hidden />} tone={snapshot.llmMode === "MOCK" ? "neutral" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? "success" : "warning"} badge={snapshot.llmMode === "MOCK" ? "Mock" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? "协议已验证" : "待验证"} value={`${modeLabel(snapshot.llmMode)} · ${snapshot.llmConfigured ? "已配置" : "未配置"}${snapshot.llmMode === "CONFIGURED" ? ` · ${snapshot.llmToolCallingVerified ? "工具协议已验证" : "工具协议未验证"}` : ""}`} description="显示已保存的配置与工具协议验证结果。" />
          <StatusCard title="媒体服务" icon={<Plugs size={20} aria-hidden />} tone={snapshot.mediaMode === "MOCK" ? "neutral" : snapshot.imageConfigured && snapshot.videoConfigured ? "success" : "warning"} badge={snapshot.mediaMode === "MOCK" ? "Mock" : "配置状态"} value={`${modeLabel(snapshot.mediaMode)} · 图片${snapshot.imageConfigured ? "已配置" : "未配置"} · 视频${snapshot.videoConfigured ? "已配置" : "未配置"}`} description="已配置不代表已完成真实生成测试。" />
        </div>
      </> : null}
      <DebugModeSection enabled={currentUser.isSuccess} />
      <div className="diagnostics-detail-layout">
        <div>{snapshot ? <Panel title="近七天任务异常" description="仅显示失败、未知或阻断任务的数量和最近更新时间；不展示素材、路径或原始错误。">
          {snapshot.recentErrors.length === 0 ? <EmptyState icon={<CheckCircle size={28} aria-hidden />} title="暂无异常任务记录。" description="最近七天没有记录到失败、未知或阻断任务。" /> :
            <ul className="diagnostics-errors">{snapshot.recentErrors.map((item) => <li key={item.status}><StatusBadge tone={item.status === "FAILED" ? "danger" : "warning"}>{errorLabel(item.status)}：{item.count} 项</StatusBadge><time dateTime={item.lastAt}>最近更新 {new Date(item.lastAt).toLocaleString()}</time></li>)}</ul>}
        </Panel> : null}</div>
        <PasswordChangeSection />
      </div>
    </div>
  </PageShell>;
}

function StatusCard({ title, value, icon, description, tone, badge }: {
  title: string; value: string; icon: ReactNode; description: string; tone: "neutral" | "success" | "warning" | "danger"; badge: string;
}) {
  return <Panel title={title} actions={<StatusBadge tone={tone}>{badge}</StatusBadge>}>
    <div className="diagnostics-stat-heading"><span className="ui-icon-tile">{icon}</span><p className="diagnostics-stat-value">{value}</p></div>
    <p className="diagnostics-stat-note">{description}</p>
  </Panel>;
}

function modeLabel(mode: string): string {
  return mode === "MOCK" ? "Mock 模式" : mode === "COMFYUI" ? "ComfyUI 配置" : "已配置模型模式";
}

function errorLabel(status: string): string {
  return { FAILED: "失败", UNKNOWN: "未知", BLOCKED: "已阻断" }[status] ?? "异常";
}
