import { Handle, NodeResizer, Position, type ResizeParams } from "@xyflow/react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowUp, ClockCounterClockwise, GearSix, Sparkle, Square, ArrowSquareOut } from "@phosphor-icons/react";
import { ApiError, cancelRun, createRun, getRunPreflight, listAgentRuns,
  type Agent, type AgentRun, type AgentRunList, type CanvasItem } from "../../shared/api/client";
import { useCanvasStore } from "./canvasStore";
import { AgentChatApproval, AgentChatMessage } from "./AgentChatPrimitives";
import { AgentRunConversation, RUN_STATUS_LABELS } from "./AgentRunConversation";
import { CanvasLoadingState } from "./CanvasLoadingState";
import "./AgentChatCard.css";

export const AGENT_CHAT_WIDTH = 460;
export const AGENT_CHAT_HEIGHT = 600;
export const AGENT_CHAT_MIN_WIDTH = 360;
export const AGENT_CHAT_MIN_HEIGHT = 420;
const MAX_AGENT_NAME = 120;
const MAX_INSTRUCTION = 8000;

export type AgentChatCardData = {
  item: CanvasItem;
  projectId: string;
  activeRun: AgentRun | null;
  redoCandidates: { artifactId: string; versionId: string; title: string }[];
  outputCount: number;
  onShowOutputs: (agent: Agent) => void;
  onResizeEnd: (itemId: string, layout: Pick<ResizeParams, "x" | "y" | "width" | "height">) => void;
  onRemove: (item: CanvasItem) => void;
  onToggleLocked: (item: CanvasItem) => void;
  onUpdateAgent: (agent: Agent, name: string, instruction: string) => void;
  updatingAgent: boolean;
  updateAgentError?: Error | null;
};

