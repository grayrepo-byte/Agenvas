import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { Select } from "../../shared/ui/Select";
import { useQuery } from "@tanstack/react-query";
import { ArrowSquareOut, ArrowsClockwise, CaretDown, ListMagnifyingGlass } from "@phosphor-icons/react";
import { Fragment, useId, useState, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, useSearchParams } from "react-router";
import { ApiError, getCurrentUser, getTask, listCallLogs, type CallLog, type CallLogFilters, type Task } from "../../shared/api/client";
import { LoadingState } from "../../shared/ui/LoadingState";
import { EmptyState, Notice, Panel, StatusBadge } from "../../shared/ui/PagePrimitives";
import { PageShell } from "../../shared/ui/PageShell";
import { CallDebugDetails } from "./CallDebugDetails";
import "./CallLogsPage.css";

const PAGE_SIZE = 20;
const FIRST_PAGE = 0;
const BAD_REQUEST_STATUS = 400;
const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;
const MILLISECONDS_PER_MINUTE = 60_000;
const LOCAL_DATE_TIME_LENGTH = 19;
const TABLE_COLUMN_COUNT = 7;
const KIND_LABELS: Record<CallLog["kind"], string> = { get LLM() { return t("文本模型"); }, get IMAGE() { return t("图片"); }, get VIDEO() { return t("视频"); }, get AUDIO() { return t("音频"); } };
const STATUS_LABELS: Record<CallLog["status"], string> = { get RUNNING() { return t("调用中"); }, get SUCCEEDED() { return t("成功"); }, get FAILED() { return t("失败"); }, get UNKNOWN() { return t("未知"); } };
const OPERATION_LABELS: Record<CallLog["operation"], string> = { get CHAT() { return t("模型对话"); }, get SUBMIT() { return t("提交生成"); }, get POLL() { return t("查询结果"); }, get LEGACY() { return t("历史任务"); } };
const STATUS_TONES = { RUNNING: "neutral", SUCCEEDED: "success", FAILED: "danger", UNKNOWN: "warning" } as const;
const TASK_STATUS_LABELS: Record<Task["status"], string> = {
  get PENDING() { return t("等待依赖"); }, get READY() { return t("排队中"); }, get SUBMITTING() { return t("提交中"); }, get RUNNING() { return t("运行中"); }, get WAITING_PROVIDER() { return t("等待外部结果"); },
  get SUCCEEDED() { return t("已完成"); }, get FAILED() { return t("失败"); }, get UNKNOWN() { return t("未知"); }, get BLOCKED() { return t("已阻断"); }, get CANCELED() { return t("已取消"); },
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

  return <PageShell title={t("调用日志")} description={t("查看模型与媒体调用的时间、结果和关联记录。本页只读，刷新和查看都不会发起生成或改变任务状态。")} actions={
    <button className="secondary-button" type="button" disabled={logs.isFetching || !currentUser.isSuccess}
      onClick={() => void logs.refetch()}><ArrowsClockwise size={16} aria-hidden />{logs.isFetching ? t("正在刷新…") : t("刷新日志")}</button>
  }><div className="ui-stack">
    <Panel title={t("筛选记录")} description={t("时间按当前设备时区显示；筛选按调用开始时间匹配。未知包含调用结果未记录与关联任务尚未核实的记录。")}>
      <CallLogFilterForm key={params.toString()} filters={filters} onApply={setParams} />
    </Panel>
    {logs.isPending && currentUser.isSuccess ? <LoadingState label={t("正在读取调用日志")} /> : null}
    {logs.isError ? <Notice tone="danger" title={forbidden ? t("无权查看调用日志") : invalidFilters ? t("筛选条件无效") : t("读取调用日志失败")}>
      <p>{forbidden ? t("当前账户没有查看这些记录的权限。") : invalidFilters ? t("请检查项目 ID、完整 Trace ID 和时间范围后重新筛选。") : data ? t("下面保留上次读取的记录，尚未更新。请重试刷新。") : t("服务暂时不可用，请重试。")}</p>
      <button className="secondary-button" type="button" disabled={logs.isFetching} onClick={() => void logs.refetch()}>{t("重试读取日志")}</button>
    </Notice> : null}
    {data ? <Panel title={t("调用记录")} description={t("共 {0} 条记录", { "0": data.totalElements })} className="call-log-results"
      actions={logs.isFetching ? <LoadingState compact label={t("正在更新调用日志")} /> : undefined}>
      <p className="ui-muted">{t("耗时按本次调用开始到返回计算；媒体查询可能包含结果读取，不包含任务排队等待。")}</p>
      {data.items.length === 0 ? <EmptyState icon={<ListMagnifyingGlass size={27} />} title={t("没有匹配的调用记录")}
        description={t("调整筛选条件，或在完成模型与媒体调用后刷新查看。")} /> :
        <table className="call-log-table"><caption className="sr-only">{t("模型与媒体调用审计记录")}</caption><thead><tr>
          <th scope="col">{t("调用")}</th><th scope="col">{t("项目")}</th><th scope="col">{t("调用时间")}</th><th scope="col">{t("响应时间")}</th>
          <th scope="col">{t("耗时")}</th><th scope="col">{t("调用结果")}</th><th scope="col"><span className="sr-only">{t("详情")}</span></th>
        </tr></thead><tbody>{data.items.map((log) => <CallLogRow key={log.id} log={log} expanded={expandedId === log.id}
          onToggle={() => setExpandedId(expandedId === log.id ? null : log.id)} />)}</tbody></table>}
      <nav className="call-log-pagination" aria-label={t("调用日志分页")}>
        <span className="ui-muted">{t("第 {0} 页 / 共 {1} 页 · 每页 {2} 条", { "0": data.page + 1, "1": Math.max(1, data.totalPages), "2": data.size })}</span>
        <div className="ui-form-actions"><button className="secondary-button" type="button" disabled={logs.isFetching || data.page === FIRST_PAGE}
          onClick={() => setPage(data.page - 1)}>{t("上一页")}</button>
          <button className="secondary-button" type="button" disabled={logs.isFetching || data.page + 1 >= data.totalPages}
            onClick={() => setPage(data.page + 1)}>{t("下一页")}</button></div>
      </nav>
    </Panel> : null}
  </div></PageShell>;
}

