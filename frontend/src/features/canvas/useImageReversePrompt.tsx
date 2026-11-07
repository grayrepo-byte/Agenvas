import { MagicWand } from "@/shared/ui/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { ApiError, cancelImagePrompt, listImagePromptTasks, runImagePrompt,
  type ImagePromptRequest, type Task } from "../../shared/api/client";
import { t } from "../../shared/i18n";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { isMediaTaskRunning, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { acknowledgePromptTask } from "./promptTaskHistory";
import { taskErrorMessage } from "./taskErrorMessages";

/** Keeps accepted work alive when the toolbar hides; an uncertain receipt replays its pinned command. */
export function useImageReversePrompt({ projectId, artifactId, canvasItemId, request, enabled }: {
  projectId: string; artifactId: string; canvasItemId: string;
  request: ImagePromptRequest | null; enabled: boolean;
}) {
  const client = useQueryClient();
  const queryKey = ["image-prompt-tasks", projectId, canvasItemId] as const;
  const command = useRef<{ key: string; request: ImagePromptRequest } | null>(null);
  const [watching, setWatching] = useState(false);
  const history = useQuery({ queryKey, queryFn: () => listImagePromptTasks(projectId, artifactId, canvasItemId),
    enabled: enabled || watching, retry: false,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning) ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const latest = history.data?.find((task) => task.input.sourceVersionId === request?.sourceVersionId);
  const active = history.data?.find(isMediaTaskRunning);
  function updateTask(task: Task) { return acknowledgePromptTask(client, queryKey, task); }
  const run = useMutation({ mutationFn: () => {
    if (!command.current) {
      if (!request) throw new Error(t("image.reversePrompt.inputUnavailable"));
      command.current = { key: crypto.randomUUID(), request };
    }
    return runImagePrompt(projectId, artifactId, command.current.request, command.current.key);
  }, onSuccess: (task) => { command.current = null; return updateTask(task); },
  onError: (error) => {
    if (error instanceof ApiError && error.status < 500 && error.status !== 408) command.current = null;
    if (error instanceof ApiError && error.code === "IMAGE_PROMPT_IN_PROGRESS") void history.refetch();
  } });
  const cancel = useMutation({ mutationFn: (task: Task) => cancelImagePrompt(projectId, artifactId, task.id),
    onSuccess: updateTask, onSettled: () => void history.refetch() });
  const completedId = latest?.status === "SUCCEEDED" ? latest.id : undefined;
  useEffect(() => {
    if (!completedId) return;
    // Polling and SSE use the same server projection; never create a card from a browser receipt.
    void client.invalidateQueries({ queryKey: ["canvas", projectId] });
    void client.invalidateQueries({ queryKey: ["canvas-connections", projectId] });
  }, [client, projectId, completedId]);
  const uncertain = run.isError && (!(run.error instanceof ApiError) || run.error.status >= 500 || run.error.status === 408);
  const error = run.error ?? cancel.error ?? history.error;
  const busy = Boolean(active) || run.isPending || cancel.isPending;
  const failed = latest?.status === "FAILED" || latest?.status === "BLOCKED" || latest?.status === "UNKNOWN";
  const label = run.isPending ? t("image.reversePrompt.submitting") : active
    ? t(active.status === "READY" ? "tasks.status.queued" : "image.reversePrompt.running")
    : uncertain ? t("common.retry") : t("image.reversePrompt.title");
  const controls = <>
    <Button variant="ghost" type="button" title={t("image.reversePrompt.hint")}
      disabled={!request || busy || history.isPending || history.isError}
      onClick={() => { if (!busy) { setWatching(true); cancel.reset(); run.mutate(); } }}>
      <MagicWand data-icon="inline-start" />{label}</Button>
    {active ? <Button variant="ghost" type="button" disabled={cancel.isPending}
      onClick={() => cancel.mutate(active)}>{t("common.cancel")}</Button> : null}
  </>;
  const hasFeedback = error || failed || latest?.status === "CANCELED"
    || (active && active.input.sourceVersionId !== request?.sourceVersionId);
  const feedback = hasFeedback ? <div className="absolute inset-x-0 bottom-0 z-3 max-h-full overflow-y-auto nodrag nopan"
    onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}>
    {active && active.input.sourceVersionId !== request?.sourceVersionId ? <Alert role="status"><AlertDescription>{t("image.reversePrompt.otherSource")}</AlertDescription></Alert> : null}
    {latest?.status === "CANCELED" ? <Alert role="status"><AlertDescription>{t("image.reversePrompt.canceled")}</AlertDescription></Alert> : null}
    {error ? <Alert variant="destructive" className="nodrag nopan" onClick={(event) => event.stopPropagation()}>
      <AlertDescription>{uncertain ? t("image.reversePrompt.uncertain") : error.message}
        {history.isError ? <Button variant="ghost" type="button" disabled={history.isFetching}
          onClick={() => void history.refetch()}>{t("common.retry")}</Button> : null}
      </AlertDescription></Alert> : null}
    {failed ? <Alert variant="destructive"><AlertDescription>
      {taskErrorMessage(latest.errorCode) || t("image.reversePrompt.failed")}
    </AlertDescription></Alert> : null}
  </div> : null;
  return { controls, feedback };
}
