import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { SystemSettingsPage } from "./SystemSettingsPage";

/** Opening the page reads only the administrator's local diagnostic snapshot. */
describe("SystemSettingsPage", () => {
  it("keeps password inputs when a delayed diagnostic snapshot arrives", async () => {
    let finishRead: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finishRead = resolve; });
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/diagnostics", async () => {
        await pending;
        return HttpResponse.json({
          checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
          llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
          mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
        });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/settings/general?tab=diagnostics"]}><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    await userEvent.setup().click(await screen.findByRole("tab", { name: "账户安全" }));
    const currentPassword = await screen.findByLabelText("当前密码");
    await userEvent.setup().type(currentPassword, "temporary-input");
    await userEvent.setup().click(screen.getByRole("tab", { name: "系统诊断" }));
    finishRead?.();
    await screen.findByText("路径检查正常（未试写）");
    await userEvent.setup().click(await screen.findByRole("tab", { name: "账户安全" }));
    expect(screen.getByLabelText("当前密码")).toBe(currentPassword);
    expect(currentPassword).toHaveValue("temporary-input");
  });

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

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/settings/general?tab=diagnostics"]}><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText("路径检查异常")).toBeInTheDocument();
    expect(screen.getByText(/工具协议未验证/)).toBeInTheDocument();
    expect(screen.getByText(/未知：2 项/)).toBeInTheDocument();
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
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/settings/general?tab=diagnostics"]}><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
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
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/settings/general?tab=diagnostics"]}><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText("路径检查正常（未试写）")).toBeInTheDocument();
    expect(screen.getByText("暂无异常任务记录。")).toBeInTheDocument();
    fail = true;
    await userEvent.setup().click(screen.getByRole("button", { name: "刷新状态" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("保留上一次读取的状态");
    expect(screen.getByText("路径检查正常（未试写）")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "刷新状态" })).toBeEnabled());
  });

  it("defaults to general, reads by category and preserves drafts across tab changes", async () => {
    const diagnostics = vi.fn(); const retention = vi.fn();
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/call-log-retention", () => { retention(); return HttpResponse.json({ retentionDays: null, version: 1 }); }),
      http.get("/api/v1/settings/diagnostics", () => { diagnostics(); return HttpResponse.json({
        checkedAt: "2026-10-01T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE", llmMode: "MOCK", llmConfigured: true,
        llmToolCallingVerified: false, mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      }); }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    expect(await screen.findByRole("tab", { name: "常规" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("link", { name: "系统设置" })).toHaveAttribute("href", "/settings/general");
    expect(diagnostics).not.toHaveBeenCalled(); expect(retention).not.toHaveBeenCalled();
    await user.click(screen.getByRole("tab", { name: "调用日志" }));
    const select = screen.getByRole("combobox", { name: "保留时长" });
    await waitFor(() => expect(select).toBeEnabled());
    await user.selectOptions(select, "custom"); const input = screen.getByRole("spinbutton");
    await user.clear(input); await user.type(input, "77");
    await user.click(screen.getByRole("tab", { name: "常规" }));
    await user.click(screen.getByRole("tab", { name: "调用日志" }));
    expect(screen.getByRole("spinbutton")).toHaveValue(77);
    expect(diagnostics).not.toHaveBeenCalled();
    const logTab = screen.getByRole("tab", { name: "调用日志" }); logTab.focus();
    await user.keyboard("{ArrowRight}");
    expect(screen.getByRole("tab", { name: "系统诊断" })).toHaveFocus();
    expect(screen.getByRole("tab", { name: "系统诊断" })).toHaveAttribute("aria-selected", "true");
    await waitFor(() => expect(diagnostics).toHaveBeenCalledTimes(1));
  });

});