/** A persisted run is one conversation. Sending a new task never implies cross-run memory. */
export function AgentChatCard({ data, selected }: { data: AgentChatCardData; selected: boolean }) {
  const queryClient = useQueryClient();
  const agent = data.item.agent;
  const bodyRef = useRef<HTMLDivElement>(null);
  const [runInstruction, setRunInstruction] = useState("");
  const [redoShotId, setRedoShotId] = useState("");
  const [reviewInstruction, setReviewInstruction] = useState<string | null>(null);
  const [reviewSelection, setReviewSelection] = useState<string[]>([]);
  const [view, setView] = useState<"chat" | "history" | "settings">("chat");
  const [cursor, setCursor] = useState<string>();
  const [priorCursors, setPriorCursors] = useState<(string | undefined)[]>([]);
  const [historicalRun, setHistoricalRun] = useState<AgentRunList["items"][number] | null>(null);
  const [submittedRun, setSubmittedRun] = useState<AgentRun | null>(null);
  const history = useQuery({
    queryKey: ["run-history", data.projectId, agent?.id, cursor],
    queryFn: () => listAgentRuns(data.projectId, agent!.id, cursor),
    enabled: Boolean(agent) && view === "history",
  });
  // Keep the latest page subscribed while browsing older history pages.
  const latestHistory = useQuery({
    queryKey: ["run-history", data.projectId, agent?.id, undefined],
    queryFn: () => listAgentRuns(data.projectId, agent!.id),
    enabled: Boolean(agent),
  });
  const runIntent = useRef<{ instruction: string; agentVersion: number;
    modelConfigSource: string; modelConfigVersion: number; systemPromptVersion: number;
    redoShotArtifactId: string | null; selectedItemIds: string[]; key: string } | null>(null);
  useEffect(() => {
    setReviewInstruction(null);
    setReviewSelection([]);
  }, [agent?.id, agent?.version, redoShotId]);
  const preflight = useQuery({
    queryKey: ["run-preflight", data.projectId, agent?.id, agent?.version],
    queryFn: () => {
      if (!agent) throw new Error("Agent 卡片不可用。");
      return getRunPreflight(data.projectId, agent.id);
    },
    enabled: false,
  });
  const start = useMutation({
    mutationFn: ({ agentId, instruction, key, expectedAgentVersion,
      expectedModelConfigSource, expectedModelConfigVersion, expectedSystemPromptVersion,
      redoShotArtifactId,
      selectedItemIds }: {
      agentId: string; instruction: string; key: string; expectedAgentVersion: number;
      expectedModelConfigSource: string; expectedModelConfigVersion: number;
      expectedSystemPromptVersion: number;
      redoShotArtifactId: string | null; selectedItemIds: string[];
    }) => createRun(data.projectId, key, { agentId, instruction, expectedAgentVersion,
      expectedModelConfigSource, expectedModelConfigVersion, expectedSystemPromptVersion,
      selectedItemIds,
      ...(redoShotArtifactId ? { redoShotArtifactId } : {}) }),
    onSuccess: async (run, submitted) => {
      setSubmittedRun(run);
      setHistoricalRun(null);
      setView("chat");
      setCursor(undefined);
      setPriorCursors([]);
      runIntent.current = null;
      setRunInstruction((draft) => draft.trim() === submitted.instruction ? "" : draft);
      setReviewInstruction(null);
      setReviewSelection([]);
      await queryClient.invalidateQueries({ queryKey: ["snapshot", data.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["run-history", data.projectId, agent?.id] });
    },
    onError: (error) => {
      if (error instanceof ApiError && (error.code === "AGENT_VERSION_CONFLICT" ||
          error.code === "MODEL_CONFIG_CONFLICT" ||
          error.code === "SYSTEM_PROMPT_CONFLICT")) {
        runIntent.current = null;
        setReviewInstruction(null);
        void queryClient.invalidateQueries({ queryKey: ["canvas", data.projectId] });
      }
    },
  });
  useEffect(() => {
    if (reviewInstruction && bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
  }, [reviewInstruction, preflight.data, preflight.isFetching]);
  const stop = useMutation({
    mutationFn: (runId: string) => cancelRun(data.projectId, runId),
    onSuccess: async (run) => {
      setSubmittedRun(run);
      await queryClient.invalidateQueries({ queryKey: ["snapshot", data.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["run-history", data.projectId, agent?.id] });
    },
  });
  if (!agent) return null;
  const ownRun = data.activeRun?.agentInstanceId === agent.id ? data.activeRun : null;
  const redoShot = data.redoCandidates.find((shot) => shot.artifactId === redoShotId) ?? null;
  const redoBound = !redoShotId || redoShot !== null;
  const newestRun = latestHistory.data?.items[0] ?? null;
  const recentSubmission = submittedRun && (!newestRun || Date.parse(submittedRun.createdAt) > Date.parse(newestRun.createdAt))
    ? submittedRun : null;
  const currentRun = ownRun ?? recentSubmission ?? newestRun ?? submittedRun;
  const displayedRun = historicalRun
    ? history.data?.items.find((run) => run.id === historicalRun.id) ?? historicalRun
    : currentRun;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    data.onUpdateAgent(agent as Agent, String(values.get("name")), String(values.get("instruction")));
  }
  function submitRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!agent || data.activeRun || start.isPending || !redoBound) return;
    const instruction = runInstruction.trim();
    if (!instruction) return;
    setHistoricalRun(null);
    setView("chat");
    setReviewInstruction(instruction);
    setReviewSelection([...useCanvasStore.getState().selectedIds]);
    void preflight.refetch();
  }
  function confirmRun() {
    const reviewed = preflight.data;
    if (!agent || !reviewInstruction || !reviewed || preflight.isFetching || preflight.isError ||
        !reviewed.modelAvailable || !reviewed.toolCalling || data.activeRun || start.isPending ||
        reviewed.policySnapshot.systemPromptVersion == null ||
        reviewed.agentVersion !== agent.version || reviewed.agentId !== agent.id) return;
    if (runIntent.current?.instruction !== reviewInstruction ||
        runIntent.current.agentVersion !== reviewed.agentVersion ||
        runIntent.current.modelConfigSource !== reviewed.policySnapshot.modelConfigSource ||
        runIntent.current.modelConfigVersion !== reviewed.policySnapshot.modelConfigVersion ||
        runIntent.current.systemPromptVersion !== reviewed.policySnapshot.systemPromptVersion ||
        runIntent.current.redoShotArtifactId !== (redoShot?.artifactId ?? null) ||
        runIntent.current.selectedItemIds.join(",") !== reviewSelection.join(",")) {
      runIntent.current = {
        instruction: reviewInstruction, agentVersion: reviewed.agentVersion,
        modelConfigSource: reviewed.policySnapshot.modelConfigSource,
        modelConfigVersion: reviewed.policySnapshot.modelConfigVersion,
        systemPromptVersion: reviewed.policySnapshot.systemPromptVersion,
        redoShotArtifactId: redoShot?.artifactId ?? null,
        selectedItemIds: reviewSelection,
        key: crypto.randomUUID(),
      };
    }
    start.mutate({ agentId: agent.id, instruction: reviewInstruction,
      key: runIntent.current.key, expectedAgentVersion: reviewed.agentVersion,
      expectedModelConfigSource: runIntent.current.modelConfigSource,
      expectedModelConfigVersion: runIntent.current.modelConfigVersion,
      expectedSystemPromptVersion: runIntent.current.systemPromptVersion,
      redoShotArtifactId: redoShot?.artifactId ?? null,
      selectedItemIds: runIntent.current.selectedItemIds });
  }
  return <>
    <Handle id="agent-input" position={Position.Left} className="agent-chat-handle-input" type="target" />
    <Handle id="agent-output" isConnectable={false} position={Position.Right} className="agent-chat-handle-output" type="source" />
    <article aria-label={`${agent.name} 聊天卡片`} className={`agent-chat-card ${selected ? "agent-chat-card--selected" : ""}`}>
      <NodeResizer isVisible={selected && !data.item.locked} minHeight={AGENT_CHAT_MIN_HEIGHT}
        minWidth={AGENT_CHAT_MIN_WIDTH} onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />
      <header className="agent-chat-header">
        <span className="agent-chat-avatar"><Sparkle weight="fill" size={18} /></span>
        <div className="agent-chat-heading"><h3>{agent.name}</h3>
          <span>{ownRun ? RUN_STATUS_LABELS[ownRun.status] : data.activeRun ? "其他 Agent 运行中" : "准备就绪"}</span>
        </div>
        <button aria-label="聊天" aria-pressed={view === "chat"} className="agent-chat-tab nodrag"
          onClick={() => setView("chat")} type="button">对话</button>
        <button aria-label="查看记录" aria-pressed={view === "history"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "history" ? "chat" : "history")} title="任务记录" type="button"><ClockCounterClockwise size={18} /></button>
        <button aria-label="Agent 设置" aria-pressed={view === "settings"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "settings" ? "chat" : "settings")} type="button"><GearSix size={18} /></button>
      </header>
      <div className="agent-chat-context nodrag">
        <button onClick={() => setView("settings")} type="button">{agent.bindings.length} 个绑定输入</button>
        <button disabled={!data.outputCount} onClick={(event) => { event.stopPropagation(); data.onShowOutputs(agent); }} type="button"><ArrowSquareOut size={13} />查看产物{data.outputCount ? ` · ${data.outputCount}` : ""}</button>
      </div>
      <div className="agent-chat-body nodrag nowheel nopan" ref={bodyRef}>
        {view === "settings" ? <section aria-label="Agent 配置" className="agent-chat-settings">
          <p className="agent-chat-eyebrow">{agent.profileKey} · v{agent.profileVersion}</p>
          <form key={agent.version} onSubmit={submit}>
            <label>名称<input defaultValue={agent.name} maxLength={MAX_AGENT_NAME} name="name" required /></label>
            <label>指令<textarea defaultValue={agent.instruction} maxLength={MAX_INSTRUCTION} name="instruction" required rows={4} /></label>
            <button className="node-action" disabled={data.updatingAgent} type="submit">{data.updatingAgent ? "保存中…" : "保存配置"}</button>
            {data.updateAgentError ? <ChatError error={data.updateAgentError} /> : null}
          </form>
          <details className="agent-chat-binding-list"><summary>明确输入（{agent.bindings.length}）</summary>
            {agent.bindings.length === 0 ? <p>无输入；不会读取项目中的其他内容。</p> : <ul>{agent.bindings.map((binding) =>
              <li key={binding.id}>Artifact {binding.artifactId}<br />Version {binding.selectedVersionId}</li>)}</ul>}
          </details>
          <div className="agent-chat-settings-actions">
            <button className="node-action" onClick={() => data.onToggleLocked(data.item)} type="button">{data.item.locked ? "解锁" : "锁定"}</button>
            <button className="node-action node-action-danger" onClick={() => data.onRemove(data.item)} type="button">移除卡片</button>
          </div>
        </section> : view === "history" ? <section aria-label="运行记录" className="agent-chat-history">
          <h4>任务记录</h4><p>每次发送创建独立任务，可打开查看完整对话与动作。</p>
          {history.isFetching ? <CanvasLoadingState compact label="正在读取记录…" /> : null}
          {history.error ? <><ChatError error={history.error} /><button className="node-action" onClick={() => void history.refetch()} type="button">重试记录</button></> : null}
          {history.data?.items.length === 0 ? <p>尚无运行记录。</p> : null}
          {history.data?.items.map((run) => <button className="agent-chat-history-item" key={run.id} onClick={() => {
            setHistoricalRun(run); setView("chat");
          }} type="button"><span>{run.instruction}</span><small>{RUN_STATUS_LABELS[run.status]} · {new Date(run.createdAt).toLocaleString()}</small></button>)}
          <div className="agent-chat-settings-actions">
            {priorCursors.length ? <button className="node-action" onClick={() => {
              setCursor(priorCursors.at(-1)); setPriorCursors(priorCursors.slice(0, -1));
            }} type="button">上一页</button> : null}
            {history.data?.nextCursor ? <button className="node-action" onClick={() => {
              setPriorCursors([...priorCursors, cursor]); setCursor(history.data?.nextCursor ?? undefined);
            }} type="button">下一页</button> : null}
          </div>
        </section> : <>
          {historicalRun ? <div className="agent-chat-history-notice">正在查看任务记录<button className="node-action" onClick={() => setHistoricalRun(null)} type="button">返回当前任务</button></div> : null}
          {!displayedRun && !reviewInstruction && !history.isFetching && !history.error ? <div className="agent-chat-empty">
            <Sparkle size={30} weight="duotone" /><h4>从一个想法开始</h4>
            <p>描述你想创作的内容，我会根据绑定素材规划，并在生成前请你审批。</p>
            <button type="button" onClick={() => setRunInstruction("根据绑定素材规划三个镜头，并提出关键帧生成计划。")}>规划三个镜头 <ArrowUp size={14} /></button>
          </div> : null}
          {!displayedRun && history.isFetching ? <CanvasLoadingState compact label="正在读取对话…" /> : null}
          {!displayedRun && history.error ? <><ChatError error={history.error} /><button className="node-action" onClick={() => void history.refetch()} type="button">重试记录</button></> : null}
          {displayedRun ? <AgentRunConversation key={displayedRun.id} projectId={data.projectId}
            run={displayedRun} active={ownRun?.id === displayedRun.id} /> : null}
          {reviewInstruction ? <AgentChatMessage role="user" label="待发送">{reviewInstruction}</AgentChatMessage> : null}
      {reviewInstruction !== null ? (
        <AgentChatApproval title="运行前确认" description="确认本次任务的模型、输入与使用限额。">
          {preflight.isPending || preflight.isFetching ? <p className="mt-2">正在核对模型与输入…</p> : null}
          {preflight.error ? <ChatError error={preflight.error} /> : null}
          {preflight.data && !preflight.isFetching && !preflight.isError ? (
            <>
              <p className="mt-2 break-words">本次任务：{reviewInstruction}</p>
              {redoShot ? <p className="mt-1">局部重做目标：{redoShot.title} · 版本 {redoShot.versionId}</p> : null}
              <p className="mt-1 break-words">Agent 指令：{preflight.data.agentInstruction}</p>
              <p className="mt-1">模型：{preflight.data.modelAvailable
                ? `${preflight.data.providerAdapter ?? "未知适配器"} / ${preflight.data.modelId ?? "未声明模型 ID"}`
                : "未配置 ChatModel，无法启动规划"}</p>
              <details className="agent-chat-review-details"><summary>输入与运行限额</summary>
              <p className="mt-1">模型配置：{preflight.data.policySnapshot.modelConfigSource} v{preflight.data.policySnapshot.modelConfigVersion}；系统提示词 v{preflight.data.policySnapshot.systemPromptVersion ?? "未知"}。确认后若配置或规则变化，需重新检查。</p>
              <p className="mt-1">精确绑定输入：{preflight.data.bindings.length} 个版本；首轮只发送有上限的内容预览，不发送图片字节。</p>
              <p className="mt-1">当前模型看不到图片像素、视频帧或音频，只能依据文字与元数据规划；生成计划不等于媒体已生成，实际结果须等待任务归档。</p>
              <p className="mt-1 break-all">当前选择：{reviewSelection.length} 张卡片
                {reviewSelection.length ? `（${reviewSelection.join("、")}）` : ""}；仅作为操作意图，不扩大 Agent 权限。</p>
              {preflight.data.bindings.map((binding) => (
                <p className="mt-1 break-all" key={binding.selectedVersionId}>
                  {binding.artifactKind}「{binding.artifactTitle}」 · Artifact {binding.artifactId}
                  <br />Version {binding.selectedVersionId}
                </p>
              ))}
              <p className="mt-1">还会发送项目名称与画幅。模型调用最多 {preflight.data.policySnapshot.maxModelTurns} 轮、工具最多 {preflight.data.policySnapshot.maxToolExecutions} 次；媒体生成仍需单独审批。</p>
              <p className="mt-1">本轮限额：图片 {preflight.data.policySnapshot.maxImages}、视频 {preflight.data.policySnapshot.maxVideos}、镜头 {preflight.data.policySnapshot.maxShots}。</p>
              </details>
              {!preflight.data.toolCalling && preflight.data.modelAvailable ?
                <p className="mt-1 text-red-700">当前模型未确认支持工具调用，无法运行。</p> : null}
              <button className="node-action mt-2" disabled={Boolean(data.activeRun) || start.isPending ||
                preflight.data.agentVersion !== agent.version || !preflight.data.modelAvailable ||
                !preflight.data.toolCalling || preflight.data.policySnapshot.systemPromptVersion == null ||
                !redoBound} onClick={confirmRun} type="button">
                {start.isPending ? "启动中…" : "确认开始规划"}
              </button>
              <button className="node-action" disabled={start.isPending} onClick={() => setReviewInstruction(null)} type="button">返回修改</button>
            </>
          ) : null}
        </AgentChatApproval>
      ) : null}

        </>}
        {start.error ? <ChatError error={start.error} /> : null}
        {stop.error ? <ChatError error={stop.error} /> : null}
      </div>
      <form aria-label="发送新任务" className="agent-chat-composer nodrag nowheel nopan" onSubmit={submitRun}>
        <label className="agent-chat-scope">运行范围<select onChange={(event) => setRedoShotId(event.target.value)} value={redoShotId}>
          <option value="">基于绑定输入创作</option>
          {data.redoCandidates.map((shot) => <option key={shot.artifactId} value={shot.artifactId}>仅重做「{shot.title}」</option>)}
        </select></label>
        {redoShot ? <p className="agent-chat-hint">仅规划「{redoShot.title}」的新版本；图片和视频分别审批。</p> : null}
        {!redoBound ? <p role="alert">目标镜头版本已变化，请重新绑定。</p> : null}
        <textarea aria-label="本次任务" maxLength={MAX_INSTRUCTION} onChange={(event) => {
          setRunInstruction(event.target.value); setReviewInstruction(null); setReviewSelection([]);
        }} onKeyDown={(event) => {
          // IME Enter confirms Chinese input; only an explicit modifier shortcut submits.
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} placeholder="描述想创作的内容…" required value={runInstruction} rows={2} />
        <div className="agent-chat-composer-footer"><span>{data.activeRun && !ownRun ? "请等待其他 Agent 任务结束" : "每条消息开启独立任务 · ⌘ / Ctrl + Enter"}</span>
          {ownRun ? <button aria-label="停止" className="agent-chat-send agent-chat-stop" disabled={stop.isPending || ownRun.status === "CANCEL_REQUESTED"}
            onClick={() => stop.mutate(ownRun.id)} title="停止后续编排" type="button"><Square weight="fill" size={14} /></button>
            : <button aria-label="发送" className="agent-chat-send" disabled={Boolean(data.activeRun) || start.isPending || preflight.isFetching || !redoBound || !runInstruction.trim()}
              title="发送并检查运行范围" type="submit"><ArrowUp size={20} weight="bold" /></button>}
        </div>
      </form>
    </article>
  </>;
}

function ChatError({ error }: { error: Error }) {
  return <p className="agent-chat-error" role="alert">{error instanceof ApiError ? error.message : "读取或提交失败，请重试；输入内容已保留。"}</p>;
}
