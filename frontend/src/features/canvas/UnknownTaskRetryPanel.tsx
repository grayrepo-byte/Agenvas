import { WarningCircle } from "@/shared/ui/icons";
import { useMutation,useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { ApiError,createManualUnknownAttempt,type ManualUnknownAttemptRequest,type Task } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import "./AgentChatPanels.css";
import { taskErrorDetail, type ProviderFailureDiagnostic } from "./taskErrorMessages";
import { EditorFeedbackRow } from "./EditorFeedbackRow";

type Props = {
  projectId: string; taskId: string; taskVersion: number;
  errorCode?: Task["errorCode"]; taskStatus?: "UNKNOWN" | "BLOCKED"; onChanged?: () => void | Promise<void>;
  compact?: boolean; providerFailure?: ProviderFailureDiagnostic | null;
};
const REQUEST_TIMEOUT_STATUS = 408;

/** A card can replace one unknown task with another without unmounting its retry slot. */
export function UnknownTaskRetryPanel(props: Props) {
  return <TaskRetryAttempt key={JSON.stringify([props.projectId, props.taskId])} {...props} />;
}

/** Offers one explicit retry for a task whose external result could not be confirmed. */
function TaskRetryAttempt({ projectId, taskId, taskVersion, errorCode, taskStatus = "UNKNOWN", providerFailure, onChanged, compact = false }: Props) {
  useLocale();
  const command = useRef<{ key: string; request: ManualUnknownAttemptRequest } | null>(null);
  const queryClient = useQueryClient();
  const reason = taskErrorDetail(errorCode, providerFailure).replace(/^ · /, "");
  const retry = useMutation({
    mutationFn: async () => {
      // Keep both key and payload when acknowledgement is lost, even if SSE advances the task.
      command.current ??= { key: crypto.randomUUID(), request: { expectedTaskVersion: taskVersion } };
      try {
        return await createManualUnknownAttempt(projectId, taskId, command.current.key, command.current.request);
      } catch (error) {
        // Only a rejected creation releases the command; a later refresh can fail after acceptance.
        if (error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== REQUEST_TIMEOUT_STATUS) command.current = null;
        throw error;
      }
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["snapshot", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["run-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["run-history-tasks", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["project-usage", projectId] }),
        queryClient.invalidateQueries({ queryKey: ["direct-media-tasks", projectId] }),
        onChanged?.(),
      ]);
    },
  });

  if (compact) return <>
    <EditorFeedbackRow tone="warning" title={taskStatus === "BLOCKED" ? t("media.card.blocked") : t("tasks.status.unknown")} action={
      <Button variant="ghost" size="xs" disabled={retry.isPending}
        onClick={() => retry.mutate()} type="button">
        {retry.isPending ? t("common.retrying") : t("common.retry")}
      </Button>
    }>{reason}</EditorFeedbackRow>
    {retry.data ? <EditorFeedbackRow tone="success">{t("tasks.retry.newTask", { "0": retry.data.id })}</EditorFeedbackRow> : null}
    {retry.error ? <EditorFeedbackRow tone="danger">{t("tasks.retry.retryFailed")}</EditorFeedbackRow> : null}
  </>;

  return <div className="agent-chat-panel agent-chat-unknown">
    <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />{taskStatus === "BLOCKED"
      ? t("media.card.blocked") : t("tasks.status.unknown")}</p>
    {/* 说明为什么未知：超时、断线、结果下载失败与协议不符的重试预期并不相同。 */}
    {reason ? <p>{reason}</p> : errorCode ? <p>{errorCode}</p> : null}
    <Button variant="outline" size="sm" className="agent-chat-panel-retry" disabled={retry.isPending}
      onClick={() => retry.mutate()} type="button">
      {retry.isPending ? t("common.retrying") : t("common.retry")}
    </Button>
    {retry.data ? <p role="status">{t("tasks.retry.newTask", { "0": retry.data.id })}</p> : null}
    {retry.error ? <p className="text-red-700" role="alert">{t("tasks.retry.retryFailed")}</p> : null}
  </div>;
}
