import { ArrowUp, CaretDown, Check, Coins, Cube, ImageSquare, Plus, SlidersHorizontal, X } from "@phosphor-icons/react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { UnknownTaskRetryPanel } from "./UnknownTaskRetryPanel";
import { taskErrorDetail } from "./taskErrorMessages";
import { latestMediaTask, occupiesMediaCard, MEDIA_TASK_REFRESH_INTERVAL_MS } from "./mediaTaskState";
import { readContentText } from "./artifactContent";
import { ApiError, assetContentUrl, cancelQueuedDirectMediaTask, getDirectMediaQueueStatus,
  getMediaDraft, getMediaSettings,
  listArtifactVersions, listArtifacts, listDirectMediaTasks, runMediaDraft, saveMediaDraft,
  type Artifact, type MediaCapability, type SaveMediaDraftRequest } from "../../shared/api/client";
import "./MediaDraftEditor.css";

const AUTOSAVE_DELAY_MS = 650;
const MAX_PROMPT_LENGTH = 20000;
const MIN_VIDEO_SECONDS = 1;
const MAX_VIDEO_SECONDS = 30;
const CONFLICT_STATUS = 409;
const QUEUE_LABELS = {
  PROJECT_CAPACITY: "项目并发已满", CAPABILITY_CAPACITY: "能力并发已满",
  COMFY_SINGLE_SLOT: "ComfyUI 正在处理其他任务", WAITING_WORKER: "等待执行器", NOT_QUEUED: "未排队",
} as const;
const FIXED_MODELS: Readonly<Record<string, string>> = {
  OPENAI_GPT_IMAGE_2: "gpt-image-2", GOOGLE_NANO_BANANA_2: "gemini-3.1-flash-image",
  ARK_SEEDANCE_2_I2V: "doubao-seedance-2-0-260128", MOCK_IMAGE: "Mock 图片演示",
  MOCK_VIDEO: "Mock 视频演示",
};
const QUALITY_LABELS = { low: "低", medium: "中", high: "高" } as const;
type DraftFields = Omit<SaveMediaDraftRequest, "expectedVersion">;
type Popover = "models" | "parameters" | "references";
type RunIntent = { key: string; expectedDraftVersion: number };

function modelName(capability: MediaCapability) {
  return FIXED_MODELS[capability.adapterId] ?? capability.settings.checkpoint
    ?? capability.settings.diffusionModel ?? capability.adapterId;
}

function imageAssetId(content: unknown) {
  const value = readContentText(content, "assetId");
  return value.trim() ? value : null;
}

/**
 * Attachment chips, raised pickers and compact task rows adapt Beautiful UI's PromptBar / TaskRows.
 * https://github.com/slev12397/beautiful-ui (MIT, Shane Levine; see beautiful-ui-LICENSE.txt).
 * All states come from persisted drafts/tasks; the source's scripted demo sequences are not used.
 */
