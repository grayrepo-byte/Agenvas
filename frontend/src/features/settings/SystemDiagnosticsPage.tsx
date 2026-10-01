import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { useQuery } from "@tanstack/react-query";
import { ArrowsClockwise, CheckCircle, Cpu, Database, HardDrives, Plugs } from "@phosphor-icons/react";
import type { ReactNode } from "react";
import { Link, Navigate } from "react-router";
import { ApiError, getCurrentUser, getSystemDiagnostics } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { DebugModeSection } from "./DebugModeSection";
import { PasswordChangeSection } from "./PasswordChangeSection";
import "./SettingsPages.css";
import { LanguageSelect } from "../../shared/i18n/LanguageSelect";

const UNAUTHORIZED_STATUS = 401;

/** System preferences and local diagnostics; refreshing never calls a paid Provider. */
export function SystemDiagnosticsPage() {
  useLocale();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const diagnostics = useQuery({
    queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, enabled: currentUser.isSuccess, retry: false,
  });

  if (diagnostics.error instanceof ApiError && diagnostics.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;

  const snapshot = diagnostics.data;
  return <PageShell title={t("系统设置")} description={t("管理密码与 debug 模式，查看本地运行状态。诊断刷新不会连接模型或媒体服务。")} actions={
    <button className="secondary-button" type="button" disabled={diagnostics.isFetching || !currentUser.isSuccess} onClick={() => { void diagnostics.refetch(); }}><ArrowsClockwise size={16} aria-hidden />{diagnostics.isFetching ? t("正在检查…") : t("刷新状态")}</button>
  }>
    <div className="ui-stack">
      <Panel title={t("语言")} description={t("选择界面和服务端响应的语言。此偏好仅保存在当前浏览器。")}>
        <LanguageSelect />
      </Panel>
      {diagnostics.isPending ? <LoadingState label={t("正在读取诊断…")} /> : null}
      {diagnostics.isError ? <Notice tone="danger" title={t("读取诊断失败")}><p>{t("数据库或会话可能不可用。请稍后重试。")}</p>{snapshot ? <p>{t("下面保留上一次读取的状态，尚未更新。")}</p> : null}</Notice> : null}
      {snapshot ? <>
        <div className="ui-toolbar"><StatusBadge>{t("本地检查")}</StatusBadge><span className="ui-muted">{t("检查时间：")}<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleString(getFormatLocale())}</time></span>{diagnostics.isFetching ? <LoadingState compact label={t("正在更新状态…")} /> : null}</div>
        <div className="diagnostics-stats">
          <StatusCard title={t("数据库")} icon={<Database size={20} aria-hidden />} tone={snapshot.database === "AVAILABLE" ? "success" : "danger"} badge={snapshot.database === "AVAILABLE" ? t("正常") : t("不可用")} value={snapshot.database === "AVAILABLE" ? t("可读取") : t("不可用")} description={t("检查应用能否读取本地数据库。")} />
          <StatusCard title={t("本地工作目录")} icon={<HardDrives size={20} aria-hidden />} tone={snapshot.storage === "AVAILABLE" ? "success" : "warning"} badge={snapshot.storage === "AVAILABLE" ? t("路径正常") : t("需检查")} value={snapshot.storage === "AVAILABLE" ? t("路径检查正常（未试写）") : t("路径检查异常")} description={<>{t("仅检查本地归档、上传校验与媒体处理的目录；不探测云存储。")}<Link to="/settings/storage">{t("管理资源存储")}</Link></>} />
          <StatusCard title={t("文本模型")} icon={<Cpu size={20} aria-hidden />} tone={snapshot.llmMode === "MOCK" ? "neutral" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? "success" : "warning"} badge={snapshot.llmMode === "MOCK" ? "Mock" : snapshot.llmConfigured && snapshot.llmToolCallingVerified ? t("协议已验证") : t("待验证")} value={`${modeLabel(snapshot.llmMode)} · ${snapshot.llmConfigured ? t("已配置") : t("未配置")}${snapshot.llmMode === "CONFIGURED" ? ` · ${snapshot.llmToolCallingVerified ? t("工具协议已验证") : t("工具协议未验证")}` : ""}`} description={t("显示已保存的配置与工具协议验证结果。")} />
          <StatusCard title={t("媒体服务")} icon={<Plugs size={20} aria-hidden />} tone={snapshot.mediaMode === "MOCK" ? "neutral" : snapshot.imageConfigured && snapshot.videoConfigured ? "success" : "warning"} badge={snapshot.mediaMode === "MOCK" ? "Mock" : t("配置状态")} value={t("{0} · 图片{1} · 视频{2}", { "0": modeLabel(snapshot.mediaMode), "1": snapshot.imageConfigured ? t("已配置") : t("未配置"), "2": snapshot.videoConfigured ? t("已配置") : t("未配置") })} description={t("已配置不代表已完成真实生成测试。")} />
        </div>
      </> : null}
      <DebugModeSection enabled={currentUser.isSuccess} />
      <div className="diagnostics-detail-layout">
        <div>{snapshot ? <Panel title={t("近七天任务异常")} description={t("仅显示失败、未知或阻断任务的数量和最近更新时间；不展示素材、路径或原始错误。")}>
          {snapshot.recentErrors.length === 0 ? <EmptyState icon={<CheckCircle size={28} aria-hidden />} title={t("暂无异常任务记录。")} description={t("最近七天没有记录到失败、未知或阻断任务。")} /> :
            <ul className="diagnostics-errors">{snapshot.recentErrors.map((item) => <li key={item.status}><StatusBadge tone={item.status === "FAILED" ? "danger" : "warning"}>{t("{0}：{1} 项", { "0": errorLabel(item.status), "1": item.count })}</StatusBadge><time dateTime={item.lastAt}>{t("最近更新 {0}", { "0": new Date(item.lastAt).toLocaleString(getFormatLocale()) })}</time></li>)}</ul>}
        </Panel> : null}</div>
        <PasswordChangeSection />
      </div>
    </div>
  </PageShell>;
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
  return mode === "MOCK" ? t("Mock 模式") : mode === "COMFYUI" ? t("ComfyUI 配置") : t("已配置模型模式");
}

function errorLabel(status: string): string {
  return { FAILED: t("失败"), UNKNOWN: t("未知"), BLOCKED: t("已阻断") }[status] ?? t("异常");
}
