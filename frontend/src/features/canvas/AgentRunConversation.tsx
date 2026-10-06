import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { getRun, listRunActions, listRunMediaApprovals, listRunTasks,
  type AgentMediaApproval, type AgentRun, type Task } from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { AgentChatMessage, AgentChatTaskRow, AgentExecutionTrace } from "./AgentChatPrimitives";
import { AgentMediaApprovalCard } from "./AgentMediaApprovalCard";
import { AgentMarkdown } from "./AgentMarkdown";
import { useRunAssistantStream } from "./agentRunStream";
import { BlockedRunNotice } from "./BlockedRunNotice";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { CreativeSkillSource } from "../skills/CreativeSkillSource";
import { taskErrorMessage } from "./taskErrorMessages";

export const RUN_STATUS_LABELS: Record<AgentRun["status"], string> = {
  get QUEUED() { return t("agent.run.pending"); }, get RUNNING() { return t("agent.run.processing"); },
  get WAITING_TASKS() { return t("agent.run.executing"); }, get BLOCKED() { return t("agent.run.needsAttention"); }, get CANCEL_REQUESTED() { return t("agent.run.stopping"); },
  get CANCELED() { return t("agent.run.stopped"); }, get FAILED() { return t("agent.run.taskFailed"); }, get SUCCEEDED() { return t("agent.run.taskSucceeded"); },
};
const TASK_STATUS: Record<Task["status"], "running" | "pending" | "completed" | "failed" | "unknown" | "canceled"> = {
  READY: "pending", RUNNING: "running", SUBMITTING: "running",
  WAITING_PROVIDER: "running", UNKNOWN: "unknown", BLOCKED: "failed", SUCCEEDED: "completed",
  FAILED: "failed", CANCELED: "canceled",
};
const TASK_LABELS: Record<Task["kind"], string> = {
  get AGENT_TURN() { return t("agent.run.modelCall"); }, get TEXT_GENERATION() { return t("text.generate"); },
  get IMAGE_GENERATION() { return t("agent.run.generateImage"); }, get AUDIO_GENERATION() { return t("agent.run.generateAudio"); }, get VIDEO_GENERATION() { return t("media.generateVideo"); },
};
const TOOL_LABELS: Record<string, string> = {
  get read_skill() { return t("skills.body"); }, get read_skill_resource() { return t("skills.resources"); }, get read_skill_asset() { return t("skills.readAsset"); }, get read_project_summary() { return t("agent.run.readProject"); }, get read_selection() { return t("agent.run.readSelection"); },
  get read_artifacts() { return t("agent.run.readArtifact"); }, get read_task_status() { return t("agent.run.readTask"); },
  get create_text() { return t("agent.run.createTextArtifact"); }, get revise_artifact() { return t("agent.run.updateArtifact"); },
  get place_artifacts() { return t("agent.run.placeArtifact"); }, get arrange_items() { return t("agent.run.arrangeCards"); },
  get list_media_capabilities() { return t("agent.trace.listCapabilities"); },
  get propose_media_generation() { return t("agent.trace.proposeMedia"); },
};
const RUNNING_STATUSES: ReadonlySet<AgentRun["status"]> = new Set(["QUEUED", "RUNNING", "WAITING_TASKS", "CANCEL_REQUESTED"]);
const DEFAULT_STEP_INDEX = 0;
const FIRST_ATTEMPT_NO = 1;
const ATTENTION_TASK_STATUSES: ReadonlySet<Task["status"]> = new Set(["FAILED", "BLOCKED", "UNKNOWN", "CANCELED"]);

function taskDetail(task: Task): string {
  return [
    task.attemptNo > FIRST_ATTEMPT_NO ? t("agent.run.attempt", { "0": task.attemptNo }) : "",
    taskErrorMessage(task.errorCode) ?? task.errorCode,
    task.cancelRequested ? t("agent.run.stopRequested") : "",
  ].filter(Boolean).join(" · ");
}

function stepIndex(task: Task) {
  if (typeof task.output?.stepIndex === "number") return task.output.stepIndex;
  return typeof task.input.stepIndex === "number" ? task.input.stepIndex : DEFAULT_STEP_INDEX;
}

