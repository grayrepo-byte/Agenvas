import { useMemo, useState } from "react";
import { ArrowsOut, CaretLeft, CaretRight, Copy } from "@phosphor-icons/react";
import { t, useLocale, formatNumber } from "../../shared/i18n";
import type { CallDebug, DebugBody } from "../../shared/api/client";
import { Dialog } from "../../shared/ui/Dialog";
import { Select } from "../../shared/ui/Select";
import { parseDebugBody, llmLogView, prettyJson, RAW_PAGE_CHARS, type LogMessage, type ParsedBody, type LlmLogView } from "./callLogFormatting";
import "./FormattedCallExchange.css";

type Exchange = CallDebug["exchanges"][number];
type ViewMode = "formatted" | "raw";
const MESSAGE_PREVIEW_CHARS = 160;
const FIRST_MESSAGE = 0;

export function FormattedCallExchange({ exchange, mode, llm }: { exchange: Exchange; mode: ViewMode; llm: boolean }) {
  useLocale();
  const request = useMemo(() => parseDebugBody(exchange.requestBody), [exchange.requestBody]);
  const response = useMemo(() => parseDebugBody(exchange.responseBody), [exchange.responseBody]);
  const view = useMemo(() => llm ? llmLogView(request, response) : null, [llm, request, response]);
  return <>
    <BodyWarning body={exchange.requestBody} title={t("请求正文")} />
    <BodyWarning body={exchange.responseBody} title={t("响应正文")} />
    {mode === "formatted" && view ? <LlmExchange view={view} requestBody={exchange.requestBody}
      responseBody={exchange.responseBody} request={request} response={response} /> : <>
      <BodyContent title={t("请求正文")} body={exchange.requestBody} parsed={request} mode={mode} empty={t("无请求正文")} />
      <BodyContent title={t("响应正文")} body={exchange.responseBody} parsed={response} mode={mode} empty={t("响应正文未采集")} />
    </>}
  </>;
}

