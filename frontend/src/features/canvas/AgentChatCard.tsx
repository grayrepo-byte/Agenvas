import { Handle, NodeResizer, Position, type ResizeParams } from "@xyflow/react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from "@tanstack/react-query";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowUp, ClockCounterClockwise, GearSix, Sparkle, Square, ArrowSquareOut, Plus } from "@phosphor-icons/react";
import { ApiError, cancelRun, createRun, getRunPreflight, listAgentConversations, createAgentConversation, selectAgentConversation, listConversationRuns,
  type Agent, type AgentRun, type AgentRunList, type AgentConversation, type AgentConversationList, type CreateRunRequest, type CanvasItem } from "../../shared/api/client";
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


const EMPTY_CONVERSATION_DRAFT = "new-conversation";
const EMPTY_DRAFT = { instruction: "", redoShotId: "" };
type ConversationDraft = typeof EMPTY_DRAFT;
type RunReview = {
  conversationId: string; instruction: string; selectedIds: string[];
  agentVersion: number; redoShotId: string;
};

/** Conversations persist across messages; each submitted message retains its own Run and approvals. */
export function AgentChatCard({ data, selected }: { data: AgentChatCardData; selected: boolean }) {
  const queryClient = useQueryClient();
  const agent = data.item.agent;
  const bodyRef = useRef<HTMLDivElement>(null);
  const positionedConversation = useRef<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, ConversationDraft>>({});
  const [review, setReview] = useState<RunReview | null>(null);
  const [view, setView] = useState<"chat" | "history" | "settings">("chat");
  const createKey = useRef<string | null>(null);
  const runIntent = useRef<{ fingerprint: string; key: string } | null>(null);
  const conversationKey = ["agent-conversations", data.projectId, agent?.id];
  const conversations = useInfiniteQuery({
    queryKey: conversationKey,
    queryFn: ({ pageParam }) => listAgentConversations(data.projectId, agent!.id, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(agent),
  });
  const conversationId = conversations.data?.pages[0]?.currentConversationId ?? null;
  const draftKey = conversationId ?? EMPTY_CONVERSATION_DRAFT;
  const draft = drafts[draftKey] ?? EMPTY_DRAFT;
  const runInstruction = draft.instruction;
  const redoShotId = draft.redoShotId;
  const sessions = conversations.data?.pages.flatMap((page) => page.items) ?? [];
  const currentConversation = sessions.find((session) => session.id === conversationId);
  const ownRun = data.activeRun?.agentInstanceId === agent?.id ? data.activeRun : null;
  const currentActiveRun = ownRun?.conversationId === conversationId ? ownRun : null;
  const runsKey = ["conversation-runs", data.projectId, agent?.id, conversationId];
  const runs = useInfiniteQuery({
    queryKey: runsKey,
    queryFn: ({ pageParam }) => listConversationRuns(data.projectId, agent!.id, conversationId!, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(agent && conversationId),
  });
  const byId = new Map((runs.data?.pages.flatMap((page) => page.items) ?? []).map((run) => [run.id, run]));
  if (currentActiveRun) byId.set(currentActiveRun.id, currentActiveRun);
  const displayedRuns = [...byId.values()].sort((left, right) => left.conversationTurn - right.conversationTurn);
  const redoShot = data.redoCandidates.find((shot) => shot.artifactId === redoShotId) ?? null;
  const redoBound = !redoShotId || redoShot !== null;
  const reviewInstruction = review?.conversationId === conversationId && review.agentVersion === agent?.version
    && review.redoShotId === redoShotId && review.instruction === runInstruction.trim() ? review.instruction : null;
  const reviewSelection = review?.selectedIds ?? [];
  const preflightKey = (id: string | null) => ["run-preflight", data.projectId, agent?.id, agent?.version, id];
  const preflight = useQuery({
    queryKey: preflightKey(conversationId),
    queryFn: () => getRunPreflight(data.projectId, agent!.id, conversationId ?? undefined),
    enabled: false,
  });

  function setRunInstruction(instruction: string) {
    setDrafts((previous) => ({ ...previous, [draftKey]: { ...previous[draftKey] ?? EMPTY_DRAFT, instruction } }));
  }
  function setRedoShotId(next: string) {
    setDrafts((previous) => ({ ...previous, [draftKey]: { ...previous[draftKey] ?? EMPTY_DRAFT, redoShotId: next } }));
    setReview(null);
  }
  function selectInCache(conversation: AgentConversation) {
    queryClient.setQueryData<InfiniteData<AgentConversationList>>(conversationKey, (previous) => {
      if (!previous) return { pages: [{ items: [conversation], currentConversationId: conversation.id, nextCursor: null }], pageParams: [undefined] };
      return { ...previous, pages: previous.pages.map((page, index) => ({ ...page,
        currentConversationId: conversation.id,
        items: index === 0 && !previous.pages.some((entry) => entry.items.some((item) => item.id === conversation.id))
          ? [conversation, ...page.items] : page.items.map((item) => item.id === conversation.id ? conversation : item),
      })) };
    });
  }
  const createConversation = useMutation({
    mutationFn: async (request: { transferDraft: boolean }) => {
      void request;
      if (!agent) throw new Error("Agent 卡片不可用。");
      createKey.current ??= crypto.randomUUID();
      return createAgentConversation(data.projectId, agent.id, createKey.current);
    },
    onSuccess: (conversation, input) => {
      createKey.current = null;
      if (input.transferDraft) setDrafts((previous) => ({ ...previous,
        [conversation.id]: previous[EMPTY_CONVERSATION_DRAFT] ?? EMPTY_DRAFT,
        [EMPTY_CONVERSATION_DRAFT]: EMPTY_DRAFT,
      }));
      selectInCache(conversation);
      setReview(null); runIntent.current = null; setView("chat");
      void queryClient.invalidateQueries({ queryKey: conversationKey });
    },
  });
  const selectConversation = useMutation({
    mutationFn: (id: string) => selectAgentConversation(data.projectId, agent!.id, id),
    onSuccess: (conversation) => {
      selectInCache(conversation);
      setReview(null); runIntent.current = null; setView("chat");
      void queryClient.invalidateQueries({ queryKey: conversationKey });
    },
  });
  const sessionBusy = createConversation.isPending || selectConversation.isPending;
  function saveRunInCache(run: AgentRun) {
    queryClient.setQueryData<InfiniteData<AgentRunList>>(
      ["conversation-runs", data.projectId, agent?.id, run.conversationId], (previous) => {
        if (!previous) return { pages: [{ items: [run], nextCursor: null }], pageParams: [undefined] };
        return { ...previous, pages: previous.pages.map((page, index) => ({ ...page,
          items: index === 0 ? [run, ...page.items.filter((item) => item.id !== run.id)]
            : page.items.filter((item) => item.id !== run.id),
        })) };
      });
  }
  const start = useMutation({
    mutationFn: ({ key, input }: { key: string; input: CreateRunRequest & { conversationId: string } }) =>
      createRun(data.projectId, key, input),
    onSuccess: async (run, submitted) => {
      saveRunInCache(run);
      runIntent.current = null;
      setDrafts((previous) => {
        const prior = previous[submitted.input.conversationId];
        if (!prior || prior.instruction.trim() !== submitted.input.instruction) return previous;
        return { ...previous, [submitted.input.conversationId]: { ...prior, instruction: "" } };
      });
      setReview((current) => current?.conversationId === submitted.input.conversationId ? null : current);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["snapshot", data.projectId] }),
        queryClient.invalidateQueries({ queryKey: conversationKey }),
        queryClient.invalidateQueries({ queryKey: ["conversation-runs", data.projectId, agent?.id, run.conversationId] }),
      ]);
    },
    onError: (error) => {
      if (error instanceof ApiError && ["AGENT_VERSION_CONFLICT", "MODEL_CONFIG_CONFLICT",
        "SYSTEM_PROMPT_CONFLICT", "CONVERSATION_VERSION_CONFLICT"].includes(error.code)) {
        runIntent.current = null; setReview(null);
        void queryClient.invalidateQueries({ queryKey: ["canvas", data.projectId] });
        void queryClient.invalidateQueries({ queryKey: conversationKey });
      }
    },
  });
  const stop = useMutation({
    mutationFn: (runId: string) => cancelRun(data.projectId, runId),
    onSuccess: async (run) => {
      saveRunInCache(run);
      await queryClient.invalidateQueries({ queryKey: ["snapshot", data.projectId] });
      await queryClient.invalidateQueries({ queryKey: ["conversation-runs", data.projectId, agent?.id, run.conversationId] });
    },
  });
  useEffect(() => {
    if (reviewInstruction && bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
  }, [reviewInstruction, preflight.data, preflight.isFetching]);
  useEffect(() => {
    if (view !== "chat" || !conversationId || !runs.isSuccess || positionedConversation.current === conversationId) return;
    if (bodyRef.current) bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    positionedConversation.current = conversationId;
  }, [conversationId, runs.isSuccess, view]);
  if (!agent) return null;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const values = new FormData(event.currentTarget);
    data.onUpdateAgent(agent as Agent, String(values.get("name")), String(values.get("instruction")));
  }
  async function submitRun() {
    if (!agent || data.activeRun || start.isPending || sessionBusy || preflight.isFetching
      || conversations.isPending || conversations.isError || !redoBound) return;
    const instruction = runInstruction.trim();
    if (!instruction) return;
    let targetId = conversationId;
    if (!targetId) {
      try { targetId = (await createConversation.mutateAsync({ transferDraft: true })).id; }
      catch { return; }
    }
    setView("chat");
    setReview({ conversationId: targetId, instruction, agentVersion: agent.version,
      selectedIds: [...useCanvasStore.getState().selectedIds], redoShotId });
    try {
      await queryClient.fetchQuery({ queryKey: preflightKey(targetId), staleTime: 0,
        queryFn: () => getRunPreflight(data.projectId, agent.id, targetId) });
    } catch { /* The subscribed preflight query renders the failure and retains the draft. */ }
  }
  function confirmRun() {
    const reviewed = preflight.data;
    if (!agent || !conversationId || !reviewInstruction || !review || !reviewed || preflight.isFetching || preflight.isError
      || !reviewed.modelAvailable || !reviewed.toolCalling || data.activeRun || start.isPending || sessionBusy
      || reviewed.policySnapshot.systemPromptVersion == null || reviewed.conversationId !== conversationId
      || reviewed.conversationVersion == null || reviewed.agentVersion !== agent.version || reviewed.agentId !== agent.id) return;
    const input: CreateRunRequest & { conversationId: string } = {
      agentId: agent.id, conversationId, expectedConversationVersion: reviewed.conversationVersion,
      instruction: reviewInstruction, expectedAgentVersion: reviewed.agentVersion,
      expectedModelConfigSource: reviewed.policySnapshot.modelConfigSource,
      expectedModelConfigVersion: reviewed.policySnapshot.modelConfigVersion,
      expectedSystemPromptVersion: reviewed.policySnapshot.systemPromptVersion,
      selectedItemIds: reviewSelection,
      ...(redoShot ? { redoShotArtifactId: redoShot.artifactId } : {}),
    };
    const fingerprint = JSON.stringify(input);
    if (runIntent.current?.fingerprint !== fingerprint) runIntent.current = { fingerprint, key: crypto.randomUUID() };
    start.mutate({ key: runIntent.current.key, input });
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
        <button aria-label="会话列表" aria-pressed={view === "history"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "history" ? "chat" : "history")} title="会话列表" type="button"><ClockCounterClockwise size={18} /></button>
        <button aria-label="新建会话" className="agent-chat-icon nodrag" disabled={sessionBusy || conversations.isPending}
          onClick={() => createConversation.mutate({ transferDraft: false })} title="新建会话" type="button"><Plus size={18} /></button>
        <button aria-label="Agent 设置" aria-pressed={view === "settings"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "settings" ? "chat" : "settings")} type="button"><GearSix size={18} /></button>
      </header>
      <div className="agent-chat-context nodrag">
        <button onClick={() => setView("settings")} type="button">{agent.bindings.length} 个绑定输入</button>
        <button disabled={!data.outputCount} onClick={(event) => { event.stopPropagation(); data.onShowOutputs(agent); }} type="button"><ArrowSquareOut size={13} />查看产物{data.outputCount ? ` · ${data.outputCount}` : ""}</button>
      </div>
      <div className="agent-chat-session-heading"><span>{currentConversation?.title || (conversationId ? "当前会话" : "新会话")}</span>
        {createConversation.isPending ? <span>正在新建…</span> : <small>消息会保留在当前会话</small>}
      </div>
      {ownRun && ownRun.conversationId !== conversationId ? <div className="agent-chat-active-session">
        <span>另一会话正在运行，审批与停止操作保留在原会话。</span>
        <button type="button" disabled={sessionBusy} onClick={() => selectConversation.mutate(ownRun.conversationId)}>返回运行会话</button>
      </div> : null}
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
            {agent.bindings.length === 0 ? <p>没有额外绑定输入；仍可使用当前会话的上下文与产物。</p> : <ul>{agent.bindings.map((binding) =>
              <li key={binding.id}>Artifact {binding.artifactId}<br />Version {binding.selectedVersionId}</li>)}</ul>}
          </details>
          <div className="agent-chat-settings-actions">
            <button className="node-action" onClick={() => data.onToggleLocked(data.item)} type="button">{data.item.locked ? "解锁" : "锁定"}</button>
            <button className="node-action node-action-danger" onClick={() => data.onRemove(data.item)} type="button">移除卡片</button>
          </div>
        </section> : view === "history" ? <section aria-label="会话列表" className="agent-chat-history">
          <h4>会话列表</h4><p>同一会话连续使用上下文；新建会话会从空白开始，已有消息仍保留。</p>
          {conversations.isPending ? <CanvasLoadingState compact label="正在读取会话…" /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><button className="node-action" onClick={() => void conversations.refetch()} type="button">重试会话列表</button></> : null}
          {conversations.isSuccess && !sessions.length ? <p>尚无会话。</p> : null}
          {sessions.map((session) => <button className="agent-chat-history-item" key={session.id}
            aria-current={session.id === conversationId ? "true" : undefined} disabled={sessionBusy}
            onClick={() => selectConversation.mutate(session.id)} type="button">
            <span>{session.title || "新会话"}</span><small>{session.turnCount} 轮消息 · {new Date(session.updatedAt).toLocaleString()}{session.id === conversationId ? " · 当前会话" : ""}</small>
          </button>)}
          {conversations.hasNextPage ? <button className="node-action" disabled={conversations.isFetchingNextPage}
            onClick={() => void conversations.fetchNextPage()} type="button">{conversations.isFetchingNextPage ? "读取中…" : "更多会话"}</button> : null}
        </section> : <>
          {conversations.isPending ? <CanvasLoadingState compact label="正在恢复会话…" /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><button className="node-action" onClick={() => void conversations.refetch()} type="button">重试会话列表</button></> : null}
          {conversationId && runs.isPending ? <CanvasLoadingState compact label="正在读取对话…" /> : null}
          {conversationId && runs.error ? <><ChatError error={runs.error} /><button className="node-action" onClick={() => void runs.refetch()} type="button">重试消息</button></> : null}
          {runs.hasNextPage ? <button className="agent-chat-earlier" disabled={runs.isFetchingNextPage}
            onClick={() => void runs.fetchNextPage()} type="button">{runs.isFetchingNextPage ? "读取中…" : "加载更早的消息"}</button> : null}
          {!displayedRuns.length && !reviewInstruction && conversations.isSuccess && (!conversationId || runs.isSuccess) ? <div className="agent-chat-empty">
            <Sparkle size={30} weight="duotone" /><h4>从一个想法开始</h4>
            <p>描述你想创作的内容，我会根据绑定素材规划。后续消息会延续当前会话的上下文。</p>
            <button type="button" onClick={() => setRunInstruction("根据绑定素材规划三个镜头，并提出关键帧生成计划。")}>规划三个镜头 <ArrowUp size={14} /></button>
          </div> : null}
          {displayedRuns.map((run) => <AgentRunConversation key={run.id} projectId={data.projectId}
            run={run} active={ownRun?.id === run.id} />)}
          {reviewInstruction ? <AgentChatMessage role="user" label="待发送">{reviewInstruction}</AgentChatMessage> : null}
      {reviewInstruction !== null ? (
        <AgentChatApproval title="运行前确认" description="确认本次任务的模型、输入与使用限额。">
          {preflight.isPending || preflight.isFetching ? <p className="mt-2">正在核对模型与输入…</p> : null}
          {preflight.error ? <ChatError error={preflight.error} /> : null}
          {preflight.data && !preflight.isFetching && !preflight.isError ? (
            <>
              <p className="mt-2 break-words">本次任务：{reviewInstruction}</p>
              <p>本会话已有 {preflight.data.conversationTurnCount} 轮消息，将继承 {preflight.data.inheritedBindingCount} 个精确素材绑定。</p>
              {preflight.data.memoryTruncated ? <p>保留早期背景和最近交流，部分历史未纳入本轮；完整记录仍可查看。</p> : null}
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
                !redoBound || preflight.data.conversationId !== conversationId || preflight.data.conversationVersion == null} onClick={confirmRun} type="button">
                {start.isPending ? "启动中…" : "确认开始规划"}
              </button>
              <button className="node-action" disabled={start.isPending} onClick={() => setReview(null)} type="button">返回修改</button>
            </>
          ) : null}
        </AgentChatApproval>
      ) : null}
        </>}
        {createConversation.error ? <ChatError error={createConversation.error} /> : null}
        {selectConversation.error ? <ChatError error={selectConversation.error} /> : null}
        {start.error && start.variables?.input.conversationId === conversationId ? <ChatError error={start.error} /> : null}
        {stop.error && displayedRuns.some((run) => run.id === stop.variables) ? <ChatError error={stop.error} /> : null}
      </div>
      <form aria-label="发送新任务" className="agent-chat-composer nodrag nowheel nopan" onSubmit={(event) => { event.preventDefault(); void submitRun(); }}>
        <label className="agent-chat-scope">运行范围<select onChange={(event) => setRedoShotId(event.target.value)} value={redoShotId}>
          <option value="">基于绑定输入创作</option>
          {data.redoCandidates.map((shot) => <option key={shot.artifactId} value={shot.artifactId}>仅重做「{shot.title}」</option>)}
        </select></label>
        {redoShot ? <p className="agent-chat-hint">仅规划「{redoShot.title}」的新版本；图片和视频分别审批。</p> : null}
        {!redoBound ? <p role="alert">目标镜头版本已变化，请重新绑定。</p> : null}
        <textarea aria-label="本次任务" disabled={conversations.isPending || (conversations.isError && !conversations.data)} maxLength={MAX_INSTRUCTION} onChange={(event) => {
          setRunInstruction(event.target.value); setReview(null);
        }} onKeyDown={(event) => {
          // IME Enter confirms Chinese input; only an explicit modifier shortcut submits.
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} placeholder="描述想创作的内容…" required value={runInstruction} rows={2} />
        <div className="agent-chat-composer-footer"><span>{data.activeRun && !ownRun ? "请等待其他 Agent 任务结束" : "同一会话共享上下文 · ⌘ / Ctrl + Enter"}</span>
          {currentActiveRun ? <button aria-label="停止" className="agent-chat-send agent-chat-stop" disabled={stop.isPending || currentActiveRun.status === "CANCEL_REQUESTED"}
            onClick={() => stop.mutate(currentActiveRun.id)} title="停止后续编排" type="button"><Square weight="fill" size={14} /></button>
            : <button aria-label="发送" className="agent-chat-send" disabled={Boolean(data.activeRun) || start.isPending || preflight.isFetching || sessionBusy || conversations.isPending || conversations.isError || !redoBound || !runInstruction.trim()}
              title="发送并检查运行范围" type="submit"><ArrowUp size={20} weight="bold" /></button>}
        </div>
      </form>
    </article>
  </>;
}

function ChatError({ error }: { error: Error }) {
  return <p className="agent-chat-error" role="alert">{error instanceof ApiError ? error.message : "读取或提交失败，请重试；输入内容已保留。"}</p>;
}
