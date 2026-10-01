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
    expect(await screen.findByText(/本次调用未开启 debug 模式/)).toBeInTheDocument();
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
