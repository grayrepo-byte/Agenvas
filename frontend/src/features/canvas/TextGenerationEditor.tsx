import { ArrowUp, Coins, Cube } from "@/shared/ui/icons";
import { useMutation, useMutationState, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef } from "react";
import {
  ApiError,
  getLlmSettings,
  getSystemDiagnostics,
  listDirectTextTasks,
  runDirectTextGeneration,
  type Artifact,
} from "../../shared/api/client";
import { t, useLocale } from "../../shared/i18n";
import { Button } from "../../shared/ui/primitives/button";
import { Textarea } from "../../shared/ui/primitives/textarea";
import "./MediaDraftEditor.css";
import { type TextGenerationIntent, useCanvasStore } from "./canvasStore";
import { EditorFeedbackRow } from "./EditorFeedbackRow";
import { latestMediaTask, MEDIA_TASK_REFRESH_INTERVAL_MS, occupiesMediaCard } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import "./TextGenerationEditor.css";
import { hasCurrentVersion } from "./versionedArtifact";
import { usePromptContentHeight } from "./usePromptContentHeight";

const MAX_PROMPT_LENGTH = 20_000;
const MIN_PROMPT_ROWS = 4;

/** Lower node-anchored prompt area: invokes the configured model to create a new text version. */
export function TextGenerationEditor({ artifact, canvasItemId }: { artifact: Artifact; canvasItemId: string }) {
  useLocale();
  const queryClient = useQueryClient();
  const id = useId();
  const draftKey = `${artifact.projectId}:${canvasItemId}`;
  const draft = useCanvasStore(state => state.textGenerationDrafts[draftKey]);
  const prompt = draft?.prompt ?? "";
  const setPrompt = useCanvasStore(state => state.setTextGenerationPrompt);
  const setIntent = useCanvasStore(state => state.setTextGenerationIntent);
  const clearIntent = useCanvasStore(state => state.clearTextGenerationIntent);
  const promptRef = useRef<HTMLTextAreaElement>(null);
  usePromptContentHeight(promptRef, MIN_PROMPT_ROWS);
  const observedSuccess = useRef<string | null>(null);
  const settings = useQuery({ queryKey: ["settings", "llm"], queryFn: getLlmSettings, retry: false });
  const diagnostics = useQuery({ queryKey: ["settings", "diagnostics"], queryFn: getSystemDiagnostics, retry: false });
  const tasksKey = ["direct-text-tasks", artifact.projectId, artifact.id] as const;
  const mutationKey = ["direct-text-generation", artifact.projectId, artifact.id] as const;
  const submissions = useMutationState({
    filters: { mutationKey, exact: true },
    select: mutation => ({
      status: mutation.state.status,
      error: mutation.state.error,
      intent: mutation.state.variables,
    }),
  });
  const submitting = submissions.some(submission => submission.status === "pending");
  // A failed submission belongs to its pinned intent, not to a later prompt edit.
  const matchingSubmission = draft?.intent
    ? submissions.findLast(submission => submission.intent === draft.intent)
    : undefined;
  const submissionError = matchingSubmission?.error;
  const directTasks = useQuery({
    queryKey: tasksKey,
    queryFn: () => listDirectTextTasks(artifact.projectId, artifact.id),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS,
  });
  const latestTask = latestMediaTask(directTasks.data);
  const run = useMutation({
    mutationKey,
    mutationFn: (intent: TextGenerationIntent) =>
      runDirectTextGeneration(
        intent.projectId,
        intent.artifactId,
        {
          prompt: intent.prompt,
          expectedArtifactVersion: intent.expectedArtifactVersion,
          expectedCurrentVersionId: intent.expectedCurrentVersionId,
        },
        intent.key,
      ),
    onSuccess: async (_task, intent) => {
      clearIntent(intent.draftKey, intent.key);
      await queryClient.invalidateQueries({ queryKey: ["direct-text-tasks", intent.projectId, intent.artifactId] });
    },
  });

  useEffect(() => {
    if (latestTask?.status !== "SUCCEEDED" || observedSuccess.current === latestTask.id) return;
    observedSuccess.current = latestTask.id;
    void Promise.all([
      queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
      queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
      queryClient.invalidateQueries({ queryKey: ["artifact-versions", artifact.projectId, artifact.id] }),
    ]);
  }, [artifact.id, artifact.projectId, latestTask, queryClient]);

  const occupied = latestTask ? occupiesMediaCard(latestTask) : false;
  const modelAvailable = settings.data?.configured === true || diagnostics.data?.llmMode === "MOCK";
  const canRun =
    hasCurrentVersion(artifact) &&
    prompt.trim().length > 0 &&
    modelAvailable &&
    settings.isSuccess &&
    diagnostics.isSuccess &&
    directTasks.isSuccess &&
    !submitting &&
    !occupied;
  function submit() {
    if (!canRun || queryClient.isMutating({ mutationKey, exact: true }) || !hasCurrentVersion(artifact)) return;
    const intent = useCanvasStore.getState().textGenerationDrafts[draftKey]?.intent ?? {
      key: crypto.randomUUID(),
      projectId: artifact.projectId,
      artifactId: artifact.id,
      draftKey,
      prompt: prompt.trim(),
      expectedArtifactVersion: artifact.version,
      expectedCurrentVersionId: artifact.resourceDefaultVersionId,
    };
    setIntent(draftKey, intent);
    run.mutate(intent);
  }
  const modelLabel = settings.isPending
    ? t("models.loading")
    : settings.error
      ? t("models.loadFailed")
      : settings.data.configured
        ? (settings.data.modelId ?? t("text.generation.configuredModel"))
        : diagnostics.data?.llmMode === "MOCK"
          ? t("text.generation.mock")
          : t("text.generation.modelMissing");
  const taskMessage =
    latestTask?.status === "SUCCEEDED"
      ? latestTask.output?.selected === false
        ? t("text.generation.resultArchived")
        : t("text.generation.resultSelected")
      : latestTask?.status === "UNKNOWN"
        ? t("tasks.status.unknown")
        : latestTask?.status === "BLOCKED"
          ? t("tasks.status.blocked")
          : latestTask?.status === "FAILED"
            ? taskErrorDetail(latestTask.errorCode)
            : latestTask?.status === "CANCELED"
              ? t("text.generation.canceled")
              : occupied
                ? t("text.generation.generating")
                : null;

  return (
    <div className="media-draft-editor text-generation-editor" aria-label={t("text.generation.title")}>
      <div className="media-draft-header">
        <strong className="studio-editor-title">{t("canvas.studio.input")}</strong>
        <span className="media-draft-save-state">{t("text.generation.versioningHint")}</span>
      </div>
      <div className="media-draft-prompt-shell">
        <label className="media-draft-prompt-label" htmlFor={`${id}-prompt`}>
          {t("text.generation.prompt")}
        </label>
        <Textarea
          variant="ghost"
          ref={promptRef}
          id={`${id}-prompt`}
          aria-describedby={`${id}-draft-hint`}
          className="media-draft-prompt"
          rows={MIN_PROMPT_ROWS}
          maxLength={MAX_PROMPT_LENGTH}
          placeholder={t("text.generation.promptPlaceholder")}
          value={prompt}
          onChange={event => {
            run.reset();
            setPrompt(draftKey, event.target.value);
          }}
        />
      </div>
      <p id={`${id}-draft-hint`} className="text-generation-draft-hint">
        {t("text.generation.localDraftHint")}
      </p>
      <div className="media-draft-toolbar">
        <span
          className="media-draft-toolbar-button text-generation-model"
          aria-label={t("text.generation.currentModel")}
        >
          <Cube size={17} />
          <span>{modelLabel}</span>
        </span>
        <span className="media-draft-cost" title={t("text.generation.unknownEstimatedCost")}>
          <Coins size={16} />
          {t("media.pricing.unknown")}
        </span>
        <Button
          size="sm"
          className="media-draft-run"
          type="button"
          disabled={!canRun}
          aria-label={submitting ? t("text.generation.submitting") : t("text.generate")}
          title={occupied ? t("text.generation.activeTask") : t("text.generate")}
          onClick={submit}
        >
          <span>{submitting ? t("text.generation.submitting") : t("text.generate")}</span>
          <ArrowUp size={18} weight="bold" aria-hidden />
        </Button>
      </div>
      <div className="media-draft-feedback">
        {settings.isSuccess && diagnostics.isSuccess && !modelAvailable ? (
          <EditorFeedbackRow
            tone="warning"
            action={
              <>
                <Button asChild variant="ghost" size="xs">
                  <a href="/settings/providers" target="_blank" rel="noopener noreferrer">
                    {t("models.configureText")}
                  </a>
                </Button>
                <Button
                  variant="ghost"
                  size="xs"
                  type="button"
                  disabled={settings.isFetching || diagnostics.isFetching}
                  onClick={() => void Promise.allSettled([settings.refetch(), diagnostics.refetch()])}
                >
                  {t("models.refreshConfiguration")}
                </Button>
              </>
            }
          >
            {t("text.generation.configureModelHint")}
          </EditorFeedbackRow>
        ) : null}
        {settings.error ? (
          <EditorFeedbackRow
            tone="danger"
            action={
              <Button variant="ghost" size="xs" type="button" onClick={() => void settings.refetch()}>
                {t("common.retry")}
              </Button>
            }
          >
            {t("text.generation.modelSettingsUnavailable")}
          </EditorFeedbackRow>
        ) : null}
        {diagnostics.error ? (
          <EditorFeedbackRow
            tone="danger"
            action={
              <Button variant="ghost" size="xs" type="button" onClick={() => void diagnostics.refetch()}>
                {t("common.retry")}
              </Button>
            }
          >
            {t("text.generation.modelModeUnavailable")}
          </EditorFeedbackRow>
        ) : null}
        {directTasks.error ? (
          <EditorFeedbackRow
            tone="danger"
            action={
              <Button variant="ghost" size="xs" type="button" onClick={() => void directTasks.refetch()}>
                {t("common.retry")}
              </Button>
            }
          >
            {t("text.generation.tasksUnavailable")}
          </EditorFeedbackRow>
        ) : null}
        {submitting ? <EditorFeedbackRow tone="loading">{t("text.generation.submitting")}</EditorFeedbackRow> : null}
        {submissionError ? (
          <EditorFeedbackRow tone="danger">
            {submissionError instanceof ApiError ? submissionError.message : t("text.generation.submitFailed")}
          </EditorFeedbackRow>
        ) : null}
        {taskMessage ? (
          <EditorFeedbackRow
            tone={
              latestTask?.status === "FAILED"
                ? "danger"
                : latestTask?.status === "UNKNOWN" || latestTask?.status === "BLOCKED"
                  ? "warning"
                  : latestTask?.status === "SUCCEEDED"
                    ? "success"
                    : occupied
                      ? "loading"
                      : "neutral"
            }
          >
            {taskMessage}
          </EditorFeedbackRow>
        ) : null}
      </div>
    </div>
  );
}
