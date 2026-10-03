import { Field, FieldLabel } from "../../shared/ui/primitives/field";
import { ArrowsOut,CaretLeft,CaretRight,Copy } from "@phosphor-icons/react";
import { useMemo,useState } from "react";
import type { CallDebug,DebugBody } from "../../shared/api/client";
import { formatNumber,t,useLocale } from "../../shared/i18n";
import { Dialog } from "../../shared/ui/Dialog";
import { Button } from "../../shared/ui/primitives/button";
import { Input } from "../../shared/ui/primitives/input";
import { Select } from "../../shared/ui/Select";
import { llmLogView,parseDebugBody,prettyJson,RAW_PAGE_CHARS,type LlmLogView,type LogMessage,type ParsedBody } from "./callLogFormatting";
import "./FormattedCallExchange.css";

type Exchange = CallDebug["exchanges"][number];
export type CallLogViewMode = "formatted" | "raw";
const MESSAGE_PREVIEW_CHARS = 160;
const FIRST_MESSAGE = 0;

export function FormattedCallExchange({ exchange, mode, llm }: { exchange: Exchange; mode: CallLogViewMode; llm: boolean }) {
  return <FormattedLogBodies requestBody={exchange.requestBody} responseBody={exchange.responseBody} mode={mode} llm={llm} />;
}

/** The model summary is separate from the captured HTTP response; it never invents an HTTP exchange. */
export function FormattedModelResponse({ content, requestBody, mode }: {
  content: NonNullable<NonNullable<CallDebug["llmStream"]>["content"]>;
  requestBody: DebugBody | null; mode: CallLogViewMode;
}) {
  useLocale();
  const responseBody = useMemo<DebugBody>(() => ({ content: content.response, encoding: "UTF8", truncated: content.truncated }), [content]);
  return <FormattedLogBodies requestBody={requestBody} responseBody={responseBody} mode={mode} llm
    responseTitle={t("logs.stream.response")} streaming />;
}

function FormattedLogBodies({ requestBody, responseBody, mode, llm, responseTitle, streaming }: {
  requestBody: DebugBody | null; responseBody: DebugBody | null; mode: CallLogViewMode; llm: boolean;
  responseTitle?: string; streaming?: boolean;
}) {
  useLocale();
  const request = useMemo(() => parseDebugBody(requestBody), [requestBody]);
  const response = useMemo(() => parseDebugBody(responseBody), [responseBody]);
  const view = useMemo(() => {
    const parsed = llm ? llmLogView(request, response) : null;
    return parsed && streaming ? { ...parsed, streaming: true } : parsed;
  }, [llm, request, response, streaming]);
  const responseLabel = responseTitle ?? t("logs.exchange.responseBody");
  return <>
    <BodyWarning body={requestBody} title={t("logs.exchange.requestBody")} />
    <BodyWarning body={responseBody} title={responseLabel} />
    {mode === "formatted" && view ? <LlmExchange view={view} requestBody={requestBody}
      responseBody={responseBody} request={request} response={response} responseTitle={responseLabel} /> : <>
      <BodyContent title={t("logs.exchange.requestBody")} body={requestBody} parsed={request} mode={mode} empty={t("logs.exchange.requestBodyMissing")} />
      <BodyContent title={responseLabel} body={responseBody} parsed={response} mode={mode} empty={t("logs.exchange.responseNotCollected")} />
    </>}
  </>;
}

