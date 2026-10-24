import { ArrowLeft,ArrowUp,ArrowUpRight,ClockCounterClockwise,FilmStrip,GearSix,MagicWand,Plus,Sparkle,Square,Stack } from "@/shared/ui/icons";
import { useInfiniteQuery,useMutation,useQuery,useQueryClient,type InfiniteData } from "@tanstack/react-query";
import { useEffect,useId,useRef,useState,type FormEvent,type ReactNode } from "react";
import {
ApiError,HTTP_STATUS,cancelRun,
createAgentConversation,
createRun,getRunPreflight,listAgentConversations,
listConversationRuns,listRunMediaApprovals, listRunTasks,
selectAgentConversation,
type Agent,
type AgentConversation,type AgentConversationList,
type AgentRun,type AgentRunList,
type CreateRunRequest
} from "../../shared/api/client";
import { getFormatLocale,t,useLocale } from "../../shared/i18n";
import { LoadingState as CanvasLoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Textarea } from "../../shared/ui/primitives/textarea";
import { Badge } from "../../shared/ui/primitives/badge";
import { Empty,EmptyDescription,EmptyHeader,EmptyTitle } from "../../shared/ui/primitives/empty";
import { Field,FieldGroup,FieldLabel } from "../../shared/ui/primitives/field";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { MessageScroller,MessageScrollerButton,MessageScrollerContent,MessageScrollerItem,MessageScrollerProvider,MessageScrollerViewport,useMessageScroller } from "../../shared/ui/primitives/message-scroller";
import "./AgentChatPanel.css";
import { AgentRunConversation,RUN_STATUS_LABELS } from "./AgentRunConversation";
import { AgentModelRetryNotice } from "./AgentModelRetryNotice";
import { BlockedRunNotice } from "./BlockedRunNotice";
import { useCanvasStore } from "./canvasStore";
import { AgentRunSkillControls,AgentSkillSettings,useAgentSkillSelection } from "../skills/AgentSkillControls";

const MAX_AGENT_NAME = 120;
const MAX_INSTRUCTION = 8000;
const TERMINAL_RUN_STATUSES: ReadonlySet<AgentRun["status"]> = new Set(["CANCELED", "FAILED", "SUCCEEDED"]);

/** An explicit workspace action retains its own intent without replacing the conversation draft. */
export type AgentChatRunRequest = {
  id: string;
  instruction: string;
  selectedItemIds: readonly string[];
};

export type AgentChatPanelData = {
  agent: Agent;
  projectId: string;
  activeRun: AgentRun | null;
  canvasNodeCount: number;
  selectedNodeCount: number;
  onUpdateAgent: (agent: Agent, name: string, instruction: string) => void;
  updatingAgent: boolean;
  updateAgentError?: Error | null;
  runRequest?: AgentChatRunRequest | null;
  onRunRequestHandled?: (id: string) => void;
};


const EMPTY_CONVERSATION_DRAFT = "new-conversation";
const EMPTY_DRAFT = { instruction: "" };
type ConversationDraft = typeof EMPTY_DRAFT;

function resolveConversationRuns(snapshotRun: AgentRun | null, listedRuns: readonly AgentRunList["items"][number][],
  agentId: string | undefined, conversationId: string | null) {
  const byId = new Map(listedRuns.map((run) => [run.id, run]));
  const snapshotOwnRun = snapshotRun?.agentInstanceId === agentId ? snapshotRun : null;
  const listedOwnRun = snapshotOwnRun ? byId.get(snapshotOwnRun.id) : undefined;
  // A Run list or decision response may arrive before the refreshed project snapshot.
  // Run summaries expose updatedAt rather than version; the list wins equal timestamps.
  const resolvedOwnRun = listedOwnRun && snapshotOwnRun
    && Date.parse(listedOwnRun.updatedAt) >= Date.parse(snapshotOwnRun.updatedAt)
    ? listedOwnRun : snapshotOwnRun;
  const resolvedProjectRun = resolvedOwnRun ?? snapshotRun;
  const projectActiveRun = resolvedProjectRun && !TERMINAL_RUN_STATUSES.has(resolvedProjectRun.status)
    ? resolvedProjectRun : null;
  const ownRun = projectActiveRun?.agentInstanceId === agentId ? projectActiveRun : null;
  const currentActiveRun = ownRun?.conversationId === conversationId ? ownRun : null;
  if (resolvedOwnRun?.conversationId === conversationId) byId.set(resolvedOwnRun.id, resolvedOwnRun);
  const displayedRuns = [...byId.values()].sort((left, right) => left.conversationTurn - right.conversationTurn);
  return { displayedRuns, projectActiveRun, ownRun, currentActiveRun };
}

