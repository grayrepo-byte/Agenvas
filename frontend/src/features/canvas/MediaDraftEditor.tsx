import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { UnknownTaskAttemptPanel } from "./UnknownTaskAttemptPanel";
import { ApiError, cancelQueuedDirectMediaTask, getDirectMediaQueueStatus,
  getMediaDraft, getMediaSettings,
  listArtifactVersions, listArtifacts, listDirectMediaTasks, runMediaDraft, saveMediaDraft,
  type Artifact, type SaveMediaDraftRequest } from "../../shared/api/client";

const AUTOSAVE_DELAY_MS = 650;
type DraftFields = Omit<SaveMediaDraftRequest, "expectedVersion">;

/** Bottom editor for one persisted media draft; failed saves keep the user's local input. */
export function MediaDraftEditor({ artifact }: { artifact: Artifact }) {
  const queryClient = useQueryClient();
  const key = ["media-draft", artifact.projectId, artifact.id] as const;
  const draft = useQuery({ queryKey: key,
    queryFn: () => getMediaDraft(artifact.projectId, artifact.id) });
  const resources = useQuery({
    queryKey: ["artifacts", artifact.projectId],
    queryFn: () => listArtifacts(artifact.projectId),
    enabled: artifact.kind === "VIDEO",
  });
  const imageResources = (resources.data?.items ?? []).filter((candidate) =>
    candidate.kind === "IMAGE" && candidate.currentVersionId !== null);
  const imageHistories = useQueries({ queries: artifact.kind === "VIDEO"
    ? imageResources.map((candidate) => ({
      queryKey: ["artifact-versions", artifact.projectId, candidate.id],
      queryFn: () => listArtifactVersions(artifact.projectId, candidate.id),
    })) : [] });
  const settings = useQuery({ queryKey: ["media-settings"], queryFn: getMediaSettings });
  const tasksKey = ["direct-media-tasks", artifact.projectId, artifact.id] as const;
  const directTasks = useQuery({ queryKey: tasksKey,
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id),
    refetchInterval: 3000 });
  const latestTask = directTasks.data?.find((task) => ["PENDING", "READY", "RUNNING",
    "SUBMITTING", "WAITING_PROVIDER", "UNKNOWN"].includes(task.status)
    || task.status === "BLOCKED" && task.providerRequestId !== null)
    ?? directTasks.data?.[0];
  const queue = useQuery({
    queryKey: ["direct-media-queue", artifact.projectId, latestTask?.id],
    queryFn: () => getDirectMediaQueueStatus(artifact.projectId, latestTask!.id),
    enabled: latestTask?.status === "READY", refetchInterval: 3000,
  });
  const [fields, setFields] = useState<DraftFields | null>(null);
  const fieldsRef = useRef<DraftFields | null>(null);
  const [expectedVersion, setExpectedVersion] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const runKey = useRef<string | null>(null);

  useEffect(() => {
    if (!draft.data || fieldsRef.current) return;
    const initial = {
      prompt: draft.data.prompt,
      inputImageVersionId: draft.data.inputImageVersionId,
      durationSeconds: draft.data.durationSeconds,
      capabilityId: draft.data.capabilityId,
    };
    fieldsRef.current = initial;
    setFields(initial);
    setExpectedVersion(draft.data.version);
  }, [draft.data]);

  const save = useMutation({
    mutationFn: (input: SaveMediaDraftRequest) =>
      saveMediaDraft(artifact.projectId, artifact.id, input),
    onSuccess: (saved, input) => {
      setExpectedVersion(saved.version);
      queryClient.setQueryData(key, saved);
      const latest = fieldsRef.current;
      if (latest && JSON.stringify(latest) === JSON.stringify({
        prompt: input.prompt,
        inputImageVersionId: input.inputImageVersionId,
        durationSeconds: input.durationSeconds,
        capabilityId: input.capabilityId,
      })) setDirty(false);
      setError(null);
    },
    onError: (failure) => setError(failure),
  });
  const run = useMutation({
    mutationFn: () => runMediaDraft(artifact.projectId, artifact.id,
      { expectedDraftVersion: expectedVersion ?? -1 },
      runKey.current ??= crypto.randomUUID()),
    onSuccess: async () => {
      runKey.current = null;
      await queryClient.invalidateQueries({ queryKey: tasksKey });
      await queryClient.invalidateQueries({ queryKey: ["project-snapshot", artifact.projectId] });
    },
  });
  const cancel = useMutation({
    mutationFn: (taskId: string) => cancelQueuedDirectMediaTask(artifact.projectId, taskId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tasksKey }),
  });

  useEffect(() => {
    if (!dirty || !fields || expectedVersion === null || save.isPending || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }),
      AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, error]);

  function edit(changes: Partial<DraftFields>) {
    runKey.current = null;
    setFields((current) => {
      if (!current) return current;
      const next = { ...current, ...changes };
      fieldsRef.current = next;
      return next;
    });
    setDirty(true);
    if (!(error instanceof ApiError && error.status === 409)) setError(null);
  }

  async function retry() {
    if (!fields || expectedVersion === null) return;
    if (error instanceof ApiError && error.status === 409) {
      try {
        const fresh = await getMediaDraft(artifact.projectId, artifact.id);
        queryClient.setQueryData(key, fresh);
        setExpectedVersion(fresh.version);
        setError(null);
      } catch (failure) {
        setError(failure instanceof Error ? failure : new Error("无法重新读取草稿"));
      }
      return;
    }
    setError(null);
    save.mutate({ ...fields, expectedVersion });
  }

  if (draft.error) return <p role="alert">无法读取工作草稿：{draft.error.message}</p>;
  if (draft.isPending || !fields) return <p role="status">正在读取工作草稿…</p>;
  const imageChoices = imageResources.flatMap((candidate, index) =>
    (imageHistories[index]?.data?.items ?? []).map((version) => ({
      id: version.id, label: `${candidate.title} · v${version.versionNo}`,
    })));
  const mediaKind = artifact.kind === "IMAGE" ? "IMAGE_GENERATION" : "VIDEO_GENERATION";
  const availableCapabilities = (settings.data?.connections ?? [])
    .filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities.filter((capability) =>
      capability.enabled && capability.kind === mediaKind).map((capability) => ({
        ...capability, connectionName: connection.name,
      })));
  const defaultCapabilityId = settings.data?.defaults.find((item) => item.kind === mediaKind)?.capabilityId;
  const chosenCapability = availableCapabilities.find((item) =>
    item.id === (fields.capabilityId ?? defaultCapabilityId));
  const occupied = latestTask && ["PENDING", "READY", "RUNNING", "SUBMITTING",
    "WAITING_PROVIDER", "UNKNOWN"].includes(latestTask.status)
    || latestTask?.status === "BLOCKED" && latestTask.providerRequestId !== null;
  const canRun = !dirty && !save.isPending && !error && !run.isPending
    && directTasks.isSuccess
    && fields.prompt.trim().length > 0 && !occupied
    && (artifact.kind === "IMAGE" || Boolean(fields.inputImageVersionId && fields.durationSeconds));
  return <div className="media-draft-editor">
    <label className="block text-sm">{artifact.kind === "IMAGE" ? "图片提示词" : "视频提示词"}
      <textarea className="mt-2 min-h-20 w-full" maxLength={20000}
        value={fields.prompt} onChange={(event) => edit({ prompt: event.target.value })} />
    </label>
    {artifact.kind === "VIDEO" ? <div className="mt-2 flex gap-3">
      <label className="flex-1 text-sm">输入图片版本
        <select className="mt-1 w-full" value={fields.inputImageVersionId ?? ""}
          onChange={(event) => edit({ inputImageVersionId: event.target.value || null })}>
          <option value="">选择同项目图片</option>
          {imageChoices.map((candidate) => <option key={candidate.id}
            value={candidate.id}>{candidate.label}</option>)}
        </select>
      </label>
      <label className="w-28 text-sm">时长（秒）
        <input min={1} max={30} step={1} type="number"
          value={fields.durationSeconds ?? ""}
          onChange={(event) => edit({ durationSeconds: event.target.value
            ? Number(event.target.value) : null })} />
      </label>
    </div> : null}
    <label className="mt-2 block text-sm">生成能力
      <select className="mt-1 w-full" value={fields.capabilityId ?? ""}
        onChange={(event) => edit({ capabilityId: event.target.value || null })}>
        <option value="">项目默认能力</option>
        {availableCapabilities.map((capability) => <option key={capability.id}
          value={capability.id}>{capability.connectionName} · {capability.name}</option>)}
      </select>
    </label>
    <div className="mt-2 flex items-center gap-3">
      <button className="node-action" type="button" disabled={!canRun}
        onClick={() => run.mutate()}>{run.isPending ? "提交中…" : "运行"}</button>
      <span className="text-xs">{chosenCapability?.name ?? "默认能力"} · 预计费用未知</span>
    </div>
    {run.error ? <p role="alert" className="mt-2 text-sm">运行失败：{run.error.message}</p> : null}
    {directTasks.isPending ? <p role="status">正在检查卡片任务…</p> : null}
    {directTasks.error ? <p role="alert">无法确认卡片任务状态：{directTasks.error.message}</p> : null}
    {latestTask ? <div className="mt-2 text-xs" role="status">
      任务：{latestTask.status}{latestTask.errorCode ? ` · ${latestTask.errorCode}` : ""}
      {queue.data && latestTask.status === "READY" ? <span> · 前方 {queue.data.waitingAhead} 项
        · {({ PROJECT_CAPACITY: "项目并发已满", CAPABILITY_CAPACITY: "能力并发已满",
          COMFY_SINGLE_SLOT: "ComfyUI 正在处理其他任务", WAITING_WORKER: "等待执行器",
          NOT_QUEUED: "未排队" } as const)[queue.data.reason]}（排位可能变化）</span> : null}
      {latestTask.status === "READY" ? <button className="node-action ml-2" type="button"
        disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>取消排队</button> : null}
      {latestTask.status === "UNKNOWN" ? <span> · 请先核对原请求</span> : null}
    </div> : null}
    {cancel.error ? <p role="alert">取消失败：{cancel.error.message}</p> : null}
    {latestTask?.status === "UNKNOWN" ? <UnknownTaskAttemptPanel
      projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version}
      planned={false} direct cancelRequested={latestTask.cancelRequested} /> : null}
    <p className="mt-2 text-xs" role="status">{save.isPending ? "保存中…" :
      dirty ? error ? "保存失败，本地输入已保留" : "待保存…" : "已保存"}</p>
    {error ? <div className="mt-2 text-sm" role="alert">
      <span>{error instanceof ApiError && error.status === 409
        ? "草稿有冲突；本地输入已保留。重新读取版本后可再保存。"
        : error.message}</span>
      <button className="node-action ml-2" onClick={() => void retry()} type="button">
        {error instanceof ApiError && error.status === 409 ? "重新读取版本" : "重试保存"}
      </button>
    </div> : null}
  </div>;
}
