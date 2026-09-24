import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { LlmSettingsPage } from "./LlmSettingsPage";

describe("LlmSettingsPage", () => {
  it("submits the key once, clears its field, and displays only the server mask", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: false, version: 0, endpoint: null, modelId: null,
        keyMask: null, toolCallingVerified: false, updatedAt: null,
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "COMFYUI", imageConfigured: true, videoConfigured: false,
        recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/llm", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({
          configured: true, version: 1, endpoint: "https://api.example.com", modelId: "model-a",
          keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
        });
      }),
    );

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    await user.type(await screen.findByRole("textbox", { name: "端点地址" }), "https://api.example.com");
    await user.type(screen.getByRole("textbox", { name: "模型 ID" }), "model-a");
    const keyInput = screen.getByLabelText("API Key（每次修改均需重新输入）") as HTMLInputElement;
    await user.type(keyInput, "secret-7890");
    await user.click(screen.getByRole("button", { name: "保存配置" }));

    expect(await screen.findByRole("status")).toHaveTextContent("配置已加密保存");
    expect(keyInput).toHaveValue("");
    expect(screen.getByText(/密钥 ••••7890/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("secret-7890");
    expect(submitted).toEqual([{ expectedVersion: 0, endpoint: "https://api.example.com", modelId: "model-a", apiKey: "secret-7890" }]);
    expect(screen.getByText(/ComfyUI 模式 · 图片已配置 · 视频未配置/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看系统诊断" })).toHaveAttribute("href", "/settings/general");
  });

  it("requires visible cost consent and updates the verified state after a full probe", async () => {
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
        keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true,
        recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({
          configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
          keyMask: "••••7890", toolCallingVerified: true, updatedAt: "2026-09-23T00:00:00Z",
        });
      }),
    );

    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const diagnose = await screen.findByRole("button", { name: "执行可能计费的诊断" });
    expect(diagnose).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "我确认此次诊断可能产生模型费用" }));
    await user.click(diagnose);

    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(screen.getByText(/完整工具往返已验证/)).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }]);
    expect(diagnose).toBeDisabled();
  });
});
