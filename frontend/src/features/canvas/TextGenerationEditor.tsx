import { ArrowUp, Coins, Cube } from "@phosphor-icons/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { ApiError, getLlmSettings, getSystemDiagnostics, listDirectTextTasks, runDirectTextGeneration,
  type Artifact } from "../../shared/api/client";
import { latestMediaTask, occupiesMediaCard, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { taskErrorDetail } from "./taskErrorMessages";
import { hasCurrentVersion } from "./versionedArtifact";
import "./MediaDraftEditor.css";
import "./TextGenerationEditor.css";

const MAX_PROMPT_LENGTH = 20_000;

type Intent = {
  key: string;
  prompt: string;
  expectedArtifactVersion: number;
  expectedCurrentVersionId: string;
};

/** Lower node-anchored prompt area: invokes the configured model to create a new text version. */
export function TextGenerationEditor({ artifact }: { artifact: Artifact }) {
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
      if (!hasCurrentVersion(artifact)) throw new Error("文字卡片还没有可生成的当前版本");
      intent.current ??= { key: crypto.randomUUID(), prompt: prompt.trim(),
        expectedArtifactVersion: artifact.version,
        expectedCurrentVersionId: artifact.currentVersionId };
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
  const modelLabel = settings.isPending ? "加载模型…"
    : settings.error ? "模型配置读取失败"
      : settings.data.configured ? settings.data.modelId ?? "已配置文字模型"
        : diagnostics.data?.llmMode === "MOCK" ? "Mock 文字演示" : "未配置文字模型";
  const taskMessage = latestTask?.status === "SUCCEEDED"
    ? latestTask.output?.selected === false ? "生成完成；卡片内容已变化，结果保存在版本历史中。" : "生成完成，卡片已切换到新版本。"
    : latestTask?.status === "FAILED" ? taskErrorDetail(latestTask.errorCode)
      : latestTask?.status === "CANCELED" ? "本次文字生成已取消。"
        : occupied ? "正在生成文字…" : null;

  return <div className="media-draft-editor text-generation-editor" aria-label="文字生成编辑器">
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      <span className="media-draft-tab-unavailable" aria-disabled="true">Agent</span>
      <span className="media-draft-save-state">生成结果会保存为新版本</span>
    </div>
    <label className="media-draft-prompt-label" htmlFor={`${id}-prompt`}>文字生成提示词</label>
    <textarea id={`${id}-prompt`} className="media-draft-prompt" maxLength={MAX_PROMPT_LENGTH}
      placeholder="描述你希望模型如何改写、补充或创作卡片内容…" value={prompt}
      onChange={(event) => {
        intent.current = null;
        run.reset();
        setPrompt(event.target.value);
      }} />
    <div className="media-draft-toolbar">
      <span className="media-draft-toolbar-button text-generation-model" aria-label="当前文字模型">
        <Cube size={17} /><span>{modelLabel}</span>
      </span>
      <span className="media-draft-cost" title="预计费用未知"><Coins size={16} />费用未知</span>
      <button className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? "正在提交文字生成" : "生成文字"}
        title={occupied ? "此卡片已有文字生成任务" : "生成文字"}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></button>
    </div>
    <div className="media-draft-feedback" aria-live="polite">
      {settings.error ? <p role="alert">无法读取文字模型配置。
        <button className="media-draft-text-action" type="button"
          onClick={() => void settings.refetch()}>重试</button></p> : null}
      {diagnostics.error ? <p role="alert">无法确认文字模型运行模式。
        <button className="media-draft-text-action" type="button"
          onClick={() => void diagnostics.refetch()}>重试</button></p> : null}
      {directTasks.error ? <p role="alert">无法读取文字生成任务。
        <button className="media-draft-text-action" type="button"
          onClick={() => void directTasks.refetch()}>重试</button></p> : null}
      {run.error ? <p role="alert">{run.error instanceof ApiError
        ? run.error.message : "文字生成提交失败；输入已保留，请重试。"}</p> : null}
      {taskMessage ? <p className="media-draft-task-status">{taskMessage}</p> : null}
    </div>
  </div>;
}
