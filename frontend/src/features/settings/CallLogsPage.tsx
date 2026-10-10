import { Field, FieldError, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowSquareOut,ListMagnifyingGlass } from "@/shared/ui/icons";
import { useQuery,useQueryClient } from "@tanstack/react-query";
import { useState,type FormEvent,type ReactNode } from "react";
import { Link,Navigate,useSearchParams } from "react-router";
import { HTTP_STATUS,ApiError,getCurrentUser,getTask,listCallLogs,type CallLog,type CallLogFilters,type Task } from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { DateTimePicker } from "../../shared/ui/DateTimePicker";
import { Dialog } from "../../shared/ui/Dialog";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState,Notice,Panel,StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Table,TableBody,TableCaption,TableCell,TableHead,TableHeader,TableRow } from "../../shared/ui/primitives/table";
import { Select } from "../../shared/ui/Select";
import { CallDebugDetails } from "./CallDebugDetails";
import "./CallLogsPage.css";

const PAGE_SIZE = 20;
const FIRST_PAGE = 0;
const BAD_REQUEST_STATUS = 400;
const KIND_LABELS: Record<CallLog["kind"], string> = { get LLM() { return t("models.text"); }, get IMAGE() { return t("common.image"); }, get VIDEO() { return t("common.video"); }, get AUDIO() { return t("common.audio"); } };
const STATUS_LABELS: Record<CallLog["status"], string> = { get RUNNING() { return t("logs.calls.calling"); }, get SUCCEEDED() { return t("logs.calls.success"); }, get FAILED() { return t("common.failed"); }, get UNKNOWN() { return t("common.unknown"); } };
const OPERATION_LABELS: Record<CallLog["operation"], string> = { get CHAT() { return t("logs.calls.modelConversation"); }, get SUBMIT() { return t("logs.calls.submitGeneration"); }, get POLL() { return t("logs.calls.results"); }, get LEGACY() { return t("logs.calls.historicalTask"); } };
const STATUS_TONES = { RUNNING: "neutral", SUCCEEDED: "success", FAILED: "danger", UNKNOWN: "warning" } as const;
const TASK_STATUS_LABELS: Record<Task["status"], string> = {
  get READY() { return t("tasks.status.queued"); }, get SUBMITTING() { return t("logs.calls.submitting"); }, get RUNNING() { return t("tasks.status.running"); }, get WAITING_PROVIDER() { return t("logs.calls.waitingForExternal"); },
  get SUCCEEDED() { return t("common.succeeded"); }, get FAILED() { return t("common.failed"); }, get UNKNOWN() { return t("common.unknown"); }, get BLOCKED() { return t("tasks.status.blocked"); }, get CANCELED() { return t("common.canceled"); },
};

function readFilters(params: URLSearchParams): CallLogFilters {
  const kind = params.get("kind");
  const status = params.get("status");
  const page = Number(params.get("page") ?? FIRST_PAGE);
  return {
    projectId: params.get("projectId") || undefined,
    kind: kind === "LLM" || kind === "IMAGE" || kind === "VIDEO" || kind === "AUDIO" ? kind : undefined,
    status: status === "RUNNING" || status === "SUCCEEDED" || status === "FAILED" || status === "UNKNOWN" ? status : undefined,
    traceId: params.get("traceId") || undefined,
    from: params.get("from") || undefined,
    to: params.get("to") || undefined,
    page: Number.isSafeInteger(page) && page >= FIRST_PAGE ? page : FIRST_PAGE,
    size: PAGE_SIZE,
  };
}

