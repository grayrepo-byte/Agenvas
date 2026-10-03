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
type CreateRunRequest,type SkillSelection
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
import { AgentRunSkillControls,AgentSkillSettings,RunSkillSummary,useAgentSkillSelection } from "../skills/AgentSkillControls";

export const AGENT_CHAT_WIDTH = 460;
export const AGENT_CHAT_HEIGHT = 600;
export const AGENT_CHAT_MIN_WIDTH = 360;
export const AGENT_CHAT_MIN_HEIGHT = 420;
const MAX_AGENT_NAME = 120;
const MAX_INSTRUCTION = 8000;
const BOTTOM_FOLLOW_THRESHOLD_PX = 24;

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
  agentVersion: number; skillSelection: SkillSelection; skillFingerprint: string;
};

/** Conversations persist across messages; each submitted message retains its own Run. */
export function AgentChatCard({ data, selected }: { data: AgentChatCardData; selected: boolean }) {
  useLocale();
  const queryClient = useQueryClient();
  const agent = data.item.agent;
  const skillState = useAgentSkillSelection(data.projectId,agent);
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
  const transcriptRef = useRef<HTMLDivElement>(null);
  const followingBottom = useRef(true);
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
    && review.skillFingerprint === skillState.fingerprint && review.instruction === runInstruction.trim() ? review.instruction : null;
  const reviewSelection = review?.selectedIds ?? [];
  const preflightKey = (id: string | null) => {
    const base = ["run-preflight", data.projectId, agent?.id, agent?.version, id];
    return [...base, skillState.fingerprint];
  };
  const preflight = useQuery({
    queryKey: preflightKey(conversationId),
    queryFn: () => getRunPreflight(data.projectId, agent!.id, conversationId ?? undefined, review?.skillSelection ?? skillState.selection),
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
      if (!agent) throw new Error(t("agent.chat.cardUnavailable"));
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
    if (reviewInstruction && bodyRef.current) {
      followingBottom.current = true;
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
  }, [reviewInstruction]);
  useEffect(() => {
    if (view !== "chat" || !conversationId || !runs.isSuccess || positionedConversation.current === conversationId) return;
    if (bodyRef.current) {
      followingBottom.current = true;
      bodyRef.current.scrollTop = bodyRef.current.scrollHeight;
    }
    positionedConversation.current = conversationId;
  }, [conversationId, runs.isSuccess, view]);
  useEffect(() => {
    const body = bodyRef.current;
    const transcript = transcriptRef.current;
    if (view !== "chat" || !body || !transcript) return;
    // Observe layout rather than token events: child stream updates never need to
    // rerender the card. Remember the user's position before content grows.
    const observer = new ResizeObserver(() => {
      if (followingBottom.current) body.scrollTop = body.scrollHeight;
    });
    observer.observe(transcript);
    observer.observe(body);
    return () => observer.disconnect();
  }, [agent?.id, conversationId, view]);
  if (!agent) return null;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (configuration) data.onUpdateAgent(configuration.base, configuration.name, configuration.instruction);
  }
  async function submitRun() {
    if (!agent || data.activeRun || start.isPending || sessionBusy || preflight.isFetching
      || conversations.isPending || conversations.isError || !skillState.ready) return;
    const instruction = runInstruction.trim();
    if (!instruction) return;
    let targetId = conversationId;
    if (!targetId) {
      try { targetId = (await createConversation.mutateAsync({ transferDraft: true })).id; }
      catch { return; }
    }
    setView("chat");
    const skillSelection = skillState.selection;
    setReview({ conversationId: targetId, instruction, agentVersion: agent.version, skillSelection, skillFingerprint: skillState.fingerprint,
      selectedIds: [...useCanvasStore.getState().selectedIds] });
    try {
      await queryClient.fetchQuery({ queryKey: preflightKey(targetId), staleTime: 0,
        queryFn: () => getRunPreflight(data.projectId, agent.id, targetId, skillSelection) });
    } catch { /* The subscribed preflight query renders the failure and retains the draft. */ }
  }
  function confirmRun() {
    const reviewed = preflight.data;
    if (!agent || !conversationId || !reviewInstruction || !review || !reviewed || preflight.isFetching || preflight.isError
      || !reviewed.modelAvailable || !reviewed.toolCalling || data.activeRun || start.isPending || sessionBusy
      || reviewed.policySnapshot.systemPromptVersion == null || reviewed.conversationId !== conversationId
      || reviewed.creativeSkill?.installed === false || reviewed.conversationVersion == null || reviewed.agentVersion !== agent.version || reviewed.agentId !== agent.id) return;
    const input: CreateRunRequest & { conversationId: string } = {
      agentId: agent.id, conversationId, expectedConversationVersion: reviewed.conversationVersion,
      instruction: reviewInstruction, expectedAgentVersion: reviewed.agentVersion,
      expectedModelConfigSource: reviewed.policySnapshot.modelConfigSource,
      expectedModelConfigVersion: reviewed.policySnapshot.modelConfigVersion,
      expectedSystemPromptVersion: reviewed.policySnapshot.systemPromptVersion,
      selectedItemIds: reviewSelection,
      skillSelection: review.skillSelection,
    };
    const fingerprint = JSON.stringify(input);
    if (runIntent.current?.fingerprint !== fingerprint) runIntent.current = { fingerprint, key: crypto.randomUUID() };
    start.mutate({ key: runIntent.current.key, input });
  }
  return <>
    <CanvasHandle id="agent-input" />
    <CanvasHandle id="agent-output" />
    <article aria-label={t("agent.chat.cardLabel", { "0": agent.name })} className={`agent-chat-card ${selected ? "agent-chat-card--selected" : ""}`}>
      <NodeResizer isVisible={selected && !data.item.locked} minHeight={AGENT_CHAT_MIN_HEIGHT}
        minWidth={AGENT_CHAT_MIN_WIDTH} onResizeEnd={(_, layout) => data.onResizeEnd(data.item.id, layout)} />
      <header className="agent-chat-header">
        <span className="agent-chat-avatar"><Sparkle weight="fill" size={18} /></span>
        <div className="agent-chat-heading"><h3>{agent.name}</h3>
          <span>{ownRun ? RUN_STATUS_LABELS[ownRun.status] : data.activeRun ? t("agent.chat.otherAgentRunning") : t("agent.chat.ready")}</span>
        </div>
        <Button variant="ghost" size="sm" aria-label={t("agent.chat.chat")} aria-pressed={view === "chat"} className="agent-chat-tab nodrag"
          onClick={() => setView("chat")} type="button">{t("agent.chat.conversation")}</Button>
        <Button variant="ghost" size="icon-sm" aria-label={t("agent.chat.sessions")} aria-pressed={view === "history"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "history" ? "chat" : "history")} title={t("agent.chat.sessions")} type="button"><ClockCounterClockwise size={18} /></Button>
        <Button variant="ghost" size="icon-sm" aria-label={t("agent.chat.createSession")} className="agent-chat-icon nodrag" disabled={sessionBusy || conversations.isPending}
          onClick={() => createConversation.mutate({ transferDraft: false })} title={t("agent.chat.createSession")} type="button"><Plus size={18} /></Button>
        <Button variant="ghost" size="icon-sm" aria-label={t("agent.chat.settings")} aria-pressed={view === "settings"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "settings" ? "chat" : "settings")} type="button"><GearSix size={18} /></Button>
      </header>
      <div className="agent-chat-context nodrag">
        <Button variant="ghost" size="xs" onClick={() => setView("settings")} type="button">{t("agent.chat.bindingCount", { "0": agent.bindings.length })}</Button>
        <Button variant="ghost" size="xs" disabled={!data.outputCount} onClick={(event) => { event.stopPropagation(); data.onShowOutputs(agent); }} type="button"><ArrowSquareOut size={13} />{t("agent.chat.viewArtifacts", { "0": data.outputCount ? ` · ${data.outputCount}` : "" })}</Button>
      </div>
      <div className="agent-chat-session-heading"><span>{currentConversation?.title || (conversationId ? t("agent.chat.currentSession") : t("agent.chat.newSession"))}</span>
        {createConversation.isPending ? <span>{t("agent.chat.creatingSession")}</span> : <small>{t("agent.chat.persistedMessagesHint")}</small>}
      </div>
      {ownRun && ownRun.conversationId !== conversationId ? <div className="agent-chat-active-session">
        <span>{t("agent.chat.otherSessionRunningHint")}</span>
        <Button variant="ghost" type="button" disabled={sessionBusy} onClick={() => selectConversation.mutate(ownRun.conversationId)}>{t("agent.chat.returnToRunningSession")}</Button>
      </div> : null}
      <div className="agent-chat-body nodrag nowheel nopan" ref={bodyRef} onScroll={(event) => {
        const body = event.currentTarget;
        followingBottom.current = body.scrollHeight - body.clientHeight - body.scrollTop <= BOTTOM_FOLLOW_THRESHOLD_PX;
      }}>
        <div className="agent-chat-transcript" ref={transcriptRef}>
        {view === "settings" ? <section aria-label={t("agent.chat.configuration")} className="agent-chat-settings">
          <p className="agent-chat-eyebrow">{agent.profileKey} · v{agent.profileVersion}</p>
          <form onSubmit={submit}>
            <label>{t("common.name")}<Input value={configuration?.name ?? agent.name} onChange={(event) => setConfiguration((current) => current ? { ...current, name: event.target.value } : current)} maxLength={MAX_AGENT_NAME} name="name" required /></label>
            <label>{t("common.instruction")}<Textarea value={configuration?.instruction ?? agent.instruction} onChange={(event) => setConfiguration((current) => current ? { ...current, instruction: event.target.value } : current)} maxLength={MAX_INSTRUCTION} name="instruction" required rows={4} /></label>
            <Button size="sm" disabled={data.updatingAgent} type="submit">{data.updatingAgent ? t("common.saving") : t("common.saveConfig")}</Button>
            {configuration && agent.version !== configuration.base.version ? <p role="status">
              {t("common.versionConflict")}<Button variant="ghost" type="button"
                onClick={() => setConfiguration({ base: agent, name: agent.name, instruction: agent.instruction })}>{t("common.refreshVersion")}</Button>
            </p> : null}
            {data.updateAgentError ? <ChatError error={data.updateAgentError} /> : null}
          </form>
          <AgentSkillSettings projectId={data.projectId} agent={agent} state={skillState} onChanged={()=>{setReview(null);runIntent.current=null;}} />
          <details className="agent-chat-binding-list"><summary>{t("agent.chat.explicitInputs", { "0": agent.bindings.length })}</summary>
            {agent.bindings.length === 0 ? <p>{t("agent.chat.noExtraInputsHint")}</p> : <ul>{agent.bindings.map((binding) =>
              <li key={binding.id}>Artifact {binding.artifactId}<br />Version {binding.selectedVersionId}</li>)}</ul>}
          </details>
          <div className="agent-chat-settings-actions">
            <Button variant="outline" size="sm" onClick={() => data.onToggleLocked(data.item)} type="button">{data.item.locked ? t("canvas.card.unlock") : t("canvas.card.lock")}</Button>
            <Button variant="outline" size="sm" className="node-action-danger" onClick={() => data.onRemove(data.item)} type="button">{t("canvas.card.remove")}</Button>
          </div>
        </section> : view === "history" ? <section aria-label={t("agent.chat.sessions")} className="agent-chat-history">
          <h4>{t("agent.chat.sessions")}</h4><p>{t("agent.chat.sessionContextHint")}</p>
          {conversations.isPending ? <CanvasLoadingState compact label={t("agent.chat.sessionLoading")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="outline" size="sm" onClick={() => void conversations.refetch()} type="button">{t("agent.chat.retrySessions")}</Button></> : null}
          {conversations.isSuccess && !sessions.length ? <p>{t("agent.chat.sessionsEmpty")}</p> : null}
          {sessions.map((session) => <Button variant="ghost" className="agent-chat-history-item" key={session.id}
            aria-current={session.id === conversationId ? "true" : undefined} disabled={sessionBusy}
            onClick={() => selectConversation.mutate(session.id)} type="button">
            <span>{session.title || t("agent.chat.newSession")}</span><small>{t("agent.chat.conversationSummary", { "0": session.turnCount, "1": new Date(session.updatedAt).toLocaleString(getFormatLocale()), "2": session.id === conversationId ? t("agent.chat.currentSessionSuffix") : "" })}</small>
          </Button>)}
          {conversations.hasNextPage ? <Button variant="outline" size="sm" disabled={conversations.isFetchingNextPage}
            onClick={() => void conversations.fetchNextPage()} type="button">{conversations.isFetchingNextPage ? t("common.loading") : t("agent.chat.moreSessions")}</Button> : null}
        </section> : <>
          {conversations.isPending ? <CanvasLoadingState compact label={t("agent.chat.restoringSession")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="outline" size="sm" onClick={() => void conversations.refetch()} type="button">{t("agent.chat.retrySessions")}</Button></> : null}
          {conversationId && runs.isPending ? <CanvasLoadingState compact label={t("agent.chat.conversationLoading")} /> : null}
          {conversationId && runs.error ? <><ChatError error={runs.error} /><Button variant="outline" size="sm" onClick={() => void runs.refetch()} type="button">{t("agent.chat.retryMessages")}</Button></> : null}
          {runs.hasNextPage ? <Button variant="ghost" className="agent-chat-earlier" disabled={runs.isFetchingNextPage}
            onClick={() => void runs.fetchNextPage()} type="button">{runs.isFetchingNextPage ? t("common.loading") : t("agent.chat.loadEarlier")}</Button> : null}
          {!displayedRuns.length && !reviewInstruction && conversations.isSuccess && (!conversationId || runs.isSuccess) ? <div className="agent-chat-empty">
            <Sparkle size={30} weight="duotone" /><h4>{t("agent.chat.emptyTitle")}</h4>
            <p>{t("agent.chat.instructionHint")}</p>
          </div> : null}
          {displayedRuns.map((run) => <AgentRunConversation key={run.id} projectId={data.projectId}
            run={run} active={ownRun?.id === run.id} />)}
          {reviewInstruction ? <AgentChatMessage role="user" label={t("agent.chat.pending")}>{reviewInstruction}</AgentChatMessage> : null}
      {reviewInstruction !== null ? (
        <AgentChatApproval title={t("agent.chat.preflightTitle")} description={t("agent.chat.preflightHint")} className="agent-chat-panel"
          footer={preflight.data && !preflight.isFetching && !preflight.isError ? <div className="agent-chat-panel-actions">
            <Button variant="outline" size="sm" className="agent-chat-panel-secondary" disabled={start.isPending} onClick={() => setReview(null)} type="button">{t("agent.chat.editAgain")}</Button>
            <Button size="sm" className="agent-chat-panel-primary" disabled={Boolean(data.activeRun) || start.isPending ||
              preflight.data.agentVersion !== agent.version || !preflight.data.modelAvailable ||
              !preflight.data.toolCalling || preflight.data.policySnapshot.systemPromptVersion == null ||
              preflight.data.conversationId !== conversationId || preflight.data.conversationVersion == null || preflight.data.creativeSkill?.installed === false} onClick={confirmRun} type="button">
              {start.isPending ? t("agent.chat.starting") : t("agent.chat.confirmStart")}
            </Button>
          </div> : undefined}>
          {preflight.isPending || preflight.isFetching ? <p className="mt-2">{t("agent.chat.preflightLoading")}</p> : null}
          {preflight.error ? <ChatError error={preflight.error} /> : null}
          {preflight.data && !preflight.isFetching && !preflight.isError ? (
            <>
              {preflight.data.creativeSkill ? <RunSkillSummary skill={preflight.data.creativeSkill} /> : null}
              <p className="mt-2 break-words">{t("agent.chat.taskSummary", { "0": reviewInstruction })}</p>
              <p>{t("agent.chat.inheritedContextHint", { "0": preflight.data.conversationTurnCount, "1": preflight.data.inheritedBindingCount })}</p>
              {preflight.data.memoryTruncated ? <p>{t("agent.chat.contextTruncatedHint")}</p> : null}
              <p className="mt-1 break-words">{t("agent.chat.instructionSummary", { "0": preflight.data.agentInstruction })}</p>
              <p className="mt-1">{t("agent.chat.modelSummary", { "0": preflight.data.modelAvailable
                ? `${preflight.data.providerAdapter ?? t("agent.chat.adapterUnknown")} / ${preflight.data.modelId ?? t("agent.chat.modelMissing")}`
                : t("agent.chat.chatModelMissing") })}</p>
              <details className="agent-chat-review-details"><summary>{t("agent.chat.inputLimits")}</summary>
              <p className="mt-1">{t("agent.chat.configSnapshotHint", { "0": preflight.data.policySnapshot.modelConfigSource, "1": preflight.data.policySnapshot.modelConfigVersion, "2": preflight.data.policySnapshot.systemPromptVersion ?? t("common.unknown") })}</p>
              <p className="mt-1">{t("agent.chat.bindingPreviewHint", { "0": preflight.data.bindings.length })}</p>
              <p className="mt-1">{t("agent.chat.textOnlyModelHint")}</p>
              <p className="mt-1 break-all">{t("agent.chat.selectionHint", { "0": reviewSelection.length, "1": reviewSelection.length ? `（${reviewSelection.join("、")}）` : "" })}</p>
              {preflight.data.bindings.map((binding) => (
                <p className="mt-1 break-all" key={binding.selectedVersionId}>
                  {binding.artifactKind}「{binding.artifactTitle}」 · Artifact {binding.artifactId}
                  <br />Version {binding.selectedVersionId}
                </p>
              ))}
              <p className="mt-1">{t("agent.chat.runLimitsHint", { "0": preflight.data.policySnapshot.maxModelTurns, "1": preflight.data.policySnapshot.maxToolExecutions })}</p>
              </details>
              {!preflight.data.toolCalling && preflight.data.modelAvailable ?
                <p className="mt-1 text-red-700">{t("agent.chat.toolsUnsupported")}</p> : null}
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
      </div>
      <form aria-label={t("agent.chat.sendTask")} className="agent-chat-composer nodrag nowheel nopan" onSubmit={(event) => { event.preventDefault(); void submitRun(); }}>
        <Textarea aria-label={t("agent.chat.currentTask")} disabled={conversations.isPending || (conversations.isError && !conversations.data)} maxLength={MAX_INSTRUCTION} onChange={(event) => {
          setRunInstruction(event.target.value); setReview(null);
        }} onKeyDown={(event) => {
          // IME Enter confirms Chinese input; only an explicit modifier shortcut submits.
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} placeholder={t("agent.chat.instructionPlaceholder")} required value={runInstruction} rows={2} />
        <div className="agent-chat-composer-footer">
          <div className="agent-chat-composer-actions">
            <AgentRunSkillControls projectId={data.projectId} agent={agent} state={skillState} onChanged={()=>{setReview(null);runIntent.current=null;}} />
          </div>
          {currentActiveRun ? <Button variant="secondary" size="icon-sm" aria-label={t("agent.chat.stop")} className="agent-chat-send agent-chat-stop" disabled={stop.isPending || currentActiveRun.status === "CANCEL_REQUESTED"}
            onClick={() => stop.mutate(currentActiveRun.id)} title={t("agent.chat.stopOrchestration")} type="button"><Square weight="fill" size={14} /></Button>
            : <Button size="icon-sm" aria-label={t("agent.chat.send")} className="agent-chat-send" disabled={Boolean(data.activeRun) || start.isPending || preflight.isFetching || sessionBusy || conversations.isPending || conversations.isError || !runInstruction.trim()}
              title={t("agent.chat.confirmInputs")} type="submit"><ArrowUp size={20} weight="bold" /></Button>}
        </div>
      </form>
    </article>
  </>;
}

function ChatError({ error }: { error: Error }) {
  useLocale();
  return <p className="agent-chat-error" role="alert">{error instanceof ApiError ? error.message : t("agent.chat.requestFailed")}</p>;
}