export function MediaDraftEditor({ artifact, canvasItemId }: {
  artifact: Artifact; canvasItemId: string;
}) {
  const queryClient = useQueryClient();
  const key = ["media-draft", artifact.projectId, canvasItemId] as const;
  const draft = useQuery({ queryKey: key,
    queryFn: () => getMediaDraft(artifact.projectId, canvasItemId) });
  const resources = useQuery({
    queryKey: ["artifacts", artifact.projectId],
    queryFn: () => listArtifacts(artifact.projectId), enabled: artifact.kind === "VIDEO",
  });
  const imageResources = (resources.data?.items ?? []).filter((candidate) =>
    candidate.kind === "IMAGE");
  const imageHistories = useQueries({ queries: artifact.kind === "VIDEO"
    ? imageResources.map((candidate) => ({
      queryKey: ["artifact-versions", artifact.projectId, candidate.id],
      queryFn: () => listArtifactVersions(artifact.projectId, candidate.id),
    })) : [] });
  const settings = useQuery({ queryKey: ["media-settings"], queryFn: getMediaSettings });
  const tasksKey = ["direct-media-tasks", artifact.projectId, artifact.id] as const;
  const directTasks = useQuery({ queryKey: tasksKey,
    queryFn: () => listDirectMediaTasks(artifact.projectId, artifact.id),
    refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS });
  const latestTask = latestMediaTask(directTasks.data);
  const queue = useQuery({
    queryKey: ["direct-media-queue", artifact.projectId, latestTask?.id],
    queryFn: () => getDirectMediaQueueStatus(artifact.projectId, latestTask!.id),
    enabled: latestTask?.status === "READY", refetchInterval: MEDIA_TASK_REFRESH_INTERVAL_MS,
  });
  const [fields, setFields] = useState<DraftFields | null>(null);
  const fieldsRef = useRef<DraftFields | null>(null);
  const [expectedVersion, setExpectedVersion] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const runIntent = useRef<RunIntent | null>(null);
  const [popover, setPopover] = useState<Popover | null>(null);
  const popoverRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const id = useId();

  useEffect(() => {
    if (!popover) return;
    const firstControl = popoverRef.current?.querySelector<HTMLElement>("[aria-checked='true'], button, select, input");
    (firstControl ?? popoverRef.current)?.focus();
    function onPointerDown(event: PointerEvent) {
      if (event.target instanceof Node && !popoverRef.current?.contains(event.target)
        && !triggerRef.current?.contains(event.target)) setPopover(null);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        setPopover(null);
        triggerRef.current?.focus();
      }
      if (popover !== "models" || !["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
      const choices = Array.from(popoverRef.current?.querySelectorAll<HTMLButtonElement>("[role='menuitemradio']") ?? []);
      if (!choices.length) return;
      event.preventDefault();
      const current = choices.findIndex((choice) => choice === document.activeElement);
      const next = event.key === "Home" ? 0 : event.key === "End" ? choices.length - 1
        : (current + (event.key === "ArrowDown" ? 1 : -1) + choices.length) % choices.length;
      choices[next]?.focus();
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown, true);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown, true);
    };
  }, [popover]);

  const save = useMutation({
    mutationFn: (input: SaveMediaDraftRequest) => saveMediaDraft(artifact.projectId, canvasItemId, input),
    onSuccess: (saved, input) => {
      setExpectedVersion(saved.version);
      queryClient.setQueryData(key, saved);
      const latest = fieldsRef.current;
      if (latest && JSON.stringify(latest) === JSON.stringify({
        prompt: input.prompt, inputImageVersionId: input.inputImageVersionId,
        durationSeconds: input.durationSeconds, capabilityId: input.capabilityId,
      })) setDirty(false);
      setError(null);
    },
    onError: (failure) => setError(failure),
  });
  const run = useMutation({
    mutationFn: () => {
      if (expectedVersion === null) throw new Error("请等待草稿读取完成");
      // A lost response may still have advanced the server draft. Retrying that submission
      // must replay its exact payload, even when SSE/refetch has supplied a newer CAS version.
      runIntent.current ??= { key: crypto.randomUUID(), expectedDraftVersion: expectedVersion };
      return runMediaDraft(artifact.projectId, artifact.id,
        { canvasItemId, expectedDraftVersion: runIntent.current.expectedDraftVersion },
        runIntent.current.key);
    },
    onSuccess: async () => {
      runIntent.current = null;
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: tasksKey }),
        queryClient.invalidateQueries({ queryKey: ["snapshot", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: ["canvas", artifact.projectId] }),
        queryClient.invalidateQueries({ queryKey: key }),
      ]);
    },
  });
  const cancel = useMutation({
    mutationFn: (taskId: string) => cancelQueuedDirectMediaTask(artifact.projectId, taskId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: tasksKey }),
  });

  useEffect(() => {
    // Run submission and result selection may advance the draft CAS version. Refresh only clean
    // fields; an in-flight save, local edit or conflict must keep its current input intact.
    if (!draft.data || dirty || save.isPending || run.isPending || error) return;
    // An earlier GET can finish after a successful save wrote its newer result to the cache.
    // The last acknowledged CAS version is monotonic even if query responses arrive out of order.
    if (expectedVersion !== null && draft.data.version < expectedVersion) return;
    const initial = {
      prompt: draft.data.prompt, inputImageVersionId: draft.data.inputImageVersionId,
      durationSeconds: draft.data.durationSeconds, capabilityId: draft.data.capabilityId,
    };
    fieldsRef.current = initial;
    setFields(initial);
    setExpectedVersion(draft.data.version);
  }, [draft.data, dirty, save.isPending, run.isPending, error, expectedVersion]);

  useEffect(() => {
    if (!dirty || !fields || expectedVersion === null || save.isPending || error) return;
    const timer = window.setTimeout(() => save.mutate({ ...fields, expectedVersion }), AUTOSAVE_DELAY_MS);
    return () => window.clearTimeout(timer);
  }, [dirty, fields, expectedVersion, save.isPending, error]);

  function edit(changes: Partial<DraftFields>) {
    runIntent.current = null;
    if (!run.isPending) run.reset();
    setFields((current) => {
      if (!current) return current;
      const next = { ...current, ...changes };
      fieldsRef.current = next;
      return next;
    });
    setDirty(true);
    if (!(error instanceof ApiError && error.status === CONFLICT_STATUS)) setError(null);
  }

  async function retry() {
    if (!fields || expectedVersion === null) return;
    if (error instanceof ApiError && error.status === CONFLICT_STATUS) {
      try {
        const fresh = await getMediaDraft(artifact.projectId, canvasItemId);
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

  function togglePopover(next: Popover, trigger: HTMLButtonElement) {
    triggerRef.current = trigger;
    setPopover((current) => current === next ? null : next);
  }

  function chooseCapability(capabilityId: string | null) {
    edit({ capabilityId });
    setPopover(null);
    triggerRef.current?.focus();
  }

  function chooseReference(inputImageVersionId: string | null) {
    edit({ inputImageVersionId });
    setPopover(null);
    triggerRef.current?.focus();
  }

  if (!fields) return <div className="media-draft-editor media-draft-initial" aria-label="媒体生成编辑器">
    {draft.error ? <div role="alert">无法读取工作草稿：{draft.error.message}
      <button className="media-draft-text-action" disabled={draft.isFetching}
        onClick={() => void draft.refetch()} type="button">重试读取草稿</button></div>
      : <CanvasLoadingState compact label="正在读取工作草稿" />}
  </div>;

  const imageChoices = imageResources.flatMap((candidate, index) =>
    (imageHistories[index]?.data?.items ?? []).flatMap((version) => {
      const assetId = imageAssetId(version.content);
      return assetId ? [{ id: version.id, label: `${candidate.title} · v${version.versionNo}`,
        title: candidate.title, versionNo: version.versionNo, assetId,
        available: imageHistories[index]?.isSuccess === true,
        current: version.id === candidate.resourceDefaultVersionId }] : [];
    }));
  const mediaKind = artifact.kind === "IMAGE" ? "IMAGE_GENERATION" : "VIDEO_GENERATION";
  const availableCapabilities = (settings.data?.connections ?? [])
    .filter((connection) => connection.enabled)
    .flatMap((connection) => connection.capabilities.filter((capability) =>
      capability.enabled && capability.kind === mediaKind).map((capability) => ({
        ...capability, connectionName: connection.name,
        realGenerationTested: connection.realGenerationTested, mock: connection.platform === "MOCK",
      })));
  const defaultCapabilityId = settings.data?.defaults.find((item) => item.kind === mediaKind)?.capabilityId;
  const chosenCapability = availableCapabilities.find((item) => item.id === (fields.capabilityId ?? defaultCapabilityId));
  const selectedReference = imageChoices.find((choice) => choice.id === fields.inputImageVersionId);
  const occupied = latestTask ? occupiesMediaCard(latestTask) : false;
  const duration = fields.durationSeconds;
  const validDuration = duration != null && Number.isInteger(duration)
    && duration >= Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)
    && duration <= Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS);
  const canRun = !dirty && !save.isPending && !error && !run.isPending
    && directTasks.isSuccess && settings.isSuccess && Boolean(chosenCapability)
    && fields.prompt.trim().length > 0 && !occupied
    && (artifact.kind === "IMAGE" || Boolean(resources.isSuccess && selectedReference?.available && validDuration));
  const isTemplate = chosenCapability?.adapterId.startsWith("COMFY_");
  const dimensionLabel = isTemplate ? "模板默认" : "由模型决定";
  const quality = chosenCapability?.settings.quality;
  const qualityLabel = quality ? `${QUALITY_LABELS[quality]}画质` : "默认画质";
  const referenceLabel = selectedReference?.label;
  const historyError = imageHistories.find((history) => history.error)?.error;
  const historyPending = resources.isPending || imageHistories.some((history) => history.isPending);
  const saveLabel = save.isPending ? "保存中…" : dirty ? error ? "保存失败，本地输入已保留" : "待保存…" : "已保存";

  return <div className="media-draft-editor" aria-label="媒体生成编辑器">
    <div className="media-draft-header">
      <span className="media-draft-tab-active">Prompt</span>
      <span className="media-draft-tab-unavailable" aria-disabled="true" title="请使用画布中的 Agent 卡片运行 Agent">Agent</span>
      <span className={`media-draft-save-state${error ? " is-error" : ""}`} role="status">{saveLabel}</span>
    </div>
    <div className="media-draft-reference-row">
      <div className="media-draft-popover-anchor">
        <button className="media-draft-reference-add" type="button"
          disabled={artifact.kind === "IMAGE"}
          aria-label={artifact.kind === "IMAGE" ? "添加参考图（暂不支持）" : "选择输入图片版本"}
          title={artifact.kind === "IMAGE" ? "当前支持文生图，参考图编辑暂不支持" : "选择同项目图片的精确版本"}
          aria-expanded={artifact.kind === "VIDEO" ? popover === "references" : undefined}
          aria-controls={artifact.kind === "VIDEO" ? `${id}-references` : undefined}
          onClick={(event) => togglePopover("references", event.currentTarget)}>
          <Plus size={20} />
        </button>
        {popover === "references" ? <div className="media-draft-popover media-draft-references" ref={popoverRef}
          id={`${id}-references`} role="dialog" aria-label="输入图片版本">
          <p className="media-draft-popover-title">选择视频首帧</p>
          <label htmlFor={`${id}-input-image`}>输入图片版本</label>
          <select id={`${id}-input-image`} value={fields.inputImageVersionId ?? ""}
            onChange={(event) => edit({ inputImageVersionId: event.target.value || null })}>
            <option value="">选择同项目图片</option>
            {fields.inputImageVersionId && !referenceLabel ? <option value={fields.inputImageVersionId}>已固定版本（暂不可用）</option> : null}
            {imageChoices.map((choice) => <option key={choice.id} value={choice.id} disabled={!choice.available}>{choice.label}</option>)}
          </select>
          <div className="media-draft-reference-options">
            {imageChoices.map((choice) => <button key={choice.id} type="button"
              className="media-draft-reference-option" aria-label={`使用 ${choice.label}`}
              aria-pressed={choice.id === fields.inputImageVersionId} disabled={!choice.available}
              onClick={() => chooseReference(choice.id)}>
              {/* Reference pixels are shown from the archived original, not the 480px preview. */}
              <img src={assetContentUrl(artifact.projectId, choice.assetId)} alt="" loading="lazy" />
              <span><strong>{choice.title}</strong><small>v{choice.versionNo} · {choice.current ? "当前选用版本" : "历史版本"}</small></span>
              {choice.id === fields.inputImageVersionId ? <Check size={15} /> : null}
            </button>)}
          </div>
          {historyPending ? <CanvasLoadingState compact label="正在读取图片版本" /> : null}
          {!historyPending && !resources.error && !historyError && !imageChoices.length ? <p>暂无已生成或上传的图片，请先添加图片。</p> : null}
          {resources.error || historyError ? <div role="alert">无法读取图片版本。
            <button className="media-draft-text-action" onClick={() => {
              void resources.refetch();
              imageHistories.forEach((history) => { if (history.error) void history.refetch(); });
            }} type="button">重试读取图片</button></div> : null}
          <p>仅显示已有媒体文件的图片版本。运行时固定首帧版本，后续修改图片不会改变已提交的任务。</p>
        </div> : null}
      </div>
      {artifact.kind === "VIDEO" && fields.inputImageVersionId ? <div className="media-draft-reference-chip">
        <button className="media-draft-reference-replace" type="button" aria-label="替换视频首帧"
          aria-expanded={popover === "references"} aria-controls={`${id}-references`}
          onClick={(event) => togglePopover("references", event.currentTarget)}>
          {selectedReference ?
            <img src={assetContentUrl(artifact.projectId, selectedReference.assetId)} alt={`${referenceLabel} 首帧`} />
            : <ImageSquare size={25} />}
          <span><strong>{referenceLabel ?? "已固定图片版本"}</strong>
            <small>{selectedReference?.available && resources.isSuccess ? "视频首帧 · 点击替换"
              : historyPending ? "正在确认首帧版本…" : "此版本暂不可用 · 点击替换"}</small></span>
        </button>
        <button className="media-draft-reference-remove" type="button" aria-label="清除视频首帧"
          onClick={() => edit({ inputImageVersionId: null })}><X size={13} /></button>
      </div> : <span className="media-draft-reference-hint">{artifact.kind === "IMAGE"
        ? "文生图 · 暂不支持参考图" : "添加图片作为视频首帧"}</span>}
    </div>
    <label className="media-draft-prompt-label" htmlFor={`${id}-prompt`}>{artifact.kind === "IMAGE" ? "图片提示词" : "视频提示词"}</label>
    <textarea id={`${id}-prompt`} className="media-draft-prompt" maxLength={MAX_PROMPT_LENGTH}
      placeholder={artifact.kind === "IMAGE" ? "描述你想创作的画面，让想象发生…" : "描述镜头、动作和运镜，让画面动起来…"}
      value={fields.prompt} onChange={(event) => edit({ prompt: event.target.value })} />
    <div className="media-draft-toolbar">
      <div className="media-draft-popover-anchor media-draft-model-anchor">
        <button className="media-draft-toolbar-button media-draft-model-trigger" type="button"
          aria-label="选择生成模型" aria-haspopup="menu" aria-expanded={popover === "models"}
          aria-controls={`${id}-models`} onClick={(event) => togglePopover("models", event.currentTarget)}>
          <Cube size={17} /><span>{settings.isPending ? "加载模型…" : settings.error ? "模型配置读取失败" : chosenCapability?.name
            ?? (fields.capabilityId ? "所选模型不可用" : "未配置默认模型")}</span><CaretDown size={12} />
        </button>
        {popover === "models" ? <div className="media-draft-popover media-draft-models" ref={popoverRef}
          id={`${id}-models`} role="menu" aria-label="生成模型">
          <p className="media-draft-popover-title">{artifact.kind === "IMAGE" ? "图片模型" : "视频模型"}</p>
          <button className="media-draft-model-option" role="menuitemradio" aria-checked={!fields.capabilityId}
            onClick={() => chooseCapability(null)} type="button">
            <span><strong>项目默认能力</strong><small>{defaultCapabilityId ? "跟随当前默认模型" : "尚未配置默认模型"}</small></span>{!fields.capabilityId ? <Check size={16} /> : null}
          </button>
          {availableCapabilities.map((capability) => <button key={capability.id} type="button"
            className="media-draft-model-option" role="menuitemradio" aria-checked={fields.capabilityId === capability.id}
            onClick={() => chooseCapability(capability.id)}>
            <span><strong>{capability.name}</strong><small>{capability.connectionName} · {modelName(capability)}</small>
              <small className={capability.mock ? "media-draft-model-mock" : ""}>{capability.mock ? "Mock 演示"
                : capability.realGenerationTested ? "已完成真实生成验证" : "尚未完成真实生成验证"}</small></span>
            {fields.capabilityId === capability.id ? <Check size={16} /> : null}
          </button>)}
          {settings.isPending ? <p role="status">正在读取可用模型…</p> : null}
          {settings.error ? <div role="alert">无法读取模型。
            <button className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">重试读取模型</button></div> : null}
          {settings.isSuccess && !availableCapabilities.length ? <p>尚无可用模型，请在媒体设置中启用对应能力。</p> : null}
        </div> : null}
      </div>
      <div className="media-draft-popover-anchor media-draft-parameters-anchor">
        <button className="media-draft-toolbar-button" type="button" aria-label="尺寸与画质"
          aria-expanded={popover === "parameters"} aria-controls={`${id}-parameters`}
          onClick={(event) => togglePopover("parameters", event.currentTarget)}>
          <SlidersHorizontal size={16} /><span>{artifact.kind === "VIDEO" ? `${duration ?? "—"} 秒 · ` : ""}{dimensionLabel} · {qualityLabel}</span><CaretDown size={12} />
        </button>
        {popover === "parameters" ? <div className="media-draft-popover media-draft-parameters" ref={popoverRef}
          tabIndex={-1} id={`${id}-parameters`} role="dialog" aria-label="尺寸与画质设置">
          <p className="media-draft-popover-title">生成参数</p>
          <dl><div><dt>比例 / 尺寸</dt><dd>{dimensionLabel}</dd></div><div><dt>画质</dt><dd>{quality ? QUALITY_LABELS[quality] : "模板默认"}</dd></div></dl>
          <p>尺寸与画质来自所选模型的固定配置，当前草稿不支持单独修改。</p>
          {artifact.kind === "VIDEO" ? <div className="media-draft-duration"><label htmlFor={`${id}-duration`}>时长（秒）</label>
            <input id={`${id}-duration`} aria-describedby={chosenCapability ? `${id}-duration-help` : undefined}
              min={Math.max(MIN_VIDEO_SECONDS, chosenCapability?.minimumSeconds ?? MIN_VIDEO_SECONDS)}
              max={Math.min(MAX_VIDEO_SECONDS, chosenCapability?.maximumSeconds ?? MAX_VIDEO_SECONDS)} step={1} type="number"
              value={duration ?? ""} onChange={(event) => edit({ durationSeconds: event.target.value ? Number(event.target.value) : null })} />
            {chosenCapability ? <span id={`${id}-duration-help`}>所选模型支持 {chosenCapability.minimumSeconds}–{chosenCapability.maximumSeconds} 秒</span> : null}
          </div> : null}
        </div> : null}
      </div>
      <span className="media-draft-cost" title="预计费用未知"><Coins size={16} /><span>费用未知</span></span>
      <button className="media-draft-run" type="button" disabled={!canRun}
        aria-label={run.isPending ? "正在提交运行" : "运行"} title={occupied ? "此卡片已有任务，请等待完成或先重试" : "运行"}
        onClick={() => run.mutate()}><ArrowUp size={21} weight="bold" /></button>
    </div>
    <div className="media-draft-feedback">
      {run.isPending ? <CanvasLoadingState compact label="正在提交任务" /> : null}
      {run.error ? <p role="alert">运行失败：{run.error.message}</p> : null}
      {directTasks.isPending ? <p role="status">正在检查卡片任务…</p> : null}
      {directTasks.error ? <div role="alert">无法确认卡片任务状态：{directTasks.error.message}
        <button className="media-draft-text-action" type="button" onClick={() => void directTasks.refetch()}>重试检查任务</button></div> : null}
      {settings.isSuccess && fields.capabilityId && !chosenCapability ? <p role="status">所选模型不可用，请选择其他模型。</p> : null}
      {settings.isSuccess && !fields.capabilityId && !chosenCapability ? <p role="status">尚未配置默认模型，请选择可用模型或先在媒体设置中配置。</p> : null}
      {settings.error ? <div role="alert">无法读取模型配置。
        <button className="media-draft-text-action" onClick={() => void settings.refetch()} type="button">重试读取模型</button></div> : null}
      {artifact.kind === "VIDEO" && duration != null && !validDuration ? <p role="alert">请填写所选模型支持的整数秒时长。</p> : null}
      {artifact.kind === "VIDEO" && fields.inputImageVersionId && !historyPending && (!resources.isSuccess || !selectedReference?.available)
        ? <p role="alert">无法确认已固定的首帧版本。原选择已保留，请重试读取图片或替换首帧。</p> : null}
      {latestTask && (latestTask.status === "FAILED" || latestTask.status === "BLOCKED")
        ? <p role="alert">生成未完成{taskErrorDetail(latestTask.errorCode)}</p> : null}
      {latestTask?.status === "READY" ? <div className="media-draft-task-status">
        {queue.data && latestTask.status === "READY" ? <span>前方 {queue.data.waitingAhead} 项 · {QUEUE_LABELS[queue.data.reason]}（排位可能变化）</span> : null}
        {queue.error && latestTask.status === "READY" ? <span role="alert">暂时无法读取排位，任务仍在排队。</span> : null}
        {latestTask.status === "READY" ? <button className="media-draft-text-action" type="button"
          disabled={cancel.isPending} onClick={() => cancel.mutate(latestTask.id)}>{cancel.isPending ? "取消中…" : "取消排队"}</button> : null}
      </div> : null}
      {cancel.error ? <p role="alert">取消失败：{cancel.error.message}</p> : null}
      {latestTask?.status === "UNKNOWN" ? <UnknownTaskRetryPanel errorCode={latestTask.errorCode}
        projectId={artifact.projectId} taskId={latestTask.id} taskVersion={latestTask.version} /> : null}
      {error ? <div role="alert"><span>{error instanceof ApiError && error.status === CONFLICT_STATUS
        ? "草稿有冲突；本地输入已保留。重新读取版本后可再保存。" : error.message}</span>
        <button className="media-draft-text-action" onClick={() => void retry()} type="button">
          {error instanceof ApiError && error.status === CONFLICT_STATUS ? "重新读取版本" : "重试保存"}</button></div> : null}
    </div>
  </div>;
}
