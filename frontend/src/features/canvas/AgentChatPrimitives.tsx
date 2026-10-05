import { t, useLocale } from "../../shared/i18n";
import { CaretDown, Check, Clock, MinusCircle, Question, Robot, Sparkle, Wrench,
  User, WarningCircle, type Icon } from "@phosphor-icons/react";
import { useId, useState, type ReactNode } from "react";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import "./AgentChatPrimitives.css";

/**
 * Chat, task and approval layouts adapted from Beautiful UI (MIT).
 * https://github.com/slev12397/beautiful-ui/tree/main/components/primitives
 * Copyright (c) 2026 Shane Levine. See beautiful-ui-LICENSE.txt.
 * These primitives render supplied messages and persisted task states only.
 */
const CHAT_ICON_SIZE = 14;
const TASK_ICON_SIZE = 15;
const MESSAGE_ROLE_LABELS = { get user() { return t("agent.status.user"); }, assistant: "Agent" } as const;
type TaskStatus = "running" | "pending" | "completed" | "failed" | "unknown" | "canceled";
const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  get running() { return t("tasks.status.running"); }, get pending() { return t("agent.status.waiting"); }, get completed() { return t("common.succeeded"); }, get failed() { return t("common.failed"); },
  get unknown() { return t("common.unknown"); }, get canceled() { return t("common.canceled"); },
};
const TASK_STATUS_ICONS: Record<Exclude<TaskStatus, "running">, Icon> = {
  pending: Clock, completed: Check, failed: WarningCircle, unknown: Question, canceled: MinusCircle,
};

export function AgentChatMessage({ role, children, label, streaming = false }: {
  role: "user" | "assistant";
  children: ReactNode;
  label?: string;
  streaming?: boolean;
}) {
  useLocale();
  const labelId = useId();
  const RoleIcon = role === "user" ? User : Robot;
  return <article className={`agent-chat-message agent-chat-message--${role}${streaming ? " agent-chat-message--streaming" : ""}`} aria-labelledby={labelId} aria-busy={streaming}>
    <div className="agent-chat-message__identity">
      <RoleIcon size={CHAT_ICON_SIZE} aria-hidden="true" />
      <span id={labelId}>{label ?? MESSAGE_ROLE_LABELS[role]}</span>
      {streaming ? <span className="agent-chat-stream-label" role="status">{t("agent.trace.streaming")}</span> : null}
    </div>
    <div className="agent-chat-message__body">{children}</div>
  </article>;
}

function TaskHeadline({ label, status }: { label: string; status: TaskStatus }) {
  useLocale();
  const StatusIcon = status === "running" ? undefined : TASK_STATUS_ICONS[status];
  return <>
    {status === "running" ? <div className="agent-chat-task__running">
      <CanvasLoadingState label={label} compact />
    </div> : <>
      <span className="agent-chat-task__icon" aria-hidden="true">
        {StatusIcon ? <StatusIcon size={TASK_ICON_SIZE} weight="bold" /> : null}
      </span>
      <span className="agent-chat-task__label">{label}</span>
    </>}
    <span className="agent-chat-task__status">{TASK_STATUS_LABELS[status]}</span>
  </>;
}

/** Detail is a verifiable action/result supplied by the caller, never a model reasoning trace. */
export function AgentChatTaskRow({ label, status, detail, toolLabel }: {
  label: string;
  status: TaskStatus;
  detail?: string;
  toolLabel?: string;
}) {
  useLocale();
  return <div className={`agent-chat-task agent-chat-task--${status}`}>
    {detail ? <details className="agent-chat-task__disclosure nodrag nowheel">
      <summary className="agent-chat-task__headline">
        <TaskHeadline label={label} status={status} />
        {toolLabel ? <span className="agent-chat-tool-chip"><Wrench size={CHAT_ICON_SIZE} aria-hidden="true" />{toolLabel}</span> : null}
        <CaretDown className="agent-chat-task__caret" size={CHAT_ICON_SIZE} aria-hidden="true" />
      </summary>
      <div className="agent-chat-task__detail">{detail}</div>
    </details> : <div className="agent-chat-task__headline">
      <TaskHeadline label={label} status={status} />
    </div>}
  </div>;
}

/** The trace describes public work and persisted outcomes, never hidden reasoning. */
export function AgentExecutionTrace({ title, count, active, children }: {
  title: string;
  count: number;
  active: boolean;
  children: ReactNode;
}) {
  useLocale();
  const [expanded, setExpanded] = useState(active);
  return <details className="agent-execution-trace nodrag nowheel" open={expanded}
    onToggle={(event) => setExpanded(event.currentTarget.open)}>
    <summary className="agent-execution-trace__summary">
      <Sparkle size={CHAT_ICON_SIZE} aria-hidden="true" />
      <span>{title}</span>
      <small>{t("agent.trace.operationCount", { "0": count })}</small>
      <CaretDown size={CHAT_ICON_SIZE} className="agent-execution-trace__caret" aria-hidden="true" />
    </summary>
    <div className="agent-execution-trace__steps">{children}</div>
  </details>;
}

/** The caller owns every approval action and supplies its complete review content. */
export function AgentChatApproval({ title, description, children, footer, className }: {
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
}) {
  useLocale();
  const titleId = useId();
  const descriptionId = useId();
  return <section className={`agent-chat-approval nodrag nowheel${className ? ` ${className}` : ""}`}
    aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}>
    <div className="agent-chat-approval__heading">
      <div className="agent-chat-approval__heading-copy">
        <h4 id={titleId}>{title}</h4>
        {description ? <p id={descriptionId}>{description}</p> : null}
      </div>
    </div>
    <div className="agent-chat-approval__content">{children}</div>
    {footer ? <footer className="agent-chat-approval__footer">{footer}</footer> : null}
  </section>;
}
