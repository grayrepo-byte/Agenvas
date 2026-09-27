import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { listRunActions, listRunTasks,
  type AgentRun, type Task } from "../../shared/api/client";
import { AgentChatMessage, AgentChatTaskRow } from "./AgentChatPrimitives";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { BlockedRunNotice } from "./BlockedRunNotice";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { taskErrorDetail } from "./taskErrorMessages";

export const RUN_STATUS_LABELS: Record<AgentRun["status"], string> = {
  QUEUED: "等待开始", RUNNING: "正在处理",
  WAITING_TASKS: "正在执行任务", BLOCKED: "需要处理", CANCEL_REQUESTED: "正在停止后续编排",
  CANCELED: "已停止", FAILED: "任务失败", SUCCEEDED: "任务完成",
};
const TASK_STATUS: Record<Task["status"], "running" | "pending" | "completed" | "failed" | "unknown" | "canceled"> = {
  PENDING: "pending", READY: "pending", RUNNING: "running", SUBMITTING: "running",
  WAITING_PROVIDER: "running", UNKNOWN: "unknown", BLOCKED: "failed", SUCCEEDED: "completed",
  FAILED: "failed", CANCELED: "canceled",
};
const TASK_LABELS: Record<Task["kind"], string> = {
  AGENT_TURN: "AI 回复", TEXT_GENERATION: "生成文字",
  IMAGE_GENERATION: "生成图片", VIDEO_GENERATION: "生成视频",
  ASSET_INGEST: "归档素材",
};
const TOOL_LABELS: Record<string, string> = {
  read_project_summary: "读取项目概况", read_selection: "读取当前选择",
  read_artifacts: "读取产物", read_task_status: "读取任务状态",
  create_text: "创建文字产物", revise_artifact: "修改产物",
  place_artifacts: "放置产物", arrange_items: "排列卡片",
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
  const tasks = useQuery({ queryKey: ["run-history-tasks", projectId, run.id],
    queryFn: () => listRunTasks(projectId, run.id) });
  const actions = useQuery({ queryKey: ["run-actions", projectId, run.id],
    queryFn: () => listRunActions(projectId, run.id) });
  const turns = (tasks.data ?? []).filter((task) => task.kind === "AGENT_TURN" && task.status === "SUCCEEDED");
  const steps = [...new Set([...turns.map(stepIndex), ...(actions.data ?? []).map((action) => action.stepIndex)])].sort((a, b) => a - b);
  const visibleTasks = (tasks.data ?? []).filter((task) => task.kind !== "AGENT_TURN" || task.status !== "SUCCEEDED");
  const unknownTasks = visibleTasks.filter((task) => task.status === "UNKNOWN");

  return <section aria-label="任务对话" className="agent-run-conversation">
    <p className="agent-chat-run-date"><time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString()}</time>{run.conversationTurn ? ` · 第 ${run.conversationTurn} 轮` : ""}</p>
    <AgentChatMessage role="user">{run.instruction}</AgentChatMessage>
    {tasks.isPending || actions.isPending ? <CanvasLoadingState compact label="正在读取任务消息…" /> : null}
    {tasks.error ? <div className="agent-chat-error" role="alert">任务消息读取失败。<button className="node-action" onClick={() => void tasks.refetch()} type="button">重试消息</button></div> : null}
    {actions.error ? <div className="agent-chat-error" role="alert">动作记录读取失败。<button className="node-action" onClick={() => void actions.refetch()} type="button">重试动作</button></div> : null}
    {actions.data?.length ? <p className="agent-chat-eyebrow">以下动作状态记录于提交时；当前进度见任务卡片。</p> : null}
    {steps.map((step) => <Fragment key={step}>
      {turns.filter((task) => stepIndex(task) === step).map((task) => {
        const reply = task.output?.assistantText;
        return typeof reply === "string" && reply.trim() ? <AgentChatMessage key={task.id} role="assistant">{reply}</AgentChatMessage> : null;
      })}
      {(actions.data ?? []).filter((action) => action.stepIndex === step).map((action) =>
        <AgentChatTaskRow key={action.id} label={action.summary} status="completed"
          detail={`${TOOL_LABELS[action.toolName] ?? action.toolName} · ${new Date(action.completedAt).toLocaleString()}`} />)}
    </Fragment>)}
    {visibleTasks.length ? <div className="agent-chat-tasks" aria-label="执行任务">
      {visibleTasks.map((task) => <AgentChatTaskRow key={task.id} label={`${TASK_LABELS[task.kind]} · ${task.stepKey}`}
        status={TASK_STATUS[task.status]} detail={`第 ${task.attemptNo} 次尝试${taskErrorDetail(task.errorCode)}${task.cancelRequested ? " · 已请求停止后续编排" : ""}`} />)}
    </div> : null}
    {active && run.status === "BLOCKED" ? <BlockedRunNotice projectId={projectId} runId={run.id} /> : null}
    {unknownTasks.map((task) => <UnknownTaskRetryPanel key={task.id} projectId={projectId} taskId={task.id}
      taskVersion={task.version} errorCode={task.errorCode} />)}
    <div className="agent-chat-run-status" role="status">
      {active && RUNNING_STATUSES.has(run.status) ? <CanvasLoadingState compact label={RUN_STATUS_LABELS[run.status]} /> : RUN_STATUS_LABELS[run.status]}
      {run.status === "CANCELED" || run.status === "CANCEL_REQUESTED" ? <p>仅停止本系统后续编排；外部任务可能继续执行并产生费用。</p> : null}
    </div>
  </section>;
}
