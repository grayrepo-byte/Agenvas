import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it } from "vitest";
import { server } from "../test/server";
import { App } from "./App";

/** Real router smoke tests prove lazy screens still resolve at their public paths. */
describe("App routes", () => {
  afterEach(() => window.history.replaceState({}, "", "/"));

  it("loads the login screen without requesting project data", async () => {
    window.history.replaceState({}, "", "/login");
    render(<App />);
    expect(await screen.findByRole("button", { name: "登录" })).toBeInTheDocument();
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
});
