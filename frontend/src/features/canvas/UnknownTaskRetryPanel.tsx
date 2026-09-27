import { useMutation, useQueryClient } from "@tanstack/react-query";
import { WarningCircle } from "@phosphor-icons/react";
import { useRef } from "react";
import { createManualUnknownAttempt, type Task } from "../../shared/api/client";
import { taskErrorMessage } from "./taskErrorMessages";
import "./AgentChatPanels.css";

/** Offers one explicit retry for a task whose external result could not be confirmed. */
export function UnknownTaskRetryPanel({ projectId, taskId, taskVersion, errorCode, onChanged }: {
  projectId: string; taskId: string; taskVersion: number;
  errorCode?: Task["errorCode"]; onChanged?: () => void | Promise<void>;
}) {
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

  return <div className="agent-chat-panel agent-chat-unknown">
    <p className="agent-chat-panel-notice-title"><WarningCircle aria-hidden="true" />结果未知</p>
    {/* 说明为什么未知：超时、断线、结果下载失败与协议不符的重试预期并不相同。 */}
    {reason ? <p>{reason}</p> : errorCode ? <p>{errorCode}</p> : null}
    <button className="agent-chat-panel-secondary" disabled={retry.isPending}
      onClick={() => retry.mutate()} type="button">
      {retry.isPending ? "正在重试…" : "重试"}
    </button>
    {retry.data ? <p role="status">已创建新任务：{retry.data.id}。</p> : null}
    {retry.error ? <p className="text-red-700" role="alert">重试失败。请刷新任务后重试。</p> : null}
  </div>;
}