/** Render persisted public stream text, committed replies, and verifiable actions. */
export function AgentRunConversation({ projectId, run, active, showFailureNotice = true }: {
  projectId: string;
  run: Pick<AgentRun, "id" | "status" | "instruction" | "createdAt"> & Partial<Pick<AgentRun, "conversationTurn" | "contextSnapshot">>;
  active: boolean;
  showFailureNotice?: boolean;
}) {
  useLocale();
  const [sourceOpen,setSourceOpen] = useState(false);
  const sourceRun = useQuery({queryKey:["run",projectId,run.id],queryFn:()=>getRun(projectId,run.id),enabled:sourceOpen && !run.contextSnapshot});
  const skillSource = run.contextSnapshot?.creativeSkill ?? sourceRun.data?.contextSnapshot.creativeSkill;
  const tasks = useQuery({ queryKey: ["run-history-tasks", projectId, run.id],
    queryFn: () => listRunTasks(projectId, run.id) });
  const actions = useQuery({ queryKey: ["run-actions", projectId, run.id],
    queryFn: () => listRunActions(projectId, run.id) });
  const approvals = useQuery({ queryKey: ["run-media-approvals", projectId, run.id],
    queryFn: () => listRunMediaApprovals(projectId, run.id) });
  const streams = useRunAssistantStream(projectId, run.id, tasks.data);
  const turns = (tasks.data ?? []).filter((task) => task.kind === "AGENT_TURN" && task.status === "SUCCEEDED");
  const visibleTasks = tasks.data ?? [];
  // Normal model rounds are already represented by public replies and the Run status.
  // Keep exceptional/retried rounds visible without counting every model call as work.
  const traceTasks = visibleTasks.filter((task) => task.kind !== "AGENT_TURN"
    || ATTENTION_TASK_STATUSES.has(task.status) || task.cancelRequested || task.attemptNo > FIRST_ATTEMPT_NO);
  const approvedTaskIds = new Set((approvals.data ?? []).flatMap((approval) => approval.taskIds));
  const unknownTasks = visibleTasks.filter((task) => task.status === "UNKNOWN"
    && typeof task.input.agentApprovalId !== "string" && !approvedTaskIds.has(task.id));
  const replies = new Map<string, { step: number; time: string; text: string; status: "STREAMING" | "COMPLETED" | "INTERRUPTED" }>();
  for (const task of turns) {
    const reply = task.output?.assistantText;
    if (typeof reply === "string" && reply.trim()) replies.set(task.id, { step: stepIndex(task),
      time: task.createdAt, text: reply, status: "COMPLETED" });
  }
  for (const stream of streams) {
    if (!turns.some((task) => task.id === stream.taskId) && stream.text.trim()) {
      replies.set(stream.taskId, { step: stream.stepIndex,
        time: visibleTasks.find((task) => task.id === stream.taskId)?.createdAt ?? run.createdAt,
        text: stream.text, status: stream.status });
    }
  }
  const traceCount = (actions.data?.length ?? 0) + traceTasks.length;
  const waitingApproval = approvals.data?.some((approval) => approval.status === "PENDING");
  const traceTitle = active && RUNNING_STATUSES.has(run.status)
    ? waitingApproval ? t("agent.trace.waitingApproval") : RUN_STATUS_LABELS[run.status]
    : run.status === "FAILED" || run.status === "BLOCKED" ? RUN_STATUS_LABELS[run.status] : t("agent.trace.completedWork");
  const steps = [...new Set([...(actions.data ?? []).map((action) => action.stepIndex), ...traceTasks.map(stepIndex)])]
    .sort((left, right) => left - right);
  // Model tasks are created before their proposals; completion happens after tools
  // execute. Creation times keep replies/approvals in place across stream completion
  // and later decisions, rather than moving the proposing reply below its approval.
  type Entry = { kind: "reply"; id: string; time: string; reply: NonNullable<ReturnType<typeof replies.get>> }
    | { kind: "approval"; id: string; time: string; approval: AgentMediaApproval };
  const entries: Entry[] = [
    ...[...replies.entries()].sort((left, right) => left[1].step - right[1].step)
      .map(([id, reply]): Entry => ({ kind: "reply", id, time: reply.time, reply })),
    ...(approvals.data ?? []).map((approval): Entry => ({ kind: "approval", id: approval.id,
      time: approval.createdAt, approval })),
  ];
  entries.sort((left, right) => Date.parse(left.time) - Date.parse(right.time));

  return <section aria-label={t("agent.run.title")} className="agent-run-conversation">
    <p className="agent-chat-run-date"><time dateTime={run.createdAt}>{new Date(run.createdAt).toLocaleString(getFormatLocale())}</time>{run.conversationTurn ? t("agent.run.roundSuffix", { "0": run.conversationTurn }) : ""}</p>
    <AgentChatMessage role="user">{run.instruction}</AgentChatMessage>
    {skillSource ? <CreativeSkillSource source={skillSource} /> : !run.contextSnapshot ? <details onToggle={(event)=>setSourceOpen(event.currentTarget.open)}><summary>{t("skills.source")}</summary>{sourceRun.isFetching ? <CanvasLoadingState compact label={t("common.loading")} /> : sourceRun.isError ? <p role="alert">{sourceRun.error.message}<Button onClick={()=>void sourceRun.refetch()}>{t("common.retry")}</Button></p> : sourceRun.data ? <p>{t("skills.none")}</p> : null}</details> : null}
    {tasks.isPending || actions.isPending ? <CanvasLoadingState compact label={t("agent.run.messagesLoading")} /> : null}
    {tasks.error ? <div className="agent-chat-error" role="alert">{t("agent.run.messagesFailed")}<Button variant="ghost" className="node-action" onClick={() => void tasks.refetch()} type="button">{t("agent.chat.retryMessages")}</Button></div> : null}
    {actions.error ? <div className="agent-chat-error" role="alert">{t("agent.run.actionsFailed")}<Button variant="ghost" className="node-action" onClick={() => void actions.refetch()} type="button">{t("agent.run.retryActions")}</Button></div> : null}
    {traceCount ? <AgentExecutionTrace title={traceTitle} count={traceCount} active={active}>
      {steps.map((step) => <div className="agent-execution-trace__round" key={step}>
        <p className="agent-execution-trace__round-label">{t("agent.trace.round", { "0": step + 1 })}</p>
        {(actions.data ?? []).filter((action) => action.stepIndex === step).map((action) =>
          <AgentChatTaskRow key={action.id} label={action.summary} status="completed"
            toolLabel={TOOL_LABELS[action.toolName] ?? action.toolName}
            detail={`${TOOL_LABELS[action.toolName] ?? action.toolName} · ${new Date(action.completedAt).toLocaleString(getFormatLocale())}`} />)}
        {traceTasks.filter((task) => stepIndex(task) === step).map((task) =>
          <AgentChatTaskRow key={task.id} label={TASK_LABELS[task.kind]}
            status={TASK_STATUS[task.status]} detail={taskDetail(task)} />)}
      </div>)}
    </AgentExecutionTrace> : null}
    {approvals.error ? <div className="agent-chat-error" role="alert">{t("agent.approval.loadFailed")}
      <Button variant="ghost" size="sm" type="button" onClick={() => void approvals.refetch()}>{t("common.retry")}</Button></div> : null}
    {entries.map((entry) => entry.kind === "approval"
      ? <div key={`approval:${entry.id}`} data-media-approval-id={entry.id} tabIndex={-1}>
        <AgentMediaApprovalCard projectId={projectId} runId={run.id} approval={entry.approval}
          disabled={!active || run.status !== "WAITING_TASKS"} />
      </div>
      : <AgentChatMessage key={`reply:${entry.id}`} role="assistant" streaming={entry.reply.status === "STREAMING"}>
        <AgentMarkdown text={entry.reply.text} />
        {entry.reply.status === "INTERRUPTED" ? <p className="agent-chat-stream-notice">{t("agent.trace.interrupted")}</p> : null}
      </AgentChatMessage>)}
    {showFailureNotice && (run.status === "BLOCKED" || run.status === "FAILED") ? <BlockedRunNotice projectId={projectId} runId={run.id} status={run.status} /> : null}
    {unknownTasks.map((task) => <UnknownTaskRetryPanel key={task.id} projectId={projectId} taskId={task.id}
      taskVersion={task.version} errorCode={task.errorCode} />)}
    <div className="agent-chat-run-status" role="status">
      {active && waitingApproval && run.status === "WAITING_TASKS" ? t("agent.trace.waitingApproval")
        : active && RUNNING_STATUSES.has(run.status) ? <CanvasLoadingState compact label={RUN_STATUS_LABELS[run.status]} /> : RUN_STATUS_LABELS[run.status]}
      {run.status === "CANCELED" || run.status === "CANCEL_REQUESTED" ? <p>{t("agent.run.cancelHint")}</p> : null}
    </div>
  </section>;
}
