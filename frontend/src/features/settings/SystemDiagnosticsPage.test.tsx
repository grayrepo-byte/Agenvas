import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { SystemDiagnosticsPage } from "./SystemDiagnosticsPage";

/** Opening the page reads only the administrator's local diagnostic snapshot. */
describe("SystemDiagnosticsPage", () => {
  it("renders redacted local states and refreshes with GET only", async () => {
    const requests = vi.fn();
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/diagnostics", () => {
        requests();
        return HttpResponse.json({
          checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "UNAVAILABLE",
          llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
          mediaMode: "COMFYUI", imageConfigured: true, videoConfigured: false,
          recentErrors: [{ status: "UNKNOWN", count: 2, lastAt: "2026-09-24T00:00:00Z" }],
        });
      }),
    );

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SystemDiagnosticsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText("路径检查异常")).toBeInTheDocument();
    expect(screen.getByText(/工具协议未验证/)).toBeInTheDocument();
    expect(screen.getByText(/待核对：2 项/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("apiKey");
    await userEvent.setup().click(screen.getByRole("button", { name: "刷新状态" }));
    expect(requests).toHaveBeenCalledTimes(2);
  });

  it("shows an actionable read failure without triggering Provider diagnostics", async () => {
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        title: "Unavailable", status: 503, code: "DATABASE_UNAVAILABLE",
      }, { status: 503 })),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SystemDiagnosticsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("数据库或会话可能不可用");
  });

  it("retains the last local snapshot when a refresh fails", async () => {
    let fail = false;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/diagnostics", () => fail ? HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }) : HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SystemDiagnosticsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText("路径检查正常（未试写）")).toBeInTheDocument();
    expect(screen.getByText("暂无异常任务记录。")).toBeInTheDocument();
    fail = true;
    await userEvent.setup().click(screen.getByRole("button", { name: "刷新状态" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保留上一次读取的状态");
    expect(screen.getByText("路径检查正常（未试写）")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "刷新状态" })).toBeEnabled());
  });

});
