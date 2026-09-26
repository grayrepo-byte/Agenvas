import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { SetupPage } from "./SetupPage";

function showPage() {
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter initialEntries={["/setup"]}><Routes><Route path="/setup" element={<SetupPage />} /><Route path="/login" element={<p>登录页</p>} /></Routes></MemoryRouter></QueryClientProvider>);
}

describe("SetupPage", () => {
  it("shows server-backed initialization without inventing provider status", async () => {
    showPage();
    expect(screen.getByText("自托管模式 · Provider 状态登录后可查看")).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("不会调用外部模型");
    expect(await screen.findByRole("button", { name: "创建管理员" })).toBeInTheDocument();
  });

  it("retries unavailable setup status and presents login when already initialized", async () => {
    let calls = 0;
    server.use(http.get("/api/v1/auth/setup-status", () => { calls += 1; return calls === 1 ? new HttpResponse(null, { status: 503 }) : HttpResponse.json({ setupRequired: false }); }));
    showPage(); const user = userEvent.setup();
    expect(await screen.findByRole("alert")).toHaveTextContent("服务端暂不可用");
    await user.click(screen.getByRole("button", { name: "重新连接" }));
    expect(await screen.findByRole("link", { name: "前往登录" })).toHaveAttribute("href", "/login");
    expect(screen.queryByRole("button", { name: "创建管理员" })).not.toBeInTheDocument();
  });

  it("keeps fields on rejection and disables duplicate setup submissions", async () => {
    const submitted: unknown[] = []; let finish: (() => void) | undefined;
    const pending = new Promise<void>((resolve) => { finish = resolve; });
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/auth/setup", async ({ request }) => {
        submitted.push(await request.json()); expect(request.headers.get("X-Agenvas-Bootstrap-Secret")).toBe("initialization-secret-123456");
        await pending;
        return HttpResponse.json({ code: "INVALID_BOOTSTRAP", title: "初始化失败", detail: "初始化密钥无效", retryable: false }, { status: 403, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    showPage(); const user = userEvent.setup();
    await screen.findByRole("button", { name: "创建管理员" });
    const secret = screen.getByLabelText(/初始化密钥/); const password = screen.getByLabelText(/管理员密码/);
    await user.type(secret, "initialization-secret-123456"); await user.type(password, "valid-password-123");
    await user.click(screen.getByRole("button", { name: "创建管理员" }));
    expect(screen.getByRole("button", { name: "正在创建…" })).toBeDisabled(); expect(secret).toBeDisabled();
    finish?.();
    expect(await screen.findByRole("alert")).toHaveTextContent("初始化密钥无效");
    expect(secret).toHaveValue("initialization-secret-123456"); expect(password).toHaveValue("valid-password-123");
    expect(submitted).toEqual([{ loginName: "admin", password: "valid-password-123" }]);
  });
});
