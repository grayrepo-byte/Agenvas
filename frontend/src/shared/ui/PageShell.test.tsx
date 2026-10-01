import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { PageShell } from "./PageShell";
import { useNavigationStore } from "./navigationStore";
import { getLocale, LOCALE_NAMES, SUPPORTED_LOCALES } from "../i18n";

function showShell(path = "/projects") {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/projects" element={<PageShell title="项目"><p>项目内容</p><input aria-label="临时草稿" defaultValue="" /></PageShell>} />
    <Route path="/settings/llm" element={<PageShell title="模型设置"><p>模型内容</p></PageShell>} />
    <Route path="/settings/media" element={<PageShell title="媒体配置"><p>媒体内容</p></PageShell>} />
    <Route path="/settings/calls" element={<PageShell title="调用日志"><p>调用记录内容</p></PageShell>} />
    <Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

describe("PageShell", () => {
  beforeEach(() => {
    useNavigationStore.setState({ collapsed: false });
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "创作者", role: "ADMIN" })));
  });

  it("preserves collapsed navigation across pages and identifies the active route", async () => {
    showShell("/settings/llm");
    expect(await screen.findByRole("link", { name: "Provider 配置" })).toHaveAttribute("aria-current", "page");
    fireEvent.click(screen.getByRole("button", { name: "收起导航" }));
    fireEvent.click(screen.getByRole("link", { name: "媒体配置" }));
    expect(await screen.findByText("媒体内容")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开导航" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("link", { name: "媒体配置" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "跳至页面内容" })).toHaveAttribute("href", "#page-content");
    fireEvent.click(screen.getByRole("link", { name: "调用日志" }));
    expect(await screen.findByText("调用记录内容")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "调用日志" })).toHaveAttribute("aria-current", "page");
  });

  it("uses a compact language button and a dialog while preserving collapsed navigation and drafts", async () => {
    const user = userEvent.setup();
    showShell();
    const draft = await screen.findByRole("textbox", { name: "临时草稿" });
    await user.type(draft, "保留这份草稿");
    await user.click(screen.getByRole("button", { name: "收起导航" }));
    const trigger = screen.getByRole("button", { name: /界面语言/ });
    expect(trigger).toHaveAttribute("aria-haspopup", "dialog");
    expect(trigger.closest(".language-select")).toHaveClass("language-select--compact");
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    await user.click(trigger);
    const dialog = within(screen.getByRole("dialog", { name: "界面语言" }));
    for (const locale of SUPPORTED_LOCALES) {
      expect(dialog.getByRole("button", { name: LOCALE_NAMES[locale] })).toHaveAttribute("aria-pressed", String(locale === "zh"));
    }
    await user.click(dialog.getByRole("button", { name: "Русский" }));
    expect(getLocale()).toBe("ru");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAccessibleName(/Русский/);
    expect(useNavigationStore.getState().collapsed).toBe(true);
    expect(draft).toHaveValue("保留这份草稿");
    await user.click(trigger);
    // jsdom's dialog fallback does not move focus like native showModal().
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("keeps a failed session read recoverable without pretending the user is logged out", async () => {
    let failed = true;
    server.use(http.get("/api/v1/auth/me", () => failed
      ? HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 })
      : HttpResponse.json({ id: "admin", loginName: "创作者", role: "ADMIN" })));
    showShell();
    expect(await screen.findByRole("alert")).toHaveTextContent("暂时无法读取会话");
    expect(screen.queryByText("项目内容")).not.toBeInTheDocument();
    expect(screen.queryByText("登录页")).not.toBeInTheDocument();
    failed = false;
    fireEvent.click(screen.getByRole("button", { name: "重试连接" }));
    expect(await screen.findByText("项目内容")).toBeInTheDocument();
  });

  it("redirects an unauthorized session to login", async () => {
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ title: "Unauthorized", status: 401 }, { status: 401 })));
    showShell();
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
    expect(screen.queryByText("项目内容")).not.toBeInTheDocument();
  });

  it("preserves page drafts when a cached session fails to refresh", async () => {
    const client = showShell();
    fireEvent.change(await screen.findByRole("textbox", { name: "临时草稿" }), { target: { value: "尚未保存的输入" } });
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 })));
    await client.invalidateQueries({ queryKey: ["auth", "me"] });
    expect(await screen.findByRole("alert")).toHaveTextContent("会话暂时无法刷新");
    expect(screen.getByRole("textbox", { name: "临时草稿" })).toHaveValue("尚未保存的输入");
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "创作者", role: "ADMIN" })));
    fireEvent.click(screen.getByRole("button", { name: "重试连接" }));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(screen.getByRole("textbox", { name: "临时草稿" })).toHaveValue("尚未保存的输入");
  });

  it("retains a failed logout and clears private cached data only after success", async () => {
    let fail = true;
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test-token" })),
      http.post("/api/v1/auth/logout", () => fail ? HttpResponse.json({ title: "Unavailable", status: 503 }, { status: 503 }) : new HttpResponse(null, { status: 204 })),
    );
    const client = showShell();
    client.setQueryData(["private-project"], { id: "project" });
    fireEvent.click(await screen.findByRole("button", { name: "退出登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("退出失败");
    expect(client.getQueryData(["private-project"])).toBeDefined();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "退出登录" }));
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
    await waitFor(() => expect(client.getQueryData(["private-project"])).toBeUndefined());
  });
});