/** Conversations persist across messages; each submitted message retains its own Run. */
export function AgentChatPanel({ data, headerActions }: {
  data: AgentChatPanelData; headerActions?: ReactNode;
}) {
  return <MessageScrollerProvider autoScroll defaultScrollPosition="start"><AgentChatPanelContent data={data} headerActions={headerActions} /></MessageScrollerProvider>;
}

function AgentChatPanelContent({ data, headerActions }: { data: AgentChatPanelData; headerActions?: ReactNode }) {
  useLocale();
  const scroller = useMessageScroller();
  const queryClient = useQueryClient();
  const agent = data.agent;
  const fieldId = useId();
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
  const positionedConversation = useRef<string | null>(null);
  const [drafts, setDrafts] = useState<Record<string, ConversationDraft>>({});
  const [preparing, setPreparing] = useState(false);
  const [submissionError, setSubmissionError] = useState<{ conversationId: string | null; error: Error } | null>(null);
  const submissionLock = useRef(false);
  const requestLifecycle = useRef({ mounted: true, projectId: data.projectId, revision: 0 });
  if (requestLifecycle.current.projectId !== data.projectId) {
    requestLifecycle.current.projectId = data.projectId;
    requestLifecycle.current.revision++;
  }
  useEffect(() => {
    requestLifecycle.current.mounted = true;
    return () => { requestLifecycle.current.mounted = false; };
  }, []);
  const attemptedRequestId = useRef<string | null>(null);
  const submitRequestedRun = useRef<(request: AgentChatRunRequest) => Promise<void>>(async () => {});
  const [view, setView] = useState<"chat" | "history" | "settings">("chat");
  const createKey = useRef<string | null>(null);
  const runIntent = useRef<{ fingerprint: string; key: string; input: CreateRunRequest & { conversationId: string }; requestId?: string } | null>(null);
  const conversationKey = ["agent-conversations", data.projectId, agent?.id];
  const conversations = useInfiniteQuery({
    queryKey: conversationKey,
    queryFn: ({ pageParam }) => listAgentConversations(data.projectId, agent!.id, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(agent),
  });
  const conversationId = conversations.data?.pages[0]?.currentConversationId ?? null;
  const draftKey = `${agent.id}:${conversationId ?? EMPTY_CONVERSATION_DRAFT}`;
  const draft = drafts[draftKey] ?? EMPTY_DRAFT;
  const runInstruction = draft.instruction;
  useEffect(() => {
    if (conversations.isPending) return;
    const composer = document.getElementById(`${fieldId}-composer`);
    const dialog = composer?.closest("[role=dialog]");
    if (dialog && dialog === document.activeElement && !composer?.closest("[hidden]")) composer?.focus({ preventScroll: true });
  }, [conversations.isPending,fieldId]);
  const sessions = conversations.data?.pages.flatMap((page) => page.items) ?? [];
  const currentConversation = sessions.find((session) => session.id === conversationId);
  const runsKey = ["conversation-runs", data.projectId, agent?.id, conversationId];
  const runs = useInfiniteQuery({
    queryKey: runsKey,
    queryFn: ({ pageParam }) => listConversationRuns(data.projectId, agent!.id, conversationId!, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
    enabled: Boolean(agent && conversationId),
  });
  const { displayedRuns, projectActiveRun, ownRun, currentActiveRun } = resolveConversationRuns(
    data.activeRun, runs.data?.pages.flatMap((page) => page.items) ?? [], agent?.id, conversationId);
  const approvals = useQuery({ queryKey: ["run-media-approvals", data.projectId, ownRun?.id],
    queryFn: () => listRunMediaApprovals(data.projectId, ownRun!.id),
    enabled: ownRun?.status === "WAITING_TASKS" });
  const pendingApprovals = ownRun?.status === "WAITING_TASKS"
    ? (approvals.data ?? []).filter((approval) => approval.status === "PENDING") : [];
  const latestRun = displayedRuns.at(-1);
  const activeTasks = useQuery({ queryKey: ["run-history-tasks", data.projectId, currentActiveRun?.id],
    queryFn: () => listRunTasks(data.projectId, currentActiveRun!.id),
    enabled: currentActiveRun?.status === "RUNNING" || currentActiveRun?.status === "QUEUED" });
  const retryTask = currentActiveRun?.status === "RUNNING" ? activeTasks.data?.find((task) =>
    task.kind === "AGENT_TURN" && task.output?.modelRetry?.schemaVersion === 1
    && (task.status === "READY" || task.status === "RUNNING")) : undefined;
  const failedRun = !preparing && (latestRun?.status === "BLOCKED" || latestRun?.status === "FAILED") ? latestRun : null;
  // Recheck editable state after asynchronous preparation; never submit a revised draft or Skill.
  const currentSubmission = useRef({ agentId: agent.id, conversationId, instruction: runInstruction.trim(), agentVersion: agent?.version,
    skillFingerprint: skillState.fingerprint, activeRun: projectActiveRun });
  currentSubmission.current = { agentId: agent.id, conversationId, instruction: runInstruction.trim(), agentVersion: agent?.version,
    skillFingerprint: skillState.fingerprint, activeRun: projectActiveRun };

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
      createKey.current ??= crypto.randomUUID();
      return createAgentConversation(data.projectId, agent.id, createKey.current);
    },
    onSuccess: (conversation, input) => {
      createKey.current = null;
      if (input.transferDraft) setDrafts((previous) => ({ ...previous,
        [`${agent.id}:${conversation.id}`]: previous[`${agent.id}:${EMPTY_CONVERSATION_DRAFT}`] ?? EMPTY_DRAFT,
        [`${agent.id}:${EMPTY_CONVERSATION_DRAFT}`]: EMPTY_DRAFT,
      }));
      selectInCache(conversation);
      setSubmissionError(null); runIntent.current = null; setView("chat");
      void queryClient.invalidateQueries({ queryKey: conversationKey });
    },
  });
  const selectConversation = useMutation({
    mutationFn: (id: string) => selectAgentConversation(data.projectId, agent!.id, id),
    onSuccess: (conversation) => {
      selectInCache(conversation);
      setSubmissionError(null); runIntent.current = null; setView("chat");
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
    mutationFn: ({ key, input }: { key: string; input: CreateRunRequest & { conversationId: string }; requestId?: string }) =>
      createRun(data.projectId, key, input),
    onSuccess: async (run, submitted) => {
      saveRunInCache(run);
      runIntent.current = null;
      if (submitted.requestId) data.onRunRequestHandled?.(submitted.requestId);
      else setDrafts((previous) => {
        const submittedDraftKey = `${submitted.input.agentId}:${submitted.input.conversationId}`;
        const prior = previous[submittedDraftKey];
        if (!prior || prior.instruction.trim() !== submitted.input.instruction) return previous;
        return { ...previous, [submittedDraftKey]: { ...prior, instruction: "" } };
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["snapshot", data.projectId] }),
        queryClient.invalidateQueries({ queryKey: conversationKey }),
        queryClient.invalidateQueries({ queryKey: ["conversation-runs", data.projectId, agent?.id, run.conversationId] }),
      ]);
    },
    onError: (error) => {
      if (error instanceof ApiError && ["AGENT_VERSION_CONFLICT", "MODEL_CONFIG_CONFLICT",
        "SYSTEM_PROMPT_CONFLICT", "CONVERSATION_VERSION_CONFLICT"].includes(error.code)) {
        runIntent.current = null;
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
    if (view !== "chat" || !conversations.isSuccess || (conversationId && !runs.isSuccess)) return;
    const positionKey = `${agent.id}:${conversationId ?? EMPTY_CONVERSATION_DRAFT}:${displayedRuns.length ? "messages" : "empty"}`;
    if (positionedConversation.current === positionKey) return;
    if (displayedRuns.length) scroller.scrollToEnd({ behavior: "auto" });
    else scroller.scrollToStart({ behavior: "auto" });
    positionedConversation.current = positionKey;
  }, [agent.id,conversationId,conversations.isSuccess,displayedRuns.length,runs.isSuccess,scroller,view]);
  // Only a new user action auto-sends. A failure or an occupied Run stays explicit;
  // query refreshes and a later idle project must never silently retry the action.
  submitRequestedRun.current = submitRun;
  useEffect(() => {
    const request = data.runRequest;
    if (!request || attemptedRequestId.current === request.id || !conversations.isSuccess
      || (conversationId && !runs.isSuccess) || sessionBusy || !skillState.ready || preparing) return;
    attemptedRequestId.current = request.id;
    setView("chat");
    void submitRequestedRun.current(request);
  }, [data.runRequest,conversations.isSuccess,conversationId,runs.isSuccess,sessionBusy,skillState.ready,preparing]);
  if (!agent) return null;

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (configuration) data.onUpdateAgent(configuration.base, configuration.name, configuration.instruction);
  }
  async function submitRun(request?: AgentChatRunRequest) {
    if (!agent || projectActiveRun || submissionLock.current || start.isPending || sessionBusy
      || conversations.isPending || conversations.isError || !skillState.ready || (!request && data.runRequest)) return;
    const instruction = (request?.instruction ?? runInstruction).trim();
    if (!instruction) return;
    const agentVersion = agent.version;
    const skillSelection = skillState.selection;
    const skillFingerprint = skillState.fingerprint;
    const selectedIds = [...request?.selectedItemIds ?? useCanvasStore.getState().selectedIds];
    const requestRevision = requestLifecycle.current.revision;
    const requestIsCurrent = () => !request || requestLifecycle.current.mounted
      && requestLifecycle.current.projectId === data.projectId && requestLifecycle.current.revision === requestRevision;
    submissionLock.current = true;
    setPreparing(true);
    setSubmissionError(null);
    setView("chat");
    let targetId = conversationId;
    try {
      if (!targetId) {
        try { targetId = (await createConversation.mutateAsync({ transferDraft: true })).id; }
        catch { return; }
      }
      if (!requestIsCurrent()) return;
      const fingerprint = JSON.stringify({ conversationId: targetId, instruction, agentVersion, skillFingerprint, selectedIds });
      // A lost create response may already have committed a Run. Replay its exact request/key,
      // without a new preflight that would reject the now-occupied project slot.
      const retryingRequest = request && runIntent.current?.requestId === request.id;
      if (!retryingRequest && (runIntent.current?.fingerprint !== fingerprint || runIntent.current?.requestId !== request?.id)) {
        const reviewed = await getRunPreflight(data.projectId, agent.id, targetId, skillSelection);
        if (!requestIsCurrent()) return;
        const current = currentSubmission.current;
        if (current.agentId !== agent.id || (!request && current.instruction !== instruction) || current.agentVersion !== agentVersion
          || current.skillFingerprint !== skillFingerprint || current.activeRun
          || (current.conversationId !== targetId && current.conversationId !== conversationId)) return;
        if (!reviewed.modelAvailable) throw new RunPreparationError(t("agent.chat.chatModelMissing"));
        if (!reviewed.toolCalling) throw new RunPreparationError(t("agent.chat.toolsUnsupported"));
        if (reviewed.policySnapshot.systemPromptVersion == null || reviewed.conversationVersion == null
          || reviewed.conversationId !== targetId || reviewed.agentVersion !== agentVersion || reviewed.agentId !== agent.id) {
          throw new RunPreparationError(t("agent.chat.configurationChanged"));
        }
        const input: CreateRunRequest & { conversationId: string } = {
          agentId: agent.id, conversationId: targetId, expectedConversationVersion: reviewed.conversationVersion,
          instruction, expectedAgentVersion: reviewed.agentVersion,
          expectedModelConfigSource: reviewed.policySnapshot.modelConfigSource,
          expectedModelConfigVersion: reviewed.policySnapshot.modelConfigVersion,
          expectedSystemPromptVersion: reviewed.policySnapshot.systemPromptVersion,
          selectedItemIds: selectedIds, skillSelection,
        };
        runIntent.current = { fingerprint, key: crypto.randomUUID(), input, requestId: request?.id };
      }
      // Once createRun is sent it must be reconciled by its original key, never canceled by navigation.
      if (!requestIsCurrent() || !runIntent.current) return;
      await start.mutateAsync({ key: runIntent.current.key, input: runIntent.current.input, requestId: request?.id });
    } catch (error) {
      if (requestIsCurrent()) setSubmissionError({ conversationId: targetId, error: error instanceof Error ? error : new Error(t("agent.chat.requestFailed")) });
    } finally {
      submissionLock.current = false;
      if (requestIsCurrent()) setPreparing(false);
    }
  }
  function reviewApproval(id: string) {
    const body = bodyRef.current;
    const target = [...body?.querySelectorAll<HTMLElement>("[data-media-approval-id]") ?? []]
      .find((element) => element.dataset.mediaApprovalId === id);
    if (!body || !target) return;
    const runId = target.closest<HTMLElement>("[data-message-id]")?.dataset.messageId;
    if (runId) scroller.scrollToMessage(runId, { align: "nearest", behavior: "smooth" });
    target.focus();
  }
  return <article aria-label={t("agent.chat.panelLabel", { "0": agent.name })} className="agent-chat-surface">
      <header className="agent-chat-header">
        <span className="agent-chat-avatar"><Sparkle weight="fill" size={18} aria-hidden="true" /></span>
        <div className="agent-chat-heading"><div className="agent-chat-title"><h3>{t("agent.chat.directorTitle")}</h3><Badge variant="outline">{t("agent.chat.builtin")}</Badge></div>
          <span className="agent-chat-presence" data-active={Boolean(ownRun || preparing)}>{pendingApprovals.length ? t("agent.trace.waitingApproval") : retryTask ? t(retryTask.status === "READY" ? "agent.retry.waiting" : "agent.retry.running") : ownRun ? RUN_STATUS_LABELS[ownRun.status] : preparing ? t("agent.chat.starting") : failedRun ? RUN_STATUS_LABELS[failedRun.status] : t("agent.chat.ready")}</span>
        </div>
        {headerActions}
      </header>
      <nav className="agent-chat-toolbar" aria-label={t("agent.chat.conversation")}>
        <Button variant={view === "chat" ? "secondary" : "ghost"} size="sm" aria-label={t("agent.chat.chat")} aria-pressed={view === "chat"} className="agent-chat-tab nodrag"
          onClick={() => setView("chat")} title={currentConversation?.title || t(conversationId ? "agent.chat.currentSession" : "agent.chat.newSession")} type="button">
          {view !== "chat" ? <ArrowLeft data-icon="inline-start" /> : <span className="agent-chat-session-dot" aria-hidden="true" />}
          <span>{currentConversation?.title || t(conversationId ? "agent.chat.currentSession" : "agent.chat.newSession")}</span></Button>
        <Button variant={view === "history" ? "secondary" : "ghost"} size="icon-sm" aria-label={t("agent.chat.sessions")} aria-pressed={view === "history"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "history" ? "chat" : "history")} title={t("agent.chat.sessions")} type="button"><ClockCounterClockwise data-icon="inline-start" /></Button>
        <Button variant={view === "settings" ? "secondary" : "ghost"} size="icon-sm" aria-label={t("agent.chat.settings")} aria-pressed={view === "settings"} className="agent-chat-icon nodrag"
          onClick={() => setView(view === "settings" ? "chat" : "settings")} title={t("agent.chat.settings")} type="button"><GearSix data-icon="inline-start" /></Button>
        <Button variant="outline" size="sm" aria-label={t("agent.chat.createSession")} className="agent-chat-new-session nodrag" disabled={sessionBusy || (preparing && !start.isPending) || conversations.isPending}
          onClick={() => createConversation.mutate({ transferDraft: false })} title={t("agent.chat.createSession")} type="button"><Plus data-icon="inline-start" />{t("agent.chat.createSession")}</Button>
      </nav>
      {view === "chat" ? <div className="agent-chat-context nodrag">
        <div className="agent-chat-context-counts" title={t("agent.chat.canvasAccessHint")}>
          <Stack size={14} aria-hidden="true" /><span>{t("agent.chat.canvasNodeCount", { "0": data.canvasNodeCount })}</span>
          {data.selectedNodeCount ? <Badge variant="secondary">{t("agent.chat.selectedNodeCount", { "0": data.selectedNodeCount })}</Badge> : null}
        </div>
      </div> : null}
      {ownRun && ownRun.conversationId !== conversationId ? <div className="agent-chat-active-session">
        <span>{t("agent.chat.otherSessionRunningHint")}</span>
        <Button variant="ghost" type="button" disabled={sessionBusy || (preparing && !start.isPending)} onClick={() => selectConversation.mutate(ownRun.conversationId)}>{t("agent.chat.returnToRunningSession")}</Button>
      </div> : null}
      {view === "chat" && runs.hasNextPage ? <Button variant="ghost" size="xs" className="agent-chat-earlier" disabled={runs.isFetchingNextPage}
        onClick={() => void runs.fetchNextPage()} type="button">{runs.isFetchingNextPage ? t("common.loading") : t("agent.chat.loadEarlier")}</Button> : null}
      <MessageScroller className="agent-chat-scroll-area">
        <MessageScrollerViewport aria-label={t("agent.chat.conversation")} className="agent-chat-body nodrag nowheel nopan" ref={bodyRef}>
        <MessageScrollerContent className="agent-chat-transcript" aria-live="polite">
        {view === "settings" ? <MessageScrollerItem messageId="settings"><section aria-label={t("agent.chat.configuration")} className="agent-chat-settings">
          <div className="agent-chat-section-heading"><h4>{t("agent.chat.configuration")}</h4><p>{t("agent.chat.settingsHint")}</p></div>
          <form onSubmit={submit}>
            <FieldGroup>
              <Field><FieldLabel htmlFor={`${fieldId}-name`}>{t("common.name")}</FieldLabel><Input id={`${fieldId}-name`} value={configuration?.name ?? agent.name} onChange={(event) => setConfiguration((current) => current ? { ...current, name: event.target.value } : current)} maxLength={MAX_AGENT_NAME} name="name" required /></Field>
              <Field><FieldLabel htmlFor={`${fieldId}-instruction`}>{t("agent.defaults.prompt")}</FieldLabel><Textarea id={`${fieldId}-instruction`} value={configuration?.instruction ?? agent.instruction} onChange={(event) => setConfiguration((current) => current ? { ...current, instruction: event.target.value } : current)} maxLength={MAX_INSTRUCTION} name="instruction" required rows={8} /></Field>
            </FieldGroup>
            <Button size="sm" disabled={data.updatingAgent} type="submit">{data.updatingAgent ? t("common.saving") : t("common.saveConfig")}</Button>
            {configuration && agent.version !== configuration.base.version ? <p role="status">
              {t("common.versionConflict")}<Button variant="ghost" type="button"
                onClick={() => setConfiguration({ base: agent, name: agent.name, instruction: agent.instruction })}>{t("common.refreshVersion")}</Button>
            </p> : null}
            {data.updateAgentError ? <ChatError error={data.updateAgentError} /> : null}
          </form>
          <AgentSkillSettings projectId={data.projectId} agent={agent} state={skillState} onChanged={()=>{if (!runIntent.current?.requestId) runIntent.current=null;}} />
        </section></MessageScrollerItem> : view === "history" ? <MessageScrollerItem messageId="history"><section aria-label={t("agent.chat.sessions")} className="agent-chat-history">
          <div className="agent-chat-section-heading"><h4>{t("agent.chat.sessions")}</h4><p>{t("agent.chat.sessionContextHint")}</p></div>
          {conversations.isPending ? <CanvasLoadingState compact label={t("agent.chat.sessionLoading")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="outline" size="sm" onClick={() => void conversations.refetch()} type="button">{t("agent.chat.retrySessions")}</Button></> : null}
          {conversations.isSuccess && !sessions.length ? <p>{t("agent.chat.sessionsEmpty")}</p> : null}
          {sessions.map((session) => <Button variant="ghost" className="agent-chat-history-item" key={session.id}
            aria-current={session.id === conversationId ? "true" : undefined} disabled={sessionBusy || (preparing && !start.isPending)}
            onClick={() => selectConversation.mutate(session.id)} type="button">
            <span className="agent-chat-history-title"><span>{session.title || t("agent.chat.newSession")}</span><ArrowUpRight data-icon="inline-end" /></span><small>{t("agent.chat.conversationSummary", { "0": session.turnCount, "1": new Date(session.updatedAt).toLocaleString(getFormatLocale()), "2": session.id === conversationId ? t("agent.chat.currentSessionSuffix") : "" })}</small>
          </Button>)}
          {conversations.hasNextPage ? <Button variant="outline" size="sm" disabled={conversations.isFetchingNextPage}
            onClick={() => void conversations.fetchNextPage()} type="button">{conversations.isFetchingNextPage ? t("common.loading") : t("agent.chat.moreSessions")}</Button> : null}
        </section></MessageScrollerItem> : <>
          {!displayedRuns.length ? <MessageScrollerItem messageId="conversation-state">
          {conversations.isPending ? <CanvasLoadingState compact label={t("agent.chat.restoringSession")} /> : null}
          {conversations.error ? <><ChatError error={conversations.error} /><Button variant="outline" size="sm" onClick={() => void conversations.refetch()} type="button">{t("agent.chat.retrySessions")}</Button></> : null}
          {conversationId && runs.isPending ? <CanvasLoadingState compact label={t("agent.chat.conversationLoading")} /> : null}
          {conversationId && runs.error ? <><ChatError error={runs.error} /><Button variant="outline" size="sm" onClick={() => void runs.refetch()} type="button">{t("agent.chat.retryMessages")}</Button></> : null}
          {!displayedRuns.length && !preparing && conversations.isSuccess && (!conversationId || runs.isSuccess) ? <Empty className="agent-chat-empty">
            <EmptyHeader>
              <span className="agent-chat-eyebrow">{t("agent.chat.emptyEyebrow")}</span>
              <EmptyTitle>{t("agent.chat.emptyTitle")}</EmptyTitle><EmptyDescription>{t("agent.chat.emptyDescription")}</EmptyDescription>
            </EmptyHeader>
            <div className="agent-chat-suggestions">
              {([{ key: "organize", icon: Stack }, { key: "storyboard", icon: FilmStrip }, { key: "polish", icon: MagicWand }] as const).map(({ key: suggestion, icon: Icon }) => <Button variant="outline" key={suggestion}
                onClick={() => { setRunInstruction(t(`agent.chat.suggestion.${suggestion}.prompt`)); document.getElementById(`${fieldId}-composer`)?.focus(); }} type="button">
                <Icon data-icon="inline-start" /><span className="agent-chat-suggestion-copy"><strong>{t(`agent.chat.suggestion.${suggestion}.title`)}</strong><small>{t(`agent.chat.suggestion.${suggestion}.description`)}</small></span><ArrowUpRight data-icon="inline-end" />
              </Button>)}
            </div>
          </Empty> : null}
          </MessageScrollerItem> : null}
          {displayedRuns.map((run) => <MessageScrollerItem key={run.id} messageId={run.id}><AgentRunConversation projectId={data.projectId}
            run={run} active={ownRun?.id === run.id} showFailureNotice={failedRun?.id !== run.id} /></MessageScrollerItem>)}
        </>}
        </MessageScrollerContent>
        </MessageScrollerViewport>
        <MessageScrollerButton aria-label={t("agent.chat.latestMessages")} />
      </MessageScroller>
      <div className="agent-chat-feedback nodrag nowheel nopan">
        {data.runRequest && !preparing ? <Alert><AlertDescription>
          <p>{t("agent.chat.compositionPending", { "0": data.runRequest.selectedItemIds.length })}</p>
          {start.isError && runIntent.current?.requestId === data.runRequest.id
            && (!(start.error instanceof ApiError) || start.error.status >= HTTP_STATUS.INTERNAL_SERVER_ERROR)
            ? <p>{t("agent.chat.compositionUncertain")}</p> : null}
          {projectActiveRun ? <p>{t("canvas.workspace.compositionBusy")}</p> : null}
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" type="button"
              disabled={Boolean(projectActiveRun) || sessionBusy || conversations.isPending || conversations.isError || !skillState.ready}
              onClick={() => { if (data.runRequest) void submitRun(data.runRequest); }}>{t("agent.chat.sendComposition")}</Button>
            <Button variant="ghost" size="sm" type="button" onClick={() => {
              if (data.runRequest) data.onRunRequestHandled?.(data.runRequest.id);
              setSubmissionError(null);
            }}>{t("canvas.feedback.dismiss")}</Button>
          </div>
        </AlertDescription></Alert> : null}
        {view === "chat" && displayedRuns.length > 0 && runs.error ? <><ChatError error={runs.error} /><Button variant="outline" size="sm" onClick={() => void runs.refetch()} type="button">{t("agent.chat.retryMessages")}</Button></> : null}
        {view === "chat" && retryTask ? <AgentModelRetryNotice task={retryTask} /> : null}
        {view === "chat" && currentActiveRun?.status === "RUNNING" && activeTasks.isError ?
          <div className="agent-chat-error" role="alert">{t("agent.retry.stateUnavailable")}
            <Button variant="ghost" size="sm" type="button" onClick={() => void activeTasks.refetch()}>{t("common.retryRead")}</Button>
          </div> : null}
        {view === "chat" && currentActiveRun && pendingApprovals.length ? <div className="agent-chat-pending-approval" role="status">
          <span>{t("agent.trace.waitingApproval")}</span>
          <Button variant="outline" size="sm" type="button" onClick={() => {
            const approval = pendingApprovals[0];
            if (approval) reviewApproval(approval.id);
          }}>{t("agent.approval.viewPending", { "0": pendingApprovals.reduce((count, approval) => count + approval.outputs.length, 0) })}</Button>
        </div> : null}
        {view === "chat" && currentActiveRun?.status === "WAITING_TASKS" && approvals.isError ? <div className="agent-chat-error" role="alert">
          {t("agent.approval.loadFailed")}<Button variant="ghost" size="sm" type="button"
            onClick={() => void approvals.refetch()}>{t("common.retry")}</Button>
        </div> : null}
        {failedRun && (failedRun.status === "FAILED" || failedRun.status === "BLOCKED") ?
          <BlockedRunNotice projectId={data.projectId} runId={failedRun.id} status={failedRun.status} /> : null}
        {preparing ? <p role="status">{start.isPending ? t("agent.chat.starting") : t("agent.chat.preflightLoading")}</p> : null}
        {createConversation.error ? <ChatError error={createConversation.error} /> : null}
        {selectConversation.error ? <ChatError error={selectConversation.error} /> : null}
        {submissionError && (submissionError.conversationId === conversationId || (!conversationId && submissionError.conversationId === null)) ? <ChatError error={submissionError.error} /> : null}
        {stop.error && displayedRuns.some((run) => run.id === stop.variables) ? <ChatError error={stop.error} /> : null}
      </div>
      {view === "chat" ? <form aria-label={t("agent.chat.sendTask")} className="agent-chat-composer nodrag nowheel nopan" onSubmit={(event) => { event.preventDefault(); void submitRun(); }}>
        {currentActiveRun?.status === "BLOCKED" ? <p className="agent-chat-run-status">{t("agent.chat.endBlockedHint")}</p> : null}
        <Textarea id={`${fieldId}-composer`} aria-label={t("agent.chat.currentTask")} disabled={conversations.isPending || (conversations.isError && !conversations.data)} maxLength={MAX_INSTRUCTION} onChange={(event) => {
          setRunInstruction(event.target.value);
        }} onKeyDown={(event) => {
          // IME Enter confirms Chinese input; only an explicit modifier shortcut submits.
          if (event.key === "Enter" && (event.metaKey || event.ctrlKey) && !event.nativeEvent.isComposing) {
            event.preventDefault(); event.currentTarget.form?.requestSubmit();
          }
        }} placeholder={t("agent.chat.instructionPlaceholder")} required value={runInstruction} rows={2} />
        <div className="agent-chat-composer-footer">
          <div className="agent-chat-composer-actions">
            <AgentRunSkillControls projectId={data.projectId} agent={agent} state={skillState} onChanged={()=>{if (!runIntent.current?.requestId) runIntent.current=null;}} />
          </div>
          {currentActiveRun?.status === "BLOCKED" ? <Button variant="outline" size="sm"
            disabled={stop.isPending} onClick={() => stop.mutate(currentActiveRun.id)}
            title={t("agent.chat.endBlockedHint")} type="button">{stop.isPending ? t("agent.chat.endingRun") : t("agent.chat.endRun")}</Button>
            : currentActiveRun ? <Button variant="secondary" size="icon-sm" aria-label={t("agent.chat.stop")} className="agent-chat-send agent-chat-stop" disabled={stop.isPending || currentActiveRun.status === "CANCEL_REQUESTED"}
            onClick={() => stop.mutate(currentActiveRun.id)} title={t("agent.chat.stopOrchestration")} type="button"><Square weight="fill" data-icon="inline-start" /></Button>
            : <Button size="icon-sm" aria-label={t("agent.chat.send")} className="agent-chat-send" disabled={Boolean(projectActiveRun || data.runRequest) || start.isPending || preparing || sessionBusy || conversations.isPending || conversations.isError || !skillState.ready || !runInstruction.trim()}
              title={t("agent.chat.sendAndStart")} type="submit"><ArrowUp data-icon="inline-start" weight="bold" /></Button>}
        </div>
        <p className="agent-chat-composer-hint">{t("agent.chat.composerHint")}</p>
      </form> : null}
    </article>;
}

class RunPreparationError extends Error {}

function ChatError({ error }: { error: Error }) {
  useLocale();
  return <Alert variant="destructive" className="agent-chat-error"><AlertDescription>{error instanceof ApiError || error instanceof RunPreparationError ? error.message : t("agent.chat.requestFailed")}</AlertDescription></Alert>;
}
