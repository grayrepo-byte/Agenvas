import { useQuery } from "@tanstack/react-query";
import { Fragment } from "react";
import { listExecutionPlans, listRunActions, listRunTasks,
  type AgentRun, type Task } from "../../shared/api/client";
import { AgentChatMessage, AgentChatTaskRow } from "./AgentChatPrimitives";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { PlanApprovalPanel } from "./PlanApprovalPanel";
import { KeyframeSelectionPanel } from "./KeyframeSelectionPanel";
import { BlockedRunNotice } from "./BlockedRunNotice";
import { UnknownTaskAttemptPanel } from "./UnknownTaskAttemptPanel";

export const RUN_STATUS_LABELS: Record<AgentRun["status"], string> = {
  QUEUED: "等待开始", RUNNING: "正在处理", WAITING_APPROVAL: "等待你的审批",
  WAITING_TASKS: "正在执行计划", BLOCKED: "需要处理", CANCEL_REQUESTED: "正在停止后续编排",
  CANCELED: "已停止", FAILED: "任务失败", SUCCEEDED: "任务完成",
};
const TASK_STATUS: Record<Task["status"], "running" | "pending" | "completed" | "failed" | "unknown" | "canceled"> = {
  PENDING: "pending", READY: "pending", RUNNING: "running", SUBMITTING: "running",
  WAITING_PROVIDER: "running", UNKNOWN: "unknown", BLOCKED: "failed", SUCCEEDED: "completed",
  FAILED: "failed", CANCELED: "canceled",
};
const TASK_LABELS: Record<Task["kind"], string> = {
  AGENT_TURN: "AI 规划", IMAGE_GENERATION: "生成图片", VIDEO_GENERATION: "生成视频",
  MEDIA_EXPORT: "导出视频", ASSET_INGEST: "归档素材",
};
const TOOL_LABELS: Record<string, string> = {
  read_artifact: "读取素材", read_artifact_version: "读取素材版本",
  list_bound_artifacts: "查看绑定素材", create_artifact: "创建产物", revise_artifact: "修改产物",
  propose_media_plan: "提出生成计划", place_artifact: "放置产物",
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
  const plans = useQuery({ queryKey: ["run-history-plans", projectId, run.id],
    queryFn: () => listExecutionPlans(projectId, run.id), enabled: !active });
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
    {actions.data?.length ? <p className="agent-chat-eyebrow">以下动作状态记录于提交时；当前进度见任务与审批卡片。</p> : null}
    {steps.map((step) => <Fragment key={step}>
      {turns.filter((task) => stepIndex(task) === step).map((task) => {
        const reply = task.output?.assistantText;
        return typeof reply === "string" && reply.trim() ? <AgentChatMessage key={task.id} role="assistant">{reply}</AgentChatMessage> : null;
      })}
      {(actions.data ?? []).filter((action) => action.stepIndex === step).map((action) =>
        <AgentChatTaskRow key={action.id} label={action.summary} status={action.status === "WAITING_APPROVAL" ? "pending" : "completed"}
          detail={`${TOOL_LABELS[action.toolName] ?? action.toolName} · ${new Date(action.completedAt).toLocaleString()}`} />)}
    </Fragment>)}
    {visibleTasks.length ? <div className="agent-chat-tasks" aria-label="执行任务">
      {visibleTasks.map((task) => <AgentChatTaskRow key={task.id} label={`${TASK_LABELS[task.kind]} · ${task.stepKey}`}
        status={TASK_STATUS[task.status]} detail={`第 ${task.attemptNo} 次尝试${task.errorCode ? ` · ${task.errorCode}` : ""}${task.cancelRequested ? " · 已请求停止后续编排" : ""}`} />)}
    </div> : null}
    {!active && plans.data?.length ? <details className="agent-chat-plan-history"><summary>审批记录 · {plans.data.length}</summary>
      {plans.data.map((plan) => <p key={plan.id}>{plan.stage === "IMAGE" ? "图片" : "视频"}计划 · 第 {plan.revision} 版 · {plan.status} · {plan.steps.length} 个步骤</p>)}
    </details> : null}
    {!active && plans.error ? <div className="agent-chat-error" role="alert">审批记录读取失败。<button className="node-action" onClick={() => void plans.refetch()} type="button">重试审批记录</button></div> : null}
    {active && run.status === "WAITING_APPROVAL" ? <PlanApprovalPanel projectId={projectId} runId={run.id} /> : null}
    {active && run.status === "WAITING_TASKS" ? <KeyframeSelectionPanel projectId={projectId} runId={run.id} /> : null}
    {active && run.status === "BLOCKED" ? <BlockedRunNotice projectId={projectId} runId={run.id} /> : null}
    {unknownTasks.map((task) => <UnknownTaskAttemptPanel key={task.id} projectId={projectId} taskId={task.id}
      taskVersion={task.version} planned={Boolean(task.planId)} direct={task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION"}
      cancelRequested={task.cancelRequested} />)}
    <div className="agent-chat-run-status" role="status">
      {active && RUNNING_STATUSES.has(run.status) ? <CanvasLoadingState compact label={RUN_STATUS_LABELS[run.status]} /> : RUN_STATUS_LABELS[run.status]}
      {run.status === "CANCELED" || run.status === "CANCEL_REQUESTED" ? <p>仅停止本系统后续编排；外部任务可能继续执行并产生费用。</p> : null}
    </div>
  </section>;
}
