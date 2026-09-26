import { CaretDown, Check, Clock, MinusCircle, Question, Robot, ShieldCheck,
  User, WarningCircle, type Icon } from "@phosphor-icons/react";
import { useId, type ReactNode } from "react";
import { CanvasLoadingState } from "./CanvasLoadingState";
import "./AgentChatPrimitives.css";

/**
 * Chat, task and approval layouts adapted from Beautiful UI (MIT).
 * https://github.com/slev12397/beautiful-ui/tree/main/components/primitives
 * Copyright (c) 2026 Shane Levine. See beautiful-ui-LICENSE.txt.
 * These primitives render supplied messages and persisted task states only.
 */
const CHAT_ICON_SIZE = 14;
const TASK_ICON_SIZE = 15;
const APPROVAL_ICON_SIZE = 18;
const MESSAGE_ROLE_LABELS = { user: "你", assistant: "Agent" } as const;
type TaskStatus = "running" | "pending" | "completed" | "failed" | "unknown" | "canceled";
const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  running: "运行中", pending: "等待中", completed: "已完成", failed: "失败",
  unknown: "待核对", canceled: "已取消",
};
const TASK_STATUS_ICONS: Record<Exclude<TaskStatus, "running">, Icon> = {
  pending: Clock, completed: Check, failed: WarningCircle, unknown: Question, canceled: MinusCircle,
};

export function AgentChatMessage({ role, children, label }: {
  role: "user" | "assistant";
  children: ReactNode;
  label?: string;
}) {
  const labelId = useId();
  const RoleIcon = role === "user" ? User : Robot;
  return <article className={`agent-chat-message agent-chat-message--${role}`} aria-labelledby={labelId}>
    <div className="agent-chat-message__identity" id={labelId}>
      <RoleIcon size={CHAT_ICON_SIZE} aria-hidden="true" />
      <span>{label ?? MESSAGE_ROLE_LABELS[role]}</span>
    </div>
    <div className="agent-chat-message__body">{children}</div>
  </article>;
}

function TaskHeadline({ label, status }: { label: string; status: TaskStatus }) {
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
export function AgentChatTaskRow({ label, status, detail }: {
  label: string;
  status: TaskStatus;
  detail?: string;
}) {
  return <div className={`agent-chat-task agent-chat-task--${status}`}>
    {detail ? <details className="agent-chat-task__disclosure nodrag nowheel">
      <summary className="agent-chat-task__headline">
        <TaskHeadline label={label} status={status} />
        <CaretDown className="agent-chat-task__caret" size={CHAT_ICON_SIZE} aria-hidden="true" />
      </summary>
      <div className="agent-chat-task__detail">{detail}</div>
    </details> : <div className="agent-chat-task__headline">
      <TaskHeadline label={label} status={status} />
    </div>}
  </div>;
}

/** The caller owns every approval action and supplies its complete review content. */
export function AgentChatApproval({ title, description, children, className }: {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}) {
  const titleId = useId();
  const descriptionId = useId();
  return <section className={`agent-chat-approval nodrag nowheel${className ? ` ${className}` : ""}`}
    aria-labelledby={titleId} aria-describedby={description ? descriptionId : undefined}>
    <div className="agent-chat-approval__heading">
      <span className="agent-chat-approval__icon" aria-hidden="true"><ShieldCheck size={APPROVAL_ICON_SIZE} /></span>
      <div className="agent-chat-approval__heading-copy">
        <h4 id={titleId}>{title}</h4>
        {description ? <p id={descriptionId}>{description}</p> : null}
      </div>
    </div>
    <div className="agent-chat-approval__content">{children}</div>
  </section>;
}
