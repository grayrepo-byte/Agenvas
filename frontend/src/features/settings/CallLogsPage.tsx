import { useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowSquareOut, ArrowsClockwise, CaretDown, ListMagnifyingGlass } from "@phosphor-icons/react";
import { Fragment, useId, useState, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, useSearchParams } from "react-router";
import { ApiError, getCurrentUser, getTask, listCallLogs, type CallLog, type CallLogFilters, type Task } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { UnknownTaskAttemptPanel } from "../canvas/UnknownTaskAttemptPanel";
import "./CallLogsPage.css";

const PAGE_SIZE = 20;
const FIRST_PAGE = 0;
const BAD_REQUEST_STATUS = 400;
const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;
const MILLISECONDS_PER_MINUTE = 60_000;
const LOCAL_DATE_TIME_LENGTH = 19;
const TABLE_COLUMN_COUNT = 7;
const KIND_LABELS: Record<CallLog["kind"], string> = { LLM: "文本模型", IMAGE: "图片", VIDEO: "视频" };
const STATUS_LABELS: Record<CallLog["status"], string> = { RUNNING: "调用中", SUCCEEDED: "成功", FAILED: "失败", UNKNOWN: "待核对" };
const OPERATION_LABELS: Record<CallLog["operation"], string> = { CHAT: "模型对话", SUBMIT: "提交生成", POLL: "查询结果", LEGACY: "历史任务" };
const STATUS_TONES = { RUNNING: "neutral", SUCCEEDED: "success", FAILED: "danger", UNKNOWN: "warning" } as const;
const TASK_STATUS_LABELS: Record<Task["status"], string> = {
  PENDING: "等待依赖", READY: "排队中", SUBMITTING: "提交中", RUNNING: "运行中", WAITING_PROVIDER: "等待外部结果",
  SUCCEEDED: "已完成", FAILED: "失败", UNKNOWN: "待核对", BLOCKED: "已阻断", CANCELED: "已取消",
};

function readFilters(params: URLSearchParams): CallLogFilters {
  const kind = params.get("kind");
  const status = params.get("status");
  const page = Number(params.get("page") ?? FIRST_PAGE);
  return {
    projectId: params.get("projectId") || undefined,
    kind: kind === "LLM" || kind === "IMAGE" || kind === "VIDEO" ? kind : undefined,
    status: status === "RUNNING" || status === "SUCCEEDED" || status === "FAILED" || status === "UNKNOWN" ? status : undefined,
    traceId: params.get("traceId") || undefined,
    from: params.get("from") || undefined,
    to: params.get("to") || undefined,
    page: Number.isSafeInteger(page) && page >= FIRST_PAGE ? page : FIRST_PAGE,
    size: PAGE_SIZE,
  };
}

