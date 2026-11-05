import { act, render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, expect, it, vi } from "vitest";
import { server } from "../test/server";
import { App } from "./App";

vi.mock("../features/auth/LoginPage", () => {
  throw new Error("Synthetic route chunk unavailable");
});

afterEach(() => window.history.replaceState({}, "", "/"));

it("shows a recovery action when a lazy page fails and allows history navigation to a healthy page", async () => {
  window.history.replaceState({}, "", "/login");
  server.use(
    http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "synthetic-admin", loginName: "synthetic-admin", role: "ADMIN" })),
    http.get("/api/v1/projects", () => HttpResponse.json({ items: [], nextCursor: null })),
  );
  render(<App />, { onCaughtError: () => {} });
  expect(await screen.findByRole("heading", { name: "页面无法显示" })).toBeVisible();
  expect(screen.getByRole("button", { name: "重新加载页面" })).toBeVisible();
  expect(screen.getByRole("alert")).toHaveTextContent("未保存的输入可能丢失");
  expect(document.body).not.toHaveTextContent("Synthetic route chunk unavailable");
  await act(async () => {
    window.history.pushState({}, "", "/projects");
    window.dispatchEvent(new PopStateEvent("popstate"));
  });
  expect(await screen.findByRole("heading", { name: "项目" }, { timeout: 5000 })).toBeVisible();
  expect(screen.queryByRole("button", { name: "重新加载页面" })).not.toBeInTheDocument();
});
