import type { DebugBody } from "../../shared/api/client";

const JSON_INDENT = 2;
const MODEL_RESPONSE_SCHEMA_VERSION = 1;
export const MAX_FORMATTED_JSON_CHARS = 2 * 1024 * 1024;
export const RAW_PAGE_CHARS = 64 * 1024;
export type JsonObject = Record<string, unknown>;
export type ParsedBody = { status: "json"; value: unknown } | { status: "empty" | "unsupported" | "invalid" | "large" };
export interface LogToolCall { id?: string; name: string; arguments: unknown }
export interface LogMessage {
  index: number; role: string; text: string; toolCalls: LogToolCall[];
  toolCallId?: string; name?: string; attachments: JsonObject[]; raw: JsonObject;
}
export interface LlmLogView {
  prompt: LogMessage[]; completion: LogMessage[]; model?: string; generationId?: string;
  finishReason?: string; streaming?: boolean; usage: {
    prompt?: number; completion?: number; total?: number; cached?: number; cost?: string;
  };
  request: JsonObject; response: JsonObject;
}

export function object(value: unknown): JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : {};
}
function array(value: unknown): unknown[] { return Array.isArray(value) ? value : []; }
function text(value: unknown): string | undefined { return typeof value === "string" ? value : undefined; }
function count(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}
export function prettyJson(value: unknown): string { return JSON.stringify(value, null, JSON_INDENT) ?? "null"; }

/** Never decode media or fetch embedded URLs. Unrecognized/partial bodies retain a raw view. */
export function parseDebugBody(body: DebugBody | null): ParsedBody {
  if (!body) return { status: "empty" };
  if (body.encoding !== "UTF8" && body.encoding !== "MULTIPART_JSON") return { status: "unsupported" };
  if (body.content.length > MAX_FORMATTED_JSON_CHARS) return { status: "large" };
  try { return { status: "json", value: JSON.parse(body.content) as unknown }; }
  catch { return { status: "invalid" }; }
}

function toolCall(value: unknown): LogToolCall {
  const call = object(value);
  const fn = object(call.function);
  let args: unknown = Object.hasOwn(fn, "arguments") ? fn.arguments : call.arguments;
  if (typeof args === "string") {
    try { args = JSON.parse(args) as unknown; } catch { /* Preserve malformed provider arguments verbatim. */ }
  }
  return { id: text(call.id), name: text(fn.name) ?? text(call.name) ?? "tool", arguments: args };
}
function message(value: unknown, index: number, fallbackRole = "unknown"): LogMessage {
  const raw = object(value);
  const content = raw.content ?? raw.text;
  const parts = array(content);
  const texts = typeof content === "string" ? [content] : parts.flatMap((part) => {
    if (typeof part === "string") return [part];
    const field = object(part).text;
    return typeof field === "string" ? [field] : [];
  });
  const attachments = parts.filter((part) => typeof part !== "string" && typeof object(part).text !== "string").map(object);
  const calls = array(raw.tool_calls ?? raw.toolCalls).map(toolCall);
  if (raw.function_call) calls.push(toolCall({ function: raw.function_call }));
  return { index, role: text(raw.role)?.toLowerCase() ?? fallbackRole, text: texts.join("\n\n"), toolCalls: calls,
    toolCallId: text(raw.tool_call_id), name: text(raw.name), attachments, raw };
}

/** Actual Chat Completions bodies and the persisted model-response protocol; no inferred usage or cost. */
export function llmLogView(requestBody: ParsedBody, responseBody: ParsedBody): LlmLogView | null {
  const request = requestBody.status === "json" ? object(requestBody.value) : {};
  const response = responseBody.status === "json" ? object(responseBody.value) : {};
  const hasPrompt = Array.isArray(request.messages);
  const hasChoices = Array.isArray(response.choices);
  const hasGenerations = response.schemaVersion === MODEL_RESPONSE_SCHEMA_VERSION && Array.isArray(response.generations);
  if (!hasPrompt && !hasChoices && !hasGenerations) return null;
  const prompt = array(request.messages).map((value, index) => message(value, index));
  const choices = hasChoices ? array(response.choices) : hasGenerations ? array(response.generations) : [];
  const completion = choices.flatMap((value, index) => {
    const choice = object(value);
    const assistant = hasChoices ? choice.message : choice.assistant;
    if (assistant !== null && typeof assistant === "object") return [message(assistant, index, "assistant")];
    if (typeof choice.text === "string") return [message({ content: choice.text, role: "assistant" }, index)];
    return [];
  });
  const metadata = hasGenerations ? object(response.metadata) : response;
  const usage = object(metadata.usage);
  const promptDetails = object(usage.prompt_tokens_details ?? usage.input_tokens_details);
  const cost = usage.cost ?? response.cost;
  return { prompt, completion, request, response, model: text(metadata.model) ?? text(request.model),
    generationId: text(metadata.id), finishReason: [...new Set(choices.flatMap((choice) => {
      const entry = object(choice);
      const reason = text(hasChoices ? entry.finish_reason : object(entry.metadata).finishReason); return reason ? [reason] : [];
    }))].join(", ") || undefined,
    streaming: typeof request.stream === "boolean" ? request.stream : undefined,
    usage: { prompt: count(usage.prompt_tokens ?? usage.input_tokens ?? usage.promptTokens),
      completion: count(usage.completion_tokens ?? usage.output_tokens ?? usage.completionTokens), total: count(usage.total_tokens ?? usage.totalTokens),
      cached: count(promptDetails.cached_tokens ?? usage.cache_read_input_tokens),
      cost: typeof cost === "string" && /^\d+(\.\d+)?$/.test(cost) ? cost
        : typeof cost === "number" && Number.isFinite(cost) && cost >= 0 ? String(cost) : undefined },
  };
}