function CallLogFilterForm({ filters, onApply }: { filters: CallLogFilters; onApply: (params: URLSearchParams) => void }) {
  useLocale();
  const [error, setError] = useState<string | null>(null);
  const filterCount = [filters.projectId, filters.kind, filters.status, filters.traceId, filters.from, filters.to].filter(Boolean).length;
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
      setError(t("结束时间不能早于开始时间。"));
      return;
    }
    onApply(next);
  }
  return <form className="ui-form" onSubmit={submit}>
    <div className="call-log-filters">
      <label className="ui-field">{t("项目 ID")}<input name="projectId" defaultValue={filters.projectId} placeholder={t("全部项目")} /></label>
      <label className="ui-field">{t("调用类型")}<Select name="kind" defaultValue={filters.kind ?? ""}><option value="">{t("全部类型")}</option>
        {Object.entries(KIND_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></label>
      <label className="ui-field">{t("调用状态")}<Select name="status" defaultValue={filters.status ?? ""}><option value="">{t("全部状态")}</option>
        {Object.entries(STATUS_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select></label>
      <label className="ui-field">Trace ID<input name="traceId" defaultValue={filters.traceId} placeholder={t("按完整 Trace ID 查找")} /></label>
      <label className="ui-field">{t("开始时间")}<input type="datetime-local" name="from" step="1" defaultValue={localDateTime(filters.from)} /></label>
      <label className="ui-field">{t("结束时间")}<input type="datetime-local" name="to" step="1" defaultValue={localDateTime(filters.to)} /></label>
    </div>
    {error ? <p className="ui-error" role="alert">{error}</p> : null}
    <div className="ui-toolbar"><span className="ui-muted" role="status">{filterCount ? t("已应用 {0} 项筛选", { "0": filterCount }) : t("当前显示全部记录")}</span>
    <div className="ui-form-actions"><button className="primary-button" type="submit">{t("筛选日志")}</button>
      <button className="ghost-button" type="button" onClick={() => onApply(new URLSearchParams())}>{t("清空筛选")}</button></div></div>
  </form>;
}

function CallLogRow({ log, expanded, onToggle }: { log: CallLog; expanded: boolean; onToggle: () => void }) {
  useLocale();
  const detailsId = useId();
  return <Fragment>
    <tr className={expanded ? "is-expanded" : undefined}>
      <td data-label={t("调用")}><strong>{KIND_LABELS[log.kind]} · {OPERATION_LABELS[log.operation]}</strong>
        <span className="call-log-secondary">{log.model ?? t("模型未记录")}</span>
        <div className="call-log-badges">{log.mock ? <StatusBadge>{t("Mock 模拟调用")}</StatusBadge> : null}
          {log.historical ? <StatusBadge>{t("历史记录")}</StatusBadge> : null}</div></td>
      <td data-label={t("项目")}><Link className="call-log-project" to={`/projects/${encodeURIComponent(log.projectId)}`}>{log.projectTitle}<ArrowSquareOut size={13} aria-hidden /></Link></td>
      <td data-label={t("调用时间")}><LogTime value={log.startedAt} /></td>
      <td data-label={t("响应时间")}><LogTime value={log.respondedAt} missing={log.status === "RUNNING" ? t("等待响应") : t("未记录")} /></td>
      <td data-label={t("耗时")}>{log.durationMs === null ? t("未记录") : `${log.durationMs.toLocaleString(getFormatLocale())} ms`}</td>
      <td data-label={t("调用结果")}><StatusBadge tone={STATUS_TONES[log.status]}>{STATUS_LABELS[log.status]}</StatusBadge>
        {log.taskStatus ? <span className="call-log-secondary">{t("任务：{0}", { "0": TASK_STATUS_LABELS[log.taskStatus] })}</span> : null}</td>
      <td className="call-log-toggle"><button className="ghost-button" type="button" aria-expanded={expanded} aria-controls={detailsId}
        aria-label={t("{0}调用详情 {1}", { "0": expanded ? t("收起") : t("查看"), "1": log.id })} onClick={onToggle}>{t("详情")}<CaretDown size={14} aria-hidden /></button></td>
    </tr>
    {expanded ? <tr className="call-log-detail-row"><td colSpan={TABLE_COLUMN_COUNT}>
      <div id={detailsId} className="call-log-details"><CallLogDetails log={log} /></div>
    </td></tr> : null}
  </Fragment>;
}

function CallLogDetails({ log }: { log: CallLog }) {
  useLocale();
  return <>
    {log.historical ? <p className="ui-muted">{t("历史记录只保留当时已保存的信息；响应时间、耗时或 Trace ID 缺失时显示“未记录”。")}</p> : null}
    <dl className="call-log-metadata">
      <Detail label="Trace ID" value={log.traceId} /><Detail label={t("Provider 请求 ID")} value={log.providerRequestId} />
      <Detail label="Provider" value={log.provider} /><Detail label={t("模型")} value={log.model} />
      <Detail label={t("调用记录 ID")} value={log.id} /><Detail label="Run ID" value={log.runId} />
      <Detail label="Task ID" value={log.taskId} /><Detail label={t("错误码")} value={log.errorCode} />
    </dl>
    {!log.historical ? <CallDebugDetails id={log.id} /> : null}
    {log.taskId ? <CallLogTask projectId={log.projectId} taskId={log.taskId} /> : null}
  </>;
}

/** Reads the related task for context only; this page never performs recovery. */
function CallLogTask({ projectId, taskId }: { projectId: string; taskId: string }) {
  useLocale();
  const task = useQuery({ queryKey: ["call-log-task", projectId, taskId], queryFn: () => getTask(projectId, taskId), retry: false });
  const forbidden = task.error instanceof ApiError && task.error.status === FORBIDDEN_STATUS;
  if (task.error instanceof ApiError && task.error.status === UNAUTHORIZED_STATUS) return <Navigate to="/login" replace />;
  return <section className="call-log-task" aria-label={t("关联任务")}>
    <h3>{t("关联任务")}</h3>
    {task.isPending ? <LoadingState compact label={t("正在读取关联任务")} /> : null}
    {task.isError ? <Notice tone="danger" title={forbidden ? t("无权查看关联任务") : t("读取关联任务失败")}>
      <p>{t("无法读取关联任务的当前状态。需要重试时，请在所属 Agent 对话或媒体卡片上处理。")}</p>
      <button className="secondary-button" type="button" disabled={task.isFetching} onClick={() => void task.refetch()}>{t("重试读取任务")}</button>
    </Notice> : null}
    {task.data && !task.isError ? <div className="ui-toolbar">
      <span className="ui-muted">{t("当前状态：{0}", { "0": TASK_STATUS_LABELS[task.data.status] })}</span>
      <Link className="secondary-button" to={`/projects/${encodeURIComponent(projectId)}`}>{t("前往项目")}<ArrowSquareOut size={14} aria-hidden /></Link>
    </div> : null}
  </section>;
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  useLocale();
  return <div><dt>{label}</dt><dd>{value ?? <span className="ui-muted">{t("未记录")}</span>}</dd></div>;
}

function LogTime({ value, missing = t("未记录") }: { value: string | null; missing?: string }) {
  useLocale();
  return value ? <time dateTime={value}>{new Date(value).toLocaleString(getFormatLocale())}</time> : <span className="ui-muted">{missing}</span>;
}

function localDateTime(value?: string): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Date(date.getTime() - date.getTimezoneOffset() * MILLISECONDS_PER_MINUTE).toISOString().slice(0, LOCAL_DATE_TIME_LENGTH);
}
