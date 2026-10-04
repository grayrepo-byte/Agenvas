import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { CallDebugDetails } from "./CallDebugDetails";

function show() {
  const client = createQueryClient();
  const rendered = render(<QueryClientProvider client={client}><MemoryRouter><Routes>
    <Route path="/" element={<CallDebugDetails id="log" />} />
    <Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...rendered, client };
}

describe("CallDebugDetails", () => {
  it("renders real URL and bodies as plain text with explicit partial marker", async () => {
    server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: true,
      exchanges: [{ method: "POST", url: "https://provider.example/v1/chat/completions", responseStatus: 500,
        requestBody: { content: '{"prompt":"<script>unsafe</script>"}', encoding: "UTF8", truncated: false },
        responseBody: { content: "partial response", encoding: "UTF8", truncated: true } }] })));
    const { unmount, client } = show();
    expect(await screen.findByText("https://provider.example/v1/chat/completions")).toBeInTheDocument();
    expect(screen.getByText(/HTTP 500/)).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "原始内容" }));
    expect(screen.getByText('{"prompt":"<script>unsafe</script>"}')).toBeInTheDocument();
    expect(document.querySelector("script")).toBeNull();
    expect(screen.getByText("partial response")).toBeInTheDocument();
    expect(screen.getByText(/响应正文 · UTF8 · 超限或未读完/)).toBeInTheDocument();
    unmount();
    // No raw body is kept in a long-lived query cache after collapsing the row.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(client.getQueryData(["call-debug", "log"])).toBeUndefined();
  });

  it("explains absent capture and does not invent a Mock request", async () => {
    const { unmount } = show();
    expect(await screen.findByText(/本次调用尚无 debug 正文/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "系统设置" })).toHaveAttribute("href", "/settings/general?tab=logs");
    unmount();
    server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: true, exchanges: [] })));
    show();
    expect(await screen.findByText(/Mock 调用没有真实请求地址/)).toBeInTheDocument();
  });

  it("shows read failures and retries, then redirects an expired session", async () => {
    server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ status: 503 }, { status: 503 })));
    show();
    expect(await screen.findByText("读取调用正文失败")).toBeInTheDocument();
    server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ status: 401 }, { status: 401 })));
    await userEvent.setup().click(screen.getByRole("button", { name: "重试读取正文" }));
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });
});

const streamMetrics = { schemaVersion: 2, firstChunkMs: 12, firstTextMs: 42, durationMs: 300,
  chunkCount: 3, promptTokens: null, completionTokens: null, totalTokens: null,
  model: "synthetic-model", responseId: "synthetic-response", finishReasons: [], status: "FAILED", errorCode: "CALL_STREAM_FAILED" };

it("formats a streamed LLM completion beside its captured prompt instead of leaving it as a separate JSON dump", async () => {
  server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: true,
    exchanges: [{ method: "POST", url: "https://provider.invalid/v1/chat/completions", responseStatus: 200,
      requestBody: { content: JSON.stringify({ model: "synthetic-model", stream: true,
        messages: [{ role: "user", content: "Create synthetic text" }] }), encoding: "UTF8", truncated: false },
      responseBody: { content: 'data: {"legacyFragment":"Synthetic legacy SSE"}\n\ndata: [DONE]\n\n', encoding: "UTF8", truncated: false } }],
    llmStream: { metrics: { ...streamMetrics, status: "COMPLETED", errorCode: null }, content: {
      response: JSON.stringify({ schemaVersion: 1, metadata: { id: "synthetic-response", model: "synthetic-model", usage: null },
        generations: [{ assistant: { role: "ASSISTANT", text: "Synthetic public answer", toolCalls: [], metadata: {} },
          metadata: { finishReason: "STOP" } }] }), truncated: false,
    } },
  })));
  show();
  const completion = await screen.findByRole("button", { name: "展开 Completion" });
  expect(completion).toBeEnabled();
  expect(screen.getAllByText("synthetic-response")).toHaveLength(1);
  expect(screen.getByRole("button", { name: "展开 Prompt" })).toBeEnabled();
  await userEvent.setup().click(completion);
  expect(screen.getByRole("dialog", { name: "Completion" })).toHaveTextContent("Synthetic public answer");
  await userEvent.setup().keyboard("{Escape}");
  await userEvent.setup().click(screen.getByRole("button", { name: "原始内容" }));
  expect(screen.queryByText(/Synthetic legacy SSE/)).not.toBeInTheDocument();
  expect(screen.getAllByText(/模型响应（JSON）/)).toHaveLength(1);
});

it("shows one partial model response and timing without delivery progress or remote rendering", async () => {
  server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: true, exchanges: [],
    llmStream: { metrics: streamMetrics, content: { response: JSON.stringify({ generations: [{ assistant: { text: '<script>received tail</script> https://example.invalid/image.png' } }] }), truncated: false } } })));
  const { unmount, client } = show();
  expect(await screen.findByText("首字延迟")).toBeInTheDocument();
  expect(screen.getByText("42 ms")).toBeInTheDocument();
  expect(screen.queryByText("首次输出延迟")).not.toBeInTheDocument();
  expect(screen.queryByText("展示输出")).not.toBeInTheDocument();
  expect(screen.queryByText("输出批次数")).not.toBeInTheDocument();
  expect(screen.getByText(/模型响应（JSON）/)).toBeInTheDocument();
  expect(screen.getByText(/received tail/)).toBeInTheDocument();
  expect(screen.getByText(/以下内容可能不完整/)).toBeInTheDocument();
  expect(document.querySelector("script, img")).toBeNull();
  unmount();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(client.getQueryData(["call-debug", "log"])).toBeUndefined();
});

it("shows metrics with debug disabled and lets an asynchronously completed log be reloaded", async () => {
  let saved = false;
  server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: false, exchanges: [],
    llmStream: saved ? { metrics: streamMetrics, content: null } : null })));
  show();
  expect(await screen.findByText(/本次调用尚无 debug 正文/)).toBeInTheDocument();
  saved = true;
  await userEvent.setup().click(screen.getByRole("button", { name: "重新读取日志" }));
  expect(await screen.findByText("首字延迟")).toBeInTheDocument();
  expect(screen.getByText("本次只记录指标。查看模型响应需在调用前开启 debug 模式。")).toBeInTheDocument();
  expect(screen.queryByText("模型响应（JSON）")).not.toBeInTheDocument();
});

it("switches a semantic-only response to exact captured JSON without inventing a prompt", async () => {
  const response = '{"schemaVersion":1,"generations":[{"assistant":{"role":"ASSISTANT","text":"<think>synthetic thought</think>Actual answer","toolCalls":[],"metadata":{"reasoningContent":"synthetic reasoning"}}}]}';
  server.use(http.get("/api/v1/call-logs/log/debug", () => HttpResponse.json({ id: "log", captured: true, exchanges: [],
    llmStream: { metrics: { ...streamMetrics, status: "COMPLETED" }, content: { response, truncated: false } } })));
  show();
  expect(await screen.findByRole("button", { name: "展开 Completion" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "展开 Prompt" })).toBeDisabled();
  expect(screen.getByText(/实际 LLM 请求与响应正文保留/)).toHaveTextContent(/仅省略结构化图片字段字节/);
  await userEvent.setup().click(screen.getByRole("button", { name: "原始内容" }));
  expect(screen.getByText(response)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "展开 Completion" })).not.toBeInTheDocument();
  expect(document.querySelector("think")).toBeNull();
});
