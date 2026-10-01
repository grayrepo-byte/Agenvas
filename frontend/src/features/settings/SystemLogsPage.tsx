import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { useQuery } from "@tanstack/react-query";
import { ArrowsClockwise, TerminalWindow } from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { Navigate } from "react-router";
import { ApiError, getCurrentUser, listSystemLogs, type SystemLogStream } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Select } from "../../shared/ui/Select";
import "./SystemLogsPage.css";

const REFRESH_INTERVAL_MS = 3000;
const DEFAULT_LIMIT = 500;
const LINE_LIMITS = [200, DEFAULT_LIMIT, 1000] as const;
const MAX_SEARCH_LENGTH = 200;
const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;

/** Replaces bounded snapshots so process restarts and retention never produce duplicate lines. */
export function SystemLogsPage() {
  useLocale();
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const [stream, setStream] = useState<SystemLogStream | undefined>();
  const [keyword, setKeyword] = useState("");
  const [search, setSearch] = useState("");
  const [limit, setLimit] = useState(DEFAULT_LIMIT);
  const [automatic, setAutomatic] = useState(true);
  const [follow, setFollow] = useState(true);
  const output = useRef<HTMLDivElement>(null);
  const logs = useQuery({
    queryKey: ["settings", "system-logs", stream, search, limit],
    queryFn: () => listSystemLogs({ stream, search, limit }),
    enabled: currentUser.isSuccess && currentUser.data.role === "ADMIN",
    retry: false,
    refetchInterval: (query) => automatic && !query.state.error ? REFRESH_INTERVAL_MS : false,
    refetchOnWindowFocus: false,
  });
  useEffect(() => {
    if (follow && output.current) output.current.scrollTop = output.current.scrollHeight;
  }, [logs.data, follow]);

  if (logs.error instanceof ApiError && logs.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  const forbidden = currentUser.data?.role !== undefined && currentUser.data.role !== "ADMIN"
    || logs.error instanceof ApiError && logs.error.status === FORBIDDEN_STATUS;
  const snapshot = logs.data;
  return <PageShell title={t("系统日志")} description={t("查看当前后端进程的标准输出与标准错误，帮助排查运行问题。")} actions={
    <button type="button" className="secondary-button" disabled={logs.isFetching || !currentUser.isSuccess || forbidden}
      onClick={() => void logs.refetch()}><ArrowsClockwise size={16} aria-hidden />{logs.isFetching ? t("正在刷新…") : t("刷新日志")}</button>
  }>
    {forbidden ? <Notice tone="danger" title={t("无权查看系统日志")}>{t("仅管理员可以读取程序运行输出。")}</Notice> : <div className="ui-stack">
      <Panel title={t("控制台输出")} description={t("保留本次进程最近 2000 行；重启后重新记录。Java 输出在换行后显示，常见凭据会脱敏。")}>
        <form className="system-logs-filters" onSubmit={(event) => { event.preventDefault(); setSearch(keyword.trim()); }}>
          <label className="ui-field">{t("输出通道")}<Select value={stream ?? ""} onChange={(event) => {
            const value = event.target.value;
            setStream(value === "STDOUT" || value === "STDERR" ? value : undefined);
          }}><option value="">{t("全部输出")}</option><option value="STDOUT">{t("标准输出 stdout")}</option><option value="STDERR">{t("标准错误 stderr")}</option></Select></label>
          <label className="ui-field system-logs-keyword">{t("关键词")}<input type="search" maxLength={MAX_SEARCH_LENGTH} value={keyword}
            placeholder={t("搜索日志内容或 Trace ID")} onChange={(event) => setKeyword(event.target.value)} /></label>
          <label className="ui-field">{t("显示行数")}<Select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
            {LINE_LIMITS.map((value) => <option key={value} value={value}>{t("最近 {0} 行", { "0": value })}</option>)}
          </Select></label>
          <button type="submit" className="secondary-button">{t("搜索日志")}</button>
        </form>
        <div className="system-logs-toolbar">
          <label><input type="checkbox" checked={automatic} onChange={(event) => setAutomatic(event.target.checked)} />{t("自动刷新（每 3 秒）")}</label>
          <label><input type="checkbox" checked={follow} onChange={(event) => setFollow(event.target.checked)} />{t("跟随最新输出")}</label>
          <span className="ui-muted">{logs.isError ? t("刷新已停止，请手动重试") : automatic ? t("自动刷新中") : t("已暂停自动刷新")}</span>
        </div>
        {logs.isPending ? <LoadingState label={t("正在读取系统日志…")} /> : null}
        {logs.isError ? <Notice tone="danger" title={t("读取系统日志失败")}><p>{t("请检查服务连接后点击“刷新日志”重试。")}</p>
          {snapshot ? <p>{t("下方保留上一次成功读取的日志。")}</p> : null}</Notice> : null}
        {snapshot ? <>
          <div className="system-logs-summary ui-muted">
            <span>{t("进程启动：")}<time dateTime={snapshot.startedAt}>{new Date(snapshot.startedAt).toLocaleString(getFormatLocale())}</time></span>
            <span>{t("显示 {0} / {1} 条匹配日志 · 已缓存 {2} 行", { "0": snapshot.entries.length, "1": snapshot.matchedCount, "2": snapshot.retainedCount })}</span>
            <span>{t("更新时间：")}<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleTimeString(getFormatLocale())}</time></span>
          </div>
          {snapshot.droppedCount > 0 ? <p className="ui-muted">{t("已淘汰 {0} 行较早输出。搜索仅覆盖当前缓存。", { "0": snapshot.droppedCount })}</p> : null}
          {snapshot.matchedCount > snapshot.entries.length ? <p className="ui-muted">{t("仅显示最新的 {0} 条匹配日志，可增加显示行数或缩小搜索范围。", { "0": snapshot.entries.length })}</p> : null}
          {snapshot.entries.length === 0 ? <EmptyState icon={<TerminalWindow size={28} aria-hidden />}
            title={search || stream ? t("没有匹配的日志") : t("暂无控制台输出")} description={t("日志在程序输出完整行后出现，可刷新或调整筛选。")} /> :
            <div className="system-logs-console" ref={output} role="region" aria-label={t("系统日志输出")} tabIndex={0}>
              {snapshot.entries.map((entry) => <div className={`system-log-line system-log-line--${entry.stream.toLowerCase()}`}
                key={`${snapshot.processId}-${entry.sequence}`}>
                <span className="system-log-sequence">{entry.sequence}</span>
                <time dateTime={entry.recordedAt}>{new Date(entry.recordedAt).toLocaleTimeString(getFormatLocale())}</time>
                <span className="system-log-stream">{entry.stream}</span>
                <span className="system-log-message">{entry.message}{entry.truncated ? <em> {t(" [该行过长，尾部已截断]")}</em> : null}</span>
              </div>)}
            </div>}
        </> : null}
      </Panel>
      <p className="ui-muted">{t("此页面仅采集后端 Java stdout / stderr；其他服务与子进程的输出请查看部署控制台。系统日志不写入项目导出。")}</p>
    </div>}
  </PageShell>;
}
