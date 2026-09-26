import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { server } from "../test/server";
import { App } from "./App";

// Lazy route imports also transform the icon package on a cold test worker.
const ROUTE_LOAD_TIMEOUT_MS = 5_000;

/** Real router smoke tests prove lazy screens still resolve at their public paths. */
describe("App routes", () => {
  afterEach(() => window.history.replaceState({}, "", "/"));

  it("loads the login screen without requesting project data", async () => {
    window.history.replaceState({}, "", "/login");
    render(<App />);
    expect(await screen.findByRole("button", { name: "登录" }, { timeout: ROUTE_LOAD_TIMEOUT_MS })).toBeInTheDocument();
    expect(screen.getByText("自托管模式 · Provider 状态登录后可查看")).toBeInTheDocument();
  });

  it("loads the project list and provider settings navigation", async () => {
    window.history.replaceState({}, "", "/projects");
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/projects", () => HttpResponse.json({ items: [], nextCursor: null })),
    );
    render(<App />);
    expect(await screen.findByRole("heading", { name: "项目" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Provider 配置" })).toHaveAttribute("href", "/settings/providers");
  });

  it("loads the call audit route and marks its navigation active", async () => {
    window.history.replaceState({}, "", "/settings/calls");
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/call-logs", () => HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    );
    render(<App />);
    expect(await screen.findByRole("heading", { name: "调用日志" }, { timeout: ROUTE_LOAD_TIMEOUT_MS })).toBeInTheDocument();
    expect(await screen.findByText("没有匹配的调用记录")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "调用日志" })).toHaveAttribute("aria-current", "page");
  });
});
