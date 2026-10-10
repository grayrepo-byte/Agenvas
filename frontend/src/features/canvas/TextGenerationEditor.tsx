import { ArrowUp,Coins,Cube } from "@/shared/ui/icons";
import { useMutation,useQuery,useQueryClient } from "@tanstack/react-query";
import { useEffect,useId,useRef,useState } from "react";
import {
ApiError,getLlmSettings,getSystemDiagnostics,listDirectTextTasks,runDirectTextGeneration,
type Artifact
} from "../../shared/api/client";
import { t,useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Textarea } from "../../shared/ui/primitives/textarea";
import "./MediaDraftEditor.css";
import { EditorFeedbackRow } from "./EditorFeedbackRow";
import { latestMediaTask,MEDIA_TASK_REFRESH_INTERVAL_MS,occupiesMediaCard } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import "./TextGenerationEditor.css";
import { hasCurrentVersion } from "./versionedArtifact";

const MAX_PROMPT_LENGTH = 20_000;

type Intent = {
  key: string;
  prompt: string;
  expectedArtifactVersion: number;
  expectedCurrentVersionId: string;
};

/** Lower node-anchored prompt area: invokes the configured model to create a new text version. */
export function TextGenerationEditor({ artifact }: { artifact: Artifact }) {
  useLocale();
  const queryClient = useQueryClient();
  const id = useId();
  const [prompt, setPrompt] = useState("");
  const intent = useRef<Intent | null>(null);
  const observedSuccess = useRef<string | null>(null);
  const settings = useQuery({ queryKey: ["settings", "llm"], queryFn: getLlmSettings,
    retry: false });
  const diagnostics = useQuery({ queryKey: ["settings", "diagnostics"],
    queryFn: getSystemDiagnostics, retry: false });
  const tasksKey = ["direct-text-tasks", artifact.projectId, artifact.id] as const;
  const directTasks = useQuery({ queryKey: tasksKey,
    queryFn: () => listDirectTextTasks(artifact.projectId, artifact.id),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS });
  const latestTask = latestMediaTask(directTasks.data);
  const run = useMutation({
    mutationFn: () => {
      if (!hasCurrentVersion(artifact)) throw new Error(t("text.generation.versionMissing"));
      intent.current ??= { key: crypto.randomUUID(), prompt: prompt.trim(),
        expectedArtifactVersion: artifact.version,
        expectedCurrentVersionId: artifact.resourceDefaultVersionId };
      return runDirectTextGeneration(artifact.projectId, artifact.id, {
        prompt: intent.current.prompt,
        expectedArtifactVersion: intent.current.expectedArtifactVersion,
        expectedCurrentVersionId: intent.current.expectedCurrentVersionId,
      }, intent.current.key);
    },
    onSuccess: async () => {
      intent.current = null;
      await queryClient.invalidateQueries({ queryKey: tasksKey });
    },
  });

  useEffect(() => {
    if (latestTask?.status !== "SUCCEEDED" || observedSuccess.current === latestTask.id) return;
    observedSuccess.current = latestTask.id;
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
      queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
      queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId,
        artifact.id] }),
    ]);
  }, [artifact.id, artifact.projectId, latestTask, queryClient]);

  const occupied = latestTask ? occupiesMediaCard(latestTask) : false;
  const modelAvailable = settings.data?.configured === true
    || diagnostics.data?.llmMode === "MOCK";
  const canRun = hasCurrentVersion(artifact) && prompt.trim().length > 0 && modelAvailable
    && settings.isSuccess && diagnostics.isSuccess && directTasks.isSuccess
    && !run.isPending && !occupied;
  const modelLabel = settings.isPending ? t("models.loading")
    : settings.error ? t("models.loadFailed")
      : settings.data.configured ? settings.data.modelId ?? t("text.generation.configuredModel")
        : diagnostics.data?.llmMode === "MOCK" ? t("text.generation.mock") : t("text.generation.modelMissing");
  const taskMessage = latestTask?.status === "SUCCEEDED"
    ? latestTask.output?.selected === false ? t("text.generation.resultArchived") : t("text.generation.resultSelected")
    : latestTask?.status === "UNKNOWN" ? t("tasks.status.unknown")
      : latestTask?.status === "BLOCKED" ? t("tasks.status.blocked")
      : latestTask?.status === "FAILED" ? taskErrorDetail(latestTask.errorCode)
      : latestTask?.status === "CANCELED" ? t("text.generation.canceled")
        : occupied ? t("text.generation.generating") : null;

  return <div className="media-draft-editor text-generation-editor" aria-label={t("text.generation.title")}>
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      <span className="media-draft-tab-unavailable" aria-disabled="true">Agent</span>
      <span className="media-draft-save-state">{t("text.generation.versioningHint")}</span>
    </div>
    <div className="media-draft-prompt-shell">
      <label className="media-draft-prompt-label" htmlFor={`${id}-prompt`}>{t("text.generation.prompt")}</label>
      <Textarea id={`${id}-prompt`} className="media-draft-prompt" maxLength={MAX_PROMPT_LENGTH}
        placeholder={t("text.generation.promptPlaceholder")} value={prompt}
        onChange={(event) => {
          intent.current = null;
          run.reset();
          setPrompt(event.target.value);
        }} />
    </div>
    <div className="media-draft-toolbar">
      <span className="media-draft-toolbar-button text-generation-model" aria-label={t("text.generation.currentModel")}>
        <Cube size={17} /><span>{modelLabel}</span>
      </span>
      <span className="media-draft-cost" title={t("text.generation.unknownEstimatedCost")}><Coins size={16} />{t("media.pricing.unknown")}</span>
      <Button variant="ghost" className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? t("text.generation.submitting") : t("text.generate")}
        title={occupied ? t("text.generation.activeTask") : t("text.generate")}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></Button>
    </div>
    <div className="media-draft-feedback">
      {settings.error ? <EditorFeedbackRow tone="danger" action={<Button variant="ghost" size="xs" type="button"
          onClick={() => void settings.refetch()}>{t("common.retry")}</Button>}>{t("text.generation.modelSettingsUnavailable")}</EditorFeedbackRow> : null}
      {diagnostics.error ? <EditorFeedbackRow tone="danger" action={<Button variant="ghost" size="xs" type="button"
          onClick={() => void diagnostics.refetch()}>{t("common.retry")}</Button>}>{t("text.generation.modelModeUnavailable")}</EditorFeedbackRow> : null}
      {directTasks.error ? <EditorFeedbackRow tone="danger" action={<Button variant="ghost" size="xs" type="button"
          onClick={() => void directTasks.refetch()}>{t("common.retry")}</Button>}>{t("text.generation.tasksUnavailable")}</EditorFeedbackRow> : null}
      {run.error ? <EditorFeedbackRow tone="danger">{run.error instanceof ApiError
        ? run.error.message : t("text.generation.submitFailed")}</EditorFeedbackRow> : null}
      {taskMessage ? <EditorFeedbackRow tone={latestTask?.status === "FAILED" ? "danger"
        : latestTask?.status === "UNKNOWN" || latestTask?.status === "BLOCKED" ? "warning"
          : latestTask?.status === "SUCCEEDED" ? "success" : occupied ? "loading" : "neutral"}>{taskMessage}</EditorFeedbackRow> : null}
    </div>
  </div>;
}
