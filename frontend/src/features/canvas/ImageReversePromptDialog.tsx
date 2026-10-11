import { CopySimple, MagicWand } from "@/shared/ui/icons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useRef, useState } from "react";
import { ApiError, assetContentUrl, cancelImagePrompt, listImagePromptTasks, runImagePrompt,
  type ImagePromptRequest, type Task } from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Alert, AlertDescription } from "../../shared/ui/primitives/alert";
import { Button } from "../../shared/ui/primitives/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "../../shared/ui/primitives/dialog";
import { Field, FieldGroup, FieldLabel } from "../../shared/ui/primitives/field";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { isMediaTaskRunning, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { taskErrorMessage } from "./taskErrorMessages";

type Props = {
  projectId: string; artifactId: string; title: string; assetId: string;
  request: ImagePromptRequest; open: boolean; onClose: () => void;
};

/** The selected image is pinned by the caller; a lost acceptance response retries the original command. */
export function ImageReversePromptDialog({ projectId, artifactId, title, assetId, request, open, onClose }: Props) {
  useLocale();
  const id = useId();
  const client = useQueryClient();
  const queryKey = ["image-prompt-tasks", projectId, request.canvasItemId] as const;
  const command = useRef<{ key: string; request: ImagePromptRequest } | null>(null);
  const [edited, setEdited] = useState<{ taskId: string; value: string } | null>(null);
  const history = useQuery({ queryKey, queryFn: () => listImagePromptTasks(projectId, artifactId, request.canvasItemId),
    enabled: open, retry: false,
    refetchInterval: (query) => query.state.data?.some(isMediaTaskRunning) ? MEDIA_TASK_REFRESH_INTERVAL_MS : false });
  const latest = history.data?.find((task) => task.input.sourceVersionId === request.sourceVersionId);
  const active = history.data?.find(isMediaTaskRunning);
  function updateTask(task: Task) {
    client.setQueryData<Task[]>(queryKey, (old) => [task, ...(old ?? []).filter((entry) => entry.id !== task.id)]);
  }
  const run = useMutation({ mutationFn: () => {
    command.current ??= { key: crypto.randomUUID(), request };
    return runImagePrompt(projectId, artifactId, command.current.request, command.current.key);
  }, onSuccess: (task) => { command.current = null; updateTask(task); },
  onError: (error) => {
    if (error instanceof ApiError && error.status < 500 && error.status !== 408) command.current = null;
    if (error instanceof ApiError && error.code === "IMAGE_PROMPT_IN_PROGRESS") void history.refetch();
  } });
  const cancel = useMutation({ mutationFn: (task: Task) => cancelImagePrompt(projectId, artifactId, task.id),
    onSuccess: updateTask, onSettled: () => void history.refetch() });
  const output = latest?.status === "SUCCEEDED" ? latest.output : undefined;
  const generated = typeof output?.prompt === "string" ? output.prompt : "";
  const value = latest && edited?.taskId === latest.id ? edited.value : generated;
  const copy = useMutation({ mutationFn: () => navigator.clipboard.writeText(value) });
  const error = run.error ?? cancel.error ?? history.error;
  const uncertain = run.isError && (!(run.error instanceof ApiError) || run.error.status >= 500 || run.error.status === 408);
  const busy = Boolean(active) || run.isPending || cancel.isPending;
  const sourceChanged = active && active.input.sourceVersionId !== request.sourceVersionId;
  const failed = latest?.status === "FAILED" || latest?.status === "BLOCKED" || latest?.status === "UNKNOWN";

  return <Dialog open={open} onOpenChange={(next) => { if (!next) onClose(); }}>
    <DialogContent className="nodrag nowheel nopan max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-2xl"
      showCloseButton={false} onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()}
      onKeyDown={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}>
      <DialogHeader>
        <DialogTitle>{t("image.reversePrompt.title")}</DialogTitle>
        <DialogDescription>{t("image.reversePrompt.hint")}</DialogDescription>
      </DialogHeader>
      <img className="max-h-48 w-full rounded-md object-contain" src={assetContentUrl(projectId, assetId)} alt={title} />
      {history.isPending ? <LoadingState compact label={t("image.reversePrompt.loading")} /> : null}
      {active ? <LoadingState compact label={active.status === "READY" ? t("tasks.status.queued") : t("image.reversePrompt.running")} /> : null}
      {sourceChanged ? <p>{t("image.reversePrompt.otherSource")}</p> : null}
      {latest?.status === "CANCELED" ? <p role="status">{t("image.reversePrompt.canceled")}</p> : null}
      {error ? <Alert variant="destructive"><AlertDescription>
        {uncertain ? t("image.reversePrompt.uncertain") : error.message}
        {history.isError ? <Button variant="outline" onClick={() => void history.refetch()}>{t("common.retry")}</Button> : null}
      </AlertDescription></Alert> : null}
      {failed ? <Alert variant="destructive"><AlertDescription>
        {taskErrorMessage(latest.errorCode) || t("image.reversePrompt.failed")}
      </AlertDescription></Alert> : null}
      {generated ? <>
        {output?.mock === true ? <p role="status">{t("image.reversePrompt.mock")}</p> : null}
        <FieldGroup><Field><FieldLabel htmlFor={id}>{t("image.reversePrompt.result")}</FieldLabel>
          <Textarea id={id} rows={8} className="max-h-72 resize-y" value={value} maxLength={20000}
            onChange={(event) => { if (latest) setEdited({ taskId: latest.id, value: event.target.value }); copy.reset(); }} />
        </Field></FieldGroup>
      </> : null}
      {copy.isError ? <Alert variant="destructive"><AlertDescription>{t("image.reversePrompt.copyFailed")}</AlertDescription></Alert> : null}
      <DialogFooter>
        <Button variant="outline" onClick={onClose}>{t("common.close")}</Button>
        {active ? <Button variant="outline" disabled={cancel.isPending} onClick={() => cancel.mutate(active)}>{t("common.cancel")}</Button> : null}
        {generated ? <Button variant="outline" disabled={!value.trim() || copy.isPending} onClick={() => copy.mutate()}>
          <CopySimple data-icon="inline-start" />{copy.isSuccess ? t("image.reversePrompt.copied") : t("image.reversePrompt.copy")}
        </Button> : null}
        <Button disabled={busy || history.isPending || history.isError} onClick={() => { copy.reset(); cancel.reset(); run.mutate(); }}>
          <MagicWand data-icon="inline-start" />{run.isPending ? t("image.reversePrompt.submitting") : uncertain ? t("common.retry")
            : latest ? t("image.reversePrompt.again") : t("image.reversePrompt.start")}
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>;
}
