import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { type McpTokenSummary } from "../../shared/api/client";
import { server } from "../../test/server";
import { McpTokensSection } from "./McpTokensSection";
import { SystemSettingsPage } from "./SystemSettingsPage";

const SAFE_TOKEN: McpTokenSummary = { id: "credential-1", name: "Synthetic client", tokenHint: "agv_mcp_fixture…", scopes: ["READ"], version: 0,
  createdAt: "2026-10-01T00:00:00Z", expiresAt: "2099-11-01T00:00:00Z", revokedAt: null, lastUsedAt: null };
const SECRET = "agv_mcp_synthetic_secret_only_for_this_component_test";

function renderSection() {
  const client = createQueryClient();
  const view = render(<QueryClientProvider client={client}><MemoryRouter><Routes>
    <Route path="/" element={<McpTokensSection enabled userId="synthetic-owner" />} />
    <Route path="/login" element={<p>Login destination</p>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return { ...view, client };
}

describe("McpTokensSection", () => {
  it("shows safe metadata and explicit active, expired and revoked states", async () => {
    server.use(http.get("/api/v1/account/mcp-tokens", () => HttpResponse.json([
      SAFE_TOKEN,
      { ...SAFE_TOKEN, id: "expired", name: "Expired client", expiresAt: "2025-01-01T00:00:00Z" },
      { ...SAFE_TOKEN, id: "revoked", name: "Revoked client", revokedAt: "2026-10-01T00:00:00Z" },
    ])));
    renderSection();
    const list = await screen.findByRole("list", { name: "MCP 访问令牌列表" });
    expect(within(list).getByText("有效")).toBeInTheDocument();
    expect(within(list).getByText("已过期")).toBeInTheDocument();
    expect(within(list).getByText("已撤销")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "撤销令牌 Expired client" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "撤销令牌 Revoked client" })).toBeDisabled();
    expect(screen.queryByLabelText("新访问令牌")).not.toBeInTheDocument();
    expect(screen.getByLabelText("MCP 服务地址")).toHaveValue(`${window.location.origin}/mcp`);
    expect(screen.getByText(/claude mcp add/)).toHaveTextContent("${AGENVAS_MCP_TOKEN}");
    expect(screen.getByRole("checkbox", { name: "读取项目与素材" })).toBeChecked();
    expect(screen.getByRole("checkbox", { name: "读取项目与素材" })).toBeDisabled();
  });

  it("creates with session CSRF, preserves the pending form and keeps the secret out of every cache", async () => {
    let finish: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { finish = resolve; });
    const requests: unknown[] = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" })),
      http.post("/api/v1/account/mcp-tokens", async ({ request }) => {
        expect(request.headers.get("X-CSRF-TOKEN")).toBeTruthy();
        expect(request.headers.get("Authorization")).toBeNull();
        requests.push(await request.json()); await gate;
        return HttpResponse.json({ credential: { ...SAFE_TOKEN, scopes: ["READ", "MEDIA_GENERATE"] }, token: SECRET }, { status: 201 });
      }));
    const user = userEvent.setup();
    const { client, unmount } = renderSection();
    await screen.findByText("还没有 MCP 访问令牌");
    await user.type(screen.getByLabelText("令牌名称"), "Synthetic client");
    await user.click(screen.getByRole("checkbox", { name: "直接生成媒体" }));
    await user.click(screen.getByRole("button", { name: "创建访问令牌" }));
    await waitFor(() => expect(requests).toEqual([{ name: "Synthetic client", scopes: ["READ", "MEDIA_GENERATE"], expiresInDays: 30 }]));
    expect(screen.getByLabelText("令牌名称")).toHaveValue("Synthetic client");
    expect(screen.getByRole("button", { name: "正在创建…" })).toBeDisabled();
    finish?.();
    expect(await screen.findByLabelText("新访问令牌")).toHaveValue(SECRET);
    expect(screen.getByRole("button", { name: "创建访问令牌" })).toBeDisabled();
    expect(JSON.stringify(client.getQueryCache().getAll().map((query) => query.state))).not.toContain(SECRET);
    expect(client.getMutationCache().getAll()).toHaveLength(0);
    expect(JSON.stringify(localStorage)).not.toContain(SECRET);
    expect(JSON.stringify(sessionStorage)).not.toContain(SECRET);
    expect(window.location.href).not.toContain(SECRET);
    expect(screen.getByText(/claude mcp add/)).not.toHaveTextContent(SECRET);
    await user.click(screen.getByRole("button", { name: "复制令牌" }));
    expect(await navigator.clipboard.readText()).toBe(SECRET);
    await user.click(screen.getByRole("button", { name: "我已保存，隐藏令牌" }));
    expect(screen.queryByLabelText("新访问令牌")).not.toBeInTheDocument();
    expect(screen.getByText("Synthetic client")).toBeInTheDocument();
    unmount();
    render(<QueryClientProvider client={client}><MemoryRouter><McpTokensSection enabled userId="synthetic-owner" /></MemoryRouter></QueryClientProvider>);
    expect(screen.queryByLabelText("新访问令牌")).not.toBeInTheDocument();
  });

  it("retains name, scopes and expiry after a failed create and allows an explicit retry", async () => {
    let fail = true;
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.post("/api/v1/account/mcp-tokens", async ({ request }) => {
        const body = await request.json();
        expect(body).toEqual({ name: "Keep draft", scopes: ["READ", "CONTENT_WRITE"], expiresInDays: 7 });
        return fail ? HttpResponse.json({ title: "Unavailable", detail: "Synthetic connection failure", status: 503 }, { status: 503 })
          : HttpResponse.json({ credential: { ...SAFE_TOKEN, name: "Keep draft" }, token: SECRET }, { status: 201 });
      }));
    const user = userEvent.setup(); renderSection(); await screen.findByText("还没有 MCP 访问令牌");
    await user.type(screen.getByLabelText("令牌名称"), "Keep draft");
    await user.clear(screen.getByLabelText("有效期（天）")); await user.type(screen.getByLabelText("有效期（天）"), "7");
    await user.click(screen.getByRole("checkbox", { name: "修改内容与画布" }));
    await user.click(screen.getByRole("button", { name: "创建访问令牌" }));
    await screen.findByText("无法创建 MCP 访问令牌");
    expect(screen.getByLabelText("令牌名称")).toHaveValue("Keep draft");
    expect(screen.getByLabelText("有效期（天）")).toHaveValue(7);
    expect(screen.getByRole("checkbox", { name: "修改内容与画布" })).toBeChecked();
    fail = false;
    await user.click(screen.getByRole("button", { name: "创建访问令牌" }));
    expect(await screen.findByLabelText("新访问令牌")).toHaveValue(SECRET);
  });

  it("rechecks a conflicting revocation and submits the refreshed version while preserving another draft", async () => {
    let version = 0; const writes: unknown[] = [];
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ token: "synthetic", headerName: "X-CSRF-TOKEN" })),
      http.get("/api/v1/account/mcp-tokens", () => HttpResponse.json([{ ...SAFE_TOKEN, version }])),
      http.post("/api/v1/account/mcp-tokens/:tokenId/revoke", async ({ request }) => {
        writes.push(await request.json());
        if (version === 0) { version = 1; return HttpResponse.json({ title: "Conflict", status: 409 }, { status: 409 }); }
        return HttpResponse.json({ ...SAFE_TOKEN, version: 2, revokedAt: "2026-10-22T00:00:00Z" });
      }));
    const user = userEvent.setup(); renderSection(); await screen.findByText("Synthetic client");
    await user.type(screen.getByLabelText("令牌名称"), "Unsent client");
    await user.click(screen.getByRole("button", { name: "撤销令牌 Synthetic client" }));
    await screen.findByText("令牌状态已变化，请刷新后重试");
    await user.click(screen.getByRole("button", { name: "重试" }));
    await waitFor(() => expect(screen.queryByText("令牌状态已变化，请刷新后重试")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "撤销令牌 Synthetic client" }));
    await screen.findByText("已撤销");
    expect(writes).toEqual([{ expectedVersion: 0 }, { expectedVersion: 1 }]);
    expect(screen.getByLabelText("令牌名称")).toHaveValue("Unsent client");
    expect(screen.getByRole("button", { name: "撤销令牌 Synthetic client" })).toBeDisabled();
  });

  it("disables credential creation when access is forbidden", async () => {
    server.use(http.get("/api/v1/account/mcp-tokens", () => HttpResponse.json({ title: "Forbidden", status: 403 }, { status: 403 })));
    renderSection(); await screen.findByText("没有管理访问令牌的权限");
    expect(screen.getByLabelText("令牌名称")).toBeDisabled();
    expect(screen.getByRole("button", { name: "创建访问令牌" })).toBeDisabled();
  });

  it("routes an expired web session to login", async () => {
    server.use(http.get("/api/v1/account/mcp-tokens", () => HttpResponse.json({ title: "Unauthorized", status: 401 }, { status: 401 })));
    renderSection(); expect(await screen.findByText("Login destination")).toBeInTheDocument();
  });

  it("reads tokens only in the security category and keeps unsaved edits across tabs", async () => {
    const reads = vi.fn();
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "owner", loginName: "owner", role: "ADMIN" })),
      http.get("/api/v1/account/mcp-tokens", () => { reads(); return HttpResponse.json([]); }));
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><SystemSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup(); await screen.findByRole("tab", { name: "常规" }); expect(reads).not.toHaveBeenCalled();
    await user.click(screen.getByRole("tab", { name: "账户安全" })); await screen.findByText("还没有 MCP 访问令牌");
    await user.type(screen.getByLabelText("令牌名称"), "Keep across tabs");
    await user.click(screen.getByRole("tab", { name: "常规" }));
    await user.click(screen.getByRole("tab", { name: "账户安全" }));
    expect(screen.getByLabelText("令牌名称")).toHaveValue("Keep across tabs");
    expect(reads).toHaveBeenCalledOnce();
  });
});
