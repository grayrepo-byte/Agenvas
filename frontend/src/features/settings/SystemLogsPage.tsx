import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowsClockwise,MagnifyingGlass,TerminalWindow } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useEffect,useId,useRef,useState } from "react";
import { Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getCurrentUser,listSystemLogs,type SystemLogStream } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Checkbox } from "../../shared/ui/primitives/checkbox";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import "./SystemLogsPage.css";

const REFRESH_INTERVAL_MS = 3000;
const DEFAULT_LIMIT = 500;
const LINE_LIMITS = [200, DEFAULT_LIMIT, 1000] as const;
const MAX_SEARCH_LENGTH = 200;

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
  const automaticId = useId();
  const followId = useId();
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

  if ((logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.UNAUTHORIZED)
    || (currentUser.error instanceof ApiError && currentUser.error.status === HTTP_STATUS.UNAUTHORIZED)) return <Navigate to="/login" replace />;
  const forbidden = currentUser.data?.role !== undefined && currentUser.data.role !== "ADMIN"
    || logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.FORBIDDEN;
  const snapshot = logs.data;
  const refreshState = logs.isError || currentUser.isError ? "error" : !snapshot && logs.isPending ? "loading" : automatic ? "running" : "paused";
  const refreshLabel = logs.isError || currentUser.isError ? t("settings.systemLogs.refreshStopped") : !snapshot && logs.isPending ? t("settings.systemLogs.loading")
    : automatic ? t("settings.systemLogs.autoRefreshing") : t("settings.systemLogs.refreshPaused");
  return <PageShell title={t("settings.systemLogs.title")} description={t("settings.systemLogs.description")}>
    {currentUser.isError ? <Notice tone="danger"><p>{t("settings.shared.sessionHint")}</p><Button variant="outline" type="button" disabled={currentUser.isFetching} onClick={() => { void currentUser.refetch(); }}>{t("common.retry")}</Button></Notice> : null}
    {forbidden ? <Notice tone="danger" title={t("settings.systemLogs.forbidden")}>{t("settings.systemLogs.adminHint")}</Notice> : <div className="system-logs-workspace ui-stack">
      <Panel className="system-logs-panel" title={t("settings.systemLogs.consoleOutput")} description={t("settings.systemLogs.retentionHint")}
        actions={<div className="system-logs-state" data-state={refreshState} data-fetching={logs.isFetching || undefined} role="status">
          <StatusBadge tone={refreshState === "error" ? "danger" : refreshState === "running" ? "success" : "neutral"}>{refreshLabel}</StatusBadge>
        </div>}>
        <div className="system-logs-controls">
        <form className="system-logs-filter-form" onSubmit={(event) => { event.preventDefault(); setSearch(keyword.trim()); setKeyword(keyword.trim()); }}>
        <FieldGroup className="system-logs-filters">
          <Field><FieldLabel className="ui-field block">{t("settings.systemLogs.channel")}<Select value={stream ?? ""} onChange={(event) => {
            const value = event.target.value;
            setStream(value === "STDOUT" || value === "STDERR" ? value : undefined);
          }}><option value="">{t("settings.systemLogs.allChannels")}</option><option value="STDOUT">{t("settings.systemLogs.stdout")}</option><option value="STDERR">{t("settings.systemLogs.stderr")}</option></Select></FieldLabel></Field>
          <Field className="system-logs-keyword"><FieldLabel htmlFor="system-logs-keyword">{t("settings.systemLogs.keyword")}</FieldLabel><Input id="system-logs-keyword" type="search" maxLength={MAX_SEARCH_LENGTH} value={keyword}
            placeholder={t("settings.systemLogs.searchPlaceholder")} onChange={(event) => setKeyword(event.target.value)} aria-describedby={keyword.trim() !== search ? "system-logs-pending-search" : undefined} /></Field>
          <Field><FieldLabel className="ui-field block">{t("settings.systemLogs.lineLimit")}<Select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
            {LINE_LIMITS.map((value) => <option key={value} value={value}>{t("settings.systemLogs.recentLines", { "0": value })}</option>)}
          </Select></FieldLabel></Field>
          <Button variant="outline" size="sm" type="submit"><MagnifyingGlass data-icon="inline-start" aria-hidden />{t("settings.systemLogs.search")}</Button>
        </FieldGroup>
        {keyword.trim() !== search ? <p className="system-logs-search-pending ui-muted" id="system-logs-pending-search" role="status">{t("settings.systemLogs.searchPending")}</p> : null}
        </form>
        <div className="system-logs-toolbar">
          <FieldGroup className="system-logs-options">
            <Field orientation="horizontal"><Checkbox id={automaticId} checked={automatic} onCheckedChange={(event) => setAutomatic(event === true)} /><FieldLabel htmlFor={automaticId}>{t("settings.systemLogs.autoRefresh")}</FieldLabel></Field>
            <Field orientation="horizontal"><Checkbox id={followId} checked={follow} onCheckedChange={(event) => setFollow(event === true)} /><FieldLabel htmlFor={followId}>{t("settings.systemLogs.followOutput")}</FieldLabel></Field>
          </FieldGroup>
          <div className="ui-form-actions system-logs-toolbar-actions">
          {keyword || search ? <Button variant="ghost" size="sm" type="button" onClick={() => { setKeyword(""); setSearch(""); }}>{t("settings.systemLogs.clearSearch")}</Button> : null}
          {!follow && snapshot?.entries.length ? <Button variant="outline" size="sm" type="button" onClick={() => setFollow(true)}>{t("settings.systemLogs.jumpToLatest")}</Button> : null}
          <Button variant="ghost" size="sm" type="button" disabled={logs.isFetching || !currentUser.isSuccess || forbidden}
            onClick={() => void logs.refetch()}><ArrowsClockwise data-icon="inline-start" aria-hidden />{logs.isFetching ? t("common.refreshing") : t("logs.refresh")}</Button>
          </div>
        </div>
        </div>
        <div className="system-logs-output">
        {!currentUser.isError && logs.isPending ? <LoadingState label={t("settings.systemLogs.loading")} /> : null}
        {logs.isError ? <Notice tone="danger" title={t("settings.systemLogs.loadFailed")}><p>{t("settings.systemLogs.retryHint")}</p>
          {snapshot ? <p>{t("settings.systemLogs.staleLogsHint")}</p> : null}</Notice> : null}
        {snapshot ? <>
          <div className="system-logs-summary ui-muted">
            <span>{t("settings.systemLogs.matchSummary", { "0": snapshot.entries.length, "1": snapshot.matchedCount, "2": snapshot.retainedCount })}</span>
            <span>{t("settings.systemLogs.updatedAt")}<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleTimeString(getFormatLocale())}</time></span>
          </div>
          {snapshot.droppedCount > 0 || snapshot.matchedCount > snapshot.entries.length ? <div className="system-logs-retention ui-muted">
            {snapshot.droppedCount > 0 ? <span>{t("settings.systemLogs.evictedLines", { "0": snapshot.droppedCount })}</span> : null}
            {snapshot.matchedCount > snapshot.entries.length ? <span>{t("settings.systemLogs.limitHint", { "0": snapshot.entries.length })}</span> : null}
          </div> : null}
          {snapshot.entries.length === 0 ? <EmptyState icon={<TerminalWindow size={28} aria-hidden />}
            title={search || stream ? t("settings.systemLogs.emptySearch") : t("settings.systemLogs.empty")} description={t("settings.systemLogs.emptyHint")} /> :
            <div className="system-logs-console" ref={output} role="region" aria-label={t("settings.systemLogs.outputLabel")} tabIndex={0}
              onScroll={(event) => { const element = event.currentTarget; if (follow && element.scrollHeight - element.clientHeight - element.scrollTop > 40) setFollow(false); }}>
              {snapshot.entries.map((entry) => <div className={`system-log-line system-log-line--${entry.stream.toLowerCase()}`}
                key={`${snapshot.processId}-${entry.sequence}`}>
                <span className="system-log-sequence">{entry.sequence}</span>
                <time dateTime={entry.recordedAt}>{new Date(entry.recordedAt).toLocaleTimeString(getFormatLocale())}</time>
                <span className="system-log-stream">{entry.stream}</span>
                <span className="system-log-message">{entry.message}{entry.truncated ? <em> {t("settings.systemLogs.truncatedLineSuffix")}</em> : null}</span>
              </div>)}
            </div>}
          <footer className="system-logs-process ui-muted"><TerminalWindow aria-hidden /><span>{t("settings.systemLogs.processStartedAt")}<time dateTime={snapshot.startedAt}>{new Date(snapshot.startedAt).toLocaleString(getFormatLocale())}</time></span></footer>
        </> : null}
        </div>
      </Panel>
      <p className="ui-muted">{t("settings.systemLogs.scopeHint")}</p>
    </div>}
  </PageShell>;
}
