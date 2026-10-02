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
  get QUEUED() { return t("agent.run.pending"); }, get RUNNING() { return t("agent.run.processing"); },
  get WAITING_TASKS() { return t("agent.run.executing"); }, get BLOCKED() { return t("agent.run.needsAttention"); }, get CANCEL_REQUESTED() { return t("agent.run.stopping"); },
  get CANCELED() { return t("agent.run.stopped"); }, get FAILED() { return t("agent.run.taskFailed"); }, get SUCCEEDED() { return t("agent.run.taskSucceeded"); },
};
const TASK_STATUS: Record<Task["status"], "running" | "pending" | "completed" | "failed" | "unknown" | "canceled"> = {
  PENDING: "pending", READY: "pending", RUNNING: "running", SUBMITTING: "running",
  WAITING_PROVIDER: "running", UNKNOWN: "unknown", BLOCKED: "failed", SUCCEEDED: "completed",
  FAILED: "failed", CANCELED: "canceled",
};
const TASK_LABELS: Record<Task["kind"], string> = {
  get AGENT_TURN() { return t("agent.run.assistant"); }, get TEXT_GENERATION() { return t("text.generate"); },
  get IMAGE_GENERATION() { return t("agent.run.generateImage"); }, get AUDIO_GENERATION() { return t("agent.run.generateAudio"); }, get VIDEO_GENERATION() { return t("media.generateVideo"); },
  get ASSET_INGEST() { return t("agent.run.archiveAsset"); },
};
const TOOL_LABELS: Record<string, string> = {
  get read_project_summary() { return t("agent.run.readProject"); }, get read_selection() { return t("agent.run.readSelection"); },
  get read_artifacts() { return t("agent.run.readArtifact"); }, get read_task_status() { return t("agent.run.readTask"); },
  get create_text() { return t("agent.run.createTextArtifact"); }, get revise_artifact() { return t("agent.run.updateArtifact"); },
  get place_artifacts() { return t("agent.run.placeArtifact"); }, get arrange_items() { return t("agent.run.arrangeCards"); },
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

  return <section aria-label={t("agent.run.title")} className="agent-run-conversation">
    <p className="agent-chat-run-date"><time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString(getFormatLocale())}</time>{run.conversationTurn ? t("agent.run.roundSuffix", { "0": run.conversationTurn }) : ""}</p>
    <AgentChatMessage role="user">{run.instruction}</AgentChatMessage>
    {tasks.isPending || actions.isPending ? <CanvasLoadingState compact label={t("agent.run.messagesLoading")} /> : null}
    {tasks.error ? <div className="agent-chat-error" role="alert">{t("agent.run.messagesFailed")}<Button variant="ghost" className="node-action" onClick={() => void tasks.refetch()} type="button">{t("agent.chat.retryMessages")}</Button></div> : null}
    {actions.error ? <div className="agent-chat-error" role="alert">{t("agent.run.actionsFailed")}<Button variant="ghost" className="node-action" onClick={() => void actions.refetch()} type="button">{t("agent.run.retryActions")}</Button></div> : null}
    {actions.data?.length ? <p className="agent-chat-eyebrow">{t("agent.run.actionSnapshotHint")}</p> : null}
    {steps.map((step) => <Fragment key={step}>
      {turns.filter((task) => stepIndex(task) === step).map((task) => {
        const reply = task.output?.assistantText;
        return typeof reply === "string" && reply.trim() ? <AgentChatMessage key={task.id} role="assistant">{reply}</AgentChatMessage> : null;
      })}
      {(actions.data ?? []).filter((action) => action.stepIndex === step).map((action) =>
        <AgentChatTaskRow key={action.id} label={action.summary} status="completed"
          detail={`${TOOL_LABELS[action.toolName] ?? action.toolName} · ${new Date(action.completedAt).toLocaleString(getFormatLocale())}`} />)}
    </Fragment>)}
    {visibleTasks.length ? <div className="agent-chat-tasks" aria-label={t("agent.run.executeTask")}>
      {visibleTasks.map((task) => <AgentChatTaskRow key={task.id} label={`${TASK_LABELS[task.kind]} · ${task.stepKey}`}
        status={TASK_STATUS[task.status]} detail={t("agent.run.attemptSummary", { "0": task.attemptNo, "1": taskErrorDetail(task.errorCode), "2": task.cancelRequested ? t("agent.run.stopRequestedSuffix") : "" })} />)}
    </div> : null}
    {active && run.status === "BLOCKED" ? <BlockedRunNotice projectId={projectId} runId={run.id} /> : null}
    {unknownTasks.map((task) => <UnknownTaskRetryPanel key={task.id} projectId={projectId} taskId={task.id}
      taskVersion={task.version} errorCode={task.errorCode} />)}
    <div className="agent-chat-run-status" role="status">
      {active && RUNNING_STATUSES.has(run.status) ? <CanvasLoadingState compact label={RUN_STATUS_LABELS[run.status]} /> : RUN_STATUS_LABELS[run.status]}
      {run.status === "CANCELED" || run.status === "CANCEL_REQUESTED" ? <p>{t("agent.run.cancelHint")}</p> : null}
    </div>
  </section>;
}
