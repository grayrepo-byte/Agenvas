import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { DebugModeSection } from "./DebugModeSection";

function show() {
  render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><Routes>
    <Route path="/" element={<DebugModeSection enabled />} />
    <Route path="/login" element={<h1>登录页</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
}

describe("DebugModeSection", () => {
  it("starts disabled, explains risk, and saves using server version", async () => {
    const save = vi.fn();
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "csrf" })),
      http.put("/api/v1/settings/debug", async ({ request }) => {
        save(await request.json());
        return HttpResponse.json({ debugMode: true, version: 2 });
      }));
    show();
    const checkbox = await screen.findByRole("checkbox", { name: "开启 debug 模式" });
    expect(checkbox).not.toBeChecked();
    expect(await screen.findByText("已关闭")).toBeInTheDocument();
    expect(screen.getByText(/可能包含完整提示词/)).toBeInTheDocument();
    expect(screen.getByText(/所有 header 均不保存/)).toBeInTheDocument();
    await userEvent.setup().click(checkbox);
    await userEvent.setup().click(screen.getByRole("button", { name: "保存 debug 设置" }));
    expect(await screen.findByText("Debug 模式已开启。")).toBeInTheDocument();
    expect(save).toHaveBeenCalledWith({ debugMode: true, expectedVersion: 1 });
  });

  it("keeps the choice after a conflict, reloads version, and retries", async () => {
    let version = 1;
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "csrf" })),
      http.get("/api/v1/settings/debug", () => HttpResponse.json({ debugMode: false, version })),
      http.put("/api/v1/settings/debug", async ({ request }) => {
        const body = await request.json() as { expectedVersion: number };
        if (body.expectedVersion === 1) { version = 2; return HttpResponse.json({ code: "VERSION_CONFLICT", status: 409 }, { status: 409 }); }
        return HttpResponse.json({ debugMode: true, version: 3 });
      }));
    show();
    await screen.findByText("已关闭");
    await userEvent.setup().click(screen.getByRole("checkbox"));
    await userEvent.setup().click(screen.getByRole("button", { name: "保存 debug 设置" }));
    expect(await screen.findByText("Debug 设置已变化")).toBeInTheDocument();
    expect(screen.getByRole("checkbox")).toBeChecked();
    await userEvent.setup().click(screen.getByRole("button", { name: "重新读取设置" }));
    await userEvent.setup().click(screen.getByRole("button", { name: "保存 debug 设置" }));
    expect(await screen.findByText("Debug 模式已开启。")).toBeInTheDocument();
  });

  it("supports switching off an enabled setting", async () => {
    const save = vi.fn();
    server.use(http.get("/api/v1/settings/debug", () => HttpResponse.json({ debugMode: true, version: 5 })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "csrf" })),
      http.put("/api/v1/settings/debug", async ({ request }) => { save(await request.json()); return HttpResponse.json({ debugMode: false, version: 6 }); }));
    show();
    await screen.findByText("已开启");
    await userEvent.setup().click(screen.getByRole("checkbox"));
    await userEvent.setup().click(screen.getByRole("button", { name: "保存 debug 设置" }));
    expect(await screen.findByText("Debug 模式已关闭。")).toBeInTheDocument();
    expect(save).toHaveBeenCalledWith({ debugMode: false, expectedVersion: 5 });
  });

  it("shows read failures with retry and redirects expired sessions", async () => {
    server.use(http.get("/api/v1/settings/debug", () => HttpResponse.json({ status: 503 }, { status: 503 })));
    show();
    expect(await screen.findByText("读取 debug 模式失败")).toBeInTheDocument();
    expect(screen.getByRole("checkbox")).toBeDisabled();
    server.use(http.get("/api/v1/settings/debug", () => HttpResponse.json({ status: 401 }, { status: 401 })));
    await userEvent.setup().click(screen.getByRole("button", { name: "重新读取设置" }));
    expect(await screen.findByRole("heading", { name: "登录页" })).toBeInTheDocument();
  });
});
