import { ArrowSquareOut,ArrowUp,ClockCounterClockwise,GearSix,Plus,Sparkle,Square } from "@phosphor-icons/react";
import { useInfiniteQuery,useMutation,useQuery,useQueryClient,type InfiniteData } from "@tanstack/react-query";
import { NodeResizer,type ResizeParams } from "@xyflow/react";
import { useEffect,useRef,useState,type FormEvent } from "react";
import {
ApiError,cancelRun,
createAgentConversation,
createRun,getRunPreflight,listAgentConversations,
listConversationRuns,
selectAgentConversation,
type Agent,
type AgentConversation,type AgentConversationList,
type AgentRun,type AgentRunList,
type CanvasItem,
type CreateRunRequest
} from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import "./AgentChatCard.css";
import { AgentChatApproval,AgentChatMessage } from "./AgentChatPrimitives";
import { AgentRunConversation,RUN_STATUS_LABELS } from "./AgentRunConversation";
import { CanvasHandle } from "./CanvasHandle";
import { useCanvasStore } from "./canvasStore";

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
const EMPTY_DRAFT = { instruction: "" };
type ConversationDraft = typeof EMPTY_DRAFT;
type RunReview = {
  conversationId: string; instruction: string; selectedIds: string[];
  agentVersion: number;
};

