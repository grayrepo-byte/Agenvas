import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import {
listRunActions,listRunTasks,
type AgentRun,type Task
} from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { AgentChatMessage,AgentChatTaskRow } from "./AgentChatPrimitives";
import { BlockedRunNotice } from "./BlockedRunNotice";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { taskErrorDetail } from "./taskErrorMessages";

export const RUN_STATUS_LABELS: Record<AgentRun["status"], string> = {
  get QUEUED() { return t("等待开始"); }, get RUNNING() { return t("正在处理"); },
  get WAITING_TASKS() { return t("正在执行任务"); }, get BLOCKED() { return t("需要处理"); }, get CANCEL_REQUESTED() { return t("正在停止后续编排"); },
  get CANCELED() { return t("已停止"); }, get FAILED() { return t("任务失败"); }, get SUCCEEDED() { return t("任务完成"); },
};
const TASK_STATUS: Record<Task["status"], "running" | "pending" | "completed" | "failed" | "unknown" | "canceled"> = {
  PENDING: "pending", READY: "pending", RUNNING: "running", SUBMITTING: "running",
  WAITING_PROVIDER: "running", UNKNOWN: "unknown", BLOCKED: "failed", SUCCEEDED: "completed",
  FAILED: "failed", CANCELED: "canceled",
};
const TASK_LABELS: Record<Task["kind"], string> = {
  get AGENT_TURN() { return t("AI 回复"); }, get TEXT_GENERATION() { return t("生成文字"); },
  get IMAGE_GENERATION() { return t("生成图片"); }, get AUDIO_GENERATION() { return t("音频生成"); }, get VIDEO_GENERATION() { return t("生成视频"); },
  get ASSET_INGEST() { return t("归档素材"); },
};
const TOOL_LABELS: Record<string, string> = {
  get read_project_summary() { return t("读取项目概况"); }, get read_selection() { return t("读取当前选择"); },
  get read_artifacts() { return t("读取产物"); }, get read_task_status() { return t("读取任务状态"); },
  get create_text() { return t("创建文字产物"); }, get revise_artifact() { return t("修改产物"); },
  get place_artifacts() { return t("放置产物"); }, get arrange_items() { return t("排列卡片"); },
};
const RUNNING_STATUSES: ReadonlySet<AgentRun["status"]> = new Set(["QUEUED", "RUNNING", "WAITING_TASKS", "CANCEL_REQUESTED"]);
const DEFAULT_STEP_INDEX = 0;

function stepIndex(task: Task) {
  return typeof task.output?.stepIndex === "number" ? task.output.stepIndex : DEFAULT_STEP_INDEX;
}

/** Render only committed public text and action summaries, never arbitrary Task JSON or model traces. */
export function AgentRunConversation({ projectId, run, active }: {
  projectId: string;
  run: Pick<AgentRun, "id" | "status" | "instruction" | "createdAt"> & Partial<Pick<AgentRun, "conversationTurn">>;
  active: boolean;
}) {
  useLocale();
  const tasks = useQuery({ queryKey: ["run-history-tasks", projectId, run.id],
    queryFn: () => listRunTasks(projectId, run.id) });
  const actions = useQuery({ queryKey: ["run-actions", projectId, run.id],
    queryFn: () => listRunActions(projectId, run.id) });
  const turns = (tasks.data ?? []).filter((task) => task.kind === "AGENT_TURN" && task.status === "SUCCEEDED");
  const steps = [...new Set([...turns.map(stepIndex), ...(actions.data ?? []).map((action) => action.stepIndex)])].sort((a, b) => a - b);
  const visibleTasks = (tasks.data ?? []).filter((task) => task.kind !== "AGENT_TURN" || task.status !== "SUCCEEDED");
  const unknownTasks = visibleTasks.filter((task) => task.status === "UNKNOWN");

  return <section aria-label={t("任务对话")} className="agent-run-conversation">
    <p className="agent-chat-run-date"><time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString(getFormatLocale())}</time>{run.conversationTurn ? t(" · 第 {0} 轮", { "0": run.conversationTurn }) : ""}</p>
    <AgentChatMessage role="user">{run.instruction}</AgentChatMessage>
    {tasks.isPending || actions.isPending ? <CanvasLoadingState compact label={t("正在读取任务消息…")} /> : null}
    {tasks.error ? <div className="agent-chat-error" role="alert">{t("任务消息读取失败。")}<Button variant="ghost" className="node-action" onClick={() => void tasks.refetch()} type="button">{t("重试消息")}</Button></div> : null}
    {actions.error ? <div className="agent-chat-error" role="alert">{t("动作记录读取失败。")}<Button variant="ghost" className="node-action" onClick={() => void actions.refetch()} type="button">{t("重试动作")}</Button></div> : null}
    {actions.data?.length ? <p className="agent-chat-eyebrow">{t("以下动作状态记录于提交时；当前进度见任务卡片。")}</p> : null}
    {steps.map((step) => <Fragment key={step}>
      {turns.filter((task) => stepIndex(task) === step).map((task) => {
        const reply = task.output?.assistantText;
        return typeof reply === "string" && reply.trim() ? <AgentChatMessage key={task.id} role="assistant">{reply}</AgentChatMessage> : null;
      })}
      {(actions.data ?? []).filter((action) => action.stepIndex === step).map((action) =>
        <AgentChatTaskRow key={action.id} label={action.summary} status="completed"
          detail={`${TOOL_LABELS[action.toolName] ?? action.toolName} · ${new Date(action.completedAt).toLocaleString(getFormatLocale())}`} />)}
    </Fragment>)}
    {visibleTasks.length ? <div className="agent-chat-tasks" aria-label={t("执行任务")}>
      {visibleTasks.map((task) => <AgentChatTaskRow key={task.id} label={`${TASK_LABELS[task.kind]} · ${task.stepKey}`}
        status={TASK_STATUS[task.status]} detail={t("第 {0} 次尝试{1}{2}", { "0": task.attemptNo, "1": taskErrorDetail(task.errorCode), "2": task.cancelRequested ? t(" · 已请求停止后续编排") : "" })} />)}
    </div> : null}
    {active && run.status === "BLOCKED" ? <BlockedRunNotice projectId={projectId} runId={run.id} /> : null}
    {unknownTasks.map((task) => <UnknownTaskRetryPanel key={task.id} projectId={projectId} taskId={task.id}
      taskVersion={task.version} errorCode={task.errorCode} />)}
    <div className="agent-chat-run-status" role="status">
      {active && RUNNING_STATUSES.has(run.status) ? <CanvasLoadingState compact label={RUN_STATUS_LABELS[run.status]} /> : RUN_STATUS_LABELS[run.status]}
      {run.status === "CANCELED" || run.status === "CANCEL_REQUESTED" ? <p>{t("仅停止本系统后续编排；外部任务可能继续执行并产生费用。")}</p> : null}
    </div>
  </section>;
}
