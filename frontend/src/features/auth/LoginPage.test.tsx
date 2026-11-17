import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { LoginPage } from "./LoginPage";
import { useCanvasStore } from "../canvas/canvasStore";

describe("LoginPage", () => {
  it("keeps credentials on failure then opens projects after successful login", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/auth/login", async ({ request }) => {
        submitted.push(await request.json());
        return submitted.length === 1
          ? HttpResponse.json(
              { code: "AUTHENTICATION_FAILED", title: "登录失败", detail: "登录名或密码不正确", retryable: false },
              { status: 401, headers: { "Content-Type": "application/problem+json" } },
            )
          : HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" });
      }),
    );
    const client = createQueryClient();
    useCanvasStore.getState().setTextGenerationPrompt("prior-session:canvas", "上个会话的提示词");
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter initialEntries={["/login"]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/projects" element={<p>项目首页</p>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    const user = userEvent.setup();
    const password = screen.getByLabelText("密码");
    await user.type(password, "password-12345");
    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("登录名或密码不正确");
    expect(password).toHaveValue("password-12345");
    expect(useCanvasStore.getState().textGenerationDrafts["prior-session:canvas"]).toBeDefined();
    await user.click(screen.getByRole("button", { name: "登录" }));
    expect(await screen.findByText("项目首页")).toBeInTheDocument();
    expect(useCanvasStore.getState().textGenerationDrafts).toEqual({});
    expect(client.getQueryData(["auth", "me"])).toEqual({ id: "admin", loginName: "admin", role: "ADMIN" });
    expect(submitted).toEqual([
      { loginName: "admin", password: "password-12345" },
      { loginName: "admin", password: "password-12345" },
    ]);
  });
});