/** Conversations persist across messages; each submitted message retains its own Run. */
export function AgentChatCard({ data, selected }: { data: AgentChatCardData; selected: boolean }) {
  useLocale();
  const queryClient = useQueryClient();
  const agent = data.item.agent;
  const [configuration, setConfiguration] = useState(() => agent
    ? { base: agent, name: agent.name, instruction: agent.instruction } : null);
  const configurationDirty = configuration != null &&
    (configuration.name !== configuration.base.name || configuration.instruction !== configuration.base.instruction);
  useEffect(() => {
    if (agent && (!configuration || agent.id !== configuration.base.id ||
      (agent.version !== configuration.base.version && (!configurationDirty ||
        (agent.name === configuration.name && agent.instruction === configuration.instruction))))) {
      setConfiguration({ base: agent, name: agent.name, instruction: agent.instruction });
    }
  }, [agent, configuration, configurationDirty]);
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
  const reviewInstruction = review?.conversationId === conversationId && review.agentVersion === agent?.version
    && review.instruction === runInstruction.trim() ? review.instruction : null;
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
      if (!agent) throw new Error(t("Agent 卡片不可用。"));
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
    if (configuration) data.onUpdateAgent(configuration.base, configuration.name, configuration.instruction);
  }
  async function submitRun() {
    if (!agent || data.activeRun || start.isPending || sessionBusy || preflight.isFetching
      || conversations.isPending || conversations.isError) return;
    const instruction = runInstruction.trim();
    if (!instruction) return;
    let targetId = conversationId;
    if (!targetId) {
      try { targetId = (await createConversation.mutateAsync({ transferDraft: true })).id; }
      catch { return; }
    }
    setView("chat");
    setReview({ conversationId: targetId, instruction, agentVersion: agent.version,
      selectedIds: [...useCanvasStore.getState().selectedIds] });
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
    };
    const fingerprint = JSON.stringify(input);
    if (runIntent.current?.fingerprint !== fingerprint) runIntent.current = { fingerprint, key: crypto.randomUUID() };
    start.mutate({ key: runIntent.current.key, input });
  }
  return <>
    <CanvasHandle id="agent-input" />
    <CanvasHandle id="agent-output" />
    <article aria-label={t("{0} 聊天卡片", { "0": agent.name })} className={`agent-chat-card ${selected ? "agent-chat-card--selected" : ""}`}>
      <NodeResizer isVisible={selected && !data.item.locked} minHeight={AGENT_CHAT_MIN_HEIGHT}
        minWidth={AGENT_CHAT_MIN_WIDTH} onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />
      <header className="agent-chat-header">
        <span className="agent-chat-avatar"><Sparkle weight="fill" size={18} /></span>
        <div className="agent-chat-heading"><h3>{agent.name}</h3>
          <span>{ownRun ? RUN_STATUS_LABELS[ownRun.status] : data.activeRun ? t("其他 Agent 运行中") : t("准备就绪")}</span>
        </div>
        <Button variant="ghost" aria-label={t("聊天")} aria-pressed={view === "chat"} className="agent-chat-tab nodrag"
          onClick={() => setView("chat")} type="button">{t("对话")}</Button>
        <Button variant="ghost" aria-label={t("会话列表")} aria-pressed={view === "history"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "history" ? "chat" : "history")} title={t("会话列表")} type="button"><ClockCounterClockwise size={18} /></Button>
        <Button variant="ghost" aria-label={t("新建会话")} className="agent-chat-icon nodrag" disabled={sessionBusy || conversations.isPending}
          onClick={() => createConversation.mutate({ transferDraft: false })} title={t("新建会话")} type="button"><Plus size={18} /></Button>
        <Button variant="ghost" aria-label={t("Agent 设置")} aria-pressed={view === "settings"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "settings" ? "chat" : "settings")} type="button"><GearSix size={18} /></Button>
      </header>
      <div className="agent-chat-context nodrag">
        <Button variant="ghost" onClick={() => setView("settings")} type="button">{t("{0} 个绑定输入", { "0": agent.bindings.length })}</Button>
        <Button variant="ghost" disabled={!data.outputCount} onClick={(event) => { event.stopPropagation(); data.onShowOutputs(agent); }} type="button"><ArrowSquareOut size={13} />{t("查看产物{0}", { "0": data.outputCount ? ` · ${data.outputCount}` : "" })}</Button>
      </div>
      <div className="agent-chat-session-heading"><span>{currentConversation?.title || (conversationId ? t("当前会话") : t("新会话"))}</span>
        {createConversation.isPending ? <span>{t("正在新建…")}</span> : <small>{t("消息会保留在当前会话")}</small>}
      </div>
      {ownRun && ownRun.conversationId !== conversationId ? <div className="agent-chat-active-session">
        <span>{t("另一会话正在运行，停止操作保留在原会话。")}</span>
        <Button variant="ghost" type="button" disabled={sessionBusy} onClick={() => selectConversation.mutate(ownRun.conversationId)}>{t("返回运行会话")}</Button>
      </div> : null}
      <div className="agent-chat-body nodrag nowheel nopan" ref={bodyRef}>
        {view === "settings" ? <section aria-label={t("Agent 配置")} className="agent-chat-settings">
          <p className="agent-chat-eyebrow">{agent.profileKey} · v{agent.profileVersion}</p>
          <form onSubmit={submit}>
            <label>{t("名称")}<Input value={configuration?.name ?? agent.name} onChange={(event) => setConfiguration((current) => current ? { ...current, name: event.target.value } : current)} maxLength={MAX_AGENT_NAME} name="name" required /></label>
            <label>{t("指令")}<Textarea value={configuration?.instruction ?? agent.instruction} onChange={(event) => setConfiguration((current) => current ? { ...current, instruction: event.target.value } : current)} maxLength={MAX_INSTRUCTION} name="instruction" required rows={4} /></label>
            <Button variant="ghost" className="node-action" disabled={data.updatingAgent} type="submit">{data.updatingAgent ? t("保存中…") : t("保存配置")}</Button>
            {configuration && agent.version !== configuration.base.version ? <p role="status">
              {t("当前版本已更新，本地修改仍保留。")}<Button variant="ghost" type="button"
                onClick={() => setConfiguration({ base: agent, name: agent.name, instruction: agent.instruction })}>{t("载入最新版本")}</Button>
            </p> : null}
            {data.updateAgentError ? <ChatError error={data.updateAgentError} /> : null}
          </form>
          <details className="agent-chat-binding-list"><summary>{t("明确输入（{0}）", { "0": agent.bindings.length })}</summary>
            {agent.bindings.length === 0 ? <p>{t("没有额外绑定输入；仍可使用当前会话的上下文与产物。")}</p> : <ul>{agent.bindings.map((binding) =>
              <li key={binding.id}>Artifact {binding.artifactId}<br />Version {binding.selectedVersionId}</li>)}</ul>}
          </details>
          <div className="agent-chat-settings-actions">
            <Button variant="ghost" className="node-action" onClick={() => data.onToggleLocked(data.item)} type="button">{data.item.locked ? t("解锁") : t("锁定")}</Button>
            <Button variant="ghost" className="node-action node-action-danger" onClick={() => data.onRemove(data.item)} type="button">{t("移除卡片")}</Button>
          </div>
        </section> : view === "history" ? <section aria-label={t("会话列表")} className="agent-chat-history">
          <h4>{t("会话列表")}</h4><p>{t("同一会话连续使用上下文；新建会话会从空白开始，已有消息仍保留。")}</p>
          {conversations.isPending ? <CanvasLoadingState compact label={t("正在读取会话…")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="ghost" className="node-action" onClick={() => void conversations.refetch()} type="button">{t("重试会话列表")}</Button></> : null}
          {conversations.isSuccess && !sessions.length ? <p>{t("尚无会话。")}</p> : null}
          {sessions.map((session) => <Button variant="ghost" className="agent-chat-history-item" key={session.id}
            aria-current={session.id === conversationId ? "true" : undefined} disabled={sessionBusy}
            onClick={() => selectConversation.mutate(session.id)} type="button">
            <span>{session.title || t("新会话")}</span><small>{t("{0} 轮消息 · {1}{2}", { "0": session.turnCount, "1": new Date(session.updatedAt).toLocaleString(getFormatLocale()), "2": session.id === conversationId ? t(" · 当前会话") : "" })}</small>
          </Button>)}
          {conversations.hasNextPage ? <Button variant="ghost" className="node-action" disabled={conversations.isFetchingNextPage}
            onClick={() => void conversations.fetchNextPage()} type="button">{conversations.isFetchingNextPage ? t("读取中…") : t("更多会话")}</Button> : null}
        </section> : <>
          {conversations.isPending ? <CanvasLoadingState compact label={t("正在恢复会话…")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="ghost" className="node-action" onClick={() => void conversations.refetch()} type="button">{t("重试会话列表")}</Button></> : null}
          {conversationId && runs.isPending ? <CanvasLoadingState compact label={t("正在读取对话…")} /> : null}
          {conversationId && runs.error ? <><ChatError error={runs.error} /><Button variant="ghost" className="node-action" onClick={() => void runs.refetch()} type="button">{t("重试消息")}</Button></> : null}
          {runs.hasNextPage ? <Button variant="ghost" className="agent-chat-earlier" disabled={runs.isFetchingNextPage}
            onClick={() => void runs.fetchNextPage()} type="button">{runs.isFetchingNextPage ? t("读取中…") : t("加载更早的消息")}</Button> : null}
          {!displayedRuns.length && !reviewInstruction && conversations.isSuccess && (!conversationId || runs.isSuccess) ? <div className="agent-chat-empty">
            <Sparkle size={30} weight="duotone" /><h4>{t("从一个想法开始")}</h4>
            <p>{t("描述你想创作的内容，我会根据绑定素材创建或修改文字产物并整理画布。后续消息会延续当前会话的上下文。")}</p>
          </div> : null}
          {displayedRuns.map((run) => <AgentRunConversation key={run.id} projectId={data.projectId}
            run={run} active={ownRun?.id === run.id} />)}
          {reviewInstruction ? <AgentChatMessage role="user" label={t("待发送")}>{reviewInstruction}</AgentChatMessage> : null}
      {reviewInstruction !== null ? (
        <AgentChatApproval title={t("运行前确认")} description={t("确认本次任务的模型、输入与使用限额。")} className="agent-chat-panel"
          footer={preflight.data && !preflight.isFetching && !preflight.isError ? <div className="agent-chat-panel-actions">
            <Button variant="ghost" className="agent-chat-panel-secondary" disabled={start.isPending} onClick={() => setReview(null)} type="button">{t("返回修改")}</Button>
            <Button variant="ghost" className="agent-chat-panel-primary" disabled={Boolean(data.activeRun) || start.isPending ||
              preflight.data.agentVersion !== agent.version || !preflight.data.modelAvailable ||
              !preflight.data.toolCalling || preflight.data.policySnapshot.systemPromptVersion == null ||
              preflight.data.conversationId !== conversationId || preflight.data.conversationVersion == null} onClick={confirmRun} type="button">
              {start.isPending ? t("启动中…") : t("确认开始")}
            </Button>
          </div> : undefined}>
          {preflight.isPending || preflight.isFetching ? <p className="mt-2">{t("正在核对模型与输入…")}</p> : null}
          {preflight.error ? <ChatError error={preflight.error} /> : null}
          {preflight.data && !preflight.isFetching && !preflight.isError ? (
            <>
              <p className="mt-2 break-words">{t("本次任务：{0}", { "0": reviewInstruction })}</p>
              <p>{t("本会话已有 {0} 轮消息，将继承 {1} 个精确素材绑定。", { "0": preflight.data.conversationTurnCount, "1": preflight.data.inheritedBindingCount })}</p>
              {preflight.data.memoryTruncated ? <p>{t("保留早期背景和最近交流，部分历史未纳入本轮；完整记录仍可查看。")}</p> : null}
              <p className="mt-1 break-words">{t("Agent 指令：{0}", { "0": preflight.data.agentInstruction })}</p>
              <p className="mt-1">{t("模型：{0}", { "0": preflight.data.modelAvailable
                ? `${preflight.data.providerAdapter ?? t("未知适配器")} / ${preflight.data.modelId ?? t("未声明模型 ID")}`
                : t("未配置 ChatModel，无法启动运行") })}</p>
              <details className="agent-chat-review-details"><summary>{t("输入与运行限额")}</summary>
              <p className="mt-1">{t("模型配置：{0} v{1}；系统提示词 v{2}。确认后若配置或规则变化，需重新检查。", { "0": preflight.data.policySnapshot.modelConfigSource, "1": preflight.data.policySnapshot.modelConfigVersion, "2": preflight.data.policySnapshot.systemPromptVersion ?? t("未知") })}</p>
              <p className="mt-1">{t("精确绑定输入：{0} 个版本；首轮只发送有上限的内容预览，不发送图片字节。", { "0": preflight.data.bindings.length })}</p>
              <p className="mt-1">{t("当前模型看不到图片像素、视频帧或音频，只能依据文字与元数据工作；图片和视频一律在对应卡片上直连发起生成。")}</p>
              <p className="mt-1 break-all">{t("当前选择：{0} 张卡片{1}；仅作为操作意图，不扩大 Agent 权限。", { "0": reviewSelection.length, "1": reviewSelection.length ? `（${reviewSelection.join("、")}）` : "" })}</p>
              {preflight.data.bindings.map((binding) => (
                <p className="mt-1 break-all" key={binding.selectedVersionId}>
                  {binding.artifactKind}「{binding.artifactTitle}」 · Artifact {binding.artifactId}
                  <br />Version {binding.selectedVersionId}
                </p>
              ))}
              <p className="mt-1">{t("还会发送项目名称与画幅。模型调用最多 {0} 轮、工具最多 {1} 次；Agent 不会触发生成。", { "0": preflight.data.policySnapshot.maxModelTurns, "1": preflight.data.policySnapshot.maxToolExecutions })}</p>
              </details>
              {!preflight.data.toolCalling && preflight.data.modelAvailable ?
                <p className="mt-1 text-red-700">{t("当前模型未确认支持工具调用，无法运行。")}</p> : null}
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
      <form aria-label={t("发送新任务")} className="agent-chat-composer nodrag nowheel nopan" onSubmit={(event) => { event.preventDefault(); void submitRun(); }}>
        <Textarea aria-label={t("本次任务")} disabled={conversations.isPending || (conversations.isError && !conversations.data)} maxLength={MAX_INSTRUCTION} onChange={(event) => {
          setRunInstruction(event.target.value); setReview(null);
        }} onKeyDown={(event) => {
          // IME Enter confirms Chinese input; only an explicit modifier shortcut submits.
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} placeholder={t("描述想创作的内容…")} required value={runInstruction} rows={2} />
        <div className="agent-chat-composer-footer"><span>{data.activeRun && !ownRun ? t("请等待其他 Agent 任务结束") : t("同一会话共享上下文 · ⌘ / Ctrl + Enter")}</span>
          {currentActiveRun ? <Button variant="ghost" aria-label={t("停止")} className="agent-chat-send agent-chat-stop" disabled={stop.isPending || currentActiveRun.status === "CANCEL_REQUESTED"}
            onClick={() => stop.mutate(currentActiveRun.id)} title={t("停止后续编排")} type="button"><Square weight="fill" size={14} /></Button>
            : <Button variant="ghost" aria-label={t("发送")} className="agent-chat-send" disabled={Boolean(data.activeRun) || start.isPending || preflight.isFetching || sessionBusy || conversations.isPending || conversations.isError || !runInstruction.trim()}
              title={t("发送并确认本次输入")} type="submit"><ArrowUp size={20} weight="bold" /></Button>}
        </div>
      </form>
    </article>
  </>;
}

function ChatError({ error }: { error: Error }) {
  useLocale();
  return <p className="agent-chat-error" role="alert">{error instanceof ApiError ? error.message : t("读取或提交失败，请重试；输入内容已保留。")}</p>;
}
