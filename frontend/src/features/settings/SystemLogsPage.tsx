import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowsClockwise,TerminalWindow } from "@/shared/ui/icons";
import { useQuery } from "@tanstack/react-query";
import { useEffect,useRef,useState } from "react";
import { Navigate } from "react-router";
import { HTTP_STATUS,ApiError,getCurrentUser,listSystemLogs,type SystemLogStream } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel } from "../../shared/ui/PagePrimitives";
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

  if (logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const forbidden = currentUser.data?.role !== undefined && currentUser.data.role !== "ADMIN"
    || logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.FORBIDDEN;
  const snapshot = logs.data;
  return <PageShell title={t("settings.systemLogs.title")} description={t("settings.systemLogs.description")} actions={
    <Button variant="outline" type="button"  disabled={logs.isFetching || !currentUser.isSuccess || forbidden}
      onClick={() => void logs.refetch()}><ArrowsClockwise size={16} aria-hidden />{logs.isFetching ? t("common.refreshing") : t("logs.refresh")}</Button>
  }>
    {forbidden ? <Notice tone="danger" title={t("settings.systemLogs.forbidden")}>{t("settings.systemLogs.adminHint")}</Notice> : <div className="ui-stack">
      <Panel title={t("settings.systemLogs.consoleOutput")} description={t("settings.systemLogs.retentionHint")}>
        <form className="system-logs-filters" onSubmit={(event) => { event.preventDefault(); setSearch(keyword.trim()); }}>
          <Field><FieldLabel className="ui-field block">{t("settings.systemLogs.channel")}<Select value={stream ?? ""} onChange={(event) => {
            const value = event.target.value;
            setStream(value === "STDOUT" || value === "STDERR" ? value : undefined);
          }}><option value="">{t("settings.systemLogs.allChannels")}</option><option value="STDOUT">{t("settings.systemLogs.stdout")}</option><option value="STDERR">{t("settings.systemLogs.stderr")}</option></Select></FieldLabel></Field>
          <label className="ui-field system-logs-keyword">{t("settings.systemLogs.keyword")}<Input type="search" maxLength={MAX_SEARCH_LENGTH} value={keyword}
            placeholder={t("settings.systemLogs.searchPlaceholder")} onChange={(event) => setKeyword(event.target.value)} /></label>
          <Field><FieldLabel className="ui-field block">{t("settings.systemLogs.lineLimit")}<Select value={limit} onChange={(event) => setLimit(Number(event.target.value))}>
            {LINE_LIMITS.map((value) => <option key={value} value={value}>{t("settings.systemLogs.recentLines", { "0": value })}</option>)}
          </Select></FieldLabel></Field>
          <Button variant="outline" type="submit" >{t("settings.systemLogs.search")}</Button>
        </form>
        <div className="system-logs-toolbar">
          <label><Checkbox  checked={automatic} onCheckedChange={(event) => setAutomatic(event === true)} />{t("settings.systemLogs.autoRefresh")}</label>
          <label><Checkbox  checked={follow} onCheckedChange={(event) => setFollow(event === true)} />{t("settings.systemLogs.followOutput")}</label>
          <span className="ui-muted">{logs.isError ? t("settings.systemLogs.refreshStopped") : automatic ? t("settings.systemLogs.autoRefreshing") : t("settings.systemLogs.refreshPaused")}</span>
        </div>
        {logs.isPending ? <LoadingState label={t("settings.systemLogs.loading")} /> : null}
        {logs.isError ? <Notice tone="danger" title={t("settings.systemLogs.loadFailed")}><p>{t("settings.systemLogs.retryHint")}</p>
          {snapshot ? <p>{t("settings.systemLogs.staleLogsHint")}</p> : null}</Notice> : null}
        {snapshot ? <>
          <div className="system-logs-summary ui-muted">
            <span>{t("settings.systemLogs.processStartedAt")}<time dateTime={snapshot.startedAt}>{new Date(snapshot.startedAt).toLocaleString(getFormatLocale())}</time></span>
            <span>{t("settings.systemLogs.matchSummary", { "0": snapshot.entries.length, "1": snapshot.matchedCount, "2": snapshot.retainedCount })}</span>
            <span>{t("settings.systemLogs.updatedAt")}<time dateTime={snapshot.checkedAt}>{new Date(snapshot.checkedAt).toLocaleTimeString(getFormatLocale())}</time></span>
          </div>
          {snapshot.droppedCount > 0 ? <p className="ui-muted">{t("settings.systemLogs.evictedLines", { "0": snapshot.droppedCount })}</p> : null}
          {snapshot.matchedCount > snapshot.entries.length ? <p className="ui-muted">{t("settings.systemLogs.limitHint", { "0": snapshot.entries.length })}</p> : null}
          {snapshot.entries.length === 0 ? <EmptyState icon={<TerminalWindow size={28} aria-hidden />}
            title={search || stream ? t("settings.systemLogs.emptySearch") : t("settings.systemLogs.empty")} description={t("settings.systemLogs.emptyHint")} /> :
            <div className="system-logs-console" ref={output} role="region" aria-label={t("settings.systemLogs.outputLabel")} tabIndex={0}>
              {snapshot.entries.map((entry) => <div className={`system-log-line system-log-line--${entry.stream.toLowerCase()}`}
                key={`${snapshot.processId}-${entry.sequence}`}>
                <span className="system-log-sequence">{entry.sequence}</span>
                <time dateTime={entry.recordedAt}>{new Date(entry.recordedAt).toLocaleTimeString(getFormatLocale())}</time>
                <span className="system-log-stream">{entry.stream}</span>
                <span className="system-log-message">{entry.message}{entry.truncated ? <em> {t("settings.systemLogs.truncatedLineSuffix")}</em> : null}</span>
              </div>)}
            </div>}
        </> : null}
      </Panel>
      <p className="ui-muted">{t("settings.systemLogs.scopeHint")}</p>
    </div>}
  </PageShell>;
}