/** A read-only audit list; recovery stays inside the owning Agent conversation or media card. */
export function CallLogsPage() {
  useLocale();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const filters = readFilters(params);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const logs = useQuery({
    queryKey: ["call-logs", filters], queryFn: () => listCallLogs(filters), enabled: currentUser.isSuccess, retry: false,
  });
  const forbidden = logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.FORBIDDEN;
  const invalidFilters = logs.error instanceof ApiError && logs.error.status === BAD_REQUEST_STATUS;
  if (logs.error instanceof ApiError && logs.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  const data = forbidden ? undefined : logs.data;
  const selectedLog = data?.items.find((log) => log.id === selectedId);
  const setPage = (page: number) => {
    setSelectedId(null);
    const next = new URLSearchParams(params);
    next.set("page", String(page));
    setParams(next);
  };
  const applyFilters = (next: URLSearchParams) => {
    setSelectedId(null);
    // Refresh the target page even when its filters are unchanged or still cached.
    void queryClient.invalidateQueries({ queryKey: ["call-logs", readFilters(next)], exact: true });
    setParams(next);
  };

  return <PageShell title={t("common.callLogs")} description={t("logs.calls.description")}><div className="ui-stack">
    <Panel title={t("logs.calls.filterRecords")} description={t("logs.calls.filterTimeHint")}>
      <CallLogFilterForm key={params.toString()} filters={filters} onApply={applyFilters} disabled={logs.isFetching || !currentUser.isSuccess} />
    </Panel>
    {logs.isPending && currentUser.isSuccess ? <LoadingState label={t("logs.calls.loading")} /> : null}
    {logs.isError ? <Notice tone="danger" title={forbidden ? t("logs.calls.forbidden") : invalidFilters ? t("logs.calls.invalidFilter") : t("logs.calls.loadFailed")}>
      <p>{forbidden ? t("logs.calls.forbiddenHint") : invalidFilters ? t("logs.calls.invalidFilterHint") : data ? t("logs.calls.staleRecordsHint") : t("logs.calls.serviceUnavailable")}</p>
      <Button variant="outline"  type="button" disabled={logs.isFetching} onClick={() => void logs.refetch()}>{t("logs.calls.retryLogs")}</Button>
    </Notice> : null}
    {data ? <Panel title={t("logs.calls.records")} description={t("logs.calls.recordCount", { "0": data.totalElements })} className="call-log-results"
      actions={logs.isFetching ? <LoadingState compact label={t("logs.calls.updating")} /> : undefined}>
      <p className="ui-muted">{t("logs.calls.durationHint")}</p>
      {data.items.length === 0 ? <EmptyState icon={<ListMagnifyingGlass size={27} />} title={t("logs.calls.empty")}
        description={t("logs.calls.emptyHint")} /> :
        <Table className="call-log-table"><TableCaption className="sr-only">{t("logs.calls.auditTitle")}</TableCaption><TableHeader><TableRow>
          <TableHead scope="col" className="call-log-model-column">{t("common.model")}</TableHead><TableHead scope="col" className="call-log-type-column">{t("logs.calls.callType")}</TableHead><TableHead scope="col">{t("common.project")}</TableHead><TableHead scope="col">{t("logs.calls.time")}</TableHead><TableHead scope="col">{t("logs.calls.responseTime")}</TableHead>
          <TableHead scope="col" className="call-log-duration-column">{t("logs.calls.duration")}</TableHead><TableHead scope="col" className="call-log-result-column">{t("logs.calls.result")}</TableHead><TableHead scope="col" className="call-log-details-column"><span className="sr-only">{t("logs.calls.details")}</span></TableHead>
        </TableRow></TableHeader><TableBody>{data.items.map((log) => <CallLogRow key={log.id} log={log}
          onDetails={() => setSelectedId(log.id)} />)}</TableBody></Table>}
      <nav className="call-log-pagination" aria-label={t("logs.calls.paginationLabel")}>
        <span className="ui-muted">{t("logs.calls.pagination", { "0": data.page + 1, "1": Math.max(1, data.totalPages), "2": data.size })}</span>
        <div className="ui-form-actions"><Button variant="outline"  type="button" disabled={logs.isFetching || data.page === FIRST_PAGE}
          onClick={() => setPage(data.page - 1)}>{t("logs.calls.previousPage")}</Button>
          <Button variant="outline"  type="button" disabled={logs.isFetching || data.page + 1 >= data.totalPages}
            onClick={() => setPage(data.page + 1)}>{t("logs.calls.nextPage")}</Button></div>
      </nav>
    </Panel> : null}
    {selectedLog ? <CallLogDetailDialog log={selectedLog} onClose={() => setSelectedId(null)} /> : null}
  </div></PageShell>;
}

function CallLogFilterForm({ filters, onApply, disabled }: { filters: CallLogFilters; onApply: (params: URLSearchParams) => void; disabled: boolean }) {
  useLocale();
  const [error, setError] = useState<string | null>(null);
  const filterCount = [filters.projectId, filters.kind, filters.status, filters.traceId, filters.from, filters.to].filter(Boolean).length;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled) return;
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    for (const field of ["projectId", "kind", "status", "traceId", "from", "to"]) {
      const value = String(form.get(field) ?? "").trim();
      if (value) next.set(field, field === "from" || field === "to" ? new Date(value).toISOString() : value);
    }
    const from = next.get("from");
    const to = next.get("to");
    if (from && to && from > to) {
      setError(t("logs.calls.invalidTimeRange"));
      return;
    }
    setError(null);
    onApply(next);
  }
  return <form className="ui-form" onSubmit={submit}>
    <FieldGroup className="call-log-filters">
      <Field><FieldLabel htmlFor="call-log-project">{t("logs.calls.projectId")}</FieldLabel>
        <Input id="call-log-project" name="projectId" defaultValue={filters.projectId} placeholder={t("logs.calls.allProjects")} /></Field>
      <Field><FieldLabel htmlFor="call-log-kind">{t("logs.calls.callType")}</FieldLabel>
        <Select id="call-log-kind" name="kind" defaultValue={filters.kind ?? ""}><option value="">{t("common.allKinds")}</option>
          {Object.entries(KIND_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></Field>
      <Field><FieldLabel htmlFor="call-log-status">{t("logs.calls.status")}</FieldLabel>
        <Select id="call-log-status" name="status" defaultValue={filters.status ?? ""}><option value="">{t("logs.calls.allStatuses")}</option>
          {Object.entries(STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></Field>
      <Field><FieldLabel htmlFor="call-log-trace">Trace ID</FieldLabel>
        <Input id="call-log-trace" name="traceId" defaultValue={filters.traceId} placeholder={t("logs.calls.traceSearchPlaceholder")} /></Field>
      <Field data-invalid={Boolean(error)}><FieldLabel htmlFor="call-log-from">{t("logs.calls.startTime")}</FieldLabel>
        <DateTimePicker id="call-log-from" name="from" label={t("logs.calls.startTime")} defaultValue={filters.from} invalid={Boolean(error)} describedBy={error ? "call-log-time-error" : undefined} /></Field>
      <Field data-invalid={Boolean(error)}><FieldLabel htmlFor="call-log-to">{t("logs.calls.endTime")}</FieldLabel>
        <DateTimePicker id="call-log-to" name="to" label={t("logs.calls.endTime")} defaultValue={filters.to} invalid={Boolean(error)} describedBy={error ? "call-log-time-error" : undefined} /></Field>
    </FieldGroup>
    {error ? <FieldError id="call-log-time-error">{error}</FieldError> : null}
    <div className="ui-toolbar"><span className="ui-muted" role="status">{filterCount ? t("logs.calls.filterCount", { "0": filterCount }) : t("logs.calls.allRecords")}</span>
    <div className="ui-form-actions"><Button variant="default"  type="submit" disabled={disabled}>{t("logs.calls.filter")}</Button>
      <Button variant="ghost"  type="button" disabled={disabled} onClick={() => { setError(null); onApply(new URLSearchParams()); }}>{t("logs.calls.clearFilters")}</Button></div></div>
  </form>;
}

function CallLogRow({ log, onDetails }: { log: CallLog; onDetails: () => void }) {
  useLocale();
  return <TableRow>
      <TableCell data-label={t("common.model")}><span className="call-log-model" title={log.model ?? t("logs.calls.modelNotRecorded")}>{log.model ?? t("logs.calls.modelNotRecorded")}</span></TableCell>
      <TableCell data-label={t("logs.calls.callType")}><strong>{KIND_LABELS[log.kind]} · {OPERATION_LABELS[log.operation]}</strong>
        <div className="call-log-badges">{log.mock ? <StatusBadge>{t("logs.calls.mock")}</StatusBadge> : null}
          {log.historical ? <StatusBadge>{t("logs.calls.historicalRecord")}</StatusBadge> : null}</div></TableCell>
      <TableCell data-label={t("common.project")}><Link className="call-log-project" to={`/projects/${encodeURIComponent(log.projectId)}`}>{log.projectTitle}<ArrowSquareOut size={13} aria-hidden /></Link></TableCell>
      <TableCell data-label={t("logs.calls.time")}><LogTime value={log.startedAt} /></TableCell>
      <TableCell data-label={t("logs.calls.responseTime")}><LogTime value={log.respondedAt} missing={log.status === "RUNNING" ? t("logs.calls.waitingForResponse") : t("common.notRecorded")} /></TableCell>
      <TableCell data-label={t("logs.calls.duration")}>{log.durationMs === null ? t("common.notRecorded") : `${log.durationMs.toLocaleString(getFormatLocale())} ms`}</TableCell>
      <TableCell data-label={t("logs.calls.result")}><StatusBadge tone={STATUS_TONES[log.status]}>{STATUS_LABELS[log.status]}</StatusBadge></TableCell>
      <TableCell className="call-log-toggle"><Button variant="ghost"  type="button" aria-haspopup="dialog"
        aria-label={t("logs.calls.detailsLabel", { "0": log.id })} onClick={onDetails}>{t("logs.calls.details")}<ArrowSquareOut size={14} aria-hidden /></Button></TableCell>
    </TableRow>;
}

function CallLogDetailDialog({ log, onClose }: { log: CallLog; onClose: () => void }) {
  useLocale();
  return <Dialog title={t("logs.calls.detailsTitle")} description={`${KIND_LABELS[log.kind]} · ${log.model ?? t("logs.calls.modelNotRecorded")}`}
    className="call-log-dialog" onClose={onClose} onSubmit={(event) => event.preventDefault()}
    footer={<Button variant="outline" type="button"  onClick={onClose}>{t("common.close")}</Button>}>
    <div className="call-log-overview">
      <Link className="call-log-project" to={`/projects/${encodeURIComponent(log.projectId)}`}>{log.projectTitle}<ArrowSquareOut size={14} aria-hidden /></Link>
      <div className="call-log-badges"><StatusBadge tone={STATUS_TONES[log.status]}>{STATUS_LABELS[log.status]}</StatusBadge>
        {log.mock ? <StatusBadge>{t("logs.calls.mock")}</StatusBadge> : null}
        {log.historical ? <StatusBadge>{t("logs.calls.historicalRecord")}</StatusBadge> : null}</div>
    </div>
    <CallLogDetails log={log} />
  </Dialog>;
}

function CallLogDetails({ log }: { log: CallLog }) {
  useLocale();
  return <>
    {log.historical ? <p className="ui-muted">{t("logs.calls.historicalFieldsHint")}</p> : null}
    <dl className="call-log-metadata">
      <Detail label={t("logs.calls.time")} value={<LogTime value={log.startedAt} />} />
      <Detail label={t("logs.calls.responseTime")} value={<LogTime value={log.respondedAt} missing={log.status === "RUNNING" ? t("logs.calls.waitingForResponse") : t("common.notRecorded")} />} />
      <Detail label={t("logs.calls.duration")} value={log.durationMs === null ? null : `${log.durationMs.toLocaleString(getFormatLocale())} ms`} />
      <Detail label={t("logs.calls.operations")} value={OPERATION_LABELS[log.operation]} />
      <Detail label={t("logs.calls.localTraceId")} value={log.traceId} /><Detail label={t(log.kind === "LLM" ? "logs.exchange.generationId" : "logs.calls.providerRequestId")} value={log.providerRequestId} />
      <Detail label="Provider" value={log.provider} /><Detail label={t("common.model")} value={log.model} />
      <Detail label={t("logs.calls.recordId")} value={log.id} /><Detail label="Run ID" value={log.runId} />
      <Detail label="Task ID" value={log.taskId} /><Detail label={t("logs.calls.errorCode")} value={log.errorCode} />
    </dl>
    {!log.historical ? <CallDebugDetails id={log.id} kind={log.kind} generationId={log.kind === "LLM" ? log.providerRequestId : undefined} /> : null}
    {log.taskId ? <CallLogTask projectId={log.projectId} taskId={log.taskId} /> : null}
  </>;
}

/** Reads the related task for context only; this page never performs recovery. */
function CallLogTask({ projectId, taskId }: { projectId: string; taskId: string }) {
  useLocale();
  const task = useQuery({ queryKey: ["call-log-task", projectId, taskId], queryFn: () => getTask(projectId, taskId), retry: false });
  const forbidden = task.error instanceof ApiError && task.error.status === HTTP_STATUS.FORBIDDEN;
  if (task.error instanceof ApiError && task.error.status === HTTP_STATUS.UNAUTHORIZED) return <Navigate to="/login" replace />;
  return <section className="call-log-task" aria-label={t("logs.calls.relatedTask")}>
    <h3>{t("logs.calls.relatedTask")}</h3>
    {task.isPending ? <LoadingState compact label={t("logs.calls.taskLoading")} /> : null}
    {task.isError ? <Notice tone="danger" title={forbidden ? t("logs.calls.taskForbidden") : t("logs.calls.taskLoadFailed")}>
      <p>{t("logs.calls.taskStatusUnavailable")}</p>
      <Button variant="outline"  type="button" disabled={task.isFetching} onClick={() => void task.refetch()}>{t("logs.calls.retryTask")}</Button>
    </Notice> : null}
    {task.data && !task.isError ? <div className="ui-toolbar">
      <span className="ui-muted">{t("logs.calls.currentStatus", { "0": TASK_STATUS_LABELS[task.data.status] })}</span>
      <Button variant="outline" asChild><Link to={`/projects/${encodeURIComponent(projectId)}`}>{t("logs.calls.openProject")}<ArrowSquareOut data-icon="inline-end" aria-hidden /></Link></Button>
    </div> : null}
  </section>;
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  useLocale();
  return <div><dt>{label}</dt><dd>{value ?? <span className="ui-muted">{t("common.notRecorded")}</span>}</dd></div>;
}

function LogTime({ value, missing = t("common.notRecorded") }: { value: string | null; missing?: string }) {
  useLocale();
  return value ? <time dateTime={value}>{new Date(value).toLocaleString(getFormatLocale())}</time> : <span className="ui-muted">{missing}</span>;
}
