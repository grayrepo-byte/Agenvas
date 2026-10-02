import { WarningCircle } from "@phosphor-icons/react";
import { useQuery } from "@tanstack/react-query";
import { listRunTasks } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import "./AgentChatPanels.css";
import { taskErrorDetail } from "./taskErrorMessages";

/** Explains a durable BLOCKED Run using only safe Task codes, never model messages. */
export function BlockedRunNotice({ projectId, runId }: {
  projectId: string; runId: string;
}) {
  useLocale();
  const tasks = useQuery({
    queryKey: ["run-tasks", projectId, runId],
    queryFn: () => listRunTasks(projectId, runId),
  });
  const modelFailure = tasks.data?.filter((task) => task.kind === "AGENT_TURN" &&
    task.status === "FAILED" && task.errorCode).at(-1);
  const staleMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_INPUT_STALE");
  const archivedMedia = tasks.data?.find((task) =>
    (task.kind === "IMAGE_GENERATION" || task.kind === "VIDEO_GENERATION") &&
    task.status === "BLOCKED" && task.errorCode === "TASK_PROJECT_ARCHIVED");
  const explanation = archivedMedia ?
    t("agent.blocked.archivedProjectHint") : staleMedia ?
    t("agent.blocked.staleInputHint") :
    switchOnFailure(modelFailure?.errorCode);

  return <section aria-label={t("agent.blocked.title")} className="agent-chat-panel agent-chat-blocked">
    <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />{t("agent.blocked.retryPolicyHint")}</p>
    {tasks.isPending ? <p>{t("agent.blocked.reasonLoading")}</p> : null}
    {tasks.error ? <p role="alert">{t("agent.blocked.reasonUnavailable")}</p> : null}
    {tasks.error ? <Button variant="ghost" className="agent-chat-panel-text-button" onClick={() => void tasks.refetch()} type="button">{t("common.retryRead")}</Button> : null}
    {tasks.data ? <p>{explanation}</p> : null}
    {archivedMedia ? <p className="text-xs">{t("agent.blocked.archivedProjectCode")}</p> : null}
    {staleMedia ? <p className="text-xs">{t("agent.blocked.staleInputCode")}</p> : null}
    {!archivedMedia && !staleMedia && modelFailure?.errorCode ? <p className="text-xs">{t("agent.blocked.diagnostic", { "0": modelFailure.errorCode, "1": taskErrorDetail(modelFailure.errorCode) })}</p> : null}
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
