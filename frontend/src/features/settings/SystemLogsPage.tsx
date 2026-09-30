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
  return <PageShell title="系统日志" description="查看当前后端进程的标准输出与标准错误，帮助排查运行问题。" actions={
    <button type="button" className="secondary-button" disabled={logs.isFetching || !currentUser.isSuccess || forbidden}
      onClick={() => void logs.refetch()}><ArrowsClockwise size={16} aria-hidden />{logs.isFetching ? "正在刷新…" : "刷新日志"}</button>
  }>
    {forbidden ? <Notice tone="danger" title="无权查看系统日志">仅管理员可以读取程序运行输出。</Notice> : <div className="ui-stack">
      <Panel title="控制台输出" description="保留本次进程最近 2000 行；重启后重新记录。Java 输出在换行后显示，常见凭据会脱敏。">
        <form className="system-logs-filters" onSubmit={(event) => { event.preventDefault(); setSearch(keyword.trim()); }}>
          <label className="ui-field">输出通道<Select value={stream ?? ""} onChange={(event) => {
            const value = event.target.value;
            setStream(value === "STDOUT" || value === "STDERR" ? value : undefined);
          }}><option value="">全部输出</option><option value="STDOUT">标准输出 stdout</option><option value="STDERR">标准错误 stderr</option></Select></label>
          <label className="ui-field system-logs-keyword">关键词<input type="search" maxLength={MAX_SEARCH_LENGTH} value={keyword}
            placeholder="搜索日志内容或 Trace ID" onChange={(event) => setKeyword(event.target.value)} /></label>
          <label className="ui-field">显示行数<Select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
            {LINE_LIMITS.map((value) => <option key={value} value={value}>最近 {value} 行</option>)}
          </Select></label>
          <button type="submit" className="secondary-button">搜索日志</button>
        </form>
        <div className="system-logs-toolbar">
          <label><input type="checkbox" checked={automatic} onChange={(event) => setAutomatic(event.target.checked)} />自动刷新（每 3 秒）</label>
          <label><input type="checkbox" checked={follow} onChange={(event) => setFollow(event.target.checked)} />跟随最新输出</label>
          <span className="ui-muted">{logs.isError ? "刷新已停止，请手动重试" : automatic ? "自动刷新中" : "已暂停自动刷新"}</span>
        </div>
        {logs.isPending ? <LoadingState label="正在读取系统日志…" /> : null}
        {logs.isError ? <Notice tone="danger" title="读取系统日志失败"><p>请检查服务连接后点击“刷新日志”重试。</p>
          {snapshot ? <p>下方保留上一次成功读取的日志。</p> : null}</Notice> : null}
        {snapshot ? <>
          <div className="system-logs-summary ui-muted">
            <span>进程启动：<time dateTime={snapshot.startedAt}>{new Date(snapshot.startedAt).toLocaleString()}</time></span>
            <span>显示 {snapshot.entries.length} / {snapshot.matchedCount} 条匹配日志 · 已缓存 {snapshot.retainedCount} 行</span>
            <span>更新时间：<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleTimeString()}</time></span>
          </div>
          {snapshot.droppedCount > 0 ? <p className="ui-muted">已淘汰 {snapshot.droppedCount} 行较早输出。搜索仅覆盖当前缓存。</p> : null}
          {snapshot.matchedCount > snapshot.entries.length ? <p className="ui-muted">仅显示最新的 {snapshot.entries.length} 条匹配日志，可增加显示行数或缩小搜索范围。</p> : null}
          {snapshot.entries.length === 0 ? <EmptyState icon={<TerminalWindow size={28} aria-hidden />}
            title={search || stream ? "没有匹配的日志" : "暂无控制台输出"} description="日志在程序输出完整行后出现，可刷新或调整筛选。" /> :
            <div className="system-logs-console" ref={output} role="region" aria-label="系统日志输出" tabIndex={0}>
              {snapshot.entries.map((entry) => <div className={`system-log-line system-log-line--${entry.stream.toLowerCase()}`}
                key={`${snapshot.processId}-${entry.sequence}`}>
                <span className="system-log-sequence">{entry.sequence}</span>
                <time dateTime={entry.recordedAt}>{new Date(entry.recordedAt).toLocaleTimeString()}</time>
                <span className="system-log-stream">{entry.stream}</span>
                <span className="system-log-message">{entry.message}{entry.truncated ? <em> [该行过长，尾部已截断]</em> : null}</span>
              </div>)}
            </div>}
        </> : null}
      </Panel>
      <p className="ui-muted">此页面仅采集后端 Java stdout / stderr；其他服务与子进程的输出请查看部署控制台。系统日志不写入项目导出。</p>
    </div>}
  </PageShell>;
}