function LlmExchange({ view, requestBody, responseBody, request, response }: {
  view: LlmLogView; requestBody: DebugBody | null; responseBody: DebugBody | null; request: ParsedBody; response: ParsedBody;
}) {
  const [expanded, setExpanded] = useState<"prompt" | "completion" | null>(null);
  const usage = view.usage;
  return <div className="llm-log">
    <dl className="llm-log-facts">
      <Fact title={t("模型")} value={view.model} /><Fact title={t("生成 ID")} value={view.generationId} />
      <Fact title={t("结束原因")} value={view.finishReason} />
      <Fact title={t("流式响应")} value={view.streaming === undefined ? undefined : view.streaming ? t("是") : t("否")} />
    </dl>
    <details className="llm-log-section" open><summary>{t("用量")}<span>{usage.total === undefined ? t("未记录") : t("{0} tokens", { "0": formatNumber(usage.total) })}</span></summary>
      <dl className="llm-log-usage">
        <Fact title="Prompt" value={usage.prompt === undefined ? undefined : formatNumber(usage.prompt)} />
        <Fact title="Completion" value={usage.completion === undefined ? undefined : formatNumber(usage.completion)} />
        <Fact title={t("缓存 Token")} value={usage.cached === undefined ? undefined : formatNumber(usage.cached)} />
        <Fact title={t("费用（Provider 返回）")} value={usage.cost} />
      </dl>
      <p className="ui-muted">{t("用量与费用只显示响应中实际返回的值，不估算逐条消息 Token 或费用。")}</p>
    </details>
    <MessageSection title="Prompt" messages={view.prompt} tokens={usage.prompt} onExpand={() => setExpanded("prompt")} />
    <MessageSection title="Completion" messages={view.completion} tokens={usage.completion} onExpand={() => setExpanded("completion")} />
    <details className="llm-log-section"><summary>{t("生成数据")}<span>JSON</span></summary>
      <BodyContent title={t("请求正文")} body={requestBody} parsed={request} mode="formatted" empty={t("无请求正文")} />
      <BodyContent title={t("响应正文")} body={responseBody} parsed={response} mode="formatted" empty={t("响应正文未采集")} />
    </details>
    {expanded ? <Dialog title={expanded === "prompt" ? "Prompt" : "Completion"}
      description={t("按角色搜索和浏览消息；内容已在服务端脱敏。")}
      className="llm-log-dialog" footer={<span className="ui-muted">{t("所有内容仅供查看，不会发起模型调用。")}</span>}
      onClose={() => setExpanded(null)} onSubmit={(event) => event.preventDefault()}>
      <MessageBrowser key={expanded} messages={expanded === "prompt" ? view.prompt : view.completion}
        tokens={expanded === "prompt" ? usage.prompt : usage.completion} />
    </Dialog> : null}
  </div>;
}
function Fact({ title, value }: { title: string; value?: string }) {
  return <div><dt>{title}</dt><dd>{value ?? <span className="ui-muted">{t("未记录")}</span>}</dd></div>;
}
function MessageSection({ title, messages, tokens, onExpand }: { title: string; messages: LogMessage[]; tokens?: number; onExpand: () => void }) {
  return <section className="llm-log-section"><header className="llm-log-section-heading">
    <div><strong>{title}</strong><span>{t("{0} 条消息", { "0": messages.length })}{tokens !== undefined ? ` · ${formatNumber(tokens)} tokens` : ""}</span></div>
    <button type="button" className="ghost-button" disabled={!messages.length} onClick={onExpand} aria-label={t("展开 {0}", { "0": title })}><ArrowsOut size={16} aria-hidden />{t("展开")}</button>
  </header>
    {messages.length ? <div className="llm-log-message-preview"><span className={`llm-log-role llm-log-role--${knownRole(messages[0]?.role ?? "")}`}>{roleLabel(messages[0]?.role ?? "unknown")}</span>
      <p>{preview(messages[0])}</p></div> : <p className="ui-muted">{t("没有已记录的消息；可在生成数据或原始内容中查看其他返回信息。")}</p>}
  </section>;
}
function knownRole(role: string): string { return ["system", "developer", "user", "assistant", "tool", "function"].includes(role) ? role : "unknown"; }
function roleLabel(role: string): string {
  switch (role) {
    case "system": return t("系统"); case "developer": return t("开发者"); case "user": return t("用户");
    case "assistant": return t("助手"); case "tool": case "function": return t("工具");
    default: return role === "unknown" ? t("未知角色") : role;
  }
}
function preview(message: LogMessage | undefined): string {
  if (!message) return "";
  const content = message.text.trim() || (message.toolCalls.length ? t("{0} 次工具调用：{1}", { "0": message.toolCalls.length, "1": message.toolCalls.map((call) => call.name).join(", ") })
    : message.attachments.length ? t("{0} 项多模态内容", { "0": message.attachments.length }) : t("空消息"));
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
    <div className="llm-message-summary"><div><strong>{t("{0} 条消息", { "0": messages.length })}</strong><span>{tokens === undefined ? t("Token 未记录") : `${formatNumber(tokens)} tokens`}</span></div>
      <div className="llm-message-role-counts">{roles.map((name) => <span key={name} className={`llm-log-role llm-log-role--${knownRole(name)}`}>{roleLabel(name)} · {messages.filter((message) => message.role === name).length}</span>)}</div>
    </div>
    <div className="llm-message-filters"><label className="ui-field"><span className="sr-only">{t("搜索消息")}</span><input type="search" value={search} placeholder={t("搜索消息、工具名称或参数…")} onChange={(event) => setSearch(event.target.value)} /></label>
      <label className="ui-field"><span className="sr-only">{t("消息角色")}</span><Select value={role} onChange={(event) => setRole(event.target.value)}><option value="">{t("全部角色")}</option>{roles.map((name) => <option key={name} value={name}>{roleLabel(name)}</option>)}</Select></label>
    </div>
    {!visible.length ? <p className="llm-message-empty" role="status">{t("没有匹配的消息。调整搜索或角色筛选。")}</p> : <div className="llm-message-layout">
      <div className="llm-message-list" aria-label={t("消息列表")}>{visible.map((message) => <button key={message.index} type="button" className="llm-message-row" aria-pressed={current?.index === message.index}
        onClick={() => { setSelected(message.index); }}><span>{message.index + 1}</span><span className={`llm-log-role llm-log-role--${knownRole(message.role)}`}>{roleLabel(message.role)}</span><span className="llm-message-row-preview">{preview(message)}</span></button>)}</div>
      {current ? <article className="llm-message-detail"><div className="llm-message-toolbar">
        <CopyButton key={`${current.index}-${raw}`} content={raw ? prettyJson(current.raw) : messageText(current)} />
        <button className="ghost-button" type="button" aria-pressed={raw} onClick={() => setRaw(!raw)}>{raw ? t("查看正文") : t("查看消息 JSON")}</button>
        <nav aria-label={t("消息翻页")}><button className="ghost-button" type="button" aria-label={t("上一条消息")} disabled={position === FIRST_MESSAGE} onClick={() => { const next = visible[position - 1]; if (next) setSelected(next.index); }}><CaretLeft size={16} /></button>
          <span>{t("第 {0} / {1} 条", { "0": position + 1, "1": visible.length })}</span>
          <button className="ghost-button" type="button" aria-label={t("下一条消息")} disabled={position + 1 >= visible.length} onClick={() => { const next = visible[position + 1]; if (next) setSelected(next.index); }}><CaretRight size={16} /></button></nav>
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
  return value === undefined ? t("参数未记录") : typeof value === "string" ? value : prettyJson(value);
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
    {message.name ? <p className="ui-muted">{t("名称")}：{message.name}</p> : null}
    {message.text ? <RawContent content={content} /> : null}
    {message.toolCalls.map((call, index) => <section className="llm-tool-call" key={index}><h4>{t("工具调用")} · {call.name}</h4>{call.id ? <p className="ui-muted">ID: {call.id}</p> : null}<RawContent content={toolArguments(call.arguments)} /></section>)}
    {message.attachments.map((part, index) => <details className="llm-tool-call" key={index}><summary>{t("多模态内容")} · {String(part.type ?? "unknown")}</summary><RawContent content={prettyJson(part)} /><p className="ui-muted">{t("日志中的媒体地址仅作文本展示，不加载远程素材。")}</p></details>)}
    {!message.text && !message.toolCalls.length && !message.attachments.length ? <p className="ui-muted">{t("空消息")}</p> : null}
  </>;
}
function BodyWarning({ body, title }: { body: DebugBody | null; title: string }) {
  return body?.truncated ? <p className="ui-muted">{title} · {t("超限或未读完")} · {t("正文超过采集上限或响应未读完；这里只包含已采集部分。")}</p> : null;
}
function BodyContent({ title, body, parsed, mode, empty }: { title: string; body: DebugBody | null; parsed: ParsedBody; mode: ViewMode; empty: string }) {
  const content = mode === "formatted" && parsed.status === "json" ? prettyJson(parsed.value) : body?.content ?? empty;
  return <details className="call-log-body" open><summary>{title}{body ? ` · ${body.encoding}` : ""}{body?.truncated ? t(" · 超限或未读完") : ""}</summary>
    {mode === "formatted" && body && parsed.status !== "json" ? <p className="ui-muted">{parsed.status === "large" ? t("正文过大，显示原始内容。") : t("此正文无法格式化，显示原始内容。")}</p> : null}
    <div className="llm-body-actions"><CopyButton key={mode} content={content} /></div>
    <RawContent key={mode} content={content} />
  </details>;
}
function RawContent({ content }: { content: string }) {
  const [limit, setLimit] = useState(RAW_PAGE_CHARS);
  return <><pre>{content.slice(0, limit)}</pre>{content.length > limit ? <button className="secondary-button" type="button" onClick={() => setLimit(limit + RAW_PAGE_CHARS)}>{t("加载更多正文（剩余 {0} 个字符）", { "0": formatNumber(content.length - limit) })}</button> : null}</>;
}
function CopyButton({ content }: { content: string }) {
  const [status, setStatus] = useState<"idle" | "done" | "failed">("idle");
  const [pending, setPending] = useState(false);
  return <span className="llm-copy-control"><button className="ghost-button" type="button" disabled={pending} onClick={async () => {
    setPending(true);
    try { await navigator.clipboard.writeText(content); setStatus("done"); }
    catch { setStatus("failed"); }
    finally { setPending(false); }
  }}><Copy size={14} aria-hidden />{t("复制")}</button>{status !== "idle" ? <span role="status">{status === "done" ? t("已复制") : t("复制失败，请手动选择正文。")}</span> : null}</span>;
}
