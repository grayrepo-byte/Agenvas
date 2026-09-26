import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { PageShell } from "./PageShell";
import { useNavigationStore } from "./navigationStore";

function showShell(path = "/projects") {
  const client = createQueryClient();
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/projects" element={<PageShell title="项目"><p>项目内容</p><input aria-label="临时草稿" defaultValue="" /></PageShell>} />
    <Route path="/settings/llm" element={<PageShell title="模型设置"><p>模型内容</p></PageShell>} />
    <Route path="/settings/media" element={<PageShell title="媒体配置"><p>媒体内容</p></PageShell>} />
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
