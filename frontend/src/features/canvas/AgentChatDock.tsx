import { useEffect,useId,useRef,useState,type ReactNode } from "react";
import { cn } from "cn";
import { ArrowsInSimple,ArrowsOutSimple,Sparkle,X } from "@/shared/ui/icons";
import { t,useLocale } from "../../shared/i18n";
import { LoadingState } from "../../shared/ui/LoadingState";
import { Button } from "../../shared/ui/primitives/button";
import { Alert,AlertDescription } from "../../shared/ui/primitives/alert";
import { Empty,EmptyDescription,EmptyHeader,EmptyMedia,EmptyTitle } from "../../shared/ui/primitives/empty";
import { AgentChatPanel,type AgentChatPanelData } from "./AgentChatPanel";
import "./AgentChatDock.css";

export type AgentChatDockProps = {
  data: AgentChatPanelData | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  loading?: boolean;
  error?: Error | null;
  onRetry?: () => void;
  controls?: ReactNode;
};

/** A nonmodal workspace companion: collapsing preserves its drafts and ongoing work. */
export function AgentChatDock({ data,open,onOpenChange,loading = false,error,onRetry,controls }: AgentChatDockProps) {
  useLocale();
  const panelId = useId();
  const launcher = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const [expanded,setExpanded] = useState(false);
  const [hasOpened,setHasOpened] = useState(open);
  const wasOpen = useRef(open);
  const previousFocus = useRef<HTMLElement | null>(null);
  // Each visited Agent keeps its own conversation drafts, configuration CAS base,
  // and Skill selection. Hidden panels still receive the project's current Run.
  const cachedPanels = useRef(new Map<string,AgentChatPanelData>());
  const cachedProject = useRef<string | null>(null);
  if (data && hasOpened) {
    if (cachedProject.current !== data.projectId) {
      cachedPanels.current.clear();
      cachedProject.current = data.projectId;
    }
    cachedPanels.current.set(data.agent.id,data);
  }
  useEffect(() => {
    if (open) {
      if (!wasOpen.current) previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setHasOpened(true);
      const frame = requestAnimationFrame(() => {
        const root = panel.current;
        if (!root) return;
        const input = root.querySelector<HTMLTextAreaElement>(".agent-chat-dock-agent:not([hidden]) .agent-chat-composer textarea:not(:disabled)");
        (input ?? root).focus({ preventScroll: true });
      });
      wasOpen.current = true;
      return () => cancelAnimationFrame(frame);
    }
    if (wasOpen.current) {
      const target = previousFocus.current;
      // A launcher is always available when the original canvas control disappeared.
      if (target?.isConnected && target !== document.body && target !== document.documentElement
        && !target.closest("[hidden],[inert]") && !panel.current?.contains(target)) {
        target.focus({ preventScroll: true });
        if (document.activeElement !== target) launcher.current?.focus({ preventScroll: true });
      } else launcher.current?.focus({ preventScroll: true });
    }
    wasOpen.current = false;
  }, [open]);
  useEffect(() => {
    if (!open || !data || document.activeElement !== panel.current) return;
    panel.current?.querySelector<HTMLTextAreaElement>(".agent-chat-dock-agent:not([hidden]) .agent-chat-composer textarea:not(:disabled)")?.focus({ preventScroll: true });
  }, [open,data,loading]);
  const close = () => onOpenChange(false);
  const headerActions = <div className="agent-chat-window-actions">
    <Button variant="ghost" size="icon-sm" aria-label={t(expanded ? "agent.chat.restoreDirector" : "agent.chat.expandDirector")}
      title={t(expanded ? "agent.chat.restoreDirector" : "agent.chat.expandDirector")} onClick={() => setExpanded((value) => !value)} type="button">
      {expanded ? <ArrowsInSimple aria-hidden="true" /> : <ArrowsOutSimple aria-hidden="true" />}
    </Button>
    <Button variant="ghost" size="icon-sm" aria-label={t("agent.chat.closeDirector")} title={t("agent.chat.closeDirector")} onClick={close} type="button"><X aria-hidden="true" /></Button>
  </div>;
  const active = data?.activeRun && !["CANCELED","FAILED","SUCCEEDED"].includes(data.activeRun.status);
  return <aside className="agent-chat-dock" aria-label={t("agent.chat.directorTitle")}>
    <div ref={panel} id={panelId} role="dialog" aria-modal="false" aria-label={t("agent.chat.directorTitle")} tabIndex={-1}
      hidden={!open} className={cn("agent-chat-dock-panel",expanded && "agent-chat-dock-panel--expanded")}
      onPointerDown={(event) => event.stopPropagation()} onClick={(event) => event.stopPropagation()} onWheel={(event) => event.stopPropagation()}
      onKeyDown={(event) => {
        if (event.key === "Escape" && !event.defaultPrevented && !event.nativeEvent.isComposing) {
          event.preventDefault(); event.stopPropagation(); close();
        }
      }}>
      {hasOpened ? <>
        {controls && !data ? <div className="agent-chat-dock-controls">{controls}</div> : null}
        {data && loading ? <LoadingState compact label={t("agent.chat.loadingDirector")} /> : null}
        {data && error ? <Alert variant="destructive"><AlertDescription><p>{t("agent.chat.requestFailed")}</p>{onRetry ? <Button variant="outline" size="sm" onClick={onRetry} type="button">{t("common.retry")}</Button> : null}</AlertDescription></Alert> : null}
        {[...cachedPanels.current.values()].map((cached) => <div key={cached.agent.id} className="agent-chat-dock-agent" hidden={cached.agent.id !== data?.agent.id}>
          <AgentChatPanel data={cached.agent.id === data?.agent.id ? data : { ...cached,
            activeRun: data?.activeRun ?? null,canvasNodeCount: data?.canvasNodeCount ?? cached.canvasNodeCount,selectedNodeCount: 0 }} headerActions={headerActions}
            headingControls={cached.agent.id === data?.agent.id ? controls : undefined} />
        </div>)}
        {!data ? <>
          <header className="agent-chat-dock-loading-header"><h2>{t("agent.chat.directorTitle")}</h2>{headerActions}</header>
          {loading ? <LoadingState label={t("agent.chat.loadingDirector")} /> : error ? <Alert variant="destructive"><AlertDescription>
            <p>{t("agent.chat.requestFailed")}</p>{onRetry ? <Button variant="outline" size="sm" onClick={onRetry} type="button">{t("common.retry")}</Button> : null}
          </AlertDescription></Alert> : <Empty><EmptyHeader><EmptyMedia variant="icon"><Sparkle /></EmptyMedia><EmptyTitle>{t("agent.chat.emptyTitle")}</EmptyTitle><EmptyDescription>{t("agent.chat.noDirector")}</EmptyDescription></EmptyHeader></Empty>}
        </> : null}
      </> : null}
    </div>
    <Button ref={launcher} className="agent-chat-launcher" hidden={open} aria-label={t("agent.chat.openDirector")} aria-expanded={open} aria-controls={panelId}
      onClick={(event) => { event.stopPropagation(); onOpenChange(true); }} type="button">
      <span className="agent-chat-launcher-icon"><Sparkle weight="fill" aria-hidden="true" /></span>
      <span>{t("agent.chat.directorTitle")}</span>{active ? <span className="agent-chat-launcher-status" aria-label={t("tasks.status.running")} /> : null}
    </Button>
  </aside>;
}
