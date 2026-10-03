import { WarningCircle } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { listRunTasks } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import "./AgentChatPanels.css";
import { taskErrorDetail } from "./taskErrorMessages";

/** Always-visible failure feedback uses durable, safe Task codes rather than model messages. */
export function BlockedRunNotice({ projectId, runId, status = "BLOCKED" }: {
  projectId: string; runId: string; status?: "BLOCKED" | "FAILED";
}) {
  useLocale();
  const tasks = useQuery({
    queryKey: ["run-history-tasks", projectId, runId],
    queryFn: () => listRunTasks(projectId, runId),
  });
  const modelFailure = tasks.data?.filter((task) => task.kind === "AGENT_TURN" &&
    (task.status === "FAILED" || task.status === "BLOCKED") && task.errorCode).at(-1);
  const staleMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_INPUT_STALE");
  const archivedMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_PROJECT_ARCHIVED");
  const explanation = archivedMedia ?
    t("agent.blocked.archivedProjectHint") : staleMedia ?
    t("agent.blocked.staleInputHint") :
    modelFailure ? switchOnFailure(modelFailure.errorCode) : status === "FAILED" ? t("agent.run.failedHint") : t("agent.blocked.unknownTaskHint");

  const noticeTitle = status === "FAILED" ? t("agent.run.failedHint") : t("agent.blocked.retryPolicyHint");

  return <section aria-label={status === "FAILED" ? t("agent.run.taskFailed") : t("agent.blocked.title")} className="agent-chat-panel agent-chat-blocked">
    <div role="alert">
      <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />{noticeTitle}</p>
      {tasks.isPending ? <p>{t("agent.blocked.reasonLoading")}</p> : null}
      {tasks.error ? <p>{t("agent.blocked.reasonUnavailable")}</p> : null}
      {tasks.error ? <Button variant="ghost" className="agent-chat-panel-text-button" onClick={() => void tasks.refetch()} type="button">{t("common.retryRead")}</Button> : null}
      {tasks.data && explanation !== noticeTitle ? <p>{explanation}</p> : null}
      {archivedMedia ? <p className="text-xs">{t("agent.blocked.archivedProjectCode")}</p> : null}
      {staleMedia ? <p className="text-xs">{t("agent.blocked.staleInputCode")}</p> : null}
      {!archivedMedia && !staleMedia && modelFailure?.errorCode ? <p className="text-xs">{t("agent.blocked.diagnostic", { "0": modelFailure.errorCode, "1": taskErrorDetail(modelFailure.errorCode) })}</p> : null}
    </div>
  </section>;
}

/** Stable, allowlisted explanations prevent raw Provider failures from reaching the page. */
function switchOnFailure(code: string | null | undefined): string {
  switch (code) {
    case "LLM_CONFIG_UNAVAILABLE":
      return t("agent.blocked.modelUnavailable");
    case "CREDENTIAL_KEY_VERSION_MISSING":
      return t("agent.blocked.keyVersionMissing");
    case "MODEL_OUTPUT_INVALID":
      return t("agent.blocked.validationFailed");
    case "MODEL_TURN_LIMIT":
      return t("agent.blocked.roundLimit");
    case "AGENT_TURN_FAILED":
      return t("agent.blocked.modelFailed");
    default:
      return t("agent.blocked.unknownTaskHint");
  }
}
