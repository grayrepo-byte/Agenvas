import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import type { SystemLogSnapshot } from "../../shared/api/client";
import { SystemLogsPage } from "./SystemLogsPage";

const snapshot: SystemLogSnapshot = {
  processId: "9d69523f-c971-483f-9573-552e9d26c62c", startedAt: "2026-09-30T00:00:00Z",
  checkedAt: "2026-09-30T00:01:00Z", capacity: 2000, retainedCount: 2, droppedCount: 0, matchedCount: 2,
  entries: [
    { sequence: 1, recordedAt: "2026-09-30T00:00:00Z", stream: "STDOUT", message: "Started AgenvasApplication", truncated: false },
    { sequence: 2, recordedAt: "2026-09-30T00:00:01Z", stream: "STDERR", message: "<script>unsafe()</script> apiKey=[REDACTED]", truncated: true },
  ],
};
function showPage() {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/settings/logs"]}><Routes>
    <Route path="/settings/logs" element={<SystemLogsPage />} />
    <Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

describe("SystemLogsPage", () => {
  beforeEach(() => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/system-logs", () => HttpResponse.json(snapshot)),
    );
  });

  it("renders both channels as safe text with truncation and retention notices", async () => {
    server.use(http.get("/api/v1/settings/system-logs", () => HttpResponse.json({ ...snapshot, droppedCount: 35, matchedCount: 20 })));
    showPage();
    expect(await screen.findByText("Started AgenvasApplication")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "系统日志" })).toHaveAttribute("aria-current", "page");
    const console = screen.getByRole("region", { name: "系统日志输出" });
    expect(console).toHaveTextContent("STDOUT");
    expect(console).toHaveTextContent("STDERR");
    expect(console).toHaveTextContent("<script>unsafe()</script>");
    expect(console.querySelector("script")).toBeNull();
    expect(console).toHaveTextContent("该行过长，尾部已截断");
    expect(screen.getByText(/已淘汰 35 行/)).toBeInTheDocument();
    expect(screen.getByText(/仅显示最新的 2 条/)).toBeInTheDocument();
  });

  it("applies server-side stream, search and limit filters without issuing writes", async () => {
    const requests: URL[] = [];
    server.use(http.get("/api/v1/settings/system-logs", ({ request }) => {
      requests.push(new URL(request.url));
      return HttpResponse.json(snapshot);
    }));
    showPage();
    await screen.findByText("Started AgenvasApplication");
    fireEvent.change(screen.getByLabelText("输出通道"), { target: { value: "STDERR" } });
    fireEvent.change(screen.getByLabelText("关键词"), { target: { value: "  Trace ID  " } });
    fireEvent.click(screen.getByRole("button", { name: "搜索日志" }));
    fireEvent.change(screen.getByLabelText("显示行数"), { target: { value: "1000" } });
    await waitFor(() => {
      const latest = requests.at(-1);
      expect(latest?.searchParams.get("stream")).toBe("STDERR");
      expect(latest?.searchParams.get("search")).toBe("Trace ID");
      expect(latest?.searchParams.get("limit")).toBe("1000");
    });
  });

  it("retains the previous snapshot after a failed refresh and recovers on manual retry", async () => {
    let fail = false;
    server.use(http.get("/api/v1/settings/system-logs", () => fail
      ? HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }) : HttpResponse.json(snapshot)));
    showPage();
    await screen.findByText("Started AgenvasApplication");
    fail = true;
    fireEvent.click(screen.getByRole("button", { name: "刷新日志" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保留上一次成功读取的日志");
    expect(screen.getByText("Started AgenvasApplication")).toBeInTheDocument();
    expect(screen.getByText("刷新已停止，请手动重试")).toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "刷新日志" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  it("shows loading and an empty filtered result", async () => {
    let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(http.get("/api/v1/settings/system-logs", async () => {
      await pending;
      return HttpResponse.json({ ...snapshot, retainedCount: 0, matchedCount: 0, entries: [] });
    }));
    showPage();
    await screen.findByLabelText("正在读取系统日志…");
    finish?.();
    expect(await screen.findByText("暂无控制台输出")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("关键词"), { target: { value: "missing" } });
    fireEvent.click(screen.getByRole("button", { name: "搜索日志" }));
    expect(await screen.findByText("没有匹配的日志")).toBeInTheDocument();
  });

  it("redirects expired sessions and reports forbidden access", async () => {
    server.use(http.get("/api/v1/settings/system-logs", () => HttpResponse.json({ status: 403 }, { status: 403 })));
    const client = showPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("仅管理员");
    server.use(http.get("/api/v1/settings/system-logs", () => HttpResponse.json({ status: 401 }, { status: 401 })));
    await act(() => client.invalidateQueries({ queryKey: ["settings", "system-logs"] }));
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });

  it("polls, pauses, and replaces output after a process restart", async () => {
    const requests = vi.fn();
    let restarted = false;
    server.use(http.get("/api/v1/settings/system-logs", () => {
      requests();
      return HttpResponse.json(restarted ? { ...snapshot, processId: "new-process", retainedCount: 1, matchedCount: 1,
        entries: [{ ...snapshot.entries[0], sequence: 1, message: "New process started" }] } : snapshot);
    }));
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    try {
      showPage();
      await screen.findByText("Started AgenvasApplication");
      restarted = true;
      await act(async () => { await vi.advanceTimersByTimeAsync(3100); });
      expect(await screen.findByText("New process started")).toBeInTheDocument();
      expect(requests).toHaveBeenCalledTimes(2);
      expect(screen.queryByText("Started AgenvasApplication")).not.toBeInTheDocument();
      fireEvent.click(screen.getByLabelText("自动刷新（每 3 秒）"));
      await act(async () => { await vi.advanceTimersByTimeAsync(6000); });
      expect(requests).toHaveBeenCalledTimes(2);
      expect(screen.getByText("已暂停自动刷新")).toBeInTheDocument();
    } finally { vi.useRealTimers(); }
  });
});
