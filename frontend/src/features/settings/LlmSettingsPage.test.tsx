import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { LlmSettings } from "../../shared/api/client";
import { MemoryRouter } from "react-router";
import { createQueryClient } from "../../app/queryClient";
import { server } from "../../test/server";
import { LlmSettingsPage } from "./LlmSettingsPage";

describe("LlmSettingsPage", () => {
  it("discards unsaved fields and credentials without saving or diagnosing", async () => {
    const writes: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json({
        configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
        keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.put("/api/v1/settings/llm", async ({ request }) => { writes.push(await request.json()); return new HttpResponse(null, { status: 500 }); }),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => { writes.push(await request.json()); return new HttpResponse(null, { status: 500 }); }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const model = await screen.findByRole("textbox", { name: "模型 ID" });
    const consent = screen.getByRole("checkbox", { name: "我确认此次诊断可能产生模型费用" });
    await user.click(consent);
    await user.clear(model); await user.type(model, "draft-model");
    const key = screen.getByLabelText("API Key（每次修改均需重新输入）");
    await user.type(key, "unsaved-secret");
    expect(screen.getByText("有未保存的修改")).toBeInTheDocument();
    expect(consent).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "撤销修改" }));
    expect(model).toHaveValue("model-a");
    expect(key).toHaveValue("");
    expect(consent).not.toBeChecked();
    expect(screen.getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    expect(screen.queryByText("有未保存的修改")).not.toBeInTheDocument();
    expect(writes).toEqual([]);
  });

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

    expect(await within(screen.getByRole("region", { name: "LLM 配置" })).findByRole("status")).toHaveTextContent("配置已加密保存");
    expect(keyInput).toHaveValue("");
    expect(screen.getByText(/密钥 ••••7890/)).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent("secret-7890");
    expect(submitted).toEqual([{ expectedVersion: 0, endpoint: "https://api.example.com", modelId: "model-a", apiKey: "secret-7890" }]);
    expect(screen.getByText(/ComfyUI 模式 · 图片已配置 · 视频未配置/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "查看系统诊断" })).toHaveAttribute("href", "/settings/general?tab=diagnostics");
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

  it("preserves a draft and its CAS base when a newer setting arrives, then explicitly loads the latest", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, modelId: "remote-model" };
    let current = original;
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(current)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.put("/api/v1/settings/llm", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({ title: "Conflict", status: 409, code: "VERSION_CONFLICT", detail: "配置已更新，请核对。" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
      }),
    );
    const queryClient = createQueryClient();
    render(<QueryClientProvider client={queryClient}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const modelInput = await screen.findByRole("textbox", { name: "模型 ID" });
    await user.clear(modelInput);
    await user.type(modelInput, "local-draft");
    const keyInput = screen.getByLabelText("API Key（每次修改均需重新输入）");
    await user.type(keyInput, "local-secret");
    current = latest;
    act(() => { queryClient.setQueryData(["settings", "llm"], latest); });
    expect(await screen.findByText("已有更新的配置")).toBeInTheDocument();
    expect(modelInput).toHaveValue("local-draft");
    expect(screen.getByRole("button", { name: "执行可能计费的诊断" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "保存配置" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("配置已更新，请核对。");
    expect(submitted).toEqual([{ expectedVersion: 4, endpoint: original.endpoint, modelId: "local-draft", apiKey: "local-secret" }]);
    expect(keyInput).toHaveValue("");
    expect(modelInput).toHaveValue("local-draft");
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(modelInput).toHaveValue("remote-model");
    expect(screen.queryByText("已有更新的配置")).not.toBeInTheDocument();
  });

  it("retries a failed configuration read without leaving the settings page", async () => {
    let shouldFail = true;
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => shouldFail ? HttpResponse.json({ status: 503, title: "Unavailable" }, { status: 503 }) : HttpResponse.json({
        configured: false, version: 0, endpoint: null, modelId: null, keyMask: null, toolCallingVerified: false, updatedAt: null,
      })),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "MOCK", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole("alert")).toHaveTextContent("读取配置失败");
    shouldFail = false;
    await userEvent.setup().click(screen.getByRole("button", { name: "重新读取配置" }));
    expect(await screen.findByRole("textbox", { name: "端点地址" })).toHaveValue("");
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });


  it("invalidates cost consent and diagnostic success when the saved configuration version changes", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, endpoint: "https://new.example.com", modelId: "model-b" };
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(original)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        submitted.push(await request.json());
        return HttpResponse.json({ ...latest, toolCallingVerified: true });
      }),
    );
    const queryClient = createQueryClient();
    render(<QueryClientProvider client={queryClient}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const consent = await screen.findByRole("checkbox", { name: "我确认此次诊断可能产生模型费用" });
    const diagnose = screen.getByRole("button", { name: "执行可能计费的诊断" });
    await user.click(consent);
    expect(diagnose).toBeEnabled();
    act(() => { queryClient.setQueryData(["settings", "llm"], latest); });
    await waitFor(() => expect(consent).not.toBeChecked());
    expect(diagnose).toBeDisabled();
    expect(submitted).toHaveLength(0);
    await user.click(consent);
    await user.click(diagnose);
    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 5, acknowledgeCost: true }]);
    act(() => { queryClient.setQueryData(["settings", "llm"], { ...latest, version: 6 }); });
    await waitFor(() => expect(screen.queryByText("已验证完整工具协议。")).not.toBeInTheDocument());
    expect(screen.getByText(/当前版本 6：尚未验证/)).toBeInTheDocument();
    expect(consent).not.toBeChecked();
    expect(diagnose).toBeDisabled();
  });

  it("refreshes a diagnostic conflict, preserves the edit base, and lets the user diagnose the latest version", async () => {
    const original: LlmSettings = {
      configured: true, version: 4, endpoint: "https://api.example.com", modelId: "model-a",
      keyMask: "••••7890", toolCallingVerified: false, updatedAt: "2026-09-23T00:00:00Z",
    };
    const latest: LlmSettings = { ...original, version: 5, modelId: "remote-model" };
    let current = original;
    const submitted: unknown[] = [];
    server.use(
      http.get("/api/v1/auth/me", () => HttpResponse.json({ id: "admin", loginName: "admin", role: "ADMIN" })),
      http.get("/api/v1/settings/llm", () => HttpResponse.json(current)),
      http.get("/api/v1/settings/diagnostics", () => HttpResponse.json({
        checkedAt: "2026-09-24T00:00:00Z", database: "AVAILABLE", storage: "AVAILABLE",
        llmMode: "CONFIGURED", llmConfigured: true, llmToolCallingVerified: false,
        mediaMode: "MOCK", imageConfigured: true, videoConfigured: true, recentErrors: [],
      })),
      http.get("/api/v1/auth/csrf", () => HttpResponse.json({ headerName: "X-XSRF-TOKEN", token: "test" })),
      http.post("/api/v1/settings/llm/diagnose", async ({ request }) => {
        const input = await request.json() as { expectedVersion: number };
        submitted.push(input);
        if (input.expectedVersion !== latest.version) {
          current = latest;
          return HttpResponse.json({ title: "Conflict", status: 409, code: "PROVIDER_CONFIG_CHANGED", detail: "配置已变更，请重新读取。" }, { status: 409, headers: { "Content-Type": "application/problem+json" } });
        }
        return HttpResponse.json({ ...latest, toolCallingVerified: true });
      }),
    );
    render(<QueryClientProvider client={createQueryClient()}><MemoryRouter><LlmSettingsPage /></MemoryRouter></QueryClientProvider>);
    const user = userEvent.setup();
    const modelInput = await screen.findByRole("textbox", { name: "模型 ID" });
    // Restoring the value leaves a pinned edit base; a conflict must not silently replace it.
    await user.type(modelInput, "x{Backspace}");
    await user.click(screen.getByRole("checkbox", { name: "我确认此次诊断可能产生模型费用" }));
    await user.click(screen.getByRole("button", { name: "执行可能计费的诊断" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("配置已变更，请重新读取。");
    expect(await screen.findByText("已有更新的配置")).toBeInTheDocument();
    expect(modelInput).toHaveValue("model-a");
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }]);
    await user.click(screen.getByRole("button", { name: "载入最新配置" }));
    expect(modelInput).toHaveValue("remote-model");
    const diagnose = screen.getByRole("button", { name: "执行可能计费的诊断" });
    expect(diagnose).toBeDisabled();
    await user.click(screen.getByRole("checkbox", { name: "我确认此次诊断可能产生模型费用" }));
    await user.click(diagnose);
    expect(await screen.findByText("已验证完整工具协议。")).toBeInTheDocument();
    expect(submitted).toEqual([{ expectedVersion: 4, acknowledgeCost: true }, { expectedVersion: 5, acknowledgeCost: true }]);
  });

});
