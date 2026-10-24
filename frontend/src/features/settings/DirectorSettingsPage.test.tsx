import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import type { DirectorSettings } from "../../shared/api/client";
import { server } from "../../test/server";
import { DirectorSettingsPage } from "./DirectorSettingsPage";

const endpoint = "/api/v1/settings/director";
const initial: DirectorSettings = { contextWindowTokens: 1_000_000, compactionThresholdPercent: 50,
  responseReserveTokens: 8192, summaryTokens: 2048, recentToolRounds: 4, recoveryWindowMinutes: 1440,
  maxModelRetries: 288, retryMaxDelaySeconds: 300, maxRepairAttempts: 4, version: 1 };
function show() {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={["/settings/director"]}><Routes>
    <Route path="/settings/director" element={<DirectorSettingsPage />} />
    <Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}
function change(label: string, value: string) { fireEvent.change(screen.getByLabelText(label), { target: { value } }); }

describe("DirectorSettingsPage", () => {
  beforeEach(() => {
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.get(endpoint, () => HttpResponse.json(initial)));
  });

  it("loads server settings, shows the half-window trigger and exposes the real navigation", async () => {
    show();
    expect(await screen.findByLabelText("上下文窗口（Token）")).toHaveValue(1_000_000);
    expect(screen.getByLabelText("自动压缩阈值（%）")).toHaveValue(50);
    expect(screen.getByText("500,000 个估算 Token")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "导演设置" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled();
    expect(screen.getAllByRole("spinbutton")).toHaveLength(9);
  });

  it("saves the complete configuration with the edited version and shows the new trigger", async () => {
    const writes: unknown[] = [];
    server.use(http.put(endpoint, async ({ request }) => {
      writes.push(await request.json());
      return HttpResponse.json({ ...initial, contextWindowTokens: 200_000, compactionThresholdPercent: 25, version: 2 });
    }));
    show(); await screen.findByLabelText("上下文窗口（Token）");
    change("上下文窗口（Token）", "200000"); change("自动压缩阈值（%）", "25");
    expect(screen.getByText("50,000 个估算 Token")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "保存导演设置" }));
    await screen.findByText("导演设置已保存，无需重启。");
    const { version, ...values } = initial;
    expect(writes).toEqual([{ ...values, contextWindowTokens: 200_000, compactionThresholdPercent: 25, expectedVersion: version }]);
    expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled();
  });

  it("retains the draft and original version after a failed save and allows retry", async () => {
    let fail = true;
    const writes: unknown[] = [];
    server.use(http.put(endpoint, async ({ request }) => {
      writes.push(await request.json());
      return fail ? HttpResponse.json({ code: "UNAVAILABLE" }, { status: 503 }) : HttpResponse.json({ ...initial, contextWindowTokens: 750_000, version: 2 });
    }));
    show(); await screen.findByLabelText("上下文窗口（Token）"); change("上下文窗口（Token）", "750000");
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "保存导演设置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("草稿已保留");
    expect(screen.getByLabelText("上下文窗口（Token）")).toHaveValue(750_000);
    fail = false; await user.click(screen.getByRole("button", { name: "保存导演设置" }));
    await screen.findByText("导演设置已保存，无需重启。"); expect(writes[1]).toEqual(writes[0]);
  });

  it("requires an explicit latest-version load after a conflict", async () => {
    let newer = false;
    server.use(http.get(endpoint, () => HttpResponse.json(newer ? { ...initial, contextWindowTokens: 900_000, version: 2 } : initial)),
      http.put(endpoint, () => { newer = true; return HttpResponse.json({ code: "VERSION_CONFLICT" }, { status: 409 }); }));
    show(); await screen.findByLabelText("上下文窗口（Token）"); change("上下文窗口（Token）", "750000");
    const user = userEvent.setup(); await user.click(screen.getByRole("button", { name: "保存导演设置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("导演设置已有新版本");
    expect(screen.getByLabelText("上下文窗口（Token）")).toHaveValue(750_000);
    change("自动压缩阈值（%）", "60");
    expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "载入最新设置" }));
    await waitFor(() => expect(screen.getByLabelText("上下文窗口（Token）")).toHaveValue(900_000));
    expect(screen.getByLabelText("自动压缩阈值（%）")).toHaveValue(50);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("keeps the draft version when a background read discovers another administrator’s save", async () => {
    const client = show(); await screen.findByLabelText("上下文窗口（Token）");
    change("上下文窗口（Token）", "750000");
    act(() => client.setQueryData(["settings", "director"], { ...initial, version: 2 }));
    expect(screen.getByLabelText("上下文窗口（Token）")).toHaveValue(750_000);
    await waitFor(() => expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled());
    expect(screen.getByRole("alert")).toHaveTextContent("草稿仍保留");
  });

  it("validates cross-field budgets, percentages, integers and empty values", async () => {
    show(); await screen.findByLabelText("上下文窗口（Token）");
    change("上下文窗口（Token）", "8192");
    expect(screen.getByLabelText("响应预留（Token）")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled();
    change("响应预留（Token）", "1024"); change("摘要预算（Token）", "512");
    expect(screen.getByRole("button", { name: "保存导演设置" })).toBeEnabled();
    for (const invalid of ["101", "0", "20.5", ""]) {
      change("自动压缩阈值（%）", invalid);
      expect(screen.getByLabelText("自动压缩阈值（%）")).toHaveAttribute("aria-invalid", "true");
      expect(screen.getByRole("button", { name: "保存导演设置" })).toBeDisabled();
    }
  });

  it("does not display or save invented defaults after a failed initial read", async () => {
    let fail = true;
    server.use(http.get(endpoint, () => fail ? HttpResponse.json({ code: "UNAVAILABLE" }, { status: 503 }) : HttpResponse.json(initial)));
    show(); expect(await screen.findByRole("alert")).toHaveTextContent("导演设置读取失败");
    expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
    fail = false; await userEvent.setup().click(screen.getByRole("button", { name: "载入最新设置" }));
    expect(await screen.findByLabelText("上下文窗口（Token）")).toHaveValue(1_000_000);
  });

  it("blocks non-administrators without requesting director configuration", async () => {
    const read = vi.fn();
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "reader", role: "USER", loginName: "reader" })),
      http.get(endpoint, () => { read(); return HttpResponse.json(initial); }));
    show(); expect(await screen.findByRole("alert")).toHaveTextContent("仅管理员");
    expect(read).not.toHaveBeenCalled(); expect(screen.queryByRole("spinbutton")).not.toBeInTheDocument();
  });

  it("redirects an expired session from the settings endpoint", async () => {
    server.use(http.get(endpoint, () => HttpResponse.json({ code: "UNAUTHENTICATED" }, { status: 401 })));
    show(); expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });
});