function LlmExchange({ view, requestBody, responseBody, request, response, responseTitle }: {
  view: LlmLogView; requestBody: DebugBody | null; responseBody: DebugBody | null; request: ParsedBody; response: ParsedBody;
  responseTitle: string;
}) {
  const [expanded, setExpanded] = useState<"prompt" | "completion" | null>(null);
  const usage = view.usage;
  return <div className="llm-log">
    <dl className="llm-log-facts">
      <Fact title={t("common.model")} value={view.model} /><Fact title={t("logs.exchange.generationId")} value={view.generationId} />
      <Fact title={t("logs.exchange.finishReason")} value={view.finishReason} />
      <Fact title={t("logs.exchange.streaming")} value={view.streaming === undefined ? undefined : view.streaming ? t("logs.exchange.yes") : t("logs.exchange.no")} />
    </dl>
    <details className="llm-log-section" open><summary>{t("logs.exchange.usage")}<span>{usage.total === undefined ? t("common.notRecorded") : t("logs.exchange.tokenCount", { "0": formatNumber(usage.total) })}</span></summary>
      <dl className="llm-log-usage">
        <Fact title="Prompt" value={usage.prompt === undefined ? undefined : formatNumber(usage.prompt)} />
        <Fact title="Completion" value={usage.completion === undefined ? undefined : formatNumber(usage.completion)} />
        <Fact title={t("logs.exchange.cachedTokens")} value={usage.cached === undefined ? undefined : formatNumber(usage.cached)} />
        <Fact title={t("logs.exchange.providerCost")} value={usage.cost} />
      </dl>
      <p className="ui-muted">{t("logs.exchange.usageHint")}</p>
    </details>
    <MessageSection title="Prompt" messages={view.prompt} tokens={usage.prompt} onExpand={() => setExpanded("prompt")} />
    <MessageSection title="Completion" messages={view.completion} tokens={usage.completion} onExpand={() => setExpanded("completion")} />
    <details className="llm-log-section"><summary>{t("logs.exchange.generationData")}<span>JSON</span></summary>
      <BodyContent title={t("logs.exchange.requestBody")} body={requestBody} parsed={request} mode="formatted" empty={t("logs.exchange.requestBodyMissing")} />
      <BodyContent title={responseTitle} body={responseBody} parsed={response} mode="formatted" empty={t("logs.exchange.responseNotCollected")} />
    </details>
    {expanded ? <Dialog title={expanded === "prompt" ? "Prompt" : "Completion"}
      description={t("logs.exchange.searchHint")}
      className="llm-log-dialog" footer={<span className="ui-muted">{t("logs.exchange.readOnlyHint")}</span>}
      onClose={() => setExpanded(null)} onSubmit={(event) => event.preventDefault()}>
      <MessageBrowser key={expanded} messages={expanded === "prompt" ? view.prompt : view.completion}
        tokens={expanded === "prompt" ? usage.prompt : usage.completion} />
    </Dialog> : null}
  </div>;
}
function Fact({ title, value }: { title: string; value?: string }) {
  return <div><dt>{title}</dt><dd>{value ?? <span className="ui-muted">{t("common.notRecorded")}</span>}</dd></div>;
}
function MessageSection({ title, messages, tokens, onExpand }: { title: string; messages: LogMessage[]; tokens?: number; onExpand: () => void }) {
  return <section className="llm-log-section"><header className="llm-log-section-heading">
    <div><strong>{title}</strong><span>{t("logs.exchange.messageCount", { "0": messages.length })}{tokens !== undefined ? ` · ${formatNumber(tokens)} tokens` : ""}</span></div>
    <Button variant="ghost" type="button"  disabled={!messages.length} onClick={onExpand} aria-label={t("logs.exchange.expandNamed", { "0": title })}><ArrowsOut size={16} aria-hidden />{t("logs.exchange.expand")}</Button>
  </header>
    {messages.length ? <div className="llm-log-message-preview"><span className={`llm-log-role llm-log-role--${knownRole(messages[0]?.role ?? "")}`}>{roleLabel(messages[0]?.role ?? "unknown")}</span>
      <p>{preview(messages[0])}</p></div> : <p className="ui-muted">{t("logs.exchange.messagesMissing")}</p>}
  </section>;
}
function knownRole(role: string): string { return ["system", "developer", "user", "assistant", "tool", "function"].includes(role) ? role : "unknown"; }
function roleLabel(role: string): string {
  switch (role) {
    case "system": return t("logs.exchange.system"); case "developer": return t("logs.exchange.developer"); case "user": return t("logs.exchange.user");
    case "assistant": return t("logs.exchange.assistant"); case "tool": case "function": return t("logs.exchange.tool");
    default: return role === "unknown" ? t("logs.exchange.unknownRole") : role;
  }
}
function preview(message: LogMessage | undefined): string {
  if (!message) return "";
  const content = message.text.trim() || (message.toolCalls.length ? t("logs.exchange.toolCallSummary", { "0": message.toolCalls.length, "1": message.toolCalls.map((call) => call.name).join(", ") })
    : message.attachments.length ? t("logs.exchange.multimodalCount", { "0": message.attachments.length }) : t("logs.exchange.emptyMessage"));
  return content.slice(0, MESSAGE_PREVIEW_CHARS);
}
function MessageBrowser({ messages, tokens }: { messages: LogMessage[]; tokens?: number }) {
  const [search, setSearch] = useState("");
  const [role, setRole] = useState("");
  const [selected, setSelected] = useState(FIRST_MESSAGE);
  const [raw, setRaw] = useState(false);
  const roles = [...new Set(messages.map((message) => message.role))];
  const query = search.trim().toLocaleLowerCase();
  const visible = useMemo(() => messages.filter((message) => (!role || role === message.role) && (!query ||
    [message.text, message.name, message.toolCallId, ...message.toolCalls.map((call) => `${call.name} ${call.id ?? ""} ${prettyJson(call.arguments)}`)]
      .some((value) => value?.toLocaleLowerCase().includes(query)))), [messages, role, query]);
  const current = visible.find((message) => message.index === selected) ?? visible[FIRST_MESSAGE];
  const position = current ? visible.indexOf(current) : FIRST_MESSAGE;
  return <div className="llm-message-browser">
    <div className="llm-message-summary"><div><strong>{t("logs.exchange.messageCount", { "0": messages.length })}</strong><span>{tokens === undefined ? t("logs.exchange.tokensNotRecorded") : `${formatNumber(tokens)} tokens`}</span></div>
      <div className="llm-message-role-counts">{roles.map((name) => <span key={name} className={`llm-log-role llm-log-role--${knownRole(name)}`}>{roleLabel(name)} · {messages.filter((message) => message.role === name).length}</span>)}</div>
    </div>
    <div className="llm-message-filters"><Field><FieldLabel className="ui-field block"><span className="sr-only">{t("logs.exchange.search")}</span><Input type="search" value={search} placeholder={t("logs.exchange.searchPlaceholder")} onChange={(event) => setSearch(event.target.value)} /></FieldLabel></Field>
      <Field><FieldLabel className="ui-field block"><span className="sr-only">{t("logs.exchange.role")}</span><Select value={role} onChange={(event) => setRole(event.target.value)}><option value="">{t("logs.exchange.allRoles")}</option>{roles.map((name) => <option key={name} value={name}>{roleLabel(name)}</option>)}</Select></FieldLabel></Field>
    </div>
    {!visible.length ? <p className="llm-message-empty" role="status">{t("logs.exchange.noMatches")}</p> : <div className="llm-message-layout">
      <div className="llm-message-list" aria-label={t("logs.exchange.messages")}>{visible.map((message) => <Button variant="ghost" key={message.index} type="button" className="llm-message-row" aria-pressed={current?.index === message.index}
        onClick={() => { setSelected(message.index); }}><span>{message.index + 1}</span><span className={`llm-log-role llm-log-role--${knownRole(message.role)}`}>{roleLabel(message.role)}</span><span className="llm-message-row-preview">{preview(message)}</span></Button>)}</div>
      {current ? <article className="llm-message-detail"><div className="llm-message-toolbar">
        <CopyButton key={`${current.index}-${raw}`} content={raw ? prettyJson(current.raw) : messageText(current)} />
        <Button variant="ghost"  type="button" aria-pressed={raw} onClick={() => setRaw(!raw)}>{raw ? t("logs.exchange.viewBody") : t("logs.exchange.viewMessageJson")}</Button>
        <nav aria-label={t("logs.exchange.pagination")}><Button variant="ghost"  type="button" aria-label={t("logs.exchange.previousMessage")} disabled={position === FIRST_MESSAGE} onClick={() => { const next = visible[position - 1]; if (next) setSelected(next.index); }}><CaretLeft size={16} /></Button>
          <span>{t("logs.exchange.messagePosition", { "0": position + 1, "1": visible.length })}</span>
          <Button variant="ghost"  type="button" aria-label={t("logs.exchange.nextMessage")} disabled={position + 1 >= visible.length} onClick={() => { const next = visible[position + 1]; if (next) setSelected(next.index); }}><CaretRight size={16} /></Button></nav>
      </div>
        <div className="llm-message-content" key={`${current.index}-${raw}`}>
          {raw ? <RawContent content={prettyJson(current.raw)} /> : <MessageContent message={current} />}
        </div>
      </article> : null}
    </div>}
  </div>;
}
function messageText(message: LogMessage): string {
  return [message.text, ...message.toolCalls.map((call) => `${call.name}\n${toolArguments(call.arguments)}`),
    ...message.attachments.map(prettyJson)].filter(Boolean).join("\n\n");
}
function toolArguments(value: unknown): string {
  return value === undefined ? t("logs.exchange.argumentsMissing") : typeof value === "string" ? value : prettyJson(value);
}
function MessageContent({ message }: { message: LogMessage }) {
  let content = message.text;
  if (message.role === "tool" || message.role === "function") {
    const parsed = parseDebugBody({ content, encoding: "UTF8", truncated: false });
    if (parsed.status === "json") content = prettyJson(parsed.value);
  }
  return <>
    <span className={`llm-log-role llm-log-role--${knownRole(message.role)}`}>{roleLabel(message.role)}</span>
    {message.toolCallId ? <p className="ui-muted">tool_call_id: {message.toolCallId}</p> : null}
    {message.name ? <p className="ui-muted">{t("common.name")}：{message.name}</p> : null}
    {message.text ? <RawContent content={content} /> : null}
    {message.toolCalls.map((call, index) => <section className="llm-tool-call" key={index}><h4>{t("logs.exchange.toolCalls")} · {call.name}</h4>{call.id ? <p className="ui-muted">ID: {call.id}</p> : null}<RawContent content={toolArguments(call.arguments)} /></section>)}
    {message.attachments.map((part, index) => <details className="llm-tool-call" key={index}><summary>{t("logs.exchange.multimodalContent")} · {String(part.type ?? "unknown")}</summary><RawContent content={prettyJson(part)} /><p className="ui-muted">{t("logs.exchange.mediaLinksHint")}</p></details>)}
    {!message.text && !message.toolCalls.length && !message.attachments.length ? <p className="ui-muted">{t("logs.exchange.emptyMessage")}</p> : null}
  </>;
}
function BodyWarning({ body, title }: { body: DebugBody | null; title: string }) {
  return body?.truncated ? <p className="ui-muted">{title} · {t("logs.exchange.truncated")} · {t("logs.exchange.truncationHint")}</p> : null;
}
function BodyContent({ title, body, parsed, mode, empty }: { title: string; body: DebugBody | null; parsed: ParsedBody; mode: CallLogViewMode; empty: string }) {
  const content = mode === "formatted" && parsed.status === "json" ? prettyJson(parsed.value) : body?.content ?? empty;
  return <details className="call-log-body" open><summary>{title}{body ? ` · ${body.encoding}` : ""}{body?.truncated ? t("logs.exchange.truncatedSuffix") : ""}</summary>
    {mode === "formatted" && body && parsed.status !== "json" ? <p className="ui-muted">{parsed.status === "large" ? t("logs.exchange.bodyTooLarge") : t("logs.exchange.formatUnsupported")}</p> : null}
    <div className="llm-body-actions"><CopyButton key={mode} content={content} /></div>
    <RawContent key={mode} content={content} />
  </details>;
}
function RawContent({ content }: { content: string }) {
  const [limit, setLimit] = useState(RAW_PAGE_CHARS);
  return <><pre>{content.slice(0, limit)}</pre>{content.length > limit ? <Button variant="outline"  type="button" onClick={() => setLimit(limit + RAW_PAGE_CHARS)}>{t("logs.exchange.loadMoreBody", { "0": formatNumber(content.length - limit) })}</Button> : null}</>;
}
function CopyButton({ content }: { content: string }) {
  const [status, setStatus] = useState<"idle" | "done" | "failed">("idle");
  const [pending, setPending] = useState(false);
  return <span className="llm-copy-control"><Button variant="ghost"  type="button" disabled={pending} onClick={async () => {
    setPending(true);
    try { await navigator.clipboard.writeText(content); setStatus("done"); }
    catch { setStatus("failed"); }
    finally { setPending(false); }
  }}><Copy size={14} aria-hidden />{t("common.copy")}</Button>{status !== "idle" ? <span role="status">{status === "done" ? t("logs.exchange.copied") : t("logs.exchange.copyFailed")}</span> : null}</span>;
}