/** A read-only audit list; recovery actions remain explicit inside the selected task's details. */
export function CallLogsPage() {
  const [params, setParams] = useSearchParams();
  const filters = readFilters(params);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const currentUser = useQuery({ queryKey: ["auth", "me"], queryFn: getCurrentUser, retry: false });
  const logs = useQuery({
    queryKey: ["call-logs", filters], queryFn: () => listCallLogs(filters), enabled: currentUser.isSuccess, retry: false,
  });
  const forbidden = logs.error instanceof ApiError && logs.error.status === FORBIDDEN_STATUS;
  const invalidFilters = logs.error instanceof ApiError && logs.error.status === BAD_REQUEST_STATUS;
  if (logs.error instanceof ApiError && logs.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  const data = forbidden ? undefined : logs.data;
  const setPage = (page: number) => {
    const next = new URLSearchParams(params);
    next.set("page", String(page));
    setParams(next);
  };

  return <PageShell title="调用日志" description="查看模型与媒体调用的时间、结果和关联记录。刷新日志不会发起生成。" actions={
    <button className="secondary-button" type="button" disabled={logs.isFetching || !currentUser.isSuccess}
      onClick={() => void logs.refetch()}><ArrowsClockwise size={16} aria-hidden />{logs.isFetching ? "正在刷新…" : "刷新日志"}</button>
  }><div className="ui-stack">
    <Panel title="筛选记录" description="时间按当前设备时区显示；筛选按调用开始时间匹配。待核对也包含关联任务尚未核实的记录。">
      <CallLogFilterForm key={params.toString()} filters={filters} onApply={setParams} />
    </Panel>
    {logs.isPending && currentUser.isSuccess ? <LoadingState label="正在读取调用日志" /> : null}
    {logs.isError ? <Notice tone="danger" title={forbidden ? "无权查看调用日志" : invalidFilters ? "筛选条件无效" : "读取调用日志失败"}>
      <p>{forbidden ? "当前账户没有查看这些记录的权限。" : invalidFilters ? "请检查项目 ID、完整 Trace ID 和时间范围后重新筛选。" : data ? "下面保留上次读取的记录，尚未更新。请重试刷新。" : "服务暂时不可用，请重试。"}</p>
      <button className="secondary-button" type="button" disabled={logs.isFetching} onClick={() => void logs.refetch()}>重试读取日志</button>
    </Notice> : null}
    {data ? <Panel title="调用记录" description={`共 ${data.totalElements} 条记录`} className="call-log-results"
      actions={logs.isFetching ? <LoadingState compact label="正在更新调用日志" /> : undefined}>
      <p className="ui-muted">耗时按本次调用开始到返回计算；媒体查询可能包含结果读取，不包含任务排队等待。</p>
      {data.items.length === 0 ? <EmptyState icon={<ListMagnifyingGlass size={27} />} title="没有匹配的调用记录"
        description="调整筛选条件，或在完成模型与媒体调用后刷新查看。" /> :
        <table className="call-log-table"><caption className="sr-only">模型与媒体调用审计记录</caption><thead><tr>
          <th scope="col">调用</th><th scope="col">项目</th><th scope="col">调用时间</th><th scope="col">响应时间</th>
          <th scope="col">耗时</th><th scope="col">调用结果</th><th scope="col"><span className="sr-only">详情</span></th>
        </tr></thead><tbody>{data.items.map((log) => <CallLogRow key={log.id} log={log} expanded={expandedId === log.id}
          onToggle={() => setExpandedId(expandedId === log.id ? null : log.id)} />)}</tbody></table>}
      <nav className="call-log-pagination" aria-label="调用日志分页">
        <span className="ui-muted">第 {data.page + 1} 页 / 共 {Math.max(1, data.totalPages)} 页 · 每页 {data.size} 条</span>
        <div className="ui-form-actions"><button className="secondary-button" type="button" disabled={logs.isFetching || data.page === FIRST_PAGE}
          onClick={() => setPage(data.page - 1)}>上一页</button>
          <button className="secondary-button" type="button" disabled={logs.isFetching || data.page + 1 >= data.totalPages}
            onClick={() => setPage(data.page + 1)}>下一页</button></div>
      </nav>
    </Panel> : null}
  </div></PageShell>;
}

function CallLogFilterForm({ filters, onApply }: { filters: CallLogFilters; onApply: (params: URLSearchParams) => void }) {
  const [error, setError] = useState<string | null>(null);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const next = new URLSearchParams();
    for (const field of ["projectId", "kind", "status", "traceId", "from", "to"]) {
      const value = String(form.get(field) ?? "").trim();
      if (value) next.set(field, field === "from" || field === "to" ? new Date(value).toISOString() : value);
    }
    const from = next.get("from");
    const to = next.get("to");
    if (from && to && from > to) {
      setError("结束时间不能早于开始时间。");
      return;
    }
    onApply(next);
  }
  return <form className="ui-form" onSubmit={submit}>
    <div className="call-log-filters">
      <label className="ui-field">项目 ID<input name="projectId" defaultValue={filters.projectId} placeholder="全部项目" /></label>
      <label className="ui-field">调用类型<select name="kind" defaultValue={filters.kind ?? ""}><option value="">全部类型</option>
        {Object.entries(KIND_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label className="ui-field">调用状态<select name="status" defaultValue={filters.status ?? ""}><option value="">全部状态</option>
        {Object.entries(STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label className="ui-field">Trace ID<input name="traceId" defaultValue={filters.traceId} placeholder="按完整 Trace ID 查找" /></label>
      <label className="ui-field">开始时间<input type="datetime-local" name="from" step="1" defaultValue={localDateTime(filters.from)} /></label>
      <label className="ui-field">结束时间<input type="datetime-local" name="to" step="1" defaultValue={localDateTime(filters.to)} /></label>
    </div>
    {error ? <p className="ui-error" role="alert">{error}</p> : null}
    <div className="ui-form-actions"><button className="primary-button" type="submit">筛选日志</button>
      <button className="ghost-button" type="button" onClick={() => onApply(new URLSearchParams())}>清空筛选</button></div>
  </form>;
}

function CallLogRow({ log, expanded, onToggle }: { log: CallLog; expanded: boolean; onToggle: () => void }) {
  const detailsId = useId();
  return <Fragment>
    <tr className={expanded ? "is-expanded" : undefined}>
      <td data-label="调用"><strong>{KIND_LABELS[log.kind]} · {OPERATION_LABELS[log.operation]}</strong>
        <span className="call-log-secondary">{log.model ?? "模型未记录"}</span>
        <div className="call-log-badges">{log.mock ? <StatusBadge>Mock 模拟调用</StatusBadge> : null}
          {log.historical ? <StatusBadge>历史记录</StatusBadge> : null}</div></td>
      <td data-label="项目"><Link className="call-log-project" to={`/projects/${encodeURIComponent(log.projectId)}`}>{log.projectTitle}<ArrowSquareOut size={13} aria-hidden /></Link></td>
      <td data-label="调用时间"><LogTime value={log.startedAt} /></td>
      <td data-label="响应时间"><LogTime value={log.respondedAt} missing={log.status === "RUNNING" ? "等待响应" : "未记录"} /></td>
      <td data-label="耗时">{log.durationMs === null ? "未记录" : `${log.durationMs.toLocaleString()} ms`}</td>
      <td data-label="调用结果"><StatusBadge tone={STATUS_TONES[log.status]}>{STATUS_LABELS[log.status]}</StatusBadge>
        {log.taskStatus ? <span className="call-log-secondary">任务：{TASK_STATUS_LABELS[log.taskStatus]}</span> : null}</td>
      <td className="call-log-toggle"><button className="ghost-button" type="button" aria-expanded={expanded} aria-controls={detailsId}
        aria-label={`${expanded ? "收起" : "查看"}调用详情 ${log.id}`} onClick={onToggle}>详情<CaretDown size={14} aria-hidden /></button></td>
    </tr>
    {expanded ? <tr className="call-log-detail-row"><td colSpan={TABLE_COLUMN_COUNT}>
      <div id={detailsId} className="call-log-details"><CallLogDetails log={log} /></div>
    </td></tr> : null}
  </Fragment>;
}

function CallLogDetails({ log }: { log: CallLog }) {
  return <>
    {log.historical ? <p className="ui-muted">历史记录只保留当时已保存的信息；响应时间、耗时或 Trace ID 缺失时显示“未记录”。</p> : null}
    <dl className="call-log-metadata">
      <Detail label="Trace ID" value={log.traceId} /><Detail label="Provider 请求 ID" value={log.providerRequestId} />
      <Detail label="Provider" value={log.provider} /><Detail label="模型" value={log.model} />
      <Detail label="调用记录 ID" value={log.id} /><Detail label="Run ID" value={log.runId} />
      <Detail label="Task ID" value={log.taskId} /><Detail label="错误码" value={log.errorCode} />
    </dl>
    {log.taskId ? <CallLogTask projectId={log.projectId} taskId={log.taskId} /> : null}
  </>;
}

function CallLogTask({ projectId, taskId }: { projectId: string; taskId: string }) {
  const queryClient = useQueryClient();
  const task = useQuery({ queryKey: ["call-log-task", projectId, taskId], queryFn: () => getTask(projectId, taskId), retry: false });
  const forbidden = task.error instanceof ApiError && task.error.status === FORBIDDEN_STATUS;
  if (task.error instanceof ApiError && task.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  return <section className="call-log-task" aria-label="关联任务">
    <h3>关联任务</h3>
    {task.isPending ? <LoadingState compact label="正在读取关联任务" /> : null}
    {task.isError ? <Notice tone="danger" title={forbidden ? "无权查看关联任务" : "读取关联任务失败"}>
      <p>重新读取当前任务状态后才能处理待核对请求。</p>
      <button className="secondary-button" type="button" disabled={task.isFetching} onClick={() => void task.refetch()}>重试读取任务</button>
    </Notice> : null}
    {task.data && !task.isError ? <>
      <div className="ui-toolbar"><span className="ui-muted">当前状态：{TASK_STATUS_LABELS[task.data.status]}</span>
        <Link className="secondary-button" to={`/projects/${encodeURIComponent(projectId)}`}>前往项目<ArrowSquareOut size={14} aria-hidden /></Link></div>
      {task.data.status === "UNKNOWN" ? <UnknownTaskAttemptPanel projectId={projectId} taskId={taskId} taskVersion={task.data.version}
        planned={Boolean(task.data.planId)} direct={task.data.kind === "IMAGE_GENERATION" || task.data.kind === "VIDEO_GENERATION"}
        cancelRequested={task.data.cancelRequested} onChanged={async () => {
          await Promise.all([
            queryClient.invalidateQueries({ queryKey: ["call-logs"] }),
            queryClient.invalidateQueries({ queryKey: ["call-log-task", projectId, taskId] }),
          ]);
        }} /> : null}
    </> : null}
  </section>;
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return <div><dt>{label}</dt><dd>{value ?? <span className="ui-muted">未记录</span>}</dd></div>;
}

function LogTime({ value, missing = "未记录" }: { value: string | null; missing?: string }) {
  return value ? <time dateTime={value}>{new Date(value).toLocaleString()}</time> : <span className="ui-muted">{missing}</span>;
}

function localDateTime(value?: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Date(date.getTime() - date.getTimezoneOffset() * MILLISECONDS_PER_MINUTE).toISOString().slice(0, LOCAL_DATE_TIME_LENGTH);
}
