import type { Task } from "../../shared/api/client";
import { getFormatLocale, t, useLocale } from "../../shared/i18n";
import { taskErrorMessage } from "./taskErrorMessages";

/** Durable retry progress stays above the composer when the transcript scrolls away. */
export function AgentModelRetryNotice({ task }: { task: Task }) {
  useLocale();
  const retry = task.output?.modelRetry;
  if (!retry || task.kind !== "AGENT_TURN" || (task.status !== "READY" && task.status !== "RUNNING")) return null;
  const format = (time: string) => new Date(time).toLocaleTimeString(getFormatLocale());
  const reason = retry.lastErrorCode === "LLM_CALL_TIMEOUT" ? t("agent.retry.timeout") : taskErrorMessage(retry.lastErrorCode);
  return <div className="agent-chat-model-retry" role="status">
    <strong>{t(task.status === "READY" ? "agent.retry.waiting" : "agent.retry.running")}
      {" · "}{t("agent.retry.attempt", { "0": retry.retryCount, "1": retry.maxRetries })}</strong>
    <span>{reason}</span>
    {task.status === "READY" ? <span>{t("agent.retry.next", { "0": format(task.nextActionAt) })}</span> : null}
    <span>{t("agent.retry.deadline", { "0": format(retry.deadlineAt) })}</span>
  </div>;
}
