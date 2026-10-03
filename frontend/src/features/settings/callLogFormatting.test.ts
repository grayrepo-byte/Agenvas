import { describe, expect, it } from "vitest";
import type { DebugBody } from "../../shared/api/client";
import { llmLogView, MAX_FORMATTED_JSON_CHARS, parseDebugBody, prettyJson } from "./callLogFormatting";

const json = (value: unknown) => parseDebugBody({ content: JSON.stringify(value), encoding: "UTF8", truncated: false });

describe("call log formatting", () => {
  it("formats the persisted streaming model protocol with actual usage and tool arguments", () => {
    const response = { schemaVersion: 1, metadata: { id: "stream-1", model: "stream-model",
      usage: { promptTokens: 12, completionTokens: 0, totalTokens: 12 } }, generations: [{
      assistant: { role: "ASSISTANT", text: "Public answer", toolCalls: [
        { id: "call-1", name: "readCanvas", arguments: '{"itemId":"synthetic-card"}' },
      ] }, metadata: { finishReason: "TOOL_CALLS" },
    }] };
    const original = JSON.stringify(response);
    const view = llmLogView(json({ stream: true, messages: [{ role: "user", content: "Synthetic prompt" }] }), json(response));
    expect(view).toMatchObject({ model: "stream-model", generationId: "stream-1", finishReason: "TOOL_CALLS",
      streaming: true, usage: { prompt: 12, completion: 0, total: 12, cached: undefined, cost: undefined } });
    expect(view?.completion[0]).toMatchObject({ role: "assistant", text: "Public answer",
      toolCalls: [{ id: "call-1", name: "readCanvas", arguments: { itemId: "synthetic-card" } }] });
    expect(JSON.stringify(response)).toBe(original);
    expect(llmLogView(parseDebugBody(null), json(response))?.completion).toHaveLength(1);
    expect(llmLogView(parseDebugBody(null), json({ ...response, schemaVersion: 2 }))).toBeNull();
  });

  it("extracts actual usage, zero values, finish reasons and wire metadata", () => {
    const view = llmLogView(json({ model: "requested", stream: false, messages: [{ role: "user", content: "hi" }] }), json({
      model: "resolved", id: "gen-1", choices: [{ message: { role: "assistant", content: "hello" }, finish_reason: "stop" }],
      usage: { prompt_tokens: 20, completion_tokens: 0, total_tokens: 20, prompt_tokens_details: { cached_tokens: 0 }, cost: "0.00138" },
    }));
    expect(view).toMatchObject({ model: "resolved", generationId: "gen-1", finishReason: "stop", streaming: false,
      usage: { prompt: 20, completion: 0, total: 20, cached: 0, cost: "0.00138" } });
    expect(view?.completion[0]?.text).toBe("hello");
  });

  it("does not invent missing usage, totals, fees or successful completion", () => {
    const view = llmLogView(json({ model: "chat", messages: [{ role: "system", content: "rules" }] }), json({ error: "failed" }));
    expect(view?.usage).toEqual({ prompt: undefined, completion: undefined, total: undefined, cached: undefined, cost: undefined });
    expect(view?.completion).toEqual([]);
    expect(view?.finishReason).toBeUndefined();
    const response = llmLogView(json({ messages: [] }), json({ choices: [], usage: { input_tokens: 8, output_tokens: 2, cost: -1 } }));
    expect(response?.usage).toMatchObject({ prompt: 8, completion: 2, total: undefined, cost: undefined });
    expect(llmLogView(json({ prompt: "media" }), json({ data: [] }))).toBeNull();
  });

  it("keeps multimodal parts and parses tool calls without losing malformed arguments", () => {
    const request = { messages: [
      { role: "user", content: [{ type: "text", text: "caption" }, { type: "image_url", image_url: { url: "https://media.invalid/x" } }] },
      { role: "assistant", tool_calls: [{ id: "c1", function: { name: "read", arguments: '{"id":"a"}' } }] },
      { role: "assistant", function_call: { name: "legacy", arguments: "broken{" } },
      { role: "tool", tool_call_id: "c1", name: "read", content: '{"text":"result"}' },
    ] };
    const original = JSON.stringify(request);
    const view = llmLogView(json(request), json({ choices: [{ text: "done" }] }));
    expect(view?.prompt[0]).toMatchObject({ text: "caption", attachments: [{ type: "image_url" }] });
    expect(view?.prompt[1]?.toolCalls[0]).toEqual({ id: "c1", name: "read", arguments: { id: "a" } });
    expect(view?.prompt[2]?.toolCalls[0]?.arguments).toBe("broken{");
    expect(view?.prompt[3]).toMatchObject({ toolCallId: "c1", name: "read" });
    expect(JSON.stringify(request)).toBe(original);
    expect(view?.completion[0]?.role).toBe("assistant");
  });

  it("falls back for absent, binary, partial and oversized bodies", () => {
    expect(parseDebugBody(null).status).toBe("empty");
    const body: DebugBody = { content: "YWJj", encoding: "BASE64", truncated: false };
    expect(parseDebugBody(body).status).toBe("unsupported");
    expect(parseDebugBody({ ...body, encoding: "UTF8", content: '{"partial":', truncated: true }).status).toBe("invalid");
    expect(parseDebugBody({ ...body, encoding: "UTF8", content: "x".repeat(MAX_FORMATTED_JSON_CHARS + 1) }).status).toBe("large");
    expect(parseDebugBody({ ...body, encoding: "MULTIPART_JSON", content: '{"parts":[]}' }).status).toBe("json");
    expect(prettyJson({ value: 1 })).toBe('{\n  "value": 1\n}');
  });
});
