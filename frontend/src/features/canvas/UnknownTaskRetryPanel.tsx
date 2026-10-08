import { WarningCircle } from "@/shared/ui/icons";
import { useMutation,useQueryClient } from "@tanstack/react-query";
import { useRef } from "react";
import { createManualUnknownAttempt,type Task } from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import "./AgentChatPanels.css";
import { taskErrorMessage } from "./taskErrorMessages";
import { EditorFeedbackRow } from "./EditorFeedbackRow";

/** Offers one explicit retry for a task whose external result could not be confirmed. */
export function UnknownTaskRetryPanel({ projectId, taskId, taskVersion, errorCode, taskStatus = "UNKNOWN", onChanged, compact = false }: {
  projectId: string; taskId: string; taskVersion: number;
  errorCode?: Task["errorCode"]; taskStatus?: "UNKNOWN" | "BLOCKED"; onChanged?: () => void | Promise<void>;
  compact?: boolean;
}) {
  useLocale();
  const retryKey = useRef<string | null>(null);
  const queryClient = useQueryClient();
  const reason = taskErrorMessage(errorCode);
  const retry = useMutation({
    mutationFn: () => {
      retryKey.current ??= crypto.randomUUID();
      return createManualUnknownAttempt(projectId, taskId, retryKey.current,
        { expectedTaskVersion: taskVersion });
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
    }>{reason ?? errorCode}</EditorFeedbackRow>
    {retry.data ? <EditorFeedbackRow tone="success">{t("tasks.retry.newTask", { "0": retry.data.id })}</EditorFeedbackRow> : null}
    {retry.error ? <EditorFeedbackRow tone="danger">{t("tasks.retry.retryFailed")}</EditorFeedbackRow> : null}
  </>;

  return <div className="agent-chat-panel agent-chat-unknown">
    <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />{taskStatus === "BLOCKED"
      ? t("media.card.blocked") : t("tasks.status.unknown")}</p>
    {/* 说明为什么未知：超时、断线、结果下载失败与协议不符的重试预期并不相同。 */}
    {reason ? <p>{reason}</p> : errorCode ? <p>{errorCode}</p> : null}
    <Button variant="ghost" className="agent-chat-panel-secondary" disabled={retry.isPending}
      onClick={() => retry.mutate()} type="button">
      {retry.isPending ? t("common.retrying") : t("common.retry")}
    </Button>
    {retry.data ? <p role="status">{t("tasks.retry.newTask", { "0": retry.data.id })}</p> : null}
    {retry.error ? <p className="text-red-700" role="alert">{t("tasks.retry.retryFailed")}</p> : null}
  </div>;
}
